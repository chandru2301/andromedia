package com.andromedia.ingestion.zoho;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Self Client OAuth: grant-token exchange and refresh-token renewal. */
public class ZohoAuthClient {

  private static final Logger log = LoggerFactory.getLogger(ZohoAuthClient.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  public interface Transport {
    JsonNode postForm(String url, Map<String, String> form) throws IOException;
  }

  private final String clientId;
  private final String clientSecret;
  private final ZohoRegion region;
  private final Transport transport;

  public ZohoAuthClient(String clientId, String clientSecret, ZohoRegion region) {
    this(clientId, clientSecret, region, ZohoHttpRetry.Policy.defaults());
  }

  public ZohoAuthClient(
      String clientId, String clientSecret, ZohoRegion region, ZohoHttpRetry.Policy retryPolicy) {
    this(clientId, clientSecret, region, new HttpTransport(retryPolicy));
  }

  public ZohoAuthClient(String clientId, String clientSecret, ZohoRegion region, Transport transport) {
    if (clientId == null || clientId.isBlank()) {
      throw new IllegalArgumentException("Zoho client ID is required");
    }
    if (clientSecret == null || clientSecret.isBlank()) {
      throw new IllegalArgumentException("Zoho client secret is required");
    }
    this.clientId = clientId.trim();
    this.clientSecret = clientSecret.trim();
    this.region = region == null ? ZohoRegion.COM : region;
    this.transport = transport;
  }

  public TokenPair exchangeGrantToken(String grantToken) throws IOException {
    if (grantToken == null || grantToken.isBlank()) {
      throw new IllegalArgumentException("Zoho grant token is required");
    }
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "authorization_code");
    form.put("client_id", clientId);
    form.put("client_secret", clientSecret);
    form.put("code", grantToken.trim());
    return parseTokenResponse(transport.postForm(region.tokenUrl(), form));
  }

  public TokenPair refreshAccessToken(String refreshToken) throws IOException {
    if (refreshToken == null || refreshToken.isBlank()) {
      throw new IllegalArgumentException("Zoho refresh token is required");
    }
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "refresh_token");
    form.put("client_id", clientId);
    form.put("client_secret", clientSecret);
    form.put("refresh_token", refreshToken.trim());
    return parseTokenResponse(transport.postForm(region.tokenUrl(), form));
  }

  static TokenPair parseTokenResponse(JsonNode body) throws IOException {
    if (body == null) {
      throw new IOException("Empty token response from Zoho");
    }
    if (body.has("error")) {
      throw new IOException(
          "Zoho OAuth error: "
              + body.path("error").asText("")
              + " — "
              + body.path("error_description").asText(""));
    }
    String accessToken = body.path("access_token").asText("");
    String refreshToken = body.path("refresh_token").asText("");
    long expiresIn = body.path("expires_in").asLong(3600);
    if (accessToken.isBlank()) {
      throw new IOException("Zoho token response missing access_token");
    }
    Instant expiresAt = Instant.now().plusSeconds(Math.max(60, expiresIn - 60));
    return new TokenPair(accessToken, refreshToken, expiresAt);
  }

  public record TokenPair(String accessToken, String refreshToken, Instant expiresAt) {
    public TokenPair {
      accessToken = accessToken == null ? "" : accessToken;
      refreshToken = refreshToken == null ? "" : refreshToken;
    }
  }

  static final class HttpTransport implements Transport {
    private final HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ZohoHttpRetry.Policy policy;
    private final ZohoHttpRetry.Sleeper sleeper;
    private final Exchange exchange;

    HttpTransport() {
      this(ZohoHttpRetry.Policy.defaults());
    }

    HttpTransport(ZohoHttpRetry.Policy policy) {
      this(policy, ZohoHttpRetry.Sleeper.THREAD, null);
    }

    HttpTransport(
        ZohoHttpRetry.Policy policy, ZohoHttpRetry.Sleeper sleeper, Exchange exchange) {
      this.policy = policy == null ? ZohoHttpRetry.Policy.defaults() : policy;
      this.sleeper = sleeper == null ? ZohoHttpRetry.Sleeper.THREAD : sleeper;
      this.exchange = exchange;
    }

    @Override
    public JsonNode postForm(String url, Map<String, String> form) throws IOException {
      String body =
          form.entrySet().stream()
              .map(
                  e ->
                      URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                          + "="
                          + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
              .collect(Collectors.joining("&"));
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(30))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      int maxAttempts = policy.maxRetries() + 1;
      IOException lastFailure = null;
      for (int attempt = 0; attempt < maxAttempts; attempt++) {
        RawResponse response = send(request);
        int status = response.statusCode();
        if (status < 400) {
          return OBJECT_MAPPER.readTree(response.body());
        }
        if (!ZohoHttpRetry.isRetryableStatus(status) || attempt >= policy.maxRetries()) {
          String snippet = truncate(response.body());
          throw new IOException(
              "Zoho OAuth HTTP "
                  + status
                  + " after "
                  + (attempt + 1)
                  + " attempt(s): "
                  + snippet);
        }
        OptionalLong retryAfter =
            ZohoHttpRetry.parseRetryAfterSeconds(response.retryAfterHeader());
        long delayMs = ZohoHttpRetry.backoffMillis(attempt, policy, retryAfter, status);
        log.warn(
            "Zoho OAuth HTTP {} on attempt {}/{}; sleeping {} ms (Retry-After={})",
            status,
            attempt + 1,
            maxAttempts,
            delayMs,
            retryAfter.isPresent() ? retryAfter.getAsLong() : "none");
        ZohoHttpRetry.sleep(sleeper, delayMs, "Interrupted during Zoho OAuth");
        lastFailure =
            new IOException(
                "Zoho OAuth HTTP " + status + " after " + (attempt + 1) + " attempt(s)");
      }
      throw lastFailure == null
          ? new IOException("Zoho OAuth request failed")
          : lastFailure;
    }

    private RawResponse send(HttpRequest request) throws IOException {
      try {
        if (exchange != null) {
          return exchange.exchange(request);
        }
        HttpResponse<String> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Optional<String> retryAfter = response.headers().firstValue("Retry-After");
        return new RawResponse(
            response.statusCode(),
            response.body() == null ? "" : response.body(),
            retryAfter.orElse(null));
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted during Zoho OAuth", ex);
      }
    }

    private static String truncate(String body) {
      if (body == null) {
        return "";
      }
      return body.length() <= 500 ? body : body.substring(0, 500);
    }
  }

  @FunctionalInterface
  interface Exchange {
    RawResponse exchange(HttpRequest request) throws IOException, InterruptedException;
  }

  record RawResponse(int statusCode, String body, String retryAfterHeader) {}
}
