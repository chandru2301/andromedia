package com.andromedia.ingestion.crm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Infers the canonical role of a custom/unknown CRM module from columns and first-page values.
 *
 * <p>Signals are weighted because a deal stage is stronger evidence than a generic amount column.
 * Value evidence is counted once per column, so repeated rows cannot overwhelm diverse signals.
 */
public final class CrmTableInferenceEngine {

  static final int MIN_CONFIDENT_SCORE = 4;
  static final int MIN_WINNING_MARGIN = 2;
  static final int MAX_SAMPLE_VALUES_PER_COLUMN = FieldClassifier.MAX_SAMPLE_RECORDS;

  private static final Pattern EMAIL =
      Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");
  private static final Pattern PHONE =
      Pattern.compile("^\\+?[\\d\\s().-]{7,20}$");
  private static final Pattern CURRENCY =
      Pattern.compile(
          "(?i)^(?:[$₹€£]\\s*\\d[\\d,]*(?:\\.\\d{1,2})?|(?:USD|INR|EUR|GBP)\\s*\\d[\\d,]*(?:\\.\\d{1,2})?)$");
  private static final Pattern STAGE =
      Pattern.compile(
          "(?i)^(?:closed\\s+won|closed\\s+lost|won|lost|negotiation|proposal|prospecting|qualification|needs\\s+analysis|value\\s+proposition)$");
  private static final Pattern SUPPORT_SEVERITY =
      Pattern.compile("(?i)^(?:critical|high|medium|low|sev[ -]?[0-4]|p[0-4])$");
  private static final Pattern ACCOUNT_SEGMENT =
      Pattern.compile("(?i)^(?:enterprise|smb|startup|mid[ -]?market|small business)$");
  private static final Pattern EMPLOYEE_COUNT =
      Pattern.compile("(?i)^\\d{1,7}\\s*(?:employees?|people|staff)$");

  private static final Map<CrmTableType, Map<String, Integer>> SIGNATURES =
      buildSignatures();

  /** Optional final fallback for modules unresolved by deterministic inference. */
  @FunctionalInterface
  public interface LlmTableClassifier {
    CrmTableType classify(List<String> columnNames, Map<String, List<String>> sampleData);
  }

  private final LlmTableClassifier llmClassifier;

  public CrmTableInferenceEngine() {
    this(null);
  }

  public CrmTableInferenceEngine(LlmTableClassifier llmClassifier) {
    this.llmClassifier = llmClassifier;
  }

  /**
   * Infers a module safely: known standard modules retain their declared role; only custom/unknown
   * modules enter inference.
   */
  public CrmTableType inferModule(
      CrmModule module, List<String> columnNames, Map<String, List<String>> sampleData) {
    Objects.requireNonNull(module, "module");
    CrmTableType declaredRole = CrmTableType.fromRole(module.role());
    if (!module.isCustom() && declaredRole != CrmTableType.UNKNOWN) {
      return declaredRole;
    }
    return inferWithFuzzyMatch(columnNames, sampleData);
  }

  /** Exact/containment weighted signature inference. */
  public CrmTableType inferTableType(List<String> columnNames) {
    if (columnNames == null || columnNames.isEmpty()) {
      return CrmTableType.UNKNOWN;
    }
    Set<String> normalizedColumns =
        columnNames.stream()
            .filter(Objects::nonNull)
            .map(CrmTableInferenceEngine::normalize)
            .filter(value -> !value.isBlank())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    Map<CrmTableType, Integer> scores = emptyScores();
    for (Map.Entry<CrmTableType, Map<String, Integer>> entry : SIGNATURES.entrySet()) {
      int score = 0;
      for (Map.Entry<String, Integer> signal : entry.getValue().entrySet()) {
        if (normalizedColumns.stream().anyMatch(column -> exactOrContained(column, signal.getKey()))) {
          score += signal.getValue();
        }
      }
      scores.put(entry.getKey(), score);
    }
    return confidentWinner(scores);
  }

