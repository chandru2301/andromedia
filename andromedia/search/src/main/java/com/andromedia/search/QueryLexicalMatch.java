package com.andromedia.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Generic query↔hit lexical alignment. Uses ordinary English function-word stopwords only — no
 * product/CRM vocabulary and no absolute score floors tied to a particular index.
 */
final class QueryLexicalMatch {

  private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9]+");

  /** Common English function words only. */
  private static final Set<String> STOPWORDS =
      Set.of(
          "a",
          "an",
          "the",
          "and",
          "or",
          "for",
          "of",
          "to",
          "in",
          "on",
          "at",
          "is",
          "are",
          "was",
          "were",
          "be",
          "been",
          "being",
          "what",
          "whats",
          "which",
          "who",
          "whom",
          "whose",
          "how",
          "when",
          "where",
          "why",
          "do",
          "does",
          "did",
          "should",
          "would",
          "could",
          "can",
          "our",
          "we",
          "i",
          "me",
          "my",
          "your",
          "with",
          "from",
          "about",
          "into",
          "over",
          "after",
          "before",
          "than",
          "then",
          "that",
          "this",
          "these",
          "those",
          "any",
          "all",
          "some",
          "not",
          "no",
          "yes",
          "as",
          "by",
          "if",
          "so",
          "too",
          "very",
          "just",
          "also",
          "have",
          "has",
          "had",
          "will",
          "shall",
          "may",
          "might",
          "must",
          "need",
          "please",
          "tell",
          "give",
          "get",
          "got",
          "make",
          "made");

  /** Competitive BM25 membership relative to the best hit (no absolute Lucene floor). */
  static final float RELATIVE_MEMBER_FRACTION = 0.25f;

  /**
   * Top BM25 must beat the median of the BM25 head by this factor to count as a strong lexical
   * lock (avoids treating flat/weak lists as anchors).
   */
  static final float STRONG_VS_MEDIAN_FACTOR = 1.2f;

  private QueryLexicalMatch() {}

  static List<String> significantTokens(String query) {
    if (query == null || query.isBlank()) {
      return List.of();
    }
    String[] raw = TOKEN_SPLIT.split(query.toLowerCase(Locale.ROOT));
    List<String> tokens = new ArrayList<>();
    for (String token : raw) {
      if (token.length() < 3 || STOPWORDS.contains(token)) {
        continue;
      }
      tokens.add(token);
    }
    return tokens;
  }

  static boolean isAligned(SearchHit hit, List<String> queryTokens) {
    if (hit == null || queryTokens == null || queryTokens.isEmpty()) {
      return false;
    }
    StringBuilder text = new StringBuilder();
    if (hit.fileName() != null) {
      text.append(' ').append(hit.fileName());
    }
    if (hit.chunkId() != null) {
      text.append(' ').append(hit.chunkId());
    }
    if (hit.path() != null) {
      text.append(' ').append(hit.path());
    }
    hit.content().ifPresent(c -> text.append(' ').append(c));
    String haystack = text.toString().toLowerCase(Locale.ROOT);
    if (haystack.isBlank()) {
      return false;
    }
    // Prefer longer tokens (names/identifiers) over short common content words still in the query.
    List<String> distinctive = new ArrayList<>();
    for (String token : queryTokens) {
      if (token.length() >= 4) {
        distinctive.add(token);
      }
    }
    List<String> effective = distinctive.isEmpty() ? queryTokens : distinctive;
    for (String token : effective) {
      if (haystack.contains(token)) {
        return true;
      }
    }
    return false;
  }

  static float memberFloor(float bestBm25) {
    if (!(bestBm25 > 0f)) {
      return Float.MAX_VALUE;
    }
    return bestBm25 * RELATIVE_MEMBER_FRACTION;
  }

  /**
   * True when the leading BM25 score dominates the head of the BM25 list (relative, index-agnostic).
   */
  static boolean hasStrongLexicalLead(List<SearchHit> bm25Hits) {
    if (bm25Hits == null || bm25Hits.isEmpty()) {
      return false;
    }
    float top = bm25Hits.getFirst().score();
    if (!(top > 0f)) {
      return false;
    }
    int n = Math.min(bm25Hits.size(), 10);
    float[] scores = new float[n];
    for (int i = 0; i < n; i++) {
      scores[i] = bm25Hits.get(i).score();
    }
    java.util.Arrays.sort(scores);
    float median =
        n % 2 == 1 ? scores[n / 2] : (scores[n / 2 - 1] + scores[n / 2]) / 2f;
    if (!(median > 0f)) {
      return top > 0f;
    }
    return top >= median * STRONG_VS_MEDIAN_FACTOR;
  }
}
