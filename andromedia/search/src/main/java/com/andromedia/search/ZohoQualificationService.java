package com.andromedia.search;

import com.andromedia.common.IndexConstants;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Service;

@Service
public class ZohoQualificationService {

  public static final String HIGHEST_PROBABILITY_MODEL_ID = "zoho-highest-probability-v1";

  private final ZohoQualificationProperties properties;

  public ZohoQualificationService(ZohoQualificationProperties properties) {
    this.properties = properties;
  }

  public QualificationResult qualifyActiveDeals(Path indexPath) {
    return qualifyActiveDeals(indexPath, Instant.now());
  }

  public QualificationResult qualifyActiveDeals(Path indexPath, Instant now) {
    List<ScoredDeal> scored;
    try {
      scored = loadAndScore(indexPath, now);
    } catch (IOException | SearchException ex) {
      return QualificationResult.insufficient("Unable to read Zoho deals from the local index.");
    }
    if (scored.isEmpty()) {
      return QualificationResult.insufficient("No active Zoho deals were found in the local index.");
    }

    List<ScoredDeal> nonDemo =
        scored.stream().filter(deal -> !deal.demo()).toList();
    List<ScoredDeal> candidates = nonDemo.isEmpty() ? scored : nonDemo;
    ScoredDeal winner =
        candidates.stream()
            .max(Comparator.comparingDouble(ScoredDeal::totalScore).thenComparing(ScoredDeal::name))
            .orElseThrow();

    String trustState = nonDemo.isEmpty() ? "demo_only" : "structured";
    List<String> reasons = buildReasons(winner, candidates);
    return new QualificationResult(
        true,
        trustState,
        winner.name(),
        winner.accountName(),
        winner.primaryFields(),
        reasons,
        winner.breakdown(),
        ZohoQualificationProperties.MODEL_ID,
        winner.flags(),
        null);
  }

  public QualificationResult selectHighestProbabilityDeal(Path indexPath) {
    return selectHighestProbabilityDeal(indexPath, Instant.now());
  }

  public QualificationResult selectHighestProbabilityDeal(Path indexPath, Instant now) {
    return rankActiveDeals(indexPath, now, "Probability", "desc");
  }

  /**
   * Full active-deal population from the local Zoho index (closed stages excluded). Used for
   * ranking/aggregation questions that must not rely on top-k semantic retrieval alone.
   */
  public List<DealLedgerEntry> listActiveDealLedger(Path indexPath) {
    return listActiveDealLedger(indexPath, Instant.now());
  }

  public List<DealLedgerEntry> listActiveDealLedger(Path indexPath, Instant now) {
    try {
      return loadAndScore(indexPath, now).stream()
          .sorted(
              Comparator.comparingDouble(ScoredDeal::totalScore)
                  .reversed()
                  .thenComparing(ScoredDeal::name, String.CASE_INSENSITIVE_ORDER))
          .map(this::toLedgerEntry)
          .toList();
    } catch (IOException | SearchException ex) {
      return List.of();
    }
  }

  /**
   * Active + closed Zoho deals for named-entity pin (e.g. Closed Won Meraas). Not for pipeline
   * aggregates.
   */
  public List<DealLedgerEntry> listDealLedgerForEntityLookup(Path indexPath) {
    return listDealLedgerForEntityLookup(indexPath, Instant.now());
  }

  public List<DealLedgerEntry> listDealLedgerForEntityLookup(Path indexPath, Instant now) {
    try {
      return loadAndScoreIncludingClosed(indexPath, now).stream()
          .sorted(
              Comparator.comparingDouble(ScoredDeal::totalScore)
                  .reversed()
                  .thenComparing(ScoredDeal::name, String.CASE_INSENSITIVE_ORDER))
          .map(this::toLedgerEntry)
          .toList();
    } catch (IOException | SearchException ex) {
      return List.of();
    }
  }

  /**
   * Deterministic max/min over the full active-deal ledger for a structured field.
   *
   * @param field Probability, Amount, Expected_Revenue, Close Date, or Modified
   * @param direction desc (default) or asc
   */
  public QualificationResult rankActiveDeals(Path indexPath, String field, String direction) {
    return rankActiveDeals(indexPath, Instant.now(), field, direction);
  }