  /**
   * Infers from first-page values. Each semantic pattern contributes at most once per column.
   * Currency alone is intentionally insufficient to classify a table as DEALS.
   */
  public CrmTableType inferFromDataPatterns(Map<String, List<String>> sampleData) {
    if (sampleData == null || sampleData.isEmpty()) {
      return CrmTableType.UNKNOWN;
    }
    Map<CrmTableType, Integer> scores = emptyScores();

    for (List<String> values : sampleData.values()) {
      List<String> sample = capped(values);
      boolean hasStage = anyMatch(sample, STAGE);
      boolean hasCurrency = anyMatch(sample, CURRENCY);
      boolean hasEmail = anyMatch(sample, EMAIL);
      boolean hasPhone = anyMatch(sample, PHONE);
      boolean hasSeverity = anyMatch(sample, SUPPORT_SEVERITY);
      boolean hasSegment = anyMatch(sample, ACCOUNT_SEGMENT);
      boolean hasEmployeeCount = anyMatch(sample, EMPLOYEE_COUNT);

      add(scores, CrmTableType.DEALS, hasStage ? 5 : 0);
      add(scores, CrmTableType.DEALS, hasCurrency ? 2 : 0);
      add(scores, CrmTableType.CONTACTS, hasEmail ? 5 : 0);
      add(scores, CrmTableType.CONTACTS, hasPhone ? 2 : 0);
      add(scores, CrmTableType.SUPPORT_CASES, hasSeverity ? 3 : 0);
      add(scores, CrmTableType.ACCOUNTS, hasSegment ? 3 : 0);
      add(scores, CrmTableType.ACCOUNTS, hasEmployeeCount ? 3 : 0);
    }
    return confidentWinner(scores);
  }

  /**
   * Ordered inference: exact/containment columns, fuzzy columns, value patterns, optional LLM.
   */
  public CrmTableType inferWithFuzzyMatch(
      List<String> columnNames, Map<String, List<String>> sampleData) {
    CrmTableType exact = inferTableType(columnNames);
    if (exact != CrmTableType.UNKNOWN) {
      return exact;
    }

    CrmTableType fuzzy = inferFuzzy(columnNames);
    if (fuzzy != CrmTableType.UNKNOWN) {
      return fuzzy;
    }

    CrmTableType data = inferFromDataPatterns(sampleData);
    if (data != CrmTableType.UNKNOWN) {
      return data;
    }

    if (llmClassifier != null) {
      try {
        CrmTableType inferred = llmClassifier.classify(safeColumns(columnNames), safeData(sampleData));
        return inferred == null ? CrmTableType.UNKNOWN : inferred;
      } catch (RuntimeException ex) {
        return CrmTableType.UNKNOWN;
      }
    }
    return CrmTableType.UNKNOWN;
  }

  static Map<CrmTableType, Map<String, Integer>> signatures() {
    return SIGNATURES;
  }

  private CrmTableType inferFuzzy(List<String> columnNames) {
    if (columnNames == null || columnNames.isEmpty()) {
      return CrmTableType.UNKNOWN;
    }
    Map<CrmTableType, Integer> scores = emptyScores();
    List<String> columns =
        columnNames.stream()
            .filter(Objects::nonNull)
            .map(CrmTableInferenceEngine::normalize)
            .filter(value -> !value.isBlank())
            .toList();

    for (Map.Entry<CrmTableType, Map<String, Integer>> table : SIGNATURES.entrySet()) {
      int score = 0;
      for (Map.Entry<String, Integer> signal : table.getValue().entrySet()) {
        boolean matched =
            columns.stream().anyMatch(column -> fuzzyMatches(column, signal.getKey()));
        if (matched) {
          score += Math.max(1, signal.getValue() - 1);
        }
      }
      scores.put(table.getKey(), score);
    }
    return confidentWinner(scores);
  }

  private static Map<CrmTableType, Map<String, Integer>> buildSignatures() {
    EnumMap<CrmTableType, Map<String, Integer>> signatures =
        new EnumMap<>(CrmTableType.class);

    signatures.put(
        CrmTableType.DEALS,
        weighted(
            "dealname", 3,
            "opportunityname", 3,
            "oppvalue", 3,
            "amount", 2,
            "stage", 3,
            "stagename", 3,
            "dealstage", 3,
            "currentstage", 3,
            "closedate", 3,
            "closingdate", 3,
            "expectedclose", 3,
            "probability", 2,
            "winprobability", 3,
            "pipeline", 1,
            "vbeln", 3,
            "netwr", 3,
            "waerk", 1));
    signatures.put(
        CrmTableType.CONTACTS,
        weighted(
            "firstname", 2,
            "lastname", 2,
            "fullname", 3,
            "contactname", 3,
            "email", 3,
            "emailaddress", 3,
            "phone", 2,
            "mobile", 2,
            "accountname", 1));
    signatures.put(
        CrmTableType.ACCOUNTS,
        weighted(
            "accountname", 3,
            "companyname", 3,
            "organisationname", 3,
            "organizationname", 3,
            "firmname", 3,
            "clientname", 2,
            "industry", 2,
            "employees", 2,
            "annualrevenue", 2,
            "website", 1,
            "billingcity", 1));
    signatures.put(
        CrmTableType.ACTIVITIES,
        weighted(
            "activitytype", 3,
            "duedate", 2,
            "callduration", 3,
            "meetingdate", 3,
            "tasksubject", 3,
            "eventtype", 3,
            "calltype", 3,
            "assignedto", 2,
            "subject", 1,
            "status", 1));
    signatures.put(
        CrmTableType.SUPPORT_CASES,
        weighted(
            "casenumber", 3,
            "ticketid", 3,
            "issuetype", 2,
            "priority", 2,
            "severity", 3,
            "resolution", 3,
            "escalated", 2,
            "subject", 1,
            "status", 1));

    EnumMap<CrmTableType, Map<String, Integer>> immutable =
        new EnumMap<>(CrmTableType.class);
    signatures.forEach((type, weights) -> immutable.put(type, Map.copyOf(weights)));
    return Map.copyOf(immutable);
  }

