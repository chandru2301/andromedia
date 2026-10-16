package com.andromedia.ingestion.crm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Three-layer CRM field classifier.
 *
 * <ol>
 *   <li>Data type rules (reliable regardless of lame field names)
 *   <li>First-page sample analysis (fill rate + average length) — max {@link #MAX_SAMPLE_RECORDS}
 *       values, never triggering extra API calls
 *   <li>Optional cheap LLM fallback for genuinely ambiguous short text fields
 * </ol>
 *
 * <p>When Layers 1–2 are undecided and the LLM is unavailable, defaults to {@link
 * CrmFieldType#STRUCTURED}.
 */
public final class FieldClassifier {

  public static final int MAX_SAMPLE_RECORDS = 200;

  /** Optional one-letter LLM classifier (A=NARRATIVE, B=STRUCTURED, C=SKIP). */
  @FunctionalInterface
  public interface LlmClassifier {
    /**
     * @return A, B, or C (case-insensitive); blank/null means unavailable
     */
    String classify(RawCrmField rawField, List<String> sampleValues);
  }

  private final LlmClassifier llmClassifier;
  private final ConcurrentMap<String, CrmFieldType> llmVerdictCache;

  public FieldClassifier() {
    this(null);
  }

  public FieldClassifier(LlmClassifier llmClassifier) {
    this.llmClassifier = llmClassifier;
    this.llmVerdictCache = new ConcurrentHashMap<>();
  }

  /** Layer-1-only classification when no sample values are available yet. */
  public CrmField classify(RawCrmField rawField) {
    return classify(rawField, List.of());
  }

  /**
   * Classify using Layers 1–3. Sample values must come from the first ingestion page (at most
   * {@link #MAX_SAMPLE_RECORDS}); callers must not fetch extra records for sampling.
   */
  public CrmField classify(RawCrmField rawField, List<String> sampleValues) {
    Objects.requireNonNull(rawField, "rawField");
    List<String> samples = truncateSamples(sampleValues);
    SampleStats stats = SampleStats.from(samples);

    CrmFieldType type =
        classifyByDataType(rawField)
            .or(() -> classifyBySamples(rawField, stats))
            .orElseGet(() -> classifyAmbiguousOrDefault(rawField, samples, stats));

    CrmFieldCategory category = categorize(rawField);
    boolean empty = stats.fillRate() < 0.05 || samples.isEmpty() && type == CrmFieldType.SKIP;
    int completeness = completenessScore(samples);

    return new CrmField(
        rawField.apiName(),
        rawField.displayLabel(),
        type,
        category,
        rawField.custom(),
        empty && completeness == 0,
        completeness);
  }

  /**
   * Percentage (0–100) of sampled records with a meaningful value (not blank / N/A / test / none /
   * TBD, length &gt; 5).
   */
  public static int completenessScore(List<String> sampleValues) {
    List<String> samples = truncateSamples(sampleValues);
    if (samples.isEmpty()) {
      return 0;
    }
    long meaningful =
        samples.stream().filter(FieldClassifier::isMeaningful).count();
    return (int) Math.round((meaningful * 100.0) / samples.size());
  }

  static Optional<CrmFieldType> classifyByDataType(RawCrmField rawField) {
    String dataType = normalizeDataType(rawField.dataType());
    int maxLength = rawField.maxLength();

    return switch (dataType) {
      case "textarea" -> Optional.of(CrmFieldType.NARRATIVE);
      case "text", "string" ->
          maxLength > 100 ? Optional.of(CrmFieldType.NARRATIVE) : Optional.empty();
      case "double",
              "integer",
              "bigint",
              "currency",
              "percent",
              "decimal",
              "long",
              "number",
              "date",
              "datetime",
              "picklist",
              "multiselectpicklist",
              "multipicklist",
              "boolean",
              "checkbox",
              "lookup",
              "ownerlookup",
              "userlookup" ->
          Optional.of(CrmFieldType.STRUCTURED);
      case "autonumber", "formula", "rollup", "summary", "calculated", "id", "reference" ->
          Optional.of(CrmFieldType.SKIP);
      case "" -> Optional.empty();
      default -> Optional.empty();
    };
  }

  static Optional<CrmFieldType> classifyBySamples(RawCrmField rawField, SampleStats stats) {
    if (stats.sampleCount() == 0) {
      return Optional.empty();
    }
    if (stats.fillRate() < 0.05) {
      return Optional.of(CrmFieldType.SKIP);
    }
    if (stats.avgLength() > 100 && stats.fillRate() > 0.2) {
      return Optional.of(CrmFieldType.NARRATIVE);
    }
    // Ambiguous short-text band is handled by Layer 3 / default — not decided here.
    if (isAmbiguousTextBand(rawField, stats)) {
      return Optional.empty();
    }
    return Optional.of(CrmFieldType.STRUCTURED);
  }

  private CrmFieldType classifyAmbiguousOrDefault(
      RawCrmField rawField, List<String> samples, SampleStats stats) {
    if (isAmbiguousTextBand(rawField, stats)) {
      Optional<CrmFieldType> llm = classifyWithLlm(rawField, samples);
      if (llm.isPresent()) {
        return llm.get();
      }
    }
    return CrmFieldType.STRUCTURED;
  }

  private Optional<CrmFieldType> classifyWithLlm(RawCrmField rawField, List<String> samples) {
    if (llmClassifier == null) {
      return Optional.empty();
    }
    String cacheKey = llmCacheKey(rawField);
    CrmFieldType cached = llmVerdictCache.get(cacheKey);
    if (cached != null) {
      return Optional.of(cached);
    }

    String verdict;
    try {
      verdict = llmClassifier.classify(rawField, samples.subList(0, Math.min(5, samples.size())));
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
    Optional<CrmFieldType> parsed = parseLlmVerdict(verdict);
    parsed.ifPresent(type -> llmVerdictCache.put(cacheKey, type));
    return parsed;
  }

  static Optional<CrmFieldType> parseLlmVerdict(String verdict) {
    if (verdict == null || verdict.isBlank()) {
      return Optional.empty();
    }
    char letter = Character.toUpperCase(verdict.trim().charAt(0));
    return switch (letter) {
      case 'A' -> Optional.of(CrmFieldType.NARRATIVE);
      case 'B' -> Optional.of(CrmFieldType.STRUCTURED);
      case 'C' -> Optional.of(CrmFieldType.SKIP);
      default -> Optional.empty();
    };
  }

  /**
   * Builds the one-letter classification prompt for Layer 3. Exposed for adapters that wrap an LLM
   * client.
   */
  public static String buildLlmPrompt(RawCrmField rawField, List<String> sampleValues) {
    List<String> samples = truncateSamples(sampleValues);
    List<String> preview = samples.subList(0, Math.min(5, samples.size()));
    StringBuilder sampleBlock = new StringBuilder();
    for (String sample : preview) {
      sampleBlock.append("- ").append(sample == null ? "" : sample).append('\n');
    }
    return """
        CRM field classification task.

        Field API name: %s
        Field display name: %s
        Data type: %s
        Sample values (up to 5):
        %s
        Is this field:
        A) NARRATIVE - contains meaningful text context worth searching (decisions, reasons, notes)
        B) STRUCTURED - a fact/number/status worth filtering by (stage, amount, date)
        C) SKIP - system field or consistently empty

        Reply with only A, B, or C.
        """
        .formatted(
            rawField.apiName(),
            rawField.displayLabel(),
            rawField.dataType(),
            sampleBlock.toString().trim());
  }

  static CrmFieldCategory categorize(RawCrmField rawField) {
    String name = rawField.apiName() == null ? "" : rawField.apiName().toLowerCase(Locale.ROOT);
    if (containsAny(
        name, "description", "next_step", "nextstep", "notes", "reason", "comments", "comment")) {
      return CrmFieldCategory.CONTEXT;
    }
    if (containsAny(name, "stage", "amount", "probability", "close", "revenue", "pipeline")) {
      return CrmFieldCategory.OUTCOME;
    }
    if (containsAny(name, "created", "modified", "updated", "owner", "_id", "id")
        && !name.contains("deal_name")
        && !name.equals("name")) {
      // owner/id/audit lean SYSTEM; pure "name" stays IDENTITY below
      if (containsAny(name, "created", "modified", "updated") || name.endsWith("_id") || name.equals("id")) {
        return CrmFieldCategory.SYSTEM;
      }
      if (name.contains("owner")) {
        return CrmFieldCategory.IDENTITY;
      }
    }
    if (containsAny(name, "call", "email", "meeting", "task", "activity", "subject")) {
      return CrmFieldCategory.ACTIVITY;
    }
    if (rawField.custom()) {
      return CrmFieldCategory.CONTEXT;
    }
    return CrmFieldCategory.IDENTITY;
  }

  static boolean isAmbiguousTextBand(RawCrmField rawField, SampleStats stats) {
    String dataType = normalizeDataType(rawField.dataType());
    if (!dataType.equals("text") && !dataType.equals("string")) {
      return false;
    }
    if (rawField.maxLength() > 100) {
      return false; // Layer 1 already treated as NARRATIVE
    }
    double avg = stats.avgLength();
    double fill = stats.fillRate();
    return avg >= 50 && avg <= 150 && fill >= 0.2 && fill <= 0.6;
  }

  static List<String> truncateSamples(List<String> sampleValues) {
    if (sampleValues == null || sampleValues.isEmpty()) {
      return List.of();
    }
    if (sampleValues.size() <= MAX_SAMPLE_RECORDS) {
      return List.copyOf(sampleValues);
    }
    return List.copyOf(sampleValues.subList(0, MAX_SAMPLE_RECORDS));
  }

  static boolean isMeaningful(String value) {
    if (value == null) {
      return false;
    }
    String trimmed = value.trim();
    if (trimmed.length() <= 5) {
      return false;
    }
    String lower = trimmed.toLowerCase(Locale.ROOT);
    return !lower.equals("n/a")
        && !lower.equals("na")
        && !lower.equals("none")
        && !lower.equals("test")
        && !lower.equals("tbd")
        && !lower.equals("-")
        && !lower.equals("--");
  }

  static boolean isFilled(String value) {
    if (value == null) {
      return false;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return false;
    }
    String lower = trimmed.toLowerCase(Locale.ROOT);
    return !lower.equals("n/a")
        && !lower.equals("na")
        && !lower.equals("none")
        && !lower.equals("test")
        && !lower.equals("tbd");
  }

  private static String normalizeDataType(String dataType) {
    return dataType == null ? "" : dataType.trim().toLowerCase(Locale.ROOT).replace(" ", "");
  }

  private static boolean containsAny(String name, String... needles) {
    for (String needle : needles) {
      if (name.contains(needle)) {
        return true;
      }
    }
    return false;
  }

  private static String llmCacheKey(RawCrmField rawField) {
    return rawField.apiName() + "|" + normalizeDataType(rawField.dataType());
  }

  /** Fill-rate and average-length stats over a capped sample window. */
  public record SampleStats(int sampleCount, double fillRate, double avgLength) {
    static SampleStats from(List<String> samples) {
      if (samples == null || samples.isEmpty()) {
        return new SampleStats(0, 0.0, 0.0);
      }
      List<String> filled = new ArrayList<>();
      for (String sample : samples) {
        if (isFilled(sample)) {
          filled.add(sample.trim());
        }
      }
      double fillRate = filled.size() / (double) samples.size();
      double avgLength =
          filled.isEmpty()
              ? 0.0
              : filled.stream().mapToInt(String::length).average().orElse(0.0);
      return new SampleStats(samples.size(), fillRate, avgLength);
    }
  }
}