  public QualificationResult rankActiveDeals(
      Path indexPath, Instant now, String field, String direction) {
    RankField rankField = RankField.from(field);
    if (rankField == null) {
      return QualificationResult.insufficient(
          "Unsupported ranking field: " + (field == null ? "null" : field) + ".");
    }
    boolean descending = direction == null || !direction.trim().equalsIgnoreCase("asc");

    List<ScoredDeal> scored;
    try {
      scored = loadAndScore(indexPath, now);
    } catch (IOException | SearchException ex) {
      return QualificationResult.insufficient("Unable to read Zoho deals from the local index.");
    }
    if (scored.isEmpty()) {
      return QualificationResult.insufficient("No active Zoho deals were found in the local index.");
    }

    List<ScoredDeal> eligible =
        scored.stream().filter(deal -> rankField.hasValue(deal)).toList();
    if (eligible.isEmpty()) {
      return QualificationResult.insufficient(
          "No active Zoho deals with a structured "
              + rankField.displayName().toLowerCase(Locale.ROOT)
              + " were found.");
    }

    Comparator<ScoredDeal> byFieldThenName =
        rankField.comparator().thenComparing(ScoredDeal::name);
    ScoredDeal winner =
        (descending
                ? eligible.stream().max(byFieldThenName)
                : eligible.stream().min(byFieldThenName))
            .orElseThrow();

    String trustState = winner.demo() ? "demo_only" : "structured";
    List<String> reasons = buildRankReasons(winner, rankField, descending, trustState);
    Map<String, Double> breakdown = new LinkedHashMap<>();
    breakdown.put(rankField.key(), round4(rankField.normalizedScore(winner)));
    breakdown.put("total", round4(rankField.normalizedScore(winner)));

    String modelId =
        rankField == RankField.PROBABILITY
            ? HIGHEST_PROBABILITY_MODEL_ID
            : "zoho-field-rank-v1";

    return new QualificationResult(
        true,
        trustState,
        winner.name(),
        winner.accountName(),
        winner.primaryFields(),
        List.copyOf(reasons),
        breakdown,
        modelId,
        winner.flags(),
        null);
  }

  private DealLedgerEntry toLedgerEntry(ScoredDeal deal) {
    return new DealLedgerEntry(
        deal.name(),
        deal.accountName(),
        deal.primaryFields(),
        deal.flags(),
        deal.demo(),
        deal.probability(),
        deal.amountValue(),
        deal.modified(),
        deal.chunkId(),
        deal.path(),
        deal.narrative(),
        deal.totalScore());
  }

  private List<String> buildRankReasons(
      ScoredDeal winner, RankField field, boolean descending, String trustState) {
    List<String> reasons = new ArrayList<>();
    String extreme = descending ? "Highest" : "Lowest";
    String valueLabel = field.formatValue(winner);
    reasons.add(
        extreme
            + " "
            + field.displayName().toLowerCase(Locale.ROOT)
            + " among all active deals ("
            + valueLabel
            + ").");
    if (winner.stage() != null && !winner.stage().isBlank()) {
      reasons.add("Current stage: " + winner.stage() + ".");
    }
    if ("demo_only".equals(trustState)) {
      if (field == RankField.PROBABILITY) {
        reasons.add("The highest-probability deal is flagged as demo/test data.");
      } else {
        reasons.add("The ranked deal is flagged as demo/test data.");
      }
    }
    return reasons;
  }

  private List<ScoredDeal> loadAndScore(Path indexPath, Instant now) throws IOException {
    return loadAndScore(indexPath, now, false);
  }

  private List<ScoredDeal> loadAndScoreIncludingClosed(Path indexPath, Instant now)
      throws IOException {
    return loadAndScore(indexPath, now, true);
  }

  private List<ScoredDeal> loadAndScore(Path indexPath, Instant now, boolean includeClosed)
      throws IOException {
    IndexAccess.validateIndexExists(indexPath);
    List<ScoredDeal> scored = new ArrayList<>();
    try (Directory directory = FSDirectory.open(indexPath);
        DirectoryReader reader = DirectoryReader.open(directory)) {
      if (reader.numDocs() == 0) {
        return List.of();
      }
      IndexSearcher searcher = new IndexSearcher(reader);
      Query zohoQuery =
          new TermQuery(
              new Term(IndexConstants.FIELD_SOURCE_TYPE, IndexConstants.SOURCE_TYPE_ZOHO));
      TopDocs matches = searcher.search(zohoQuery, Math.max(1, reader.numDocs()));
      for (ScoreDoc match : matches.scoreDocs) {
        Document document = searcher.storedFields().document(match.doc);
        Optional<ScoredDeal> scoredDeal = scoreDocument(document, now, includeClosed);
        scoredDeal.ifPresent(scored::add);
      }
    }
    return scored;
  }

