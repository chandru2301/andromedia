package com.andromedia.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * After RRF, injects top BM25 hits whose {@code (sourceType, canonicalEntity)} never appeared in
 * the semantic channel (typical when some sources lack embeddings and another source's KNN floods
 * fusion). Also injects competitive, query-aligned BM25 hits when that source type is already
 * present but for a different entity (wrong-entity KNN "coverage").
 *
 * <p>All thresholds are relative to the BM25 score distribution; alignment uses query tokens only.
 * Coverage is tracked per {@code (sourceType, canonicalEntity)}, not source-type alone.
 */
final class SourceCoverageMerger {

  private SourceCoverageMerger() {}

  static List<SearchHit> merge(
      List<SearchHit> fused,
      List<SearchHit> bm25Hits,
      List<SearchHit> semanticHits,
      int maxResults) {
    return merge(fused, bm25Hits, semanticHits, maxResults, null);
  }

  static List<SearchHit> merge(
      List<SearchHit> fused,
      List<SearchHit> bm25Hits,
      List<SearchHit> semanticHits,
      int maxResults,
      String query) {
    int limit = Math.max(1, maxResults);
    if (fused == null || fused.isEmpty()) {
      return bm25Hits == null ? List.of() : bm25Hits.stream().limit(limit).toList();
    }
    if (bm25Hits == null || bm25Hits.isEmpty()) {
      return fused.stream().limit(limit).toList();
    }

    Set<String> semanticSources = sourceTypes(semanticHits);
    if (semanticSources.isEmpty()) {
      return fused.stream().limit(limit).toList();
    }

    Set<String> semanticCoveragePairs = coveragePairs(semanticHits);
    Set<String> fusedChunkIds = chunkIds(fused);
    List<SearchHit> coverageHits = new ArrayList<>();
    Set<String> seenChunkIds = new LinkedHashSet<>();
    Set<String> coveredMissingTypes = new LinkedHashSet<>();
    Set<String> injectedPairs = new LinkedHashSet<>();
    float bestBm25 =
        bm25Hits.stream().map(SearchHit::score).max(Float::compare).orElse(0f);
    double coverageFloor = bestBm25 > 0f ? bestBm25 * 0.08 : 0.0;
    float entityFloor = QueryLexicalMatch.memberFloor(bestBm25);
    List<String> queryTokens = QueryLexicalMatch.significantTokens(query);
    String queryEntityKey = CanonicalEntityKey.key(query);

    // Pass 1: source types absent from semantic (still one row per missing type).
    for (SearchHit hit : bm25Hits) {
      String source = normalizedSource(hit);
      if (source.isBlank()
          || semanticSources.contains(source)
          || coveredMissingTypes.contains(source)) {
        continue;
      }
      if (hit.score() < coverageFloor) {
        continue;
      }
      String chunkId = hit.chunkId() == null ? "" : hit.chunkId();
      if (!chunkId.isBlank() && (fusedChunkIds.contains(chunkId) || !seenChunkIds.add(chunkId))) {
        continue;
      }
      coverageHits.add(hit);
      coveredMissingTypes.add(source);
      String pair = coveragePair(hit);
      if (!pair.isBlank()) {
        injectedPairs.add(pair);
      }
    }

    // Pass 2: query-aligned BM25 when (source, entity) is not yet covered by semantic.
    for (SearchHit hit : bm25Hits) {
      String source = normalizedSource(hit);
      if (source.isBlank() || !semanticSources.contains(source)) {
        continue;
      }
      String pair = coveragePair(hit);
      if (pair.isBlank()) {
        continue;
      }
      if (semanticCoveragePairs.contains(pair) || injectedPairs.contains(pair)) {
        continue;
      }
      if (hit.score() < entityFloor) {
        continue;
      }
      if (!QueryLexicalMatch.isAligned(hit, queryTokens)
          && !entityKeyAligned(hit, queryEntityKey)) {
        continue;
      }
      String chunkId = hit.chunkId() == null ? "" : hit.chunkId();
      if (chunkId.isBlank() || fusedChunkIds.contains(chunkId) || !seenChunkIds.add(chunkId)) {
        continue;
      }
      coverageHits.add(hit);
      injectedPairs.add(pair);
    }

    if (coverageHits.isEmpty()) {
      return fused.stream().limit(limit).toList();
    }

    int maxReserve = Math.max(1, limit / 3);
    if (limit >= 5) {
      maxReserve = Math.max(2, limit / 3);
    }
    // Prefer query-entity injects near the front of the reserved window.
    coverageHits.sort(
        (a, b) -> Boolean.compare(entityKeyAligned(b, queryEntityKey), entityKeyAligned(a, queryEntityKey)));
    int reserve = Math.min(coverageHits.size(), maxReserve);
    int keepFromFused = Math.max(0, limit - reserve);

    List<SearchHit> merged = new ArrayList<>(limit);
    Set<String> mergedChunkIds = new LinkedHashSet<>();

    float fusedTop = fused.getFirst().score();
    if (!(fusedTop > 0f)) {
      fusedTop = 0.01f;
    }
    int coverageIndex = 0;
    for (SearchHit hit : coverageHits) {
      if (merged.size() >= reserve) {
        break;
      }
      String chunkId = hit.chunkId() == null ? "" : hit.chunkId();
      if (!chunkId.isBlank() && !mergedChunkIds.add(chunkId)) {
        continue;
      }
      float scaled = fusedTop * (1.02f - Math.min(0.15f, coverageIndex * 0.03f));
      if (!(scaled > 0f)) {
        scaled = fusedTop * 0.9f;
      }
      merged.add(hit.withScore(scaled));
      coverageIndex++;
    }

    for (SearchHit hit : fused) {
      if (merged.size() >= limit) {
        break;
      }
      String chunkId = hit.chunkId() == null ? "" : hit.chunkId();
      if (!chunkId.isBlank() && !mergedChunkIds.add(chunkId)) {
        continue;
      }
      merged.add(hit);
      if (merged.size() >= keepFromFused + reserve && merged.size() >= limit) {
        break;
      }
    }
    return List.copyOf(merged);
  }

