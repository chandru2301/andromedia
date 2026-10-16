package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.ChunkUnitType;
import com.andromedia.common.IndexConstants;
import java.util.List;
import org.junit.jupiter.api.Test;

class HeuristicRankerTest {

  private final RankingHeuristicsProperties properties = new RankingHeuristicsProperties();
  private final HeuristicRanker ranker =
      new HeuristicRanker(properties, new QueryTypeClassifier());

  @Test
  void penalizesTestPathsWithoutExcluding() {
    SearchHit main =
        hit("main", "/project/src/main/java/demo/AuthService.java", "AuthService", 2.0f);
    SearchHit test =
        hit("test", "/project/src/test/java/demo/AuthServiceTest.java", "AuthServiceTest", 2.0f);

    List<SearchHit> ranked = ranker.rank("AuthService", List.of(test, main));

    assertEquals("main", ranked.getFirst().chunkId());
    assertTrue(ranked.get(1).score() < ranked.getFirst().score());
    assertTrue(ranked.get(1).score() > 0f);
  }

  @Test
  void boostsExactFilenameMatchForIdentifierQuery() {
    SearchHit exact =
        hit(
            "exact",
            "/project/src/main/java/kafka/AnomalyKafkaListener.java",
            "AnomalyKafkaListener",
            1.0f);
    SearchHit other =
        hit("other", "/project/src/main/java/kafka/OtherListener.java", "OtherListener", 1.2f);

    List<SearchHit> ranked =
        ranker.rank("AnomalyKafkaListener.java", QueryType.IDENTIFIER, List.of(other, exact));

    assertTrue(ranked.getFirst().fileName().contains("AnomalyKafkaListener"));
  }

  @Test
  void filtersWeakSemanticScores() {
    SearchHit strong = semanticHit(0.2f);
    SearchHit weak = semanticHit(0.01f);

    List<SearchHit> filtered = ranker.filterSemanticByThreshold(List.of(strong, weak));

    assertEquals(1, filtered.size());
    assertEquals(0.2f, filtered.getFirst().semanticScore());
  }

  @Test
  void deprioritizesTestPathsForHistoryIntentQueries() {
    properties.setGitHistoryBoost(2.0);
    SearchHit testHit =
        hit("test", "/project/src/test/java/demo/ExploreServiceTest.java", "ExploreServiceTest", 10.0f);
    SearchHit gitHit =
        gitOrCodeHit("git", IndexConstants.SOURCE_TYPE_GIT, 5.0f);

    List<SearchHit> ranked =
        ranker.rank("why was impact and plan options implemented", List.of(testHit, gitHit));

    assertEquals("git", ranked.getFirst().chunkId());
  }

  @Test
  void boostsSlackHitsForDecisionIntentQuery() {
    properties.setSlackDecisionBoost(2.5);
    SearchHit codeHit =
        gitOrCodeHit("code", IndexConstants.SOURCE_TYPE_CODE, 1.0f);
    SearchHit slackHit =
        slackHit("slack", 1.0f, "thread: 3 messages · reactions: 7\nwe decided to use Kafka\n");

    List<SearchHit> ranked =
        ranker.rank("what did we decide about payment timeouts", List.of(codeHit, slackHit));

    assertEquals("slack", ranked.getFirst().chunkId());
    assertTrue(ranked.getFirst().score() > codeHit.score());
  }

  private static SearchHit slackHit(String chunkId, float score, String content) {
    return new SearchHit(
        "slack://#backend-eng/" + chunkId,
        "backend-eng",
        score,
        chunkId,
        null,
        null,
        ChunkUnitType.SLACK,
        0,
        0,
        null,
        null,
        List.of(),
        List.of(),
        List.of(),
        java.util.Optional.of(IndexConstants.SOURCE_TYPE_SLACK),
        java.util.Optional.empty(),
        java.util.Optional.of("2024-06-14T12:00:00Z"),
        java.util.Optional.of(content));
  }

  private static SearchHit gitOrCodeHit(String chunkId, String sourceType, float score) {
    return new SearchHit(
        "/project/" + chunkId,
        chunkId + ".java",
        score,
        chunkId,
        "Demo",
        null,
        ChunkUnitType.CLASS,
        1,
        10,
        null,
        null,
        List.of(),
        List.of(),
        List.of(),
        java.util.Optional.of(sourceType),
        java.util.Optional.empty(),
        java.util.Optional.empty(),
        java.util.Optional.empty());
  }

  private static SearchHit hit(
      String chunkId, String absolutePath, String symbol, float score) {
    return new SearchHit(
        absolutePath,
        symbol + ".java",
        score,
        chunkId,
        symbol,
        null,
        ChunkUnitType.CLASS,
        1,
        10);
  }

  private static SearchHit semanticHit(float semanticScore) {
    return new SearchHit(
        "/project/src/main/java/demo/AuthService.java",
        "AuthService.java",
        semanticScore,
        "chunk-1",
        "AuthService",
        "validate",
        ChunkUnitType.METHOD,
        1,
        10,
        null,
        semanticScore);
  }
}
