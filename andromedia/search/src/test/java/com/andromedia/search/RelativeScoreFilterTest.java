package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.ChunkUnitType;
import com.andromedia.common.IndexConstants;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RelativeScoreFilterTest {

  @Test
  void dropsWeakZohoWhenNotionDominates() {
    List<SearchHit> ranked =
        List.of(
            hit("notion-1", IndexConstants.SOURCE_TYPE_NOTION, 8.5f),
            hit("linear-1", IndexConstants.SOURCE_TYPE_LINEAR, 5.0f),
            hit("zoho-1", IndexConstants.SOURCE_TYPE_ZOHO, 0.03f),
            hit("zoho-2", IndexConstants.SOURCE_TYPE_ZOHO, 0.02f));

    List<SearchHit> filtered = RelativeScoreFilter.apply(ranked, 10);

    assertEquals(2, filtered.size());
    assertTrue(
        filtered.stream()
            .noneMatch(h -> IndexConstants.SOURCE_TYPE_ZOHO.equals(h.sourceType().orElse(""))));
  }

  @Test
  void keepsCloseZohoPeersOnCrmQuery() {
    List<SearchHit> ranked =
        List.of(
            hit("zoho-1", IndexConstants.SOURCE_TYPE_ZOHO, 0.05f),
            hit("zoho-2", IndexConstants.SOURCE_TYPE_ZOHO, 0.04f),
            hit("zoho-3", IndexConstants.SOURCE_TYPE_ZOHO, 0.035f));

    List<SearchHit> filtered = RelativeScoreFilter.apply(ranked, 10);

    assertEquals(3, filtered.size());
  }

  private static SearchHit hit(String chunkId, String sourceType, float score) {
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
        Optional.of(chunkId),
        Optional.empty());
  }
}
