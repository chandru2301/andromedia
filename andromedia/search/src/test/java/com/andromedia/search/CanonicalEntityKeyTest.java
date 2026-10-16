package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class CanonicalEntityKeyTest {

  @Test
  void stripsOrgSuffixesAndArticles() {
    assertEquals("al habtoor", CanonicalEntityKey.key("Al Habtoor Group"));
    assertEquals("al habtoor", CanonicalEntityKey.key("the Al Habtoor"));
    assertEquals("al habtoor", CanonicalEntityKey.key("Al Habtoor – Phase 2 Expansion"));
  }

  @Test
  void matchesShortFormToFullDealTitle() {
    assertTrue(CanonicalEntityKey.matches("Al Habtoor", "Al Habtoor – Phase 2 Expansion"));
    assertTrue(CanonicalEntityKey.matches("Damac", "Damac Properties"));
    assertTrue(CanonicalEntityKey.matches("the Damac deal", "Damac – Q3 Analytics Package"));
    assertFalse(CanonicalEntityKey.matches("Al Maha", "Al Habtoor Group"));
    assertFalse(CanonicalEntityKey.matches("King Chapman Mega Deal", "King"));
    assertFalse(CanonicalEntityKey.matches("King Chapman Mega Deal", "King ERP"));
  }

  @Test
  void queryMentionsRecordAllowsConversationalExtras() {
    assertTrue(
        CanonicalEntityKey.queryMentionsRecord(
            "Bright Horizon Fatima stalled", "Bright Horizon - Enterprise License"));
    assertTrue(
        CanonicalEntityKey.queryMentionsRecord(
            "What's going on with Bright Horizon and Fatima?", "Bright Horizon Realty"));
    assertFalse(CanonicalEntityKey.queryMentionsRecord("King Chapman Mega Deal", "King"));
  }

  @Test
  void stripsQuestionBoilerplateFromValueQueries() {
    assertEquals(
        "al habtoor",
        CanonicalEntityKey.key("What's the current deal value for Al Habtoor?"));
  }

  @Test
  void stripsUpdateAndHappeningBoilerplateForMeraas() {
    assertEquals("meraas", CanonicalEntityKey.key("Any update on Meraas?"));
    assertEquals("meraas", CanonicalEntityKey.key("What's happening with Meraas?"));
    assertEquals("meraas", CanonicalEntityKey.key("Meraas Holding"));
    assertEquals("meraas", CanonicalEntityKey.key("Meraas – Annual Contract Renewal"));
    assertTrue(CanonicalEntityKey.matches("Any update on Meraas?", "Meraas Holding"));
    assertTrue(
        CanonicalEntityKey.matches(
            "What's happening with Meraas?", "Meraas – Annual Contract Renewal"));
  }

  @Test
  void coveragePairUsesSourceAndEntity() {
    assertEquals(
        "ZOHO|al habtoor",
        CanonicalEntityKey.coveragePair("zoho", "Al Habtoor – Phase 2 Expansion"));
  }

  @Test
  void stripsDealTypeTailSoAccountAndDealShareKey() {
    assertEquals("bright horizon", CanonicalEntityKey.key("Bright Horizon Realty"));
    assertEquals("bright horizon", CanonicalEntityKey.key("Bright Horizon - Enterprise License"));
    assertEquals("bright horizon", CanonicalEntityKey.key("Bright Horizon Professional"));
    assertTrue(
        CanonicalEntityKey.matches("Bright Horizon", "Bright Horizon - Enterprise License"));
  }

  @Test
  void discriminatorTokensKeepDealTypeWords() {
    assertEquals(
        Set.of("enterprise", "license"),
        CanonicalEntityKey.discriminatorTokens(
            "Bright Horizon - Enterprise License", "Bright Horizon Realty"));
    assertEquals(
        Set.of("renewal"),
        CanonicalEntityKey.discriminatorTokens("Acme Renewal", "Acme Corp"));
  }
}
