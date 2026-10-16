package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.ChunkUnitType;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReciprocalRankFusionTest {

  @Test
  void deduplicatesByChunkIdAndRanksSharedHitsHigher() {
    SearchHit sharedBm25 =
        new SearchHit(
            "/a.java", "a.java", 10f, "chunk-a", "A", "shared", ChunkUnitType.METHOD, 1, 5);
    SearchHit sharedSemantic =
        new SearchHit(
            "/a.java", "a.java", 0.9f, "chunk-a", "A", "shared", ChunkUnitType.METHOD, 1, 5);
    SearchHit bm25Only =
        new SearchHit(
            "/b.java",
            "b.java",
            8f,
            "chunk-b",
            "B",
            "bm25Only",
            ChunkUnitType.METHOD,
            1,
            5);
    SearchHit semanticOnly =
        new SearchHit(
            "/c.java",
            "c.java",
            0.8f,
            "chunk-c",
            "C",
            "semanticOnly",
            ChunkUnitType.METHOD,
            1,
            5);

    List<SearchHit> fused =
        ReciprocalRankFusion.fuse(
            List.of(sharedBm25, bm25Only),
            List.of(sharedSemantic, semanticOnly),
            3,
            ReciprocalRankFusion.DEFAULT_K);

    assertEquals(3, fused.size());
    assertEquals("chunk-a", fused.getFirst().chunkId());
    assertTrue(fused.getFirst().score() > fused.get(1).score());
    assertEquals(10f, fused.getFirst().bm25Score());
    assertEquals(0.9f, fused.getFirst().semanticScore());
  }
}
