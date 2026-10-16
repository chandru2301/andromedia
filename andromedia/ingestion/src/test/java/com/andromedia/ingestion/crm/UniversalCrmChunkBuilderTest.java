package com.andromedia.ingestion.crm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UniversalCrmChunkBuilderTest {

  @Test
  void narrativeFieldsAreIncludedAndExcludedNamesAreSkipped() {
    CrmRecord record =
        new CrmRecord(
            "Deals",
            "d1",
            Map.of(
                "Description", "Already covered",
                "Field1__c",
                    "Procurement changed vendors after a lengthy legal review and budget freeze.",
                "Amount", "50000"));
    List<CrmField> fields =
        List.of(
            field("Description", "Description", CrmFieldType.NARRATIVE),
            field("Field1__c", "Field 1", CrmFieldType.NARRATIVE),
            field("Amount", "Amount", CrmFieldType.STRUCTURED));

    String extras =
        UniversalCrmChunkBuilder.buildNarrativeExtras(
            record, fields, Set.of("Description", "Amount"));

    assertTrue(extras.contains("Field 1:"));
    assertTrue(extras.contains("Procurement changed vendors"));
    assertFalse(extras.contains("Already covered"));
    assertFalse(extras.contains("Amount:"));
  }

  @Test
  void structuredExtrasAreFoldedIntoNarrative() {
    CrmRecord record =
        new CrmRecord("Deals", "d1", Map.of("Key_Stakeholder__c", "Jordan <jordan@acme.com>"));
    List<CrmField> fields =
        List.of(field("Key_Stakeholder__c", "Key Stakeholder", CrmFieldType.STRUCTURED));

    String extras =
        UniversalCrmChunkBuilder.buildNarrativeExtras(record, fields, Set.of());

    assertEquals("Key Stakeholder: Jordan <jordan@acme.com>", extras);
  }

  @Test
  void lookupObjectsRenderName() {
    assertEquals(
        "Acme",
        UniversalCrmChunkBuilder.renderValue(Map.of("id", "a1", "name", "Acme")));
  }

  @Test
  void appendSectionRespectsBudget() {
    String combined =
        UniversalCrmChunkBuilder.appendSection(
            "base", "Additional deal fields", "Field 1: long value here", 24);
    assertTrue(combined.startsWith("base"));
    assertTrue(combined.endsWith("…"));
    assertEquals(24, combined.length());
  }

  private static CrmField field(String apiName, String label, CrmFieldType type) {
    return new CrmField(apiName, label, type, CrmFieldCategory.CONTEXT, true, false, 50);
  }
}