  private static boolean entityKeyAligned(SearchHit hit, String queryEntityKey) {
    if (queryEntityKey == null || queryEntityKey.isBlank() || hit == null) {
      return false;
    }
    String hitKey = CanonicalEntityKey.key(hit.fileName());
    if (hitKey.isBlank()) {
      return false;
    }
    return hitKey.equals(queryEntityKey)
        || hitKey.contains(queryEntityKey)
        || queryEntityKey.contains(hitKey);
  }

  private static Set<String> coveragePairs(List<SearchHit> hits) {
    Set<String> pairs = new LinkedHashSet<>();
    if (hits == null) {
      return pairs;
    }
    for (SearchHit hit : hits) {
      String pair = coveragePair(hit);
      if (!pair.isBlank()) {
        pairs.add(pair);
      }
    }
    return pairs;
  }

  private static String coveragePair(SearchHit hit) {
    if (hit == null) {
      return "";
    }
    return CanonicalEntityKey.coveragePair(normalizedSource(hit), hit.fileName());
  }

  private static Set<String> chunkIds(List<SearchHit> hits) {
    Set<String> ids = new LinkedHashSet<>();
    for (SearchHit hit : hits) {
      if (hit.chunkId() != null && !hit.chunkId().isBlank()) {
        ids.add(hit.chunkId());
      }
    }
    return ids;
  }

  private static Set<String> sourceTypes(List<SearchHit> hits) {
    Set<String> sources = new LinkedHashSet<>();
    if (hits == null) {
      return sources;
    }
    for (SearchHit hit : hits) {
      String source = normalizedSource(hit);
      if (!source.isBlank()) {
        sources.add(source);
      }
    }
    return sources;
  }

  private static String normalizedSource(SearchHit hit) {
    if (hit == null || hit.sourceType().isEmpty()) {
      return "";
    }
    String value = hit.sourceType().get().trim();
    return value.isEmpty() ? "" : value.toUpperCase(Locale.ROOT);
  }
}
