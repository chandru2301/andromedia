package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.ChunkUnitType;
import com.andromedia.common.IndexConstants;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LexicalAnchorFilterTest {

  @Test
  void dropsSemanticOnlyZohoWhenNotionBm25IsStrong() {
    List<SearchHit> bm25 =
        List.of(
            hit("notion-1", IndexConstants.SOURCE_TYPE_NOTION, 8.5f),
            hit("zoho-bm25", IndexConstants.SOURCE_TYPE_ZOHO, 3.2f));
    List<SearchHit> hybrid = new java.util.ArrayList<>();
    hybrid.add(hit("notion-1", IndexConstants.SOURCE_TYPE_NOTION, 0.04f));
    hybrid.addAll(
        IntStream.range(0, 7)
            .mapToObj(i -> hit("zoho-sem-" + i, IndexConstants.SOURCE_TYPE_ZOHO, 0.03f))
            .toList());
    hybrid.add(hit("zoho-bm25", IndexConstants.SOURCE_TYPE_ZOHO, 0.025f));

    List<SearchHit> filtered = LexicalAnchorFilter.apply(hybrid, bm25, 10);

    assertTrue(
        filtered.stream()
            .anyMatch(h -> IndexConstants.SOURCE_TYPE_NOTION.equals(h.sourceType().orElse(""))));
    assertTrue(filtered.stream().anyMatch(h -> "zoho-bm25".equals(h.chunkId())));
    assertFalse(
        filtered.stream().anyMatch(h -> h.chunkId() != null && h.chunkId().startsWith("zoho-sem-")),
        "semantic-only Zoho padding should be dropped");
  }

  @Test
  void dropsSemanticOnlyZohoWhenStrongBm25IsCrmLed() {
    List<SearchHit> bm25 =
        List.of(
            hit("zoho-1", IndexConstants.SOURCE_TYPE_ZOHO, 5.5f),
            hit("notion-1", IndexConstants.SOURCE_TYPE_NOTION, 4.5f),
            hit("zoho-weak-tail", IndexConstants.SOURCE_TYPE_ZOHO, 0.8f));
    List<SearchHit> hybrid =
        List.of(
            hit("zoho-sem", IndexConstants.SOURCE_TYPE_ZOHO, 0.05f),
            hit("zoho-1", IndexConstants.SOURCE_TYPE_ZOHO, 0.03f),
            hit("zoho-weak-tail", IndexConstants.SOURCE_TYPE_ZOHO, 0.028f),
            hit("notion-1", IndexConstants.SOURCE_TYPE_NOTION, 0.025f));

    List<SearchHit> filtered = LexicalAnchorFilter.apply(hybrid, bm25, 10);

    assertEquals(2, filtered.size());
    assertEquals("zoho-1", filtered.get(0).chunkId());
    assertEquals("notion-1", filtered.get(1).chunkId());
  }

  @Test
  void dropsWeakBm25ZohoAuthorizationWhenSlackAnchors() {
    List<SearchHit> bm25 =
        List.of(
            hit("slack-1", IndexConstants.SOURCE_TYPE_SLACK, 6.0f),
            hit("notion-1", IndexConstants.SOURCE_TYPE_NOTION, 2.6f),
            hit("zoho-weak", IndexConstants.SOURCE_TYPE_ZOHO, 1.4f));
    List<SearchHit> hybrid =
        List.of(
            hit("slack-1", IndexConstants.SOURCE_TYPE_SLACK, 0.06f),
            hit("zoho-sem", IndexConstants.SOURCE_TYPE_ZOHO, 0.04f),
            hit("zoho-weak", IndexConstants.SOURCE_TYPE_ZOHO, 0.035f),
            hit("notion-1", IndexConstants.SOURCE_TYPE_NOTION, 0.03f));

    List<SearchHit> filtered = LexicalAnchorFilter.apply(hybrid, bm25, 10);

    assertTrue(filtered.stream().anyMatch(h -> "slack-1".equals(h.chunkId())));
    assertTrue(filtered.stream().anyMatch(h -> "notion-1".equals(h.chunkId())));
    assertFalse(filtered.stream().anyMatch(h -> "zoho-sem".equals(h.chunkId())));
    assertFalse(
        filtered.stream().anyMatch(h -> "zoho-weak".equals(h.chunkId())),
        "weak BM25 Zoho must not authorize itself against a strong non-CRM anchor");
  }

  @Test
  void keepsSemanticZohoWhenBm25IsWeak() {
    List<SearchHit> bm25 =
        List.of(hit("zoho-weak", IndexConstants.SOURCE_TYPE_ZOHO, 1.2f));
    List<SearchHit> hybrid =
        List.of(
            hit("zoho-sem", IndexConstants.SOURCE_TYPE_ZOHO, 0.05f),
            hit("zoho-weak", IndexConstants.SOURCE_TYPE_ZOHO, 0.04f));

    List<SearchHit> filtered = LexicalAnchorFilter.apply(hybrid, bm25, 10);

    assertEquals(2, filtered.size());
  }

  @Test
  void promotesStrongBm25ZohoMissingFromHybridList() {
    List<SearchHit> bm25 =
        List.of(
            hit(
                "zoho-al-habtoor",
                IndexConstants.SOURCE_TYPE_ZOHO,
                8.5f,
                "Al Habtoor – Phase 2 Expansion Amount 310000"),
            hit("notion-1", IndexConstants.SOURCE_TYPE_NOTION, 4.5f));
    List<SearchHit> hybrid =
        IntStream.range(0, 5)
            .mapToObj(i -> hit("zoho-wrong-" + i, IndexConstants.SOURCE_TYPE_ZOHO, 0.05f - i * 0.001f))
            .toList();

    List<SearchHit> filtered =
        LexicalAnchorFilter.apply(
            hybrid, bm25, 5, "What's the current deal value for Al Habtoor?");

    assertTrue(
        filtered.stream().anyMatch(h -> "zoho-al-habtoor".equals(h.chunkId())),
        "strong BM25 Al Habtoor must be force-included even when absent from hybrid");
    assertFalse(
        filtered.stream().anyMatch(h -> h.chunkId() != null && h.chunkId().startsWith("zoho-wrong-")),
        "semantic-only wrong Zoho should still be dropped");
  }

  private static SearchHit hit(String chunkId, String sourceType, float score) {
    return hit(chunkId, sourceType, score, chunkId);
  }

  private static SearchHit hit(String chunkId, String sourceType, float score, String content) {
    return new SearchHit(
        sourceType.toLowerCase() + "/" + chunkId,
        chunkId,
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
