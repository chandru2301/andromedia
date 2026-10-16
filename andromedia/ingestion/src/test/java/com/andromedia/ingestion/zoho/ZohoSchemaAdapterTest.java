package com.andromedia.ingestion.zoho;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.andromedia.ingestion.crm.CrmConnection;
import com.andromedia.ingestion.crm.CrmFetchOptions;
import com.andromedia.ingestion.crm.CrmFetchQuery;
import com.andromedia.ingestion.crm.CrmField;
import com.andromedia.ingestion.crm.CrmFieldCategory;
import com.andromedia.ingestion.crm.CrmFieldType;
import com.andromedia.ingestion.crm.CrmModule;
import com.andromedia.ingestion.crm.FieldClassifier;
import com.andromedia.ingestion.crm.RawCrmField;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ZohoSchemaAdapterTest {

  private static final CrmConnection CONNECTION =
      new CrmConnection("org-1", "zoho", Map.of());
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void discoversCanonicalAndCustomModules() throws IOException {
    ZohoApiClient apiClient = mock(ZohoApiClient.class);
    when(apiClient.discoverModules())
        .thenReturn(
            List.of(
                new ZohoApiClient.ZohoModuleDefinition("Deals", "Deals", false),
                new ZohoApiClient.ZohoModuleDefinition(
                    "Custom_Pipeline_Data__c", "Pipeline Data", true)));
    ZohoSchemaAdapter adapter =
        new ZohoSchemaAdapter(apiClient, new FieldClassifier(), FIXED_CLOCK);

    List<CrmModule> modules = adapter.discoverModules(CONNECTION);

    assertEquals(CrmModule.ROLE_DEALS, modules.get(0).role());
    assertFalse(modules.get(0).isCustom());
    assertEquals(CrmModule.ROLE_UNKNOWN, modules.get(1).role());
    assertTrue(modules.get(1).isCustom());
  }

  @Test
  void discoversClassifiesAndDropsSkippedFields() throws IOException {
    ZohoApiClient apiClient = mock(ZohoApiClient.class);
    when(apiClient.discoverModuleSchema("Deals"))
        .thenReturn(
            new ZohoApiClient.ZohoModuleSchema(
                "Deals",
                List.of(
                    field("Deal_Name", "Deal Name", "text", 80, false),
                    field("Description", "Description", "textarea", 2_000, false),
                    field("Amount", "Amount", "currency", 0, false),
                    field("Field1__c", "Field 1", "text", 80, true),
                    field("Rollup_Total", "Rollup Total", "formula", 0, true))));
    ZohoSchemaAdapter adapter =
        new ZohoSchemaAdapter(apiClient, new FieldClassifier(), FIXED_CLOCK);

    List<CrmField> fields = adapter.discoverFields(CONNECTION, "Deals");

    assertEquals(4, fields.size());
    assertEquals(CrmFieldType.STRUCTURED, find(fields, "Deal_Name").type());
    assertEquals(CrmFieldCategory.IDENTITY, find(fields, "Deal_Name").category());
    assertEquals(CrmFieldType.NARRATIVE, find(fields, "Description").type());
    assertEquals(CrmFieldType.STRUCTURED, find(fields, "Amount").type());
    assertEquals(CrmFieldCategory.OUTCOME, find(fields, "Amount").category());
    assertEquals(CrmFieldCategory.CONTEXT, find(fields, "Field1__c").category());
    assertTrue(fields.stream().noneMatch(field -> field.apiName().equals("Rollup_Total")));
  }

  @Test
  void buildFetchQueryUsesNonSkipFieldsInStableOrder() {
    ZohoSchemaAdapter adapter =
        new ZohoSchemaAdapter(mock(ZohoApiClient.class), new FieldClassifier(), FIXED_CLOCK);
    List<CrmField> fields =
        List.of(
            crmField("Deal_Name", CrmFieldType.STRUCTURED),
            crmField("Description", CrmFieldType.NARRATIVE),
            crmField("Field1__c", CrmFieldType.NARRATIVE),
            crmField("Formula_Total", CrmFieldType.SKIP),
            crmField("Deal_Name", CrmFieldType.STRUCTURED));

    CrmFetchQuery query =
        adapter.buildFetchQuery("Deals", fields, new CrmFetchOptions(90, 500));

    assertEquals("/crm/v3/Deals", query.path());
    assertEquals(ZohoApiClient.DEAL_FIELDS + ",Field1__c", query.fieldList());
    assertEquals("Modified_Time:greater_than:2026-04-21T00:00:00+00:00", query.criteria());
    assertEquals(500, query.maxRecords());
  }

  @Test
  void buildFetchQueryUsesInstantWatermarkForIncremental() {
    ZohoSchemaAdapter adapter =
        new ZohoSchemaAdapter(mock(ZohoApiClient.class), new FieldClassifier(), FIXED_CLOCK);
    Instant since = Instant.parse("2026-07-21T10:00:00Z");
    CrmFetchQuery query =
        adapter.buildFetchQuery(
            "Deals",
            List.of(crmField("Deal_Name", CrmFieldType.STRUCTURED)),
            new CrmFetchOptions(90, 500, since, false));

    assertEquals("Modified_Time:greater_than:2026-07-21T10:00:00+00:00", query.criteria());
  }

  @Test
  void buildFetchQueryOmitsCriteriaForFullSync() {
    ZohoSchemaAdapter adapter =
        new ZohoSchemaAdapter(mock(ZohoApiClient.class), new FieldClassifier(), FIXED_CLOCK);
    CrmFetchQuery query =
        adapter.buildFetchQuery(
            "Deals",
            List.of(crmField("Deal_Name", CrmFieldType.STRUCTURED)),
            new CrmFetchOptions(90, 500, null, true));

    assertEquals(null, query.criteria());
  }

  @Test
  void classifyFieldUsesUniversalClassifierRules() {
    ZohoSchemaAdapter adapter =
        new ZohoSchemaAdapter(mock(ZohoApiClient.class), new FieldClassifier(), FIXED_CLOCK);

    CrmField result =
        adapter.classifyField(
            new RawCrmField(
                "Loss_Reason_Detail__c", "Loss Reason Detail", "textarea", 1_000, true));

    assertEquals(CrmFieldType.NARRATIVE, result.type());
    assertEquals(CrmFieldCategory.CONTEXT, result.category());
    assertTrue(result.isCustom());
  }

  private static ZohoApiClient.ZohoFieldSchema field(
      String apiName, String label, String dataType, int length, boolean custom) {
    return new ZohoApiClient.ZohoFieldSchema(
        apiName, label, dataType, length, custom, dataType.contains("text"));
  }

  private static CrmField crmField(String apiName, CrmFieldType type) {
    return new CrmField(
        apiName, apiName, type, CrmFieldCategory.CONTEXT, true, false, 0);
  }

  private static CrmField find(List<CrmField> fields, String apiName) {
    return fields.stream()
        .filter(field -> field.apiName().equals(apiName))
        .findFirst()
        .orElseThrow();
  }
}
