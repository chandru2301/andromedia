package com.andromedia.search;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DecisionIntentDetector {

  private static final Set<String> KEYWORDS =
      Set.of(
          "decided",
          "decide",
          "decision",
          "agreed",
          "agree",
          "because",
          "chose",
          "choose",
          "chosen",
          "why",
          "instead",
          "rather",
          "consensus",
          "discussed",
          "discussion");

  private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-zA-Z?]+");

  private DecisionIntentDetector() {}

  public static boolean isDecisionIntent(String query) {
    if (query == null || query.isBlank()) {
      return false;
    }
    String normalized = query.toLowerCase(Locale.ROOT);
    Matcher matcher = TOKEN_PATTERN.matcher(normalized);
    while (matcher.find()) {
      if (isKeyword(matcher.group())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Removes decision-intent filler terms so BM25 can match Slack prose (e.g. drop {@code decide}
   * from "why did we decide to switch to Kafka").
   */
  public static String sanitizeForRetrieval(String query) {
    if (query == null || query.isBlank()) {
      return query;
    }
    Matcher matcher = TOKEN_PATTERN.matcher(query);
    StringBuilder sanitized = new StringBuilder();
    while (matcher.find()) {
      String token = matcher.group();
      String normalized = token.toLowerCase(Locale.ROOT);
      if (normalized.endsWith("?")) {
        normalized = normalized.substring(0, normalized.length() - 1);
      }
      if (KEYWORDS.contains(normalized)) {
        continue;
      }
      if (!sanitized.isEmpty()) {
        sanitized.append(' ');
      }
      sanitized.append(normalized);
    }
    return sanitized.toString().trim();
  }

  private static boolean isKeyword(String token) {
    String normalized = token.toLowerCase(Locale.ROOT);
    String stripped =
        normalized.endsWith("?") ? normalized.substring(0, normalized.length() - 1) : normalized;
    return KEYWORDS.contains(stripped);
  }
}
