package com.andromedia.ingestion.zoho;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ZohoAuthClientTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void parseTokenResponseReadsAccessAndRefreshTokens() throws Exception {
    JsonNode body =
        MAPPER.readTree(
            """
            {
              "access_token": "access-123",
              "refresh_token": "refresh-456",
              "expires_in": 3600
            }
            """);
    ZohoAuthClient.TokenPair tokens = ZohoAuthClient.parseTokenResponse(body);
    assertEquals("access-123", tokens.accessToken());
    assertEquals("refresh-456", tokens.refreshToken());
    assertTrue(tokens.expiresAt().isAfter(Instant.now()));
  }

  @Test
  void httpTransportRetries429ThenSucceeds() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    List<Long> sleeps = new ArrayList<>();
    ZohoHttpRetry.Policy policy = new ZohoHttpRetry.Policy(2, 1000L, 2.0, 5000L, 60_000L);

    ZohoAuthClient.HttpTransport transport =
        new ZohoAuthClient.HttpTransport(
            policy,
            sleeps::add,
            request -> {
              int call = calls.incrementAndGet();
              if (call == 1) {
                return new ZohoAuthClient.RawResponse(429, "rate limited", null);
              }
              return new ZohoAuthClient.RawResponse(
                  200,
                  "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":3600}",
                  null);
            });

    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "refresh_token");
    JsonNode body = transport.postForm("https://accounts.zoho.com/oauth/v2/token", form);

    assertEquals("a", body.path("access_token").asText());
    assertEquals(2, calls.get());
    assertEquals(1, sleeps.size());
    assertEquals(5000L, sleeps.get(0));
  }

  @Test
  void httpTransportFailsFastOnNonRetryable400() {
    AtomicInteger calls = new AtomicInteger();
    List<Long> sleeps = new ArrayList<>();

    ZohoAuthClient.HttpTransport transport =
        new ZohoAuthClient.HttpTransport(
            ZohoHttpRetry.Policy.defaults(),
            sleeps::add,
            request -> {
              calls.incrementAndGet();
              return new ZohoAuthClient.RawResponse(400, "invalid_client", null);
            });

    IOException ex =
        assertThrows(
            IOException.class,
            () ->
                transport.postForm(
                    "https://accounts.zoho.com/oauth/v2/token", Map.of("grant_type", "refresh_token")));
    assertTrue(ex.getMessage().contains("400"));
    assertEquals(1, calls.get());
    assertTrue(sleeps.isEmpty());
  }
}
