package com.andromedia.ingestion.crm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FieldClassifierTest {

  private final FieldClassifier classifier = new FieldClassifier();

  @Test
  void layer1CurrencyIsStructuredRegardlessOfName() {
    CrmField field =
        classifier.classify(new RawCrmField("Field1__c", "Field1", "currency", 0, true));
    assertEquals(CrmFieldType.STRUCTURED, field.type());
    assertEquals(CrmFieldCategory.CONTEXT, field.category()); // custom default
  }

  @Test
  void layer1TextareaIsNarrative() {
    CrmField field =
        classifier.classify(new RawCrmField("Misc_Notes__c", "Misc Notes", "textarea", 0, true));
    assertEquals(CrmFieldType.NARRATIVE, field.type());
    assertEquals(CrmFieldCategory.CONTEXT, field.category());
  }

  @Test
  void layer1FormulaIsSkipped() {
    CrmField field =
        classifier.classify(new RawCrmField("Rollup_Total", "Rollup", "formula", 0, false));
    assertEquals(CrmFieldType.SKIP, field.type());
  }

  @Test
  void layer2ProseInLameNamedFieldIsNarrative() {
    List<String> samples = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      samples.add(
          "We lost this deal because the procurement team changed vendors at the last minute "
              + "and the budget was reallocated to another initiative.");
    }
    CrmField field =
        classifier.classify(new RawCrmField("Field1__c", "Field1", "text", 80, true), samples);
    assertEquals(CrmFieldType.NARRATIVE, field.type());
    assertTrue(field.completenessScore() > 50);
  }

  @Test
  void layer2NearEmptyFieldIsSkipped() {
    List<String> samples = new ArrayList<>();
    samples.add("real note that is long enough to count");
    for (int i = 0; i < 30; i++) {
      samples.add("");
    }
    // fill rate ~1/31 ≈ 0.03 < 0.05
    CrmField field =
        classifier.classify(new RawCrmField("old_field_2__c", "Old", "text", 50, true), samples);
    assertEquals(CrmFieldType.SKIP, field.type());
  }

  @Test
  void layer3AmbiguousUsesLlmWhenAvailable() {
    AtomicInteger calls = new AtomicInteger();
    FieldClassifier withLlm =
        new FieldClassifier(
            (raw, samples) -> {
              calls.incrementAndGet();
              return "A";
            });

    List<String> samples = ambiguousBandSamples();
    CrmField field =
        withLlm.classify(new RawCrmField("xyz_field__c", "Xyz", "text", 80, true), samples);
    assertEquals(CrmFieldType.NARRATIVE, field.type());
    assertEquals(1, calls.get());

    // cached — no second LLM call
    withLlm.classify(new RawCrmField("xyz_field__c", "Xyz", "text", 80, true), samples);
    assertEquals(1, calls.get());
  }

  @Test
  void layer3AmbiguousDefaultsToStructuredWithoutLlm() {
    List<String> samples = ambiguousBandSamples();
    CrmField field =
        classifier.classify(new RawCrmField("xyz_field__c", "Xyz", "text", 80, true), samples);
    assertEquals(CrmFieldType.STRUCTURED, field.type());
  }

  @Test
  void samplesAreCappedAt200() {
    List<String> samples = new ArrayList<>();
    for (int i = 0; i < 500; i++) {
      samples.add("x");
    }
    assertEquals(200, FieldClassifier.truncateSamples(samples).size());
  }

  @Test
  void completenessIgnoresPlaceholderValues() {
    List<String> samples =
        List.of(
            "Strong relationship with CTO and budget approved for Q3 rollout",
            "N/A",
            "test",
            "none",
            "TBD",
            "");
    assertEquals(17, FieldClassifier.completenessScore(samples)); // 1/6 ≈ 16.7 -> 17
  }

  @Test
  void parseLlmVerdictLetters() {
    assertEquals(CrmFieldType.NARRATIVE, FieldClassifier.parseLlmVerdict("A").orElseThrow());
    assertEquals(CrmFieldType.STRUCTURED, FieldClassifier.parseLlmVerdict("b").orElseThrow());
    assertEquals(CrmFieldType.SKIP, FieldClassifier.parseLlmVerdict("C - skip").orElseThrow());
    assertTrue(FieldClassifier.parseLlmVerdict("maybe").isEmpty());
  }

  /** ~40% fill, avg length ~80 — ambiguous text band for Layer 3. */
  private static List<String> ambiguousBandSamples() {
    List<String> samples = new ArrayList<>();
    String mid =
        "Budget approved waiting legal sign-off on the MSA before we can proceed further now.";
    // length ~80
    for (int i = 0; i < 4; i++) {
      samples.add(mid);
    }
    for (int i = 0; i < 6; i++) {
      samples.add("");
    }
    // 4/10 fill = 0.4, avg length of filled ~80
    return samples;
  }
}
