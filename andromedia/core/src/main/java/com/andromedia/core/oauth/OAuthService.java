package com.andromedia.core.oauth;

import com.andromedia.core.credentials.CredentialPublicView;
import com.andromedia.core.credentials.UserCredentialStore;
import com.andromedia.core.credentials.ZohoConnectionVault;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Generic "click Login, come back connected" OAuth flow shared by every OAuth-capable connector
 * (Slack and Notion today). Providers that only support a pasted API token/secret are untouched —
 * this never replaces {@link UserCredentialStore}'s manual-token path, it just writes into the
 * same table via the same secret field name, so existing ingestion code needs no changes.
 */
@Service
public class OAuthService {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration STATE_TTL = Duration.ofMinutes(10);

  private final OAuthProperties properties;
  private final UserCredentialStore credentialStore;
  private final ZohoConnectionVault zohoConnectionVault;
  private final HttpClient httpClient;
  private final Map<String, PendingState> pendingStates = new ConcurrentHashMap<>();

  public OAuthService(
      OAuthProperties properties,
      UserCredentialStore credentialStore,
      ZohoConnectionVault zohoConnectionVault) {
    this.properties = properties;
    this.credentialStore = credentialStore;
    this.zohoConnectionVault = zohoConnectionVault;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  public boolean configured(OAuthProvider provider) {
    return properties.configured(provider);
  }

  /** Builds the provider's consent-screen URL and remembers which trial started this flow. */
  public URI buildAuthorizeUrl(OAuthProvider provider, String trialId) {
    if (trialId == null || trialId.isBlank()) {
      throw new IllegalArgumentException("trialId is required");
    }
    if (!configured(provider)) {
      throw new OAuthNotConfiguredException(provider);
    }
    String state = UUID.randomUUID().toString();
    pruneExpiredStates();
    pendingStates.put(state, new PendingState(trialId.trim(), Instant.now()));

    StringBuilder url = new StringBuilder(provider.authorizeUrl());
    url.append("?client_id=").append(encode(properties.clientId(provider)));
    url.append("&redirect_uri=").append(encode(properties.redirectUri(provider)));
    url.append("&state=").append(encode(state));
    if (provider == OAuthProvider.SLACK) {
      url.append("&scope=").append(encode(provider.scope()));
      url.append("&user_scope=");
    } else if (provider == OAuthProvider.NOTION) {
      url.append("&response_type=code&owner=user");
    } else if (provider == OAuthProvider.GOOGLE_CHAT) {
      url.append("&response_type=code");
      url.append("&scope=").append(encode(provider.scope()));
      // access_type=offline + prompt=consent is what makes Google actually hand back a
      // refresh_token (otherwise only a short-lived access_token comes back).
      url.append("&access_type=offline&prompt=consent");
    } else if (provider == OAuthProvider.LINEAR) {
      url.append("&response_type=code&scope=").append(encode(provider.scope()));
    } else if (provider == OAuthProvider.ZOHO) {
      url.append("&response_type=code");
      url.append("&scope=").append(encode(provider.scope()));
      // access_type=offline + prompt=consent — same reasoning as Google above: without these,
      // Zoho only hands back a short-lived access_token and no refresh_token.
      url.append("&access_type=offline&prompt=consent");
    } else if (provider == OAuthProvider.JIRA) {
      url.append("&response_type=code");
      url.append("&scope=").append(encode(provider.scope()));
      // audience pins the token to Atlassian's cloud API gateway; without it the token can't be
      // used against api.atlassian.com/ex/jira/{cloudId}.
      url.append("&audience=api.atlassian.com&prompt=consent");
    }
    return URI.create(url.toString());
  }

  /** Exchanges the authorization code for a token and stores it. Returns a short summary. */
  public CallbackResult handleCallback(OAuthProvider provider, String code, String state) {
    if (code == null || code.isBlank()) {
      return CallbackResult.failure("Missing authorization code");
    }
    PendingState pending = state == null ? null : pendingStates.remove(state);
    if (pending == null || pending.isExpired()) {
      return CallbackResult.failure("This login link expired or was already used — try connecting again");
    }
    if (!configured(provider)) {
      return CallbackResult.failure(provider.id() + " OAuth is not configured on this server yet");
    }

    try {
      if (provider == OAuthProvider.ZOHO) {
        return handleZohoCallback(pending.trialId(), code);
      }
      ExchangedToken token = exchangeCode(provider, code);
      credentialStore.upsert(
          pending.trialId(),
          provider.credentialProvider(),
          token.secrets(),
          Map.of("authMethod", "oauth", "connectedAt", Instant.now().toString(), "label", token.label()),
          true);
      return CallbackResult.success(token.label());
    } catch (Exception ex) {
      return CallbackResult.failure("Could not complete " + provider.id() + " login: " + ex.getMessage());
    }
  }

  /**
   * Zoho's storage is connection-object shaped ({@link ZohoConnectionVault}, not a flat secret
   * map), because downstream Zoho ingestion needs the app's own client id/secret alongside the
   * user's refresh token to mint access tokens per request. So this bypasses the generic {@link
   * UserCredentialStore#upsert} path every other provider uses.
   */
  private CallbackResult handleZohoCallback(String trialId, String code) throws Exception {
    String form =
        "grant_type=authorization_code"
            + "&client_id=" + encode(properties.clientId(OAuthProvider.ZOHO))
            + "&client_secret=" + encode(properties.clientSecret(OAuthProvider.ZOHO))
            + "&redirect_uri=" + encode(properties.redirectUri(OAuthProvider.ZOHO))
            + "&code=" + encode(code);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(OAuthProvider.ZOHO.tokenUrl()))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
    JsonNode body = send(request);
    String refreshToken = body.path("refresh_token").asText(null);
    if (refreshToken == null || refreshToken.isBlank()) {
      String error = body.path("error").asText("Zoho did not return a refresh token");
      return CallbackResult.failure(error);
    }
    // api_domain looks like "https://www.zohoapis.in" — the segment after "zohoapis." is the
    // region suffix ZohoRegion.fromToken expects ("com", "in", ...).
    String apiDomain = body.path("api_domain").asText("https://www.zohoapis.com");
    String region = regionFromApiDomain(apiDomain);
    zohoConnectionVault.upsertZohoConnection(
        trialId,
        properties.clientId(OAuthProvider.ZOHO),
        properties.clientSecret(OAuthProvider.ZOHO),
        refreshToken,
        region);
    return CallbackResult.success("Zoho CRM");
  }

