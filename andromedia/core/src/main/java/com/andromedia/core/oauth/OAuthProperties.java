package com.andromedia.core.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OAuth app credentials per provider. Flat (not nested) so relaxed env-var binding stays simple
 * and predictable, matching {@code CredentialsProperties}. All fields are blank by default —
 * {@link OAuthService} reports a provider as "not configured" rather than failing until real
 * values are set via env vars (e.g. {@code ANDROMEDIA_OAUTH_SLACK_CLIENT_ID}).
 *
 * <p>Google Chat, Gmail and Docs all share ONE Google Cloud OAuth app (one Client ID/Secret,
 * incremental scopes) — that's how Google's console works — so there's a single {@code
 * googleClientId}/{@code googleClientSecret} rather than one pair per Google product.
 */
@ConfigurationProperties(prefix = "andromedia.oauth")
public record OAuthProperties(
    String slackClientId,
    String slackClientSecret,
    String slackRedirectUri,
    String notionClientId,
    String notionClientSecret,
    String notionRedirectUri,
    String googleClientId,
    String googleClientSecret,
    String googleChatRedirectUri,
    String linearClientId,
    String linearClientSecret,
    String linearRedirectUri,
    String zohoClientId,
    String zohoClientSecret,
    String zohoRedirectUri,
    String jiraClientId,
    String jiraClientSecret,
    String jiraRedirectUri) {

  public OAuthProperties {
    slackClientId = nullToEmpty(slackClientId);
    slackClientSecret = nullToEmpty(slackClientSecret);
    slackRedirectUri = nullToEmpty(slackRedirectUri);
    notionClientId = nullToEmpty(notionClientId);
    notionClientSecret = nullToEmpty(notionClientSecret);
    notionRedirectUri = nullToEmpty(notionRedirectUri);
    googleClientId = nullToEmpty(googleClientId);
    googleClientSecret = nullToEmpty(googleClientSecret);
    googleChatRedirectUri = nullToEmpty(googleChatRedirectUri);
    linearClientId = nullToEmpty(linearClientId);
    linearClientSecret = nullToEmpty(linearClientSecret);
    linearRedirectUri = nullToEmpty(linearRedirectUri);
    zohoClientId = nullToEmpty(zohoClientId);
    zohoClientSecret = nullToEmpty(zohoClientSecret);
    zohoRedirectUri = nullToEmpty(zohoRedirectUri);
    jiraClientId = nullToEmpty(jiraClientId);
    jiraClientSecret = nullToEmpty(jiraClientSecret);
    jiraRedirectUri = nullToEmpty(jiraRedirectUri);
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  public String clientId(OAuthProvider provider) {
    return switch (provider) {
      case SLACK -> slackClientId;
      case NOTION -> notionClientId;
      case GOOGLE_CHAT -> googleClientId;
      case LINEAR -> linearClientId;
      case ZOHO -> zohoClientId;
      case JIRA -> jiraClientId;
    };
  }

  public String clientSecret(OAuthProvider provider) {
    return switch (provider) {
      case SLACK -> slackClientSecret;
      case NOTION -> notionClientSecret;
      case GOOGLE_CHAT -> googleClientSecret;
      case LINEAR -> linearClientSecret;
      case ZOHO -> zohoClientSecret;
      case JIRA -> jiraClientSecret;
    };
  }

  /** Defaults to the local backend's own callback route when unset — the common case in dev. */
  public String redirectUri(OAuthProvider provider) {
    String configured =
        switch (provider) {
          case SLACK -> slackRedirectUri;
          case NOTION -> notionRedirectUri;
          case GOOGLE_CHAT -> googleChatRedirectUri;
          case LINEAR -> linearRedirectUri;
          case ZOHO -> zohoRedirectUri;
          case JIRA -> jiraRedirectUri;
        };
    if (!configured.isBlank()) {
      return configured;
    }
    return "http://localhost:8083/api/v1/oauth/" + provider.id() + "/callback";
  }

  public boolean configured(OAuthProvider provider) {
    return !clientId(provider).isBlank() && !clientSecret(provider).isBlank();
  }
}
