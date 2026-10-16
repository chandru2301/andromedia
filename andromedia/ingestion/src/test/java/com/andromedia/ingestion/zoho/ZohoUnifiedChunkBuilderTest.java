package com.andromedia.ingestion.zoho;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.ZohoDealContextChunk;
import com.andromedia.ingestion.crm.CrmField;
import com.andromedia.ingestion.crm.CrmFieldCategory;
import com.andromedia.ingestion.crm.CrmFieldType;
import com.andromedia.ingestion.crm.CrmRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ZohoUnifiedChunkBuilderTest {

  @Test
  void buildsDeterministicNarrativeWithProvenance() {
    ZohoDeal deal =
        new ZohoDeal(
            "1001",
            "Acme Platform Renewal",
            "Negotiation",
            "120000",
            "100000",
            "60",
            "2026-08-15",
            "Discount discussion with procurement.",
            Instant.parse("2026-07-10T05:00:00Z"),
            "2001",
            "Acme Corp",
            "3001",
            "Ravi Kumar",
            "Alice Owner",
            "Partner",
            "");
    ZohoAccount account =
        new ZohoAccount(
            "2001",
            "Acme Corp",
            "Enterprise SaaS buyer.",
            Instant.parse("2026-07-09T05:30:00Z"));
    ZohoContact contact =
        new ZohoContact(
            "3001",
            "Ravi Kumar",
            "ravi@acme.com",
            "555-0100",
            "2001",
            "Acme Corp",
            Instant.parse("2026-07-08T04:30:00Z"));
    ZohoNote note =
        new ZohoNote(
            "4001",
            "Procurement discount ask",
            "Buyer asked for 15% multi-year discount.",
            "1001",
            Instant.parse("2026-07-11T03:30:00Z"));
    ZohoActivity task =
        new ZohoActivity(
            "5001",
            "task",
            "Send revised quote",
            "Not Started",
            "Include multi-year pricing option.",
            "1001",
            Instant.parse("2026-07-12T04:30:00Z"),
            Instant.parse("2026-07-12T04:30:00Z"));
    ZohoRecordQualityScorer.QualityResult quality =
        new ZohoRecordQualityScorer.QualityResult(
            0.92, List.of("has_description", "has_notes", "has_activities"));

    ZohoDealContextChunk chunk =
        ZohoUnifiedChunkBuilder.build(
            deal, account, List.of(contact), List.of(note), List.of(task), quality);

    assertEquals("deal_context", chunk.entityType());
    assertEquals("120000", chunk.amount());
    assertEquals("100000", chunk.expectedRevenue());
    assertEquals("60", chunk.probability());
    assertEquals("2026-08-15", chunk.closingDate());
    assertEquals("", chunk.nextStep());
    assertTrue(chunk.narrativeText().startsWith("[ZOHO] Deal"));
    assertTrue(chunk.narrativeText().contains("Structured:"));
    assertTrue(chunk.narrativeText().contains("Amount: 120000"));
    assertTrue(chunk.narrativeText().contains("Close date: 2026-08-15"));
    assertTrue(chunk.narrativeText().contains("Discount discussion"));
    assertTrue(chunk.narrativeText().contains("Ravi Kumar"));
    assertTrue(chunk.narrativeText().contains("Notes:"));
    assertTrue(chunk.narrativeText().contains("Procurement discount ask"));
    assertTrue(chunk.narrativeText().contains("Activities:"));
    assertTrue(chunk.narrativeText().contains("[Task] Send revised quote"));
    assertTrue(chunk.noteSnippets().stream().anyMatch(n -> n.contains("15% multi-year")));
    assertTrue(chunk.provenance().stream().anyMatch(p -> "Notes".equals(p.module())));
    assertTrue(chunk.provenance().stream().anyMatch(p -> "Tasks".equals(p.module())));
    assertEquals(0.92, chunk.qualityScore());
  }

  @Test
  void includesNextStepInNarrativeAndProvenance() {
    ZohoDeal deal =
        new ZohoDeal(
            "1001",
            "Acme Platform Renewal",
            "Negotiation",
            "120000",
            "100000",
            "60",
            "2026-08-15",
            "Discount discussion with procurement.",
            Instant.parse("2026-07-10T05:00:00Z"),
            "2001",
            "Acme Corp",
            "3001",
            "Ravi Kumar",
            "Alice Owner",
            "Partner",
            "Schedule product demo");
    ZohoRecordQualityScorer.QualityResult quality =
        new ZohoRecordQualityScorer.QualityResult(0.9, List.of("has_description"));
    ZohoDealContextChunk chunk =
        ZohoUnifiedChunkBuilder.build(deal, null, List.of(), List.of(), List.of(), quality);
    assertEquals("Schedule product demo", chunk.nextStep());
    assertTrue(chunk.narrativeText().contains("Next step:"));
    assertTrue(chunk.narrativeText().contains("Schedule product demo"));
    assertTrue(
        chunk.provenance().stream()
            .anyMatch(p -> "Deals".equals(p.module()) && "Next_Step".equals(p.field())));
  }

  @Test
  void notesAppearEvenWithoutAccountDescription() {
    ZohoDeal deal =
        new ZohoDeal(
            "1005",
            "CRM License Renewal",
            "Qualification",
            "250000",
            "",
            "",
            "2026-07-24",
            "Annual CRM subscription renewal.",
            Instant.now(),
            "2005",
            "TechNova Solutions",
            "",
            "",
            "",
            "",
            "");
    ZohoAccount account = new ZohoAccount("2005", "TechNova Solutions", "", Instant.now());
    ZohoNote note =
        new ZohoNote(
            "4003",
            "Renewal discussion",
            "Rajesh confirmed annual CRM subscription renewal timeline.",
            "1005",
            Instant.now());
    ZohoRecordQualityScorer.QualityResult quality =
        ZohoRecordQualityScorer.scoreDealContext(
            deal, account, List.of(), List.of(note), List.of(), 90);

    assertTrue(quality.signals().contains("has_notes"));
    assertTrue(quality.signals().contains("missing_account_description"));
    var chunk =
        ZohoUnifiedChunkBuilder.build(deal, account, List.of(), List.of(note), List.of(), quality);
    assertTrue(chunk.narrativeText().contains("Notes:"));
    assertTrue(chunk.narrativeText().contains("Rajesh confirmed"));
    assertFalse(chunk.narrativeText().contains("Account context:"));
    assertTrue(chunk.narrativeText().contains("Structured:"));
    assertTrue(chunk.narrativeText().contains("Amount: 250000"));
  }

  @Test
  void sparseDealStillGetsStructuredSummaryForRetrieval() {
    ZohoDeal deal =
        new ZohoDeal(
            "1005",
            "CRM License Renewal",
            "Qualification",
            "250000",
            "",
            "10",
            "2026-07-24",
            "",
            Instant.now(),
            "2005",
            "TechNova Solutions",
            "",
            "",
            "",
            "",
            "");
    ZohoAccount account = new ZohoAccount("2005", "TechNova Solutions", "", Instant.now());
    ZohoRecordQualityScorer.QualityResult quality =
        ZohoRecordQualityScorer.scoreDealContext(
            deal, account, List.of(), List.of(), List.of(), 90);

    var chunk =
        ZohoUnifiedChunkBuilder.build(deal, account, List.of(), List.of(), List.of(), quality);

    assertTrue(chunk.narrativeText().contains("Structured:"));
    assertTrue(chunk.narrativeText().contains("Account: TechNova Solutions"));
    assertTrue(chunk.narrativeText().contains("Amount: 250000"));
    assertTrue(chunk.narrativeText().contains("Probability: 10%"));
    assertTrue(chunk.narrativeText().contains("Close date: 2026-07-24"));
    assertTrue(chunk.narrativeText().contains("Next step: (not set)"));
  }

  @Test
  void enrichesNarrativeWithDiscoveredCustomFieldsWithoutCanonicalAmount() {
    ZohoDeal deal =
        new ZohoDeal(
            "1006",
            "Acme Expansion",
            "Proposal",
            "90000",
            "70000",
            "40",
            "2026-09-01",
            "Core deal description.",
            Instant.parse("2026-07-12T05:00:00Z"),
            "2006",
            "Acme Corp",
            "",
            "",
            "Alice Owner",
            "Web",
            "Send proposal");
    ZohoAccount account =
        new ZohoAccount(
            "2006",
            "Acme Corp",
            "Enterprise SaaS buyer.",
            Instant.parse("2026-07-11T05:00:00Z"));
    ZohoRecordQualityScorer.QualityResult quality =
        ZohoRecordQualityScorer.scoreDealContext(
            deal, account, List.of(), List.of(), List.of(), 90);

    CrmRecord dealRecord =
        new CrmRecord(
            "Deals",
            "1006",
            Map.of(
                "Field1__c",
                "Procurement changed vendors after a lengthy legal review.",
                "Amount",
                "90000",
                "Description",
                "Core deal description."));
    List<CrmField> dealFields =
        List.of(
            new CrmField(
                "Field1__c",
                "Field 1",
                CrmFieldType.NARRATIVE,
                CrmFieldCategory.CONTEXT,
                true,
                false,
                80),
            new CrmField(
                "Amount",
                "Amount",
                CrmFieldType.STRUCTURED,
                CrmFieldCategory.OUTCOME,
                false,
                false,
                100),
            new CrmField(
                "Description",
                "Description",
                CrmFieldType.NARRATIVE,
                CrmFieldCategory.CONTEXT,
                false,
                false,
                100));
    CrmRecord accountRecord =
        new CrmRecord(
            "Accounts",
            "2006",
            Map.of("Industry_Notes__c", "Prefers annual contracts with opt-out."));
    List<CrmField> accountFields =
        List.of(
            new CrmField(
                "Industry_Notes__c",
                "Industry Notes",
                CrmFieldType.NARRATIVE,
                CrmFieldCategory.CONTEXT,
                true,
                false,
                70));

    ZohoDealContextChunk chunk =
        ZohoUnifiedChunkBuilder.build(
            deal,
            account,
            List.of(),
            List.of(),
            List.of(),
            quality,
            dealRecord,
            dealFields,
            accountRecord,
            accountFields);

    assertTrue(chunk.narrativeText().contains("Additional deal fields"));
    assertTrue(chunk.narrativeText().contains("Field 1:"));
    assertTrue(chunk.narrativeText().contains("Procurement changed vendors"));
    assertTrue(chunk.narrativeText().contains("Additional account fields"));
    assertTrue(chunk.narrativeText().contains("Industry Notes:"));
    assertTrue(chunk.narrativeText().contains("Structured:"));
    assertTrue(chunk.narrativeText().contains("Amount: 90000"));
    assertTrue(
        chunk.provenance().stream()
            .anyMatch(p -> "Deals".equals(p.module()) && "Field1__c".equals(p.field())));
    assertTrue(
        chunk.provenance().stream()
            .anyMatch(
                p -> "Accounts".equals(p.module()) && "Industry_Notes__c".equals(p.field())));
    assertFalse(
        chunk.provenance().stream()
            .anyMatch(p -> "Deals".equals(p.module()) && "Amount".equals(p.field())));
  }
}
