package com.andromedia.ingestion.zoho;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.andromedia.ingestion.crm.CrmConnection;
import com.andromedia.ingestion.crm.CrmFetchQuery;
import com.andromedia.ingestion.crm.CrmFieldType;
import com.andromedia.ingestion.crm.CrmRecord;
import com.andromedia.ingestion.crm.CrmSchemaCache;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ZohoDynamicSchemaIngestionTest {

  @Test
  void ingestionUsesDynamicProjectionRefinesFirstPageAndRetainsRawRecords() throws Exception {
    ZohoApiClient apiClient = mock(ZohoApiClient.class);
    when(apiClient.discoverModuleSchema("Deals"))
        .thenReturn(
            new ZohoApiClient.ZohoModuleSchema(
                "Deals",
                List.of(
                    new ZohoApiClient.ZohoFieldSchema(
                        "Field1__c", "Field 1", "text", 80, true, true),
                    new ZohoApiClient.ZohoFieldSchema(
                        "old_field_2__c", "Old Field 2", "text", 50, true, true))));
    when(apiClient.discoverModuleSchema("Accounts"))
        .thenReturn(new ZohoApiClient.ZohoModuleSchema("Accounts", List.of()));
    when(apiClient.discoverModuleSchema("Contacts"))
        .thenReturn(new ZohoApiClient.ZohoModuleSchema("Contacts", List.of()));

    ZohoDeal deal =
        new ZohoDeal(
            "d1",
            "CRM Renewal",
            "Qualification",
            "50000",
            "25000",
            "50",
            "2026-12-31",
            "Renewal opportunity with a confirmed budget and active evaluation.",
            Instant.parse("2026-07-19T00:00:00Z"),
            "a1",
            "Acme",
            "",
            "",
            "Owner",
            "Referral",
            "Schedule legal review");
    String customNarrative =
        "The procurement team changed vendors after a lengthy legal review, while the CTO "
            + "approved the technical scope and asked us to wait for final budget sign-off.";
    List<CrmRecord> dealSamples = new ArrayList<>();
    dealSamples.add(
        new CrmRecord(
            "Deals",
            "d1",
            Map.of(
                "id",
                "d1",
                "Deal_Name",
                "CRM Renewal",
                "Field1__c",
                customNarrative,
                "old_field_2__c",
                "")));
    for (int i = 2; i <= 31; i++) {
      dealSamples.add(
          new CrmRecord(
              "Deals",
              "d" + i,
              Map.of(
                  "id",
                  "d" + i,
                  "Deal_Name",
                  "Deal " + i,
                  "Field1__c",
                  customNarrative,
                  "old_field_2__c",
                  "")));
    }
    CrmRecord rawDeal = dealSamples.get(0);
    when(apiClient.fetchDeals(any(CrmFetchQuery.class)))
        .thenReturn(
            new ZohoApiClient.ZohoModuleRecords<>(
                List.of(deal), List.of(rawDeal), List.copyOf(dealSamples)));

    ZohoAccount account =
        new ZohoAccount(
            "a1", "Acme", "Enterprise account", Instant.parse("2026-07-19T00:00:00Z"));
    CrmRecord rawAccount =
        new CrmRecord("Accounts", "a1", Map.of("id", "a1", "Account_Name", "Acme"));
    when(apiClient.fetchAccounts(any(CrmFetchQuery.class)))
        .thenReturn(
            new ZohoApiClient.ZohoModuleRecords<>(
                List.of(account), List.of(rawAccount), List.of(rawAccount)));
    when(apiClient.fetchContacts(any(CrmFetchQuery.class)))
        .thenReturn(new ZohoApiClient.ZohoModuleRecords<>(List.of(), List.of(), List.of()));
    when(apiClient.fetchDealNotes(any())).thenReturn(List.of());
    when(apiClient.fetchDealActivities(any())).thenReturn(List.of());

    CrmSchemaCache cache = new CrmSchemaCache();
    CrmConnection connection = new CrmConnection("org-1", "zoho", Map.of());
    ZohoSchemaAdapter adapter = new ZohoSchemaAdapter(apiClient);
    ZohoIngestionService service =
        new ZohoIngestionService(apiClient, adapter, cache, connection);

    ZohoIngestionService.IngestResult result =
        service.ingest(new ZohoIngestionOptions(90, 0.1));

    assertEquals(1, result.dealsFetched());
    assertEquals(customNarrative, result.rawRecordsByModule().get("Deals").get(0).getString("Field1__c"));
    assertEquals(
        CrmFieldType.NARRATIVE,
        result.schema().get("Deals").stream()
            .filter(field -> field.apiName().equals("Field1__c"))
            .findFirst()
            .orElseThrow()
            .type());
    assertEquals(
        CrmFieldType.SKIP,
        result.schema().get("Deals").stream()
            .filter(field -> field.apiName().equals("old_field_2__c"))
            .findFirst()
            .orElseThrow()
            .type());

    assertEquals(1, result.chunks().size());
    String narrative = result.chunks().get(0).narrativeText();
    assertTrue(narrative.contains("Additional deal fields"));
    assertTrue(narrative.contains("Field 1:"));
    assertTrue(narrative.contains("procurement team changed vendors"));
    assertFalse(narrative.contains("Old Field 2:"));
    assertTrue(narrative.contains("Structured:"));
    assertTrue(narrative.contains("Amount:"));

    assertTrue(
        result.dataGapReport().stream()
            .anyMatch(
                e ->
                    e.apiName().equals("old_field_2__c")
                        && e.type().equals("SKIP")
                        && !e.recommendation().isBlank()));
    assertTrue(
        result.dataGapReport().stream()
            .anyMatch(e -> e.apiName().equals("Field1__c") && e.type().equals("NARRATIVE")));

    assertTrue(cache.containsFresh("org-1", "Deals"));
    // Schema discovery once per module + one fetch per module — no extra sampling API calls.
    verify(apiClient, times(1)).discoverModuleSchema("Deals");
    verify(apiClient, times(1)).discoverModuleSchema("Accounts");
    verify(apiClient, times(1)).discoverModuleSchema("Contacts");
    verify(apiClient, times(1)).fetchDeals(any(CrmFetchQuery.class));
    verify(apiClient, times(1)).fetchAccounts(any(CrmFetchQuery.class));
    verify(apiClient, times(1)).fetchContacts(any(CrmFetchQuery.class));
    verify(apiClient, never()).fetchDeals();
    verify(apiClient, never()).fetchAccounts();
    verify(apiClient, never()).fetchContacts();
  }
}
