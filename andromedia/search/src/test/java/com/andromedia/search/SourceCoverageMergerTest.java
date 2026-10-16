package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.ChunkUnitType;
import com.andromedia.common.IndexConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SourceCoverageMergerTest {

  @Test
  void injectsNotionBm25HitWhenSemanticChannelIsZohoOnly() {
    List<SearchHit> semantic = zohoHits(40);
    List<SearchHit> bm25 = new ArrayList<>();
    bm25.add(hit("notion-al-habtoor", "Deal Notes / Proposals", IndexConstants.SOURCE_TYPE_NOTION, 9f));
    bm25.addAll(zohoHits(10));

    List<SearchHit> fused =
        ReciprocalRankFusion.fuse(
            bm25,
            semantic,
            10,
            ReciprocalRankFusion.DEFAULT_K,
            new RankingHeuristicsProperties.ChannelWeights(0.6, 1.0));

    assertFalse(
        fused.stream().anyMatch(h -> IndexConstants.SOURCE_TYPE_NOTION.equals(h.sourceType().orElse(""))),
        "precondition: RRF with NL weights should drown Notion");

    List<SearchHit> merged = SourceCoverageMerger.merge(fused, bm25, semantic, 10);

    assertEquals(10, merged.size());
    assertTrue(
        merged.stream()
            .anyMatch(h -> IndexConstants.SOURCE_TYPE_NOTION.equals(h.sourceType().orElse(""))),
        "coverage merge must surface Notion");
    assertEquals("notion-al-habtoor", merged.getFirst().chunkId());
  }

  @Test
  void doesNotInventSourcesAbsentFromBm25() {
    List<SearchHit> semantic = zohoHits(10);
    List<SearchHit> bm25 = zohoHits(10);
    List<SearchHit> fused =
        ReciprocalRankFusion.fuse(
            bm25, semantic, 5, ReciprocalRankFusion.DEFAULT_K, null);

    List<SearchHit> merged = SourceCoverageMerger.merge(fused, bm25, semantic, 5);

    assertEquals(5, merged.size());
    assertTrue(
        merged.stream()
            .allMatch(h -> IndexConstants.SOURCE_TYPE_ZOHO.equals(h.sourceType().orElse(""))));
  }

  @Test
  void coversMultipleMissingSourceTypes() {
    List<SearchHit> semantic = zohoHits(20);
    List<SearchHit> bm25 =
        List.of(
            hit("notion-1", "Deal Notes", IndexConstants.SOURCE_TYPE_NOTION, 8f),
            hit("slack-1", "all-causr", IndexConstants.SOURCE_TYPE_SLACK, 7f),
            hit("zoho-bm25", "Al Habtoor", IndexConstants.SOURCE_TYPE_ZOHO, 6f));

    List<SearchHit> fused =
        ReciprocalRankFusion.fuse(
            bm25,
            semantic,
            5,
            ReciprocalRankFusion.DEFAULT_K,
            new RankingHeuristicsProperties.ChannelWeights(0.6, 1.0));

    List<SearchHit> merged = SourceCoverageMerger.merge(fused, bm25, semantic, 5);

    assertTrue(
        merged.stream()
            .anyMatch(h -> IndexConstants.SOURCE_TYPE_NOTION.equals(h.sourceType().orElse(""))));
    assertTrue(
        merged.stream()
            .anyMatch(h -> IndexConstants.SOURCE_TYPE_SLACK.equals(h.sourceType().orElse(""))));
  }

  @Test
  void injectsQueryAlignedZohoWhenSemanticHasWrongZohoEntity() {
    List<SearchHit> semantic =
        IntStream.range(0, 20)
            .mapToObj(
                i ->
                    hit(
                        "zoho-wrong-" + i,
                        "HDFC Core Banking Upgrade " + i,
                        IndexConstants.SOURCE_TYPE_ZOHO,
                        0.05f - i * 0.001f))
            .toList();
    List<SearchHit> bm25 = new ArrayList<>();
    bm25.add(
        hit(
            "zoho-al-habtoor",
            "Al Habtoor – Phase 2 Expansion",
            IndexConstants.SOURCE_TYPE_ZOHO,
            8.5f,
            "Deal · Al Habtoor – Phase 2 Expansion · Amount: 310000"));
    bm25.add(
        hit(
            "notion-al-habtoor",
            "Deal Notes / Proposals",
            IndexConstants.SOURCE_TYPE_NOTION,
            7.0f,
            "Al Habtoor draft proposal $450,000"));
    bm25.addAll(
        IntStream.range(0, 5)
            .mapToObj(
                i ->
                    hit(
                        "zoho-wrong-bm25-" + i,
                        "Wipro Annual License",
                        IndexConstants.SOURCE_TYPE_ZOHO,
                        1.2f))
            .toList());

    List<SearchHit> fused =
        ReciprocalRankFusion.fuse(
            bm25,
            semantic,
            5,
            ReciprocalRankFusion.DEFAULT_K,
            new RankingHeuristicsProperties.ChannelWeights(0.6, 1.0));

    assertFalse(
        fused.stream().anyMatch(h -> "zoho-al-habtoor".equals(h.chunkId())),
        "precondition: early RRF top-5 should exclude BM25-only Al Habtoor");

    List<SearchHit> merged =
        SourceCoverageMerger.merge(
            fused,
            bm25,
            semantic,
            25,
            "What's the current deal value for Al Habtoor?");

    assertTrue(
        merged.stream().anyMatch(h -> "zoho-al-habtoor".equals(h.chunkId())),
        "entity-aware coverage must inject Al Habtoor Zoho despite ZOHO already in semantic");
    assertTrue(
        merged.stream().anyMatch(h -> "notion-al-habtoor".equals(h.chunkId())),
        "Notion coverage must still apply");
  }

  private static List<SearchHit> zohoHits(int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> hit("zoho-" + i, "Deal " + i, IndexConstants.SOURCE_TYPE_ZOHO, 1f + i * 0.01f))
        .toList();
  }

  private static SearchHit hit(String chunkId, String name, String sourceType, float score) {
    return hit(chunkId, name, sourceType, score, name);
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
