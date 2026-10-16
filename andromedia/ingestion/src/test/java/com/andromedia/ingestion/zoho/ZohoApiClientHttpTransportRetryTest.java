package com.andromedia.ingestion.zoho;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ZohoApiClientHttpTransportRetryTest {

  @Test
  void retries429ThenSucceeds() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    List<Long> sleeps = new ArrayList<>();
    ZohoHttpRetry.Policy policy = new ZohoHttpRetry.Policy(2, 1000L, 2.0, 5000L, 60_000L);

    ZohoApiClient.HttpTransport transport =
        new ZohoApiClient.HttpTransport(
            policy,
            sleeps::add,
            request -> {
              int call = calls.incrementAndGet();
              if (call == 1) {
                return new ZohoApiClient.RawResponse(429, "rate limited", "2");
              }
              return new ZohoApiClient.RawResponse(200, "{\"ok\":true}", null);
            });

    JsonNode body = transport.get("https://example.test/crm", "token");
    assertTrue(body.path("ok").asBoolean());
    assertEquals(2, calls.get());
    assertEquals(List.of(5000L), sleeps);
  }

  @Test
  void exhaustsRetriesOnPersistent429() {
    AtomicInteger calls = new AtomicInteger();
    List<Long> sleeps = new ArrayList<>();
    ZohoHttpRetry.Policy policy = new ZohoHttpRetry.Policy(2, 1000L, 2.0, 1000L, 60_000L);

    ZohoApiClient.HttpTransport transport =
        new ZohoApiClient.HttpTransport(
            policy,
            sleeps::add,
            request -> {
              calls.incrementAndGet();
              return new ZohoApiClient.RawResponse(429, "still limited", null);
            });

    IOException ex =
        assertThrows(
            IOException.class, () -> transport.get("https://example.test/crm", "token"));
    assertTrue(ex.getMessage().contains("429"));
    assertTrue(ex.getMessage().contains("3 attempt"));
    assertEquals(3, calls.get());
    assertEquals(2, sleeps.size());
  }

  @Test
  void nonRetryable400FailsImmediately() {
    AtomicInteger calls = new AtomicInteger();
    List<Long> sleeps = new ArrayList<>();
    ZohoHttpRetry.Policy policy = new ZohoHttpRetry.Policy(5, 1000L, 2.0, 5000L, 60_000L);

    ZohoApiClient.HttpTransport transport =
        new ZohoApiClient.HttpTransport(
            policy,
            sleeps::add,
            request -> {
              calls.incrementAndGet();
              return new ZohoApiClient.RawResponse(400, "{\"code\":\"BAD\"}", null);
            });

    IOException ex =
        assertThrows(
            IOException.class, () -> transport.get("https://example.test/crm", "token"));
    assertTrue(ex.getMessage().contains("400"));
    assertEquals(1, calls.get());
    assertTrue(sleeps.isEmpty());
  }

  @Test
  void unauthorizedFailsImmediately() {
    AtomicInteger calls = new AtomicInteger();
    List<Long> sleeps = new ArrayList<>();

    ZohoApiClient.HttpTransport transport =
        new ZohoApiClient.HttpTransport(
            ZohoHttpRetry.Policy.defaults(),
            sleeps::add,
            request -> {
              calls.incrementAndGet();
              return new ZohoApiClient.RawResponse(401, "unauthorized", null);
            });

    IOException ex =
        assertThrows(
            IOException.class, () -> transport.get("https://example.test/crm", "token"));
    assertTrue(ex.getMessage().contains("unauthorized"));
    assertEquals(1, calls.get());
    assertTrue(sleeps.isEmpty());
  }
}