  Optional<ScoredDeal> scoreDocument(Document document, Instant now) {
    return scoreDocument(document, now, false);
  }

  Optional<ScoredDeal> scoreDocument(Document document, Instant now, boolean includeClosed) {
    String stage = value(document, IndexConstants.FIELD_DEAL_STAGE);
    if (!includeClosed && isClosedStage(stage)) {
      return Optional.empty();
    }

    String name = firstNonBlank(
        value(document, IndexConstants.FIELD_DEAL_NAME),
        value(document, IndexConstants.FIELD_FILE_NAME),
        "Unnamed deal");
    String accountName = value(document, IndexConstants.FIELD_ACCOUNT_NAME);
    String amount = value(document, IndexConstants.FIELD_DEAL_AMOUNT);
    String expectedRevenue = value(document, IndexConstants.FIELD_EXPECTED_REVENUE);
    String probabilityRaw = value(document, IndexConstants.FIELD_PROBABILITY);
    String closingDate = value(document, IndexConstants.FIELD_CLOSING_DATE);
    String modifiedRaw = value(document, IndexConstants.FIELD_TIMESTAMP);
    String owner = value(document, IndexConstants.FIELD_DEAL_OWNER);
    String chunkId = value(document, IndexConstants.FIELD_CHUNK_ID);
    String path = value(document, IndexConstants.FIELD_PATH);
    String narrative = value(document, IndexConstants.FIELD_CONTENT);
    List<String> flags = List.of(document.getValues(IndexConstants.FIELD_QUALITY_SIGNALS));
    boolean demo = flags.stream().anyMatch("test_demo_pattern"::equals);

    double probability = parseProbability(probabilityRaw);
    double amountValue = parseMoney(amount);
    double expectedRevenueValue = parseMoney(expectedRevenue);
    double probabilityComponent = properties.probabilityWeight() * (probability / 100.0);

    StageProgress stageProgress = stageProgress(stage);
    double stageComponent = properties.stageWeight() * stageProgress.normalized();

    Instant modified = parseInstant(modifiedRaw);
    double freshnessNormalized = freshnessScore(modified, now);
    double freshnessComponent = properties.freshnessWeight() * freshnessNormalized;

    boolean stale =
        modified != null
            && modified.isBefore(now.minus(properties.staleAfterDays(), ChronoUnit.DAYS));
    boolean overdue = isCloseDatePassed(closingDate, now);
    boolean unknownStage = !stageProgress.known();

    double total =
        probabilityComponent
            + stageComponent
            + freshnessComponent
            - (stale ? properties.stalePenalty() : 0.0)
            - (overdue ? properties.overduePenalty() : 0.0);
    total = Math.max(0.0, Math.min(1.0, total));

    Map<String, String> primary = new LinkedHashMap<>();
    put(primary, "Probability", formatProbability(probabilityRaw, probability));
    put(primary, "Stage", stage);
    put(primary, "Amount", amount);
    put(primary, "Expected_Revenue", expectedRevenue);
    put(primary, "Close Date", closingDate);
    put(primary, "Modified", modified == null ? "" : modified.toString());
    put(primary, "Account", accountName);
    put(primary, "Owner", owner);
    put(primary, "Next Step", value(document, IndexConstants.FIELD_NEXT_STEP));

    Map<String, Double> breakdown = new LinkedHashMap<>();
    breakdown.put("probability", round4(probabilityComponent));
    breakdown.put("stage", round4(stageComponent));
    breakdown.put("freshness", round4(freshnessComponent));
    breakdown.put("stalePenalty", stale ? properties.stalePenalty() : 0.0);
    breakdown.put("overduePenalty", overdue ? properties.overduePenalty() : 0.0);
    breakdown.put("total", round4(total));

    List<String> dealFlags = new ArrayList<>(flags);
    if (unknownStage) {
      dealFlags.add("unknown_stage");
    }
    if (stale && !dealFlags.contains("stale_record")) {
      dealFlags.add("stale_record");
    }
    if (overdue && !dealFlags.contains("close_date_passed")) {
      dealFlags.add("close_date_passed");
    }

    return Optional.of(
        new ScoredDeal(
            name,
            accountName,
            total,
            demo,
            primary,
            breakdown,
            List.copyOf(dealFlags),
            probability,
            amountValue,
            expectedRevenueValue,
            stage,
            stageProgress,
            modified,
            parseCloseInstant(closingDate),
            stale,
            overdue,
            unknownStage,
            chunkId,
            path,
            narrative));
  }

