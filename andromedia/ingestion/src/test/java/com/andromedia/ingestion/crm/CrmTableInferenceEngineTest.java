package com.andromedia.ingestion.crm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CrmTableInferenceEngineTest {

  private final CrmTableInferenceEngine engine = new CrmTableInferenceEngine();

  @Test
  void infersCustomDealsModuleFromWeightedColumns() {
    CrmTableType result =
        engine.inferTableType(
            List.of(
                "Opp_Value__c",
                "Current_Stage__c",
                "Expected_Close__c",
                "Win_Probability__c",
                "Client_Company__c"));

    assertEquals(CrmTableType.DEALS, result);
  }

  @Test
  void infersContactsFromContactColumns() {
    CrmTableType result =
        engine.inferTableType(
            List.of("First_Name", "Last_Name", "Email_Address", "Mobile", "Account_Name"));

    assertEquals(CrmTableType.CONTACTS, result);
  }

  @Test
  void infersDealsFromLameColumnsAndValuePatterns() {
    Map<String, List<String>> samples =
        Map.of(
            "col1", List.of("$45,000", "$18,500", ""),
            "col2", List.of("Closed Won", "Negotiation", "Proposal"),
            "col3", List.of("ACME", "Globex", "Initech"));

    CrmTableType result =
        engine.inferWithFuzzyMatch(List.of("col1", "col2", "col3"), samples);

    assertEquals(CrmTableType.DEALS, result);
  }

  @Test
  void currencyOnlyIsNotEnoughToCallTableDeals() {
    CrmTableType result =
        engine.inferFromDataPatterns(
            Map.of("col1", List.of("$45,000", "$18,500", "$1,000")));

    assertEquals(CrmTableType.UNKNOWN, result);
  }

  @Test
  void repeatedRowsContributeOnlyOncePerColumn() {
    List<String> repeatedCurrency = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      repeatedCurrency.add("$45,000");
    }

    CrmTableType result =
        engine.inferFromDataPatterns(Map.of("col1", repeatedCurrency));

    assertEquals(CrmTableType.UNKNOWN, result);
  }

  @Test
  void stageEvidenceOutweighsGenericCurrencyEvidence() {
    CrmTableType result =
        engine.inferFromDataPatterns(
            Map.of(
                "col1", List.of("$45,000", "$18,500"),
                "col2", List.of("Closed Won", "Qualification")));

    assertEquals(CrmTableType.DEALS, result);
  }

  @Test
  void fuzzyNamesCanInferDeals() {
    CrmTableType result =
        engine.inferWithFuzzyMatch(
            List.of("opp_val", "close_dt", "win_prob"), Map.of());

    assertEquals(CrmTableType.DEALS, result);
  }

  @Test
  void knownStandardModuleIsNeverReInferred() {
    CrmModule deals = new CrmModule("Deals", "Deals", CrmModule.ROLE_DEALS, false);

    CrmTableType result =
        engine.inferModule(
            deals,
            List.of("Email", "Phone", "First_Name"),
            Map.of("Email", List.of("person@example.com")));

    assertEquals(CrmTableType.DEALS, result);
  }

  @Test
  void customModuleIsEligibleForInference() {
    CrmModule custom =
        new CrmModule("Custom_Pipeline_Data__c", "Pipeline Data", CrmModule.ROLE_UNKNOWN, true);

    CrmTableType result =
        engine.inferModule(
            custom,
            List.of("Email", "Phone", "First_Name", "Last_Name"),
            Map.of());

    assertEquals(CrmTableType.CONTACTS, result);
  }

  @Test
  void mislabeledCustomModuleInfersDealsFromDealLikeColumns() {
    CrmModule mislabeled =
        new CrmModule("Custom_Module_X__c", "Module X", CrmModule.ROLE_UNKNOWN, true);

    CrmTableType result =
        engine.inferModule(
            mislabeled,
            List.of(
                "Opp_Value__c",
                "Current_Stage__c",
                "Expected_Close__c",
                "Win_Probability__c",
                "Client_Company__c"),
            Map.of());

    assertEquals(CrmTableType.DEALS, result);
  }

  @Test
  void signaturesInitializeWithoutDuplicateLiteralFailure() {
    Map<CrmTableType, Map<String, Integer>> signatures =
        CrmTableInferenceEngine.signatures();

    assertNotNull(signatures);
    assertFalse(signatures.isEmpty());
    assertEquals(
        signatures.get(CrmTableType.DEALS).size(),
        signatures.get(CrmTableType.DEALS).keySet().stream().distinct().count());
  }

  @Test
  void unknownCanUseOptionalLlmFallback() {
    AtomicInteger calls = new AtomicInteger();
    CrmTableInferenceEngine withLlm =
        new CrmTableInferenceEngine(
            (columns, samples) -> {
              calls.incrementAndGet();
              return CrmTableType.SUPPORT_CASES;
            });

    CrmTableType result =
        withLlm.inferWithFuzzyMatch(List.of("col1", "col2"), Map.of());

    assertEquals(CrmTableType.SUPPORT_CASES, result);
    assertEquals(1, calls.get());
  }

  @Test
  void noSignalsReturnsUnknown() {
    assertEquals(
        CrmTableType.UNKNOWN,
        engine.inferWithFuzzyMatch(List.of("col1", "misc", "data"), Map.of()));
  }
}