  private static String regionFromApiDomain(String apiDomain) {
    int marker = apiDomain.indexOf("zohoapis.");
    if (marker < 0) {
      return "com";
    }
    return apiDomain.substring(marker + "zohoapis.".length());
  }

  public Status status(OAuthProvider provider, String trialId) {
    boolean configured = configured(provider);
    var publicView =
        trialId == null || trialId.isBlank()
            ? Optional.<CredentialPublicView>empty()
            : credentialStore.findPublic(trialId.trim(), provider.credentialProvider());
    boolean connected = publicView.isPresent();
    // Zoho's vault (ZohoConnectionVault) writes into the same row via the generic upsert path, so
    // its metadata — including connectionId, which the frontend needs to call test/index — is
    // already here for free; every other provider's metadata is harmless extra detail.
    Map<String, Object> metadata = publicView.map(CredentialPublicView::metadata).orElse(Map.of());
    return new Status(provider.id(), configured, connected, metadata);
  }

  private ExchangedToken exchangeCode(OAuthProvider provider, String code) throws Exception {
    return switch (provider) {
      case SLACK -> exchangeSlack(code);
      case NOTION -> exchangeNotion(code);
      case GOOGLE_CHAT -> exchangeGoogle(code, OAuthProvider.GOOGLE_CHAT);
      case LINEAR -> exchangeLinear(code);
      case JIRA -> exchangeJira(code);
      // ZOHO is handled entirely by handleZohoCallback before exchangeCode is ever reached.
      case ZOHO -> throw new IllegalStateException("Zoho is handled by handleZohoCallback");
    };
  }

