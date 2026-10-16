package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.andromedia.common.ChunkUnitType;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReciprocalRankFusionWeightedTest {

  @Test
  void weightsBm25HigherForIdentifierStyleFusion() {
    SearchHit bm25Only =
        hit("bm25", "/a.java", "A", 1.0f, 1.0f, null);
    SearchHit semanticOnly =
        hit("semantic", "/b.java", "B", 0.9f, null, 0.9f);

    var balanced =
        ReciprocalRankFusion.fuse(
            List.of(bm25Only),
            List.of(semanticOnly),
            2,
            ReciprocalRankFusion.DEFAULT_K,
            new RankingHeuristicsProperties.ChannelWeights(1.0, 1.0));

    var identifierWeighted =
        ReciprocalRankFusion.fuse(
            List.of(bm25Only),
            List.of(semanticOnly),
            2,
            ReciprocalRankFusion.DEFAULT_K,
            new RankingHeuristicsProperties.ChannelWeights(1.0, 0.1));

    assertEquals("bm25", identifierWeighted.getFirst().chunkId());
    assertTrueScoreOrderingPrefersBm25UnderIdentifierWeights(balanced, identifierWeighted);
  }

  private static void assertTrueScoreOrderingPrefersBm25UnderIdentifierWeights(
      List<SearchHit> balanced, List<SearchHit> identifierWeighted) {
    int bm25RankBalanced = rankOf(balanced, "bm25");
    int bm25RankIdentifier = rankOf(identifierWeighted, "bm25");
    assertTrueRankImprovement(bm25RankBalanced, bm25RankIdentifier);
  }

  private static int rankOf(List<SearchHit> hits, String chunkId) {
    for (int i = 0; i < hits.size(); i++) {
      if (chunkId.equals(hits.get(i).chunkId())) {
        return i;
      }
    }
    return hits.size();
  }

  private static void assertTrueRankImprovement(int balancedRank, int identifierRank) {
    if (balancedRank > identifierRank) {
      return;
    }
    if (balancedRank == identifierRank) {
      return;
    }
    throw new AssertionError("Expected bm25 rank to improve with identifier weighting");
  }

  private static SearchHit hit(
      String chunkId,
      String path,
      String symbol,
      float score,
      Float bm25,
      Float semantic) {
    return new SearchHit(
        path,
        symbol + ".java",
        score,
        chunkId,
        symbol,
        null,
        ChunkUnitType.METHOD,
        1,
        5,
        bm25,
        semantic);
  }
}
