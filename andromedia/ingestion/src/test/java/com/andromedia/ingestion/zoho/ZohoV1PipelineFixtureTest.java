package com.andromedia.ingestion.zoho;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ZohoV1PipelineFixtureTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void fixturePipelineBuildsEnrichedChunksAndIndexesDemoWithFlag() throws Exception {
    List<ZohoDeal> deals = loadDeals();
    List<ZohoAccount> accounts = loadAccounts();
    List<ZohoContact> contacts = loadContacts();
    Map<String, List<ZohoNote>> notesByDeal = ZohoApiClient.indexNotesByDeal(loadNotes());
    Map<String, List<ZohoActivity>> activitiesByDeal =
        ZohoApiClient.indexActivitiesByDeal(loadActivities());

    Map<String, ZohoAccount> accountsById = new HashMap<>();
    for (ZohoAccount account : accounts) {
      accountsById.put(account.id(), account);
    }
    Map<String, List<ZohoContact>> contactsByAccountId = new HashMap<>();
    Map<String, ZohoContact> contactsById = new HashMap<>();
    for (ZohoContact contact : contacts) {
      contactsById.put(contact.id(), contact);
      if (!contact.accountId().isBlank()) {
        contactsByAccountId
            .computeIfAbsent(contact.accountId(), key -> new ArrayList<>())
            .add(contact);
      }
    }

    int indexed = 0;
    int skipped = 0;
    boolean sawAcme = false;
    boolean sawSofttwig = false;
    boolean sawCrmLicense = false;
    boolean sawDemo = false;

    for (ZohoDeal deal : deals) {
      ZohoAccount account =
          deal.accountId().isBlank() ? null : accountsById.get(deal.accountId());
      List<ZohoContact> linked =
          ZohoIngestionService.resolveContacts(deal, account, contactsByAccountId, contactsById);
      List<ZohoNote> notes = notesByDeal.getOrDefault(deal.id(), List.of());
      List<ZohoActivity> activities = activitiesByDeal.getOrDefault(deal.id(), List.of());
      ZohoRecordQualityScorer.QualityResult quality =
          ZohoRecordQualityScorer.scoreDealContext(
              deal, account, linked, notes, activities, 90);
      if (!ZohoRecordFilter.shouldIndex(deal, quality, 0.35)) {
        skipped++;
        continue;
      }
      var chunk =
          ZohoUnifiedChunkBuilder.build(deal, account, linked, notes, activities, quality);
      indexed++;
      if ("Acme Platform Renewal".equals(chunk.dealName())) {
        sawAcme = true;
        assertEquals("120000", chunk.amount());
        assertTrue(chunk.narrativeText().contains("Structured:"));
        assertTrue(chunk.narrativeText().contains("Amount: 120000"));
        assertTrue(chunk.narrativeText().contains("procurement"));
        assertTrue(chunk.narrativeText().contains("Ravi Kumar"));
        assertTrue(chunk.narrativeText().contains("Notes:"));
        assertTrue(chunk.narrativeText().contains("15% multi-year discount"));
        assertTrue(chunk.narrativeText().contains("[Task] Send revised quote"));
        assertEquals("3001", deal.contactId());
      }
      if ("Softtwig Expansion".equals(chunk.dealName())) {
        sawSofttwig = true;
        assertTrue(chunk.narrativeText().contains("Priya Nair"));
        assertTrue(chunk.narrativeText().contains("[Event] Softtwig expansion review"));
      }
      if ("CRM License Renewal".equals(chunk.dealName())) {
        sawCrmLicense = true;
        assertTrue(chunk.narrativeText().contains("Ravi Kumar"));
        assertTrue(chunk.narrativeText().contains("Contacts:"));
        assertFalse(chunk.narrativeText().contains("Account context:"));
        assertTrue(chunk.narrativeText().contains("Notes:"));
        assertTrue(chunk.narrativeText().contains("[Call] Discovery call with Rajesh"));
        assertTrue(quality.signals().contains("has_notes"));
        assertTrue(quality.signals().contains("has_activities"));
        assertTrue(quality.signals().contains("missing_account_description"));
      }
      if ("Test Deal Demo".equals(chunk.dealName())) {
        sawDemo = true;
        assertTrue(quality.signals().contains("test_demo_pattern"));
      }
    }

    assertEquals(5, indexed);
    assertEquals(0, skipped);
    assertTrue(sawAcme);
    assertTrue(sawSofttwig);
    assertTrue(sawCrmLicense);
    assertTrue(sawDemo);
    assertTrue(ZohoRecordQualityScorer.looksLikeTestRecord("Test Deal Demo"));
  }

  @Test
  void dealLevelContactEnrichesWhenContactBelongsToDifferentAccount() {
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
            "3001",
            "Ravi Kumar",
            "",
            "",
            "");
    ZohoAccount technova = new ZohoAccount("2005", "TechNova Solutions", "", Instant.now());
    ZohoContact ravi =
        new ZohoContact(
            "3001", "Ravi Kumar", "ravi@acme.com", "", "2001", "Acme Corp", Instant.now());

    Map<String, List<ZohoContact>> byAccount = Map.of("2001", List.of(ravi));
    Map<String, ZohoContact> byId = Map.of("3001", ravi);

    List<ZohoContact> linked =
        ZohoIngestionService.resolveContacts(deal, technova, byAccount, byId);

    assertEquals(1, linked.size());
    assertEquals("Ravi Kumar", linked.getFirst().name());

    ZohoRecordQualityScorer.QualityResult quality =
        ZohoRecordQualityScorer.scoreDealContext(
            deal, technova, linked, List.of(), List.of(), 90);
    var chunk =
        ZohoUnifiedChunkBuilder.build(
            deal, technova, linked, List.of(), List.of(), quality);
    assertTrue(chunk.narrativeText().contains("Contacts:"));
    assertTrue(chunk.narrativeText().contains("Ravi Kumar"));
    assertFalse(chunk.narrativeText().contains("Account context:"));
  }

  @Test
  void contactOnlyNotesAreNotJoinedToDeals() throws Exception {
    List<ZohoNote> notes = loadNotes();
    List<String> dealIds = List.of("1001", "1004", "1005");
    List<ZohoNote> dealNotes =
        notes.stream()
            .filter(n -> !n.parentDealId().isBlank() && dealIds.contains(n.parentDealId()))
            .toList();
    Map<String, List<ZohoNote>> byDeal = ZohoApiClient.indexNotesByDeal(dealNotes);
    assertFalse(byDeal.containsKey("3001"));
    assertTrue(byDeal.get("1001").size() >= 2);
    assertEquals(1, byDeal.get("1005").size());
    ZohoNote moduleless = ZohoApiClient.parseNote(
        MAPPER.readTree(
            "{\"id\":\"x\",\"Note_Content\":\"revenue\",\"Parent_Id\":{\"name\":\"ERP\",\"id\":\"1001\"}}"));
    assertEquals("1001", moduleless.parentDealId());
    ZohoNote contactNote =
        notes.stream().filter(n -> "4004".equals(n.id())).findFirst().orElseThrow();
    assertTrue(contactNote.parentDealId().isBlank());
  }

  @Test
  void staleEmptyDealScoresLowAndIsSkipped() {
    ZohoDeal stale =
        new ZohoDeal(
            "999",
            "Infosys Pilot",
            "Qualification",
            "45000",
            "",
            "",
            "2026-03-15",
            "",
            Instant.now().minus(200, ChronoUnit.DAYS),
            "2002",
            "Infosys Ltd",
            "",
            "",
            "",
            "",
            "");
    ZohoAccount account = new ZohoAccount("2002", "Infosys Ltd", "", Instant.EPOCH);
    ZohoRecordQualityScorer.QualityResult quality =
        ZohoRecordQualityScorer.scoreDealContext(
            stale, account, List.of(), List.of(), List.of(), 90);
    assertTrue(quality.signals().contains("stale_record"));
    assertTrue(quality.signals().contains("close_date_passed"));
    assertTrue(quality.signals().contains("no_contacts"));
    assertTrue(quality.signals().contains("missing_account_description"));
    assertFalse(ZohoRecordFilter.shouldIndex(stale, quality, 0.35));
  }

  @Test
  void placeholderContactEmailFlagsDemoPattern() {
    ZohoDeal deal =
        new ZohoDeal(
            "d1",
            "Real Looking Deal",
            "Negotiation",
            "10000",
            "",
            "",
            "2026-12-01",
            "Has real description text.",
            Instant.now(),
            "a1",
            "Acme",
            "c1",
            "Chau Kitzman (Sample)",
            "",
            "",
            "");
    ZohoContact sample =
        new ZohoContact(
            "c1",
            "Chau Kitzman (Sample)",
            "chau@noemail.invalid",
            "555-555-5555",
            "a1",
            "Acme",
            Instant.now());
    ZohoRecordQualityScorer.QualityResult quality =
        ZohoRecordQualityScorer.scoreDealContext(
            deal, null, List.of(sample), List.of(), List.of(), 90);
    assertTrue(quality.signals().contains("test_demo_pattern"));
    assertTrue(ZohoRecordFilter.shouldIndex(deal, quality, 0.35));
  }

  @Test
  void placeholderContactOnAccountFlagsSiblingDealWithoutContacts() {
    ZohoAccount account = new ZohoAccount("a1", "Acme Clean Name", "Real account description.", Instant.now());
    ZohoContact sample =
        new ZohoContact(
            "c1",
            "Chau Kitzman",
            "chau@noemail.invalid",
            "555-555-5555",
            "a1",
            "Acme Clean Name",
            Instant.now());
    ZohoDeal sibling =
        new ZohoDeal(
            "d2",
            "ERP Implementation",
            "Needs Analysis",
            "850000",
            "",
            "10",
            "2026-12-01",
            "Looks like a real deal description.",
            Instant.now(),
            "a1",
            "Acme Clean Name",
            "",
            "",
            "",
            "",
            "");
    ZohoDemoAccountDetector.DemoAccountIdentities demoAccounts =
        ZohoDemoAccountDetector.compute(List.of(account), List.of(sample), false, List.of());
    ZohoRecordQualityScorer.QualityResult quality =
        ZohoRecordQualityScorer.scoreDealContext(
            sibling, account, List.of(), List.of(), List.of(), 90, demoAccounts);
    assertTrue(quality.signals().contains("test_demo_pattern"));
  }

  @Test
  void sandboxSignatureFlagsCleanLookingDealWhenEnabled() {
    ZohoAccount account = new ZohoAccount("a9", "Chapman", "Privately held company.", Instant.now());
    ZohoDeal deal =
        new ZohoDeal(
            "d9",
            "Needs Analysis",
            "Negotiation/Review",
            "70000",
            "",
            "90",
            "2026-07-17",
            "Clean looking opportunity.",
            Instant.now(),
            "a9",
            "Chapman",
            "",
            "",
            "",
            "",
            "");
    ZohoDemoAccountDetector.DemoAccountIdentities enabled =
        ZohoDemoAccountDetector.compute(List.of(account), List.of(), true, List.of("Chapman"));
    ZohoDemoAccountDetector.DemoAccountIdentities disabled =
        ZohoDemoAccountDetector.compute(List.of(account), List.of(), false, List.of("Chapman"));

    assertTrue(
        ZohoRecordQualityScorer.scoreDealContext(
                deal, account, List.of(), List.of(), List.of(), 90, enabled)
            .signals()
            .contains("test_demo_pattern"));
    assertFalse(
        ZohoRecordQualityScorer.scoreDealContext(
                deal, account, List.of(), List.of(), List.of(), 90, disabled)
            .signals()
            .contains("test_demo_pattern"));
  }

  @Test
  void dealAccountNameFallbackFlagsUnresolvedAccount() {
    ZohoDeal deal =
        new ZohoDeal(
            "d3",
            "ERP Implementation",
            "Needs Analysis",
            "850000",
            "",
            "10",
            "2026-12-01",
            "Looks real.",
            Instant.now(),
            "",
            "King (Sample)",
            "",
            "",
            "",
            "",
            "");
    ZohoRecordQualityScorer.QualityResult quality =
        ZohoRecordQualityScorer.scoreDealContext(
            deal, null, List.of(), List.of(), List.of(), 90);
    assertTrue(quality.signals().contains("test_demo_pattern"));
  }

  @Test
  void parsesNextStepFromDealFixture() throws Exception {
    JsonNode root = loadFixture("deals.json");
    ZohoDeal renewal = null;
    for (JsonNode row : root.path("data")) {
      if ("CRM License Renewal".equals(row.path("Deal_Name").asText())) {
        renewal = ZohoApiClient.parseDeal(row);
      }
    }
    assertTrue(renewal != null);
    assertEquals("Schedule product demo", renewal.nextStep());
  }

  private static List<ZohoDeal> loadDeals() throws Exception {
    JsonNode root = loadFixture("deals.json");
    List<ZohoDeal> deals = new ArrayList<>();
    for (JsonNode row : root.path("data")) {
      deals.add(ZohoApiClient.parseDeal(row));
    }
    return deals;
  }

  private static List<ZohoAccount> loadAccounts() throws Exception {
    JsonNode root = loadFixture("accounts.json");
    List<ZohoAccount> accounts = new ArrayList<>();
    for (JsonNode row : root.path("data")) {
      accounts.add(ZohoApiClient.parseAccount(row));
    }
    return accounts;
  }

  private static List<ZohoContact> loadContacts() throws Exception {
    JsonNode root = loadFixture("contacts.json");
    List<ZohoContact> contacts = new ArrayList<>();
    for (JsonNode row : root.path("data")) {
      contacts.add(ZohoApiClient.parseContact(row));
    }
    return contacts;
  }

  private static List<ZohoNote> loadNotes() throws Exception {
    JsonNode root = loadFixture("notes.json");
    List<ZohoNote> notes = new ArrayList<>();
    for (JsonNode row : root.path("data")) {
      notes.add(ZohoApiClient.parseNote(row));
    }
    return notes;
  }

  private static List<ZohoActivity> loadActivities() throws Exception {
    JsonNode root = loadFixture("activities.json");
    List<ZohoActivity> activities = new ArrayList<>();
    for (JsonNode row : root.path("tasks").path("data")) {
      activities.add(ZohoApiClient.parseActivity(row, "task"));
    }
    for (JsonNode row : root.path("calls").path("data")) {
      activities.add(ZohoApiClient.parseActivity(row, "call"));
    }
    for (JsonNode row : root.path("events").path("data")) {
      activities.add(ZohoApiClient.parseActivity(row, "event"));
    }
    return activities;
  }

  private static JsonNode loadFixture(String name) throws Exception {
    try (InputStream in =
        ZohoV1PipelineFixtureTest.class.getResourceAsStream("/zoho/fixtures/" + name)) {
      if (in == null) {
        throw new IllegalStateException("Missing fixture: " + name);
      }
      return MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }
}