  private ExchangedToken exchangeSlack(String code) throws Exception {
    String form =
        "client_id=" + encode(properties.clientId(OAuthProvider.SLACK))
            + "&client_secret=" + encode(properties.clientSecret(OAuthProvider.SLACK))
            + "&code=" + encode(code)
            + "&redirect_uri=" + encode(properties.redirectUri(OAuthProvider.SLACK));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(OAuthProvider.SLACK.tokenUrl()))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
    JsonNode body = send(request);
    if (!body.path("ok").asBoolean(false)) {
      throw new IllegalStateException(body.path("error").asText("unknown error"));
    }
    String accessToken = body.path("access_token").asText(null);
    if (accessToken == null || accessToken.isBlank()) {
      throw new IllegalStateException("Slack response had no access_token");
    }
    String teamName = body.path("team").path("name").asText(null);
    return new ExchangedToken(
        Map.of(OAuthProvider.SLACK.secretField(), accessToken),
        teamName == null ? "Slack workspace" : teamName);
  }

  private ExchangedToken exchangeNotion(String code) throws Exception {
    String credentials =
        properties.clientId(OAuthProvider.NOTION) + ":" + properties.clientSecret(OAuthProvider.NOTION);
    String basicAuth =
        Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    String json =
        MAPPER
            .createObjectNode()
            .put("grant_type", "authorization_code")
            .put("code", code)
            .put("redirect_uri", properties.redirectUri(OAuthProvider.NOTION))
            .toString();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(OAuthProvider.NOTION.tokenUrl()))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/json")
            .header("Notion-Version", "2022-06-28")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
    JsonNode body = send(request);
    String accessToken = body.path("access_token").asText(null);
    if (accessToken == null || accessToken.isBlank()) {
      String error = body.path("error_description").asText(body.path("error").asText("unknown error"));
      throw new IllegalStateException(error);
    }
    String workspaceName = body.path("workspace_name").asText(null);
    return new ExchangedToken(
        Map.of(OAuthProvider.NOTION.secretField(), accessToken),
        workspaceName == null ? "Notion workspace" : workspaceName);
  }

  private ExchangedToken exchangeGoogle(String code, OAuthProvider provider) throws Exception {
    String form =
        "client_id=" + encode(properties.clientId(provider))
            + "&client_secret=" + encode(properties.clientSecret(provider))
            + "&code=" + encode(code)
            + "&redirect_uri=" + encode(properties.redirectUri(provider))
            + "&grant_type=authorization_code";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(provider.tokenUrl()))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
    JsonNode body = send(request);
    String accessToken = body.path("access_token").asText(null);
    if (accessToken == null || accessToken.isBlank()) {
      String error = body.path("error_description").asText(body.path("error").asText("unknown error"));
      throw new IllegalStateException(error);
    }
    // Google only returns a refresh_token on the FIRST consent (access_type=offline, prompt=consent
    // in the authorize URL) — a later re-auth without revoking prior access may omit it; that's
    // fine here since re-connecting just replaces the access token and the user re-authorizes
    // again once it expires (no silent refresh yet, see class-level note below).
    String refreshToken = body.path("refresh_token").asText(null);
    long expiresInSeconds = body.path("expires_in").asLong(3600);
    String expiresAt = Instant.now().plusSeconds(expiresInSeconds).toString();

    Map<String, Object> secrets = new java.util.LinkedHashMap<>();
    secrets.put("accessToken", accessToken);
    secrets.put("expiresAt", expiresAt);
    if (refreshToken != null && !refreshToken.isBlank()) {
      secrets.put("refreshToken", refreshToken);
    }
    return new ExchangedToken(secrets, "Google account");
  }

  private ExchangedToken exchangeLinear(String code) throws Exception {
    String form =
        "client_id=" + encode(properties.clientId(OAuthProvider.LINEAR))
            + "&client_secret=" + encode(properties.clientSecret(OAuthProvider.LINEAR))
            + "&code=" + encode(code)
            + "&redirect_uri=" + encode(properties.redirectUri(OAuthProvider.LINEAR))
            + "&grant_type=authorization_code";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(OAuthProvider.LINEAR.tokenUrl()))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
    JsonNode body = send(request);
    String accessToken = body.path("access_token").asText(null);
    if (accessToken == null || accessToken.isBlank()) {
      throw new IllegalStateException(body.path("error").asText("Linear response had no access_token"));
    }
    return new ExchangedToken(Map.of(OAuthProvider.LINEAR.secretField(), accessToken), "Linear workspace");
  }

  private ExchangedToken exchangeJira(String code) throws Exception {
    String json =
        MAPPER
            .createObjectNode()
            .put("grant_type", "authorization_code")
            .put("client_id", properties.clientId(OAuthProvider.JIRA))
            .put("client_secret", properties.clientSecret(OAuthProvider.JIRA))
            .put("code", code)
            .put("redirect_uri", properties.redirectUri(OAuthProvider.JIRA))
            .toString();
    HttpRequest tokenRequest =
        HttpRequest.newBuilder(URI.create(OAuthProvider.JIRA.tokenUrl()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
    JsonNode tokenBody = send(tokenRequest);
    String accessToken = tokenBody.path("access_token").asText(null);
    if (accessToken == null || accessToken.isBlank()) {
      String error = tokenBody.path("error_description").asText(tokenBody.path("error").asText("unknown error"));
      throw new IllegalStateException(error);
    }
    String refreshToken = tokenBody.path("refresh_token").asText(null);
    long expiresInSeconds = tokenBody.path("expires_in").asLong(3600);
    String expiresAt = Instant.now().plusSeconds(expiresInSeconds).toString();

    // Atlassian tokens aren't scoped to a site by the authorize step — accessible-resources tells
    // us which Jira Cloud site(s) the user just granted access to; take the first one.
    HttpRequest resourcesRequest =
        HttpRequest.newBuilder(URI.create("https://api.atlassian.com/oauth/token/accessible-resources"))
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "application/json")
            .GET()
            .build();
    JsonNode resources = send(resourcesRequest);
    if (!resources.isArray() || resources.isEmpty()) {
      throw new IllegalStateException("No accessible Jira Cloud sites granted for this account");
    }
    JsonNode site = resources.get(0);
    String cloudId = site.path("id").asText(null);
    String siteUrl = site.path("url").asText(null);
    if (cloudId == null || cloudId.isBlank()) {
      throw new IllegalStateException("Jira site response had no cloud id");
    }

    Map<String, Object> secrets = new LinkedHashMap<>();
    secrets.put("accessToken", accessToken);
    secrets.put("expiresAt", expiresAt);
    secrets.put("cloudId", cloudId);
    secrets.put("siteUrl", siteUrl == null ? "" : siteUrl);
    if (refreshToken != null && !refreshToken.isBlank()) {
      secrets.put("refreshToken", refreshToken);
    }
    return new ExchangedToken(secrets, siteUrl == null ? "Jira site" : siteUrl);
  }

  /**
   * Refreshes a provider's access token in place if it's expired or close to it. Only Jira needs
   * this today — Slack/Notion/Linear tokens don't expire, and Google/Zoho aren't read through this
   * path (Google has no background job reading it yet; Zoho mints a fresh access token per request
   * from its refresh token via {@code ZohoAuthClient}, not through this store). Safe to call before
   * every Jira API call; it's a no-op when the stored token still has more than a minute left.
   */
  public void refreshIfNeeded(OAuthProvider provider, String trialId) {
    if (provider != OAuthProvider.JIRA) {
      throw new UnsupportedOperationException("refreshIfNeeded is only implemented for Jira today");
    }
    JsonNode payload =
        credentialStore.findDecryptedPayload(trialId, provider.credentialProvider()).orElse(null);
    if (payload == null) {
      return;
    }
    String expiresAt = payload.path("expiresAt").asText(null);
    if (expiresAt != null && Instant.parse(expiresAt).isAfter(Instant.now().plusSeconds(60))) {
      return;
    }
    String refreshToken = payload.path("refreshToken").asText(null);
    if (refreshToken == null || refreshToken.isBlank()) {
      return;
    }
    try {
      String json =
          MAPPER
              .createObjectNode()
              .put("grant_type", "refresh_token")
              .put("client_id", properties.clientId(provider))
              .put("client_secret", properties.clientSecret(provider))
              .put("refresh_token", refreshToken)
              .toString();
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(provider.tokenUrl()))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .build();
      JsonNode body = send(request);
      String newAccessToken = body.path("access_token").asText(null);
      if (newAccessToken == null || newAccessToken.isBlank()) {
        return;
      }
      long expiresInSeconds = body.path("expires_in").asLong(3600);
      // Atlassian rotates the refresh token on every use — the old one stops working, so the new
      // one must replace it, falling back to the old value only if the response omits it.
      String newRefreshToken = body.path("refresh_token").asText(refreshToken);

      Map<String, Object> secrets = new LinkedHashMap<>();
      payload.fieldNames().forEachRemaining(field -> secrets.put(field, payload.path(field).asText()));
      secrets.put("accessToken", newAccessToken);
      secrets.put("expiresAt", Instant.now().plusSeconds(expiresInSeconds).toString());
      secrets.put("refreshToken", newRefreshToken);
      credentialStore.upsert(
          trialId,
          provider.credentialProvider(),
          secrets,
          Map.of("authMethod", "oauth", "refreshedAt", Instant.now().toString()),
          true);
    } catch (Exception ex) {
      // Leave the stale token in place — the caller's own API call will fail with a clear 401
      // rather than this silently swallowing a refresh failure into a confusing partial state.
    }
  }

  private JsonNode send(HttpRequest request) throws Exception {
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 400) {
      throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
    }
    return MAPPER.readTree(response.body());
  }

  private void pruneExpiredStates() {
    pendingStates.entrySet().removeIf(entry -> entry.getValue().isExpired());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  private record PendingState(String trialId, Instant createdAt) {
    boolean isExpired() {
      return Instant.now().isAfter(createdAt.plus(STATE_TTL));
    }
  }

  private record ExchangedToken(Map<String, Object> secrets, String label) {}

  public record Status(
      String provider, boolean configured, boolean connected, Map<String, Object> metadata) {}

  public record CallbackResult(boolean ok, String label, String error) {
    static CallbackResult success(String label) {
      return new CallbackResult(true, label, null);
    }

    static CallbackResult failure(String error) {
      return new CallbackResult(false, null, error);
    }
  }

  public static final class OAuthNotConfiguredException extends RuntimeException {
    public OAuthNotConfiguredException(OAuthProvider provider) {
      super(
          provider.id()
              + " OAuth is not configured on this server yet — set ANDROMEDIA_OAUTH_"
              + envPrefix(provider)
              + "_CLIENT_ID / _CLIENT_SECRET");
    }

    // Google Chat/Gmail/Docs share one Google Cloud OAuth app/env-var pair, so the hint here must
    // say GOOGLE, not GOOGLE_CHAT — the actual property is googleClientId, not googleChatClientId.
    private static String envPrefix(OAuthProvider provider) {
      return provider == OAuthProvider.GOOGLE_CHAT
          ? "GOOGLE"
          : provider.id().toUpperCase(java.util.Locale.ROOT);
    }
  }
}