  private List<String> buildReasons(ScoredDeal winner, List<ScoredDeal> candidates) {
    List<String> reasons = new ArrayList<>();
    double maxProbability =
        candidates.stream().mapToDouble(ScoredDeal::probability).max().orElse(0.0);
    if (winner.probability() >= maxProbability && winner.probability() > 0) {
      reasons.add(
          "Highest probability among all active deals ("
              + trimNumber(winner.probability())
              + "%).");
    } else if (winner.probability() > 0) {
      reasons.add("Probability " + trimNumber(winner.probability()) + "%.");
    }

    if (winner.stageProgress().known()) {
      reasons.add(
          "Configured stage contribution: "
              + winner.stage()
              + " (rank "
              + (winner.stageProgress().index() + 1)
              + "/"
              + properties.activeStageOrder().size()
              + ").");
    } else if (winner.unknownStage()) {
      reasons.add("Stage is not in the configured pipeline order (no stage contribution).");
    }

    if (winner.modified() != null) {
      String date = winner.modified().toString();
      if (date.length() >= 10) {
        date = date.substring(0, 10);
      }
      reasons.add("Recent activity (modified on " + date + ").");
    }

    if (winner.stale()) {
      reasons.add("Stale-record penalty applied (modified over " + properties.staleAfterDays() + " days ago).");
    }
    if (winner.overdue()) {
      reasons.add("Overdue close-date penalty applied.");
    }
    if (winner.demo()) {
      reasons.add("All eligible deals are demo/test data; ranking is demo-only.");
    }
    return List.copyOf(reasons);
  }

