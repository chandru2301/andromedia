package com.andromedia.search;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ReciprocalRankFusion {

  static final int DEFAULT_K = 60;

  private ReciprocalRankFusion() {}

  static List<SearchHit> fuse(
      List<SearchHit> bm25Hits,
      List<SearchHit> semanticHits,
      int maxResults,
      int k,
      RankingHeuristicsProperties.ChannelWeights channelWeights) {
    Map<String, MutableFusedHit> fusedByChunkId = new LinkedHashMap<>();
    double bm25Weight = channelWeights == null ? 1.0 : channelWeights.bm25Weight();
    double semanticWeight = channelWeights == null ? 1.0 : channelWeights.semanticWeight();

    accumulateRankScores(fusedByChunkId, bm25Hits, k, true, bm25Weight);
    accumulateRankScores(fusedByChunkId, semanticHits, k, false, semanticWeight);

    return fusedByChunkId.values().stream()
        .sorted(Comparator.comparingDouble(MutableFusedHit::fusedScore).reversed())
        .limit(maxResults)
        .map(MutableFusedHit::toSearchHit)
        .toList();
  }

  static List<SearchHit> fuse(
      List<SearchHit> bm25Hits, List<SearchHit> semanticHits, int maxResults, int k) {
    return fuse(bm25Hits, semanticHits, maxResults, k, null);
  }

  private static void accumulateRankScores(
      Map<String, MutableFusedHit> fusedByChunkId,
      List<SearchHit> rankedHits,
      int k,
      boolean bm25Channel,
      double channelWeight) {
    int rank = 1;
    for (SearchHit hit : rankedHits) {
      if (hit.chunkId() == null || hit.chunkId().isBlank()) {
        continue;
      }
      double contribution = channelWeight / (k + rank);
      MutableFusedHit fused =
          fusedByChunkId.computeIfAbsent(hit.chunkId(), ignored -> MutableFusedHit.from(hit));
      fused.addContribution(contribution, hit.score(), bm25Channel);
      rank++;
    }
  }

  private static final class MutableFusedHit {
    private final SearchHit template;
    private double fusedScore;
    private Float bm25Score;
    private Float semanticScore;

    private MutableFusedHit(SearchHit template) {
      this.template = template;
    }

    static MutableFusedHit from(SearchHit hit) {
      return new MutableFusedHit(hit);
    }

    void addContribution(double contribution, float channelScore, boolean bm25Channel) {
      fusedScore += contribution;
      if (bm25Channel) {
        bm25Score = channelScore;
      } else {
        semanticScore = channelScore;
      }
    }

    double fusedScore() {
      return fusedScore;
    }

    SearchHit toSearchHit() {
      return new SearchHit(
          template.path(),
          template.fileName(),
          (float) fusedScore,
          template.chunkId(),
          template.className(),
          template.methodName(),
          template.unitType(),
          template.startLine(),
          template.endLine(),
          bm25Score,
          semanticScore,
          template.imports(),
          template.symbolIds(),
          template.symbolNames(),
          template.sourceType(),
          template.commitSha(),
          template.timestamp(),
          template.content(),
          template.zoho());
    }
  }
}
