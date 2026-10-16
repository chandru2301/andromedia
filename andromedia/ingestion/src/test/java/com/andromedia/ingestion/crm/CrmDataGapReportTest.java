package com.andromedia.ingestion.crm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CrmDataGapReportTest {

  @Test
  void recommendsLowCompletenessNarrativeAndOutcomeFields() {
    Map<String, List<CrmField>> schema =
        Map.of(
            "Deals",
            List.of(
                new CrmField(
                    "Loss_Reason_Detail",
                    "Loss Reason Detail",
                    CrmFieldType.NARRATIVE,
                    CrmFieldCategory.CONTEXT,
                    true,
                    false,
                    23),
                new CrmField(
                    "Amount",
                    "Amount",
                    CrmFieldType.STRUCTURED,
                    CrmFieldCategory.OUTCOME,
                    false,
                    false,
                    40),
                new CrmField(
                    "Description",
                    "Description",
                    CrmFieldType.NARRATIVE,
                    CrmFieldCategory.CONTEXT,
                    false,
                    false,
                    90)));

    List<CrmDataGapReport.Entry> report = CrmDataGapReport.build(schema);

    assertEquals(3, report.size());
    CrmDataGapReport.Entry loss =
        report.stream()
            .filter(e -> e.apiName().equals("Loss_Reason_Detail"))
            .findFirst()
            .orElseThrow();
    assertEquals(23, loss.completenessScore());
    assertEquals("NARRATIVE", loss.type());
    assertTrue(loss.recommendation().contains("Only 23% of deals fill Loss Reason Detail"));
    assertTrue(loss.recommendation().contains("make it required"));

    CrmDataGapReport.Entry amount =
        report.stream().filter(e -> e.apiName().equals("Amount")).findFirst().orElseThrow();
    assertTrue(amount.recommendation().contains("Only 40% of deals fill Amount"));

    CrmDataGapReport.Entry description =
        report.stream()
            .filter(e -> e.apiName().equals("Description"))
            .findFirst()
            .orElseThrow();
    assertEquals("", description.recommendation());
  }

  @Test
  void recommendsConsistentlyEmptySkipFields() {
    List<CrmDataGapReport.Entry> report =
        CrmDataGapReport.build(
            Map.of(
                "Deals",
                List.of(
                    new CrmField(
                        "old_field_2__c",
                        "Old Field 2",
                        CrmFieldType.SKIP,
                        CrmFieldCategory.CONTEXT,
                        true,
                        true,
                        3))));

    assertEquals(1, report.size());
    assertTrue(report.get(0).recommendation().contains("consistently empty"));
    assertFalse(CrmDataGapReport.toMaps(report).get(0).get("recommendation").toString().isBlank());
  }
}
