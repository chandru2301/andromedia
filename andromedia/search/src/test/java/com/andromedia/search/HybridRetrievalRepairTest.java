package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.ChunkUnitType;
import com.andromedia.common.IndexConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Regression for BM25-strong / KNN-weak same-source entity loss (Al Habtoor $310K vs Notion
 * $450K): wrong Zoho KNN floods RRF top-5; correct Zoho must survive fusePool → coverage →
 * lexical promote → limit(5).
 */
class HybridRetrievalRepairTest {

  private static final String QUERY = "What's the current deal value for Al Habtoor?";

  @Test
  void correctBm25ZohoSurvivesWhenWrongZohoOwnsKnnTopK() {
    List<SearchHit> semantic =
        IntStream.range(0, 30)
            .mapToObj(
                i ->
                    hit(
                        "zoho-wrong-" + i,
                        "HDFC Core Banking Upgrade",
                        IndexConstants.SOURCE_TYPE_ZOHO,
                        0.06f - i * 0.001f,
                        "HDFC Bank Corporate Amount 2100000"))
            .toList();

    List<SearchHit> bm25 = new ArrayList<>();
    bm25.add(
        hit(
            "zoho-al-habtoor",
            "Al Habtoor – Phase 2 Expansion",
            IndexConstants.SOURCE_TYPE_ZOHO,
            8.5f,
            "Deal · Al Habtoor – Phase 2 Expansion · Negotiation/Review · Amount: 310000"));
    bm25.add(
        hit(
            "notion-al-habtoor",
            "Deal Notes / Proposals",
            IndexConstants.SOURCE_TYPE_NOTION,
            7.0f,
            "Al Habtoor draft proposal sent to client: $450,000"));
    bm25.addAll(
        IntStream.range(0, 10)
            .mapToObj(
                i ->
                    hit(
                        "zoho-wrong-bm25-" + i,
                        "Wipro Annual License",
                        IndexConstants.SOURCE_TYPE_ZOHO,
                        1.1f,
                        "Wipro Enterprise Solutions"))
            .toList());

    int maxResults = 5;
    int fusePool = Math.max(maxResults * 4, 25);
    RankingHeuristicsProperties.ChannelWeights nlWeights =
        new RankingHeuristicsProperties.ChannelWeights(0.6, 1.0);

    List<SearchHit> fusedEarly =
        ReciprocalRankFusion.fuse(
            bm25, semantic, maxResults, ReciprocalRankFusion.DEFAULT_K, nlWeights);
    assertFalse(
        fusedEarly.stream().anyMatch(h -> "zoho-al-habtoor".equals(h.chunkId())),
        "precondition: early truncate to maxResults must drop BM25-only Al Habtoor");

    List<SearchHit> fused =
        ReciprocalRankFusion.fuse(
            bm25, semantic, fusePool, ReciprocalRankFusion.DEFAULT_K, nlWeights);
    List<SearchHit> covered =
        SourceCoverageMerger.merge(fused, bm25, semantic, fusePool, QUERY);
    List<SearchHit> relevanceFiltered = RelativeScoreFilter.apply(covered, fusePool);
    List<SearchHit> lexicallyAnchored =
        LexicalAnchorFilter.apply(relevanceFiltered, bm25, fusePool, QUERY);
    List<SearchHit> finalHits =
        lexicallyAnchored.stream().limit(maxResults).toList();

    assertTrue(
        finalHits.stream().anyMatch(h -> "zoho-al-habtoor".equals(h.chunkId())),
        "final top-" + maxResults + " must include correct Al Habtoor Zoho deal, not merely any ZOHO");
    assertTrue(
        finalHits.stream().anyMatch(h -> "notion-al-habtoor".equals(h.chunkId())),
        "Notion $450K note should remain for conflict awareness");
    assertFalse(
        finalHits.stream().allMatch(h -> IndexConstants.SOURCE_TYPE_ZOHO.equals(h.sourceType().orElse(""))
            && h.chunkId() != null
            && h.chunkId().startsWith("zoho-wrong")),
        "must not be only wrong-entity Zoho padding");
  }

  private static SearchHit hit(
      String chunkId, String name, String sourceType, float score, String content) {
    return new SearchHit(
        sourceType.toLowerCase() + "/" + chunkId,
        name,
        score,
        chunkId,
        null,
        null,
        ChunkUnitType.ZOHO,
        0,
        0,
        score,
        null,
        List.of(),
        List.of(),
        List.of(),
        Optional.of(sourceType),
        Optional.empty(),
        Optional.empty(),
        Optional.of(content),
        Optional.empty());
  }
}
