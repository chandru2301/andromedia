package com.andromedia.search;

import com.andromedia.common.IndexConstants;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * When BM25 has a strong relative lead (and optionally query-aligned anchors), drop semantic-only
 * Zoho padding and force-include competitive BM25 anchors missing from the hybrid list.
 *
 * <p>No absolute Lucene score floors and no entity/seed-specific vocabulary — strength is relative
 * to the BM25 distribution; alignment uses {@link QueryLexicalMatch}.
 */
final class LexicalAnchorFilter {

  private LexicalAnchorFilter() {}

  static List<SearchHit> apply(
      List<SearchHit> hybridRanked, List<SearchHit> bm25Hits, int maxResults) {
    return apply(hybridRanked, bm25Hits, maxResults, null);
  }

  static List<SearchHit> apply(
      List<SearchHit> hybridRanked, List<SearchHit> bm25Hits, int maxResults, String query) {
    int limit = Math.max(1, maxResults);
    if (hybridRanked == null || hybridRanked.isEmpty()) {
      if (bm25Hits == null || bm25Hits.isEmpty()) {
        return List.of();
      }
      return promoteOnly(bm25Hits, limit, query);
    }
    if (bm25Hits == null || bm25Hits.isEmpty()) {
      return hybridRanked.stream().limit(limit).toList();
    }

    if (!QueryLexicalMatch.hasStrongLexicalLead(bm25Hits)) {
      return hybridRanked.stream().limit(limit).toList();
    }

    float topBm25 = bm25Hits.getFirst().score();
    float memberFloor = QueryLexicalMatch.memberFloor(topBm25);
    List<String> queryTokens = QueryLexicalMatch.significantTokens(query);
    boolean requireAlignment = !queryTokens.isEmpty();

    Map<String, SearchHit> anchorByChunkId = new LinkedHashMap<>();
    for (SearchHit hit : bm25Hits) {
      if (hit.score() < memberFloor) {
        continue;
      }
      if (requireAlignment && !QueryLexicalMatch.isAligned(hit, queryTokens)) {
        continue;
      }
      if (hit.chunkId() != null && !hit.chunkId().isBlank()) {
        anchorByChunkId.putIfAbsent(hit.chunkId(), hit);
      }
      if (anchorByChunkId.size() >= 25) {
        break;
      }
    }
    if (anchorByChunkId.isEmpty()) {
      return hybridRanked.stream().limit(limit).toList();
    }
    Set<String> bm25ChunkIds = anchorByChunkId.keySet();

    List<SearchHit> filtered = new ArrayList<>();
    Set<String> filteredIds = new LinkedHashSet<>();
    for (SearchHit hit : hybridRanked) {
      String source =
          hit.sourceType().map(s -> s.trim().toUpperCase(Locale.ROOT)).orElse("");
      if (IndexConstants.SOURCE_TYPE_ZOHO.equals(source)
          && (hit.chunkId() == null || !bm25ChunkIds.contains(hit.chunkId()))) {
        continue;
      }
      String chunkId = hit.chunkId() == null ? "" : hit.chunkId();
      if (!chunkId.isBlank() && !filteredIds.add(chunkId)) {
        continue;
      }
      filtered.add(hit);
    }

    List<SearchHit> promoted = new ArrayList<>();
    Set<String> promotedIds = new LinkedHashSet<>();
    for (SearchHit anchor : anchorByChunkId.values()) {
      String chunkId = anchor.chunkId();
      if (chunkId == null || chunkId.isBlank() || filteredIds.contains(chunkId)) {
        continue;
      }
      if (!promotedIds.add(chunkId)) {
        continue;
      }
      float hybridFloor =
          filtered.isEmpty() ? hybridRanked.getFirst().score() : filtered.getFirst().score();
      if (!(hybridFloor > 0f)) {
        hybridFloor = 0.01f;
      }
      float scaled = hybridFloor * (1.05f - Math.min(0.3f, promoted.size() * 0.05f));
      promoted.add(anchor.withScore(scaled));
      if (promoted.size() >= limit) {
        break;
      }
    }

    List<SearchHit> result = new ArrayList<>(limit);
    Set<String> resultIds = new LinkedHashSet<>();
    for (SearchHit hit : promoted) {
      if (result.size() >= limit) {
        break;
      }
      String chunkId = hit.chunkId() == null ? "" : hit.chunkId();
      if (!chunkId.isBlank() && !resultIds.add(chunkId)) {
        continue;
      }
      result.add(hit);
    }
    for (SearchHit hit : filtered) {
      if (result.size() >= limit) {
        break;
      }
      String chunkId = hit.chunkId() == null ? "" : hit.chunkId();
      if (!chunkId.isBlank() && !resultIds.add(chunkId)) {
        continue;
      }
      result.add(hit);
    }
    if (result.isEmpty()) {
      return hybridRanked.stream().limit(limit).toList();
    }
    return List.copyOf(result);
  }

  private static List<SearchHit> promoteOnly(List<SearchHit> bm25Hits, int limit, String query) {
    if (!QueryLexicalMatch.hasStrongLexicalLead(bm25Hits)) {
      return bm25Hits.stream().limit(limit).toList();
    }
    float memberFloor = QueryLexicalMatch.memberFloor(bm25Hits.getFirst().score());
    List<String> queryTokens = QueryLexicalMatch.significantTokens(query);
    boolean requireAlignment = !queryTokens.isEmpty();
    List<SearchHit> out = new ArrayList<>();
    for (SearchHit hit : bm25Hits) {
      if (out.size() >= limit) {
        break;
      }
      if (hit.score() < memberFloor) {
        continue;
      }
      if (requireAlignment && !QueryLexicalMatch.isAligned(hit, queryTokens)) {
        continue;
      }
      out.add(hit);
    }
    return out.isEmpty() ? bm25Hits.stream().limit(limit).toList() : List.copyOf(out);
  }
}
