package com.andromedia.ingestion.zoho;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.andromedia.ingestion.crm.CrmFetchQuery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ZohoApiClientPaginationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void fetchDealsUsesPageTokenNotOffsetPage() throws Exception {
    List<String> requestedUrls = new ArrayList<>();
    AtomicInteger calls = new AtomicInteger();

    ZohoApiClient.Transport crmTransport =
        (url, accessToken) -> {
          requestedUrls.add(url);
          int call = calls.incrementAndGet();
          if (call == 1) {
            assertFalse(hasOffsetPageParam(url), "first page must not use offset page=");
            assertFalse(url.contains("page_token="), "first page must omit page_token");
            return pageResponse(1, 2, "cursor-abc", true);
          }
          if (call == 2) {
            assertTrue(url.contains("page_token=cursor-abc"));
            assertFalse(hasOffsetPageParam(url), "cursor pages must not use offset page=");
            return pageResponse(3, 3, null, false);
          }
          fail("Unexpected extra CRM request: " + url);
          return MAPPER.createObjectNode();
        };

    ZohoApiClient client = clientWith(crmTransport);
    List<ZohoDeal> deals = client.fetchDeals();

    assertEquals(3, deals.size());
    assertEquals("Deal 1", deals.get(0).name());
    assertEquals("Deal 2", deals.get(1).name());
    assertEquals("Deal 3", deals.get(2).name());
    assertEquals(2, requestedUrls.size());
  }

  @Test
  void expiredPageTokenRestartsModuleFetchOnce() throws Exception {
    AtomicInteger calls = new AtomicInteger();

    ZohoApiClient.Transport crmTransport =
        (url, accessToken) -> {
          int call = calls.incrementAndGet();
          if (call == 1) {
            return pageResponse(1, 1, "stale-token", true);
          }
          if (call == 2) {
            assertTrue(url.contains("page_token=stale-token"));
            throw new IOException(
                "Zoho API HTTP 400: {\"code\":\"INVALID_TOKEN\",\"message\":\"invalid page_token\"}");
          }
          if (call == 3) {
            assertFalse(url.contains("page_token="), "restart must start without page_token");
            return pageResponse(10, 11, "fresh-token", true);
          }
          if (call == 4) {
            assertTrue(url.contains("page_token=fresh-token"));
            return pageResponse(12, 12, null, false);
          }
          fail("Unexpected extra CRM request #" + call + ": " + url);
          return MAPPER.createObjectNode();
        };

    ZohoApiClient client = clientWith(crmTransport);
    List<ZohoDeal> deals = client.fetchDeals();

    assertEquals(3, deals.size());
    assertEquals("Deal 10", deals.get(0).name());
    assertEquals("Deal 12", deals.get(2).name());
    assertEquals(4, calls.get());
  }

  @Test
  void isRecoverablePageTokenErrorDetectsZohoCodes() {
    assertTrue(
        ZohoApiClient.isRecoverablePageTokenError(
            new IOException(
                "Zoho API HTTP 400: {\"code\":\"DISCRETE_PAGINATION_LIMIT_EXCEEDED\"}")));
    assertTrue(
        ZohoApiClient.isRecoverablePageTokenError(
            new IOException("Zoho API HTTP 400: invalid page_token expired")));
    assertFalse(
        ZohoApiClient.isRecoverablePageTokenError(
            new IOException("Zoho API HTTP 401: unauthorized")));
  }

  @Test
  void buildModuleListUrlEncodesPageToken() {
    ZohoApiClient client =
        clientWith(
            (url, token) -> {
              fail("should not call transport");
              return MAPPER.createObjectNode();
            });
    String first = client.buildModuleListUrl("Deals", "Deal_Name", null);
    assertTrue(first.contains("/Deals?fields="));
    assertTrue(first.contains("per_page=200"));
    assertFalse(hasOffsetPageParam(first));
    assertFalse(first.contains("page_token="));

    String next = client.buildModuleListUrl("Deals", "Deal_Name", "tok/with space");
    assertTrue(next.contains("page_token="));
    assertFalse(hasOffsetPageParam(next));
    assertTrue(next.contains("tok%2Fwith+space") || next.contains("tok%2Fwith%20space"));
  }

  @Test
  void dynamicFetchUsesSearchWhenCriteriaPresent() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    ZohoApiClient.Transport crmTransport =
        (url, accessToken) -> {
          calls.incrementAndGet();
          assertTrue(url.contains("/Deals/search?"), "incremental must use Search Records");
          assertTrue(url.contains("criteria="), "Search Records must include criteria");
          assertTrue(url.contains("Field1__c"));
          ObjectNode response = (ObjectNode) pageResponse(1, 2, null, false);
          ((ObjectNode) response.withArray("data").get(0))
              .put("Field1__c", "Procurement changed vendors after legal review");
          return response;
        };
    ZohoApiClient client = clientWith(crmTransport);
    CrmFetchQuery query =
        new CrmFetchQuery(
            "/crm/v3/Deals",
            ZohoApiClient.DEAL_FIELDS + ",Field1__c",
            "Modified_Time:greater_than:2026-04-21T00:00:00+00:00",
            200);

    ZohoApiClient.ZohoModuleRecords<ZohoDeal> result = client.fetchDeals(query);

    assertEquals(1, calls.get(), "sampling must not issue an extra API request");
    assertEquals(2, result.records().size());
    assertEquals(2, result.rawRecords().size());
    assertEquals(2, result.firstPageSamples().size());
    assertEquals(
        "Procurement changed vendors after legal review",
        result.rawRecords().get(0).getString("Field1__c"));
  }

  @Test
  void fullSyncFetchOmitsCriteriaOnGetRecords() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    ZohoApiClient.Transport crmTransport =
        (url, accessToken) -> {
          calls.incrementAndGet();
          assertFalse(url.contains("/search?"), "full sync uses Get Records");
          assertFalse(url.contains("criteria="), "Get Records does not support criteria");
          return pageResponse(1, 1, null, false);
        };
    ZohoApiClient client = clientWith(crmTransport);
    CrmFetchQuery query =
        new CrmFetchQuery("/crm/v3/Deals", ZohoApiClient.DEAL_FIELDS, null, 200);

    client.fetchDeals(query);

    assertEquals(1, calls.get());
  }

  @Test
  void buildModuleSearchUrlEncodesCriteria() {
    ZohoApiClient client =
        clientWith(
            (url, token) -> {
              fail("should not call transport");
              return MAPPER.createObjectNode();
            });
    String url =
        client.buildModuleSearchUrl(
            "Deals", "Deal_Name", "Modified_Time:greater_than:2026-07-21T10:00:00+00:00", null);
    assertTrue(url.contains("/Deals/search?criteria="));
    assertTrue(url.contains("per_page=200"));
    assertFalse(url.contains("page_token="));
  }

  @Test
  void fetchDeletedSinceFiltersByWatermarkAndPaginates() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    ZohoApiClient.Transport crmTransport =
        (url, accessToken) -> {
          int call = calls.incrementAndGet();
          assertTrue(url.contains("/Deals/deleted?type=all"));
          ObjectNode root = MAPPER.createObjectNode();
          ArrayNode data = root.putArray("data");
          if (call == 1) {
            assertFalse(url.contains("page_token="));
            ObjectNode old = data.addObject();
            old.put("id", "old-1");
            old.put("display_name", "Too Old");
            old.put("type", "permanent");
            old.put("deleted_time", "2026-07-01T00:00:00Z");
            ObjectNode recent = data.addObject();
            recent.put("id", "new-1");
            recent.put("display_name", "Recent Delete");
            recent.put("type", "recycle");
            recent.put("deleted_time", "2026-07-20T14:30:00+05:30");
            ObjectNode info = root.putObject("info");
            info.put("more_records", true);
            info.put("next_page_token", "del-token");
            return root;
          }
          if (call == 2) {
            assertTrue(url.contains("page_token=del-token"));
            ObjectNode second = data.addObject();
            second.put("id", "new-2");
            second.put("display_name", "Second Page");
            second.put("type", "permanent");
            second.put("deleted_time", "2026-07-21T01:00:00Z");
            ObjectNode info = root.putObject("info");
            info.put("more_records", false);
            return root;
          }
          fail("Unexpected deleted API call #" + call);
          return root;
        };

    ZohoApiClient client = clientWith(crmTransport);
    List<ZohoApiClient.ZohoDeletedRecord> deleted =
        client.fetchDeletedSince("Deals", Instant.parse("2026-07-15T00:00:00Z"));

    assertEquals(2, deleted.size());
    assertEquals("new-1", deleted.get(0).zohoId());
    assertEquals("new-2", deleted.get(1).zohoId());
    assertEquals(2, calls.get());
  }

  @Test
  void dynamicProjectionOver50FieldsIsBatchedAndMergedByRecordId() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    ZohoApiClient.Transport crmTransport =
        (url, accessToken) -> {
          int call = calls.incrementAndGet();
          ObjectNode response = (ObjectNode) pageResponse(1, 1, null, false);
          ObjectNode row = (ObjectNode) response.withArray("data").get(0);
          if (call == 1) {
            row.put("Custom_1__c", "first batch");
          } else if (call == 2) {
            row.put("Custom_40__c", "second batch");
          } else {
            fail("Unexpected projection batch #" + call);
          }
          return response;
        };
    StringBuilder fields = new StringBuilder();
    for (int i = 1; i <= 40; i++) {
      if (!fields.isEmpty()) {
        fields.append(',');
      }
      fields.append("Custom_").append(i).append("__c");
    }
    ZohoApiClient client = clientWith(crmTransport);
    CrmFetchQuery query =
        new CrmFetchQuery("/crm/v3/Deals", fields.toString(), null, 200);

    ZohoApiClient.ZohoModuleRecords<ZohoDeal> result = client.fetchDeals(query);

    assertEquals(2, calls.get());
    assertEquals(1, result.records().size());
    assertEquals("first batch", result.rawRecords().get(0).getString("Custom_1__c"));
    assertEquals("second batch", result.rawRecords().get(0).getString("Custom_40__c"));
    assertEquals(
        "second batch", result.firstPageSamples().get(0).getString("Custom_40__c"));
  }

  /** True only for Zoho offset {@code page=} — not {@code per_page=} or {@code page_token=}. */
  private static boolean hasOffsetPageParam(String url) {
    return url.matches(".*[?&]page=\\d+.*");
  }

  private static ZohoApiClient clientWith(ZohoApiClient.Transport crmTransport) {
    ZohoAuthClient auth =
        new ZohoAuthClient(
            "client",
            "secret",
            ZohoRegion.COM,
            (url, form) -> {
              ObjectNode body = MAPPER.createObjectNode();
              body.put("access_token", "access-test");
              body.put("expires_in", 3600);
              return body;
            });
    return new ZohoApiClient(auth, "refresh-test", ZohoRegion.COM, crmTransport);
  }

  private static JsonNode pageResponse(
      int fromInclusive, int toInclusive, String nextPageToken, boolean moreRecords) {
    ObjectNode root = MAPPER.createObjectNode();
    ArrayNode data = root.putArray("data");
    for (int i = fromInclusive; i <= toInclusive; i++) {
      ObjectNode row = data.addObject();
      row.put("id", String.valueOf(1000 + i));
      row.put("Deal_Name", "Deal " + i);
      row.put("Stage", "Qualification");
      row.put("Amount", "1000");
      row.put("Probability", "10");
      row.put("Closing_Date", "2026-12-01");
      row.put("Description", "desc");
      row.put("Modified_Time", "2026-07-18T00:00:00Z");
      row.put("Lead_Source", "");
      row.put("Next_Step", "");
      ObjectNode account = row.putObject("Account_Name");
      account.put("id", "a1");
      account.put("name", "Acme");
    }
    ObjectNode info = root.putObject("info");
    info.put("per_page", 200);
    info.put("count", toInclusive - fromInclusive + 1);
    info.put("more_records", moreRecords);
    if (nextPageToken != null && !nextPageToken.isBlank()) {
      info.put("next_page_token", nextPageToken);
    }
    return root;
  }
}