  /**
   * Builds a weighted signature and fails immediately on duplicate keys instead of silently
   * overwriting them.
   */
  private static Map<String, Integer> weighted(Object... keyWeights) {
    if (keyWeights.length % 2 != 0) {
      throw new IllegalArgumentException("Signature entries must be key/weight pairs");
    }
    Map<String, Integer> weights = new LinkedHashMap<>();
    for (int i = 0; i < keyWeights.length; i += 2) {
      String key = normalize(String.valueOf(keyWeights[i]));
      int weight = (Integer) keyWeights[i + 1];
      if (weights.putIfAbsent(key, weight) != null) {
        throw new IllegalArgumentException("Duplicate CRM table signature: " + key);
      }
    }
    return weights;
  }

  private static CrmTableType confidentWinner(Map<CrmTableType, Integer> scores) {
    List<Map.Entry<CrmTableType, Integer>> ranked =
        scores.entrySet().stream()
            .filter(entry -> entry.getKey() != CrmTableType.UNKNOWN)
            .sorted(Map.Entry.<CrmTableType, Integer>comparingByValue(Comparator.reverseOrder()))
            .toList();
    if (ranked.isEmpty() || ranked.get(0).getValue() < MIN_CONFIDENT_SCORE) {
      return CrmTableType.UNKNOWN;
    }
    int second = ranked.size() > 1 ? ranked.get(1).getValue() : 0;
    return ranked.get(0).getValue() - second >= MIN_WINNING_MARGIN
        ? ranked.get(0).getKey()
        : CrmTableType.UNKNOWN;
  }

  private static boolean exactOrContained(String column, String signature) {
    return column.equals(signature)
        || (column.length() >= 6 && signature.length() >= 4 && column.contains(signature));
  }

  private static boolean fuzzyMatches(String column, String signature) {
    if (exactOrContained(column, signature)) {
      return true;
    }
    int maxLength = Math.max(column.length(), signature.length());
    if (maxLength < 5) {
      return false;
    }
    int distance = levenshtein(column, signature);
    double similarity = 1.0 - (distance / (double) maxLength);
    return distance <= 2 || similarity >= 0.78;
  }

  private static int levenshtein(String left, String right) {
    int[] previous = new int[right.length() + 1];
    for (int j = 0; j <= right.length(); j++) {
      previous[j] = j;
    }
    for (int i = 1; i <= left.length(); i++) {
      int[] current = new int[right.length() + 1];
      current[0] = i;
      for (int j = 1; j <= right.length(); j++) {
        int substitution = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
        current[j] =
            Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution);
      }
      previous = current;
    }
    return previous[right.length()];
  }

  private static String normalize(String value) {
    if (value == null) {
      return "";
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    if (normalized.endsWith("__c")) {
      normalized = normalized.substring(0, normalized.length() - 3);
    }
    return normalized.replaceAll("[^a-z0-9]", "");
  }

  private static boolean anyMatch(List<String> values, Pattern pattern) {
    for (String value : values) {
      if (value != null && pattern.matcher(value.trim()).matches()) {
        return true;
      }
    }
    return false;
  }

  private static List<String> capped(List<String> values) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    int limit = Math.min(MAX_SAMPLE_VALUES_PER_COLUMN, values.size());
    return List.copyOf(values.subList(0, limit));
  }

  private static Map<CrmTableType, Integer> emptyScores() {
    EnumMap<CrmTableType, Integer> scores = new EnumMap<>(CrmTableType.class);
    for (CrmTableType type : CrmTableType.values()) {
      scores.put(type, 0);
    }
    return scores;
  }

  private static void add(
      Map<CrmTableType, Integer> scores, CrmTableType type, int contribution) {
    if (contribution > 0) {
      scores.merge(type, contribution, Integer::sum);
    }
  }

  private static List<String> safeColumns(List<String> columns) {
    return columns == null ? List.of() : List.copyOf(columns);
  }

  private static Map<String, List<String>> safeData(Map<String, List<String>> sampleData) {
    if (sampleData == null || sampleData.isEmpty()) {
      return Map.of();
    }
    Map<String, List<String>> copy = new LinkedHashMap<>();
    sampleData.forEach((column, values) -> copy.put(column, capped(values)));
    return Map.copyOf(copy);
  }
}