  private boolean isClosedStage(String stage) {
    if (stage == null || stage.isBlank()) {
      return false;
    }
    String normalized = stage.trim().toLowerCase(Locale.ROOT);
    for (String marker : properties.closedStageMarkers()) {
      if (marker != null && !marker.isBlank() && normalized.contains(marker.toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  private StageProgress stageProgress(String stage) {
    if (stage == null || stage.isBlank()) {
      return StageProgress.unknown();
    }
    List<String> order = properties.activeStageOrder();
    for (int i = 0; i < order.size(); i++) {
      if (stage.equalsIgnoreCase(order.get(i))) {
        double normalized = order.size() <= 1 ? 1.0 : (double) i / (order.size() - 1);
        return new StageProgress(true, i, normalized);
      }
    }
    return StageProgress.unknown();
  }

  private double freshnessScore(Instant modified, Instant now) {
    if (modified == null) {
      return 0.0;
    }
    long ageDays = Math.max(0, ChronoUnit.DAYS.between(modified, now));
    double halfLife = properties.freshnessHalfLifeDays();
    return Math.pow(0.5, ageDays / halfLife);
  }

  private static boolean isCloseDatePassed(String closingDate, Instant now) {
    if (closingDate == null || closingDate.isBlank()) {
      return false;
    }
    try {
      String trimmed = closingDate.trim();
      LocalDate close =
          LocalDate.parse(trimmed.substring(0, Math.min(10, trimmed.length())));
      LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
      return close.isBefore(today);
    } catch (DateTimeParseException | StringIndexOutOfBoundsException ex) {
      return false;
    }
  }

  private static double parseProbability(String raw) {
    if (raw == null || raw.isBlank()) {
      return 0.0;
    }
    try {
      double value = Double.parseDouble(raw.trim().replace("%", ""));
      if (value < 0) {
        return 0.0;
      }
      if (value <= 1.0) {
        return value * 100.0;
      }
      return Math.min(100.0, value);
    } catch (NumberFormatException ex) {
      return 0.0;
    }
  }

  private static double parseMoney(String raw) {
    if (raw == null || raw.isBlank()) {
      return Double.NaN;
    }
    try {
      String cleaned = raw.trim().replace("$", "").replace(",", "");
      return Double.parseDouble(cleaned);
    } catch (NumberFormatException ex) {
      return Double.NaN;
    }
  }

  private static Instant parseCloseInstant(String closingDate) {
    if (closingDate == null || closingDate.isBlank()) {
      return null;
    }
    try {
      String trimmed = closingDate.trim();
      LocalDate close = LocalDate.parse(trimmed.substring(0, Math.min(10, trimmed.length())));
      return close.atStartOfDay().toInstant(ZoneOffset.UTC);
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private static Instant parseInstant(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(raw.trim());
    } catch (DateTimeParseException ex) {
      try {
        LocalDate date = LocalDate.parse(raw.trim().substring(0, Math.min(10, raw.trim().length())));
        return date.atStartOfDay().toInstant(ZoneOffset.UTC);
      } catch (RuntimeException ignored) {
        return null;
      }
    }
  }

  private static String formatProbability(String raw, double parsed) {
    if (raw != null && !raw.isBlank()) {
      return raw.endsWith("%") ? raw : trimNumber(parsed) + "%";
    }
    return parsed > 0 ? trimNumber(parsed) + "%" : "";
  }

  private static String trimNumber(double value) {
    if (Math.rint(value) == value) {
      return String.valueOf((long) value);
    }
    return String.format(Locale.ROOT, "%.1f", value);
  }

  private static String value(Document document, String field) {
    String raw = document.get(field);
    return raw == null ? "" : raw.trim();
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private static void put(Map<String, String> map, String key, String value) {
    if (value != null && !value.isBlank()) {
      map.put(key, value);
    }
  }

  private static double round4(double value) {
    return Math.round(value * 10_000.0) / 10_000.0;
  }

  record StageProgress(boolean known, int index, double normalized) {
    static StageProgress unknown() {
      return new StageProgress(false, -1, 0.0);
    }
  }

  record ScoredDeal(
      String name,
      String accountName,
      double totalScore,
      boolean demo,
      Map<String, String> primaryFields,
      Map<String, Double> breakdown,
      List<String> flags,
      double probability,
      double amountValue,
      double expectedRevenueValue,
      String stage,
      StageProgress stageProgress,
      Instant modified,
      Instant closeDate,
      boolean stale,
      boolean overdue,
      boolean unknownStage,
      String chunkId,
      String path,
      String narrative) {}

  public record DealLedgerEntry(
      String name,
      String accountName,
      Map<String, String> primaryFields,
      List<String> flags,
      boolean demo,
      double probability,
      double amountValue,
      Instant modified,
      String chunkId,
      String path,
      String narrative,
      double qualificationScore) {

    public DealLedgerEntry {
      primaryFields = primaryFields == null ? Map.of() : Map.copyOf(primaryFields);
      flags = flags == null ? List.of() : List.copyOf(flags);
      chunkId = chunkId == null ? "" : chunkId.trim();
      path = path == null ? "" : path.trim();
      narrative = narrative == null ? "" : narrative;
    }

    /** Test/helper constructor when chunk identity and narrative are unknown. */
    public DealLedgerEntry(
        String name,
        String accountName,
        Map<String, String> primaryFields,
        List<String> flags,
        boolean demo,
        double probability,
        double amountValue,
        Instant modified) {
      this(
          name,
          accountName,
          primaryFields,
          flags,
          demo,
          probability,
          amountValue,
          modified,
          "",
          "",
          "",
          0.0);
    }

    /** Helper when score is unknown. */
    public DealLedgerEntry(
        String name,
        String accountName,
        Map<String, String> primaryFields,
        List<String> flags,
        boolean demo,
        double probability,
        double amountValue,
        Instant modified,
        String chunkId,
        String path,
        String narrative) {
      this(
          name,
          accountName,
          primaryFields,
          flags,
          demo,
          probability,
          amountValue,
          modified,
          chunkId,
          path,
          narrative,
          0.0);
    }
  }

  enum RankField {
    PROBABILITY("Probability", "probability"),
    AMOUNT("Amount", "amount"),
    EXPECTED_REVENUE("Expected_Revenue", "expectedRevenue"),
    CLOSE_DATE("Close Date", "closeDate"),
    MODIFIED("Modified", "modified");

    private final String displayName;
    private final String key;

    RankField(String displayName, String key) {
      this.displayName = displayName;
      this.key = key;
    }

    String displayName() {
      return displayName;
    }

    String key() {
      return key;
    }

    static RankField from(String raw) {
      if (raw == null || raw.isBlank()) {
        return null;
      }
      String normalized = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
      return switch (normalized) {
        case "probability", "prob" -> PROBABILITY;
        case "amount", "deal_amount", "deal_size" -> AMOUNT;
        case "expected_revenue", "expectedrevenue", "revenue" -> EXPECTED_REVENUE;
        case "close_date", "closedate", "close", "closing_date" -> CLOSE_DATE;
        case "modified", "modified_time", "updated", "freshness" -> MODIFIED;
        default -> null;
      };
    }

    boolean hasValue(ScoredDeal deal) {
      return switch (this) {
        case PROBABILITY -> deal.probability() > 0.0;
        case AMOUNT -> !Double.isNaN(deal.amountValue());
        case EXPECTED_REVENUE -> !Double.isNaN(deal.expectedRevenueValue());
        case CLOSE_DATE -> deal.closeDate() != null;
        case MODIFIED -> deal.modified() != null;
      };
    }

    Comparator<ScoredDeal> comparator() {
      return switch (this) {
        case PROBABILITY -> Comparator.comparingDouble(ScoredDeal::probability);
        case AMOUNT -> Comparator.comparingDouble(ScoredDeal::amountValue);
        case EXPECTED_REVENUE -> Comparator.comparingDouble(ScoredDeal::expectedRevenueValue);
        case CLOSE_DATE -> Comparator.comparing(ScoredDeal::closeDate);
        case MODIFIED -> Comparator.comparing(ScoredDeal::modified);
      };
    }

    double normalizedScore(ScoredDeal deal) {
      return switch (this) {
        case PROBABILITY -> deal.probability() / 100.0;
        case AMOUNT -> Double.isNaN(deal.amountValue()) ? 0.0 : deal.amountValue();
        case EXPECTED_REVENUE ->
            Double.isNaN(deal.expectedRevenueValue()) ? 0.0 : deal.expectedRevenueValue();
        case CLOSE_DATE -> deal.closeDate() == null ? 0.0 : deal.closeDate().getEpochSecond();
        case MODIFIED -> deal.modified() == null ? 0.0 : deal.modified().getEpochSecond();
      };
    }

    String formatValue(ScoredDeal deal) {
      return switch (this) {
        case PROBABILITY -> trimNumber(deal.probability()) + "%";
        case AMOUNT ->
            deal.primaryFields().getOrDefault("Amount", trimNumber(deal.amountValue()));
        case EXPECTED_REVENUE ->
            deal.primaryFields()
                .getOrDefault("Expected_Revenue", trimNumber(deal.expectedRevenueValue()));
        case CLOSE_DATE -> deal.primaryFields().getOrDefault("Close Date", "n/a");
        case MODIFIED -> {
          String modified = deal.primaryFields().getOrDefault("Modified", "n/a");
          yield modified.length() >= 10 ? modified.substring(0, 10) : modified;
        }
      };
    }
  }

  public record QualificationResult(
      boolean success,
      String trustState,
      String recordName,
      String accountName,
      Map<String, String> primaryFields,
      List<String> reasons,
      Map<String, Double> scoreBreakdown,
      String modelId,
      List<String> flags,
      String message) {

    public QualificationResult {
      primaryFields = primaryFields == null ? Map.of() : Map.copyOf(primaryFields);
      reasons = reasons == null ? List.of() : List.copyOf(reasons);
      scoreBreakdown = scoreBreakdown == null ? Map.of() : Map.copyOf(scoreBreakdown);
      flags = flags == null ? List.of() : List.copyOf(flags);
      trustState = trustState == null ? "insufficient_data" : trustState;
      modelId = modelId == null ? ZohoQualificationProperties.MODEL_ID : modelId;
    }

    static QualificationResult insufficient(String message) {
      return new QualificationResult(
          false,
          "insufficient_data",
          null,
          null,
          Map.of(),
          List.of(),
          Map.of(),
          ZohoQualificationProperties.MODEL_ID,
          List.of(),
          message);
    }
  }
}
