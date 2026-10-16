package com.andromedia.search;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ArchitectureIntentDetector {

  private static final Set<String> PHRASE_KEYWORDS =
      Set.of("how does", "why was", "why is", "why did");

  private static final Set<String> TOKEN_KEYWORDS =
      Set.of(
          "architecture",
          "adr",
          "design",
          "built",
          "decision",
          "pattern",
          "approach",
          "structure");

  private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-zA-Z?]+");

  private ArchitectureIntentDetector() {}

  public static boolean isArchitectureIntent(String query) {
    if (query == null || query.isBlank()) {
      return false;
    }
    String normalized = query.toLowerCase(Locale.ROOT);
    for (String phrase : PHRASE_KEYWORDS) {
      if (normalized.contains(phrase)) {
        return true;
      }
    }
    Matcher matcher = TOKEN_PATTERN.matcher(normalized);
    while (matcher.find()) {
      String token = matcher.group();
      if (token.endsWith("?")) {
        token = token.substring(0, token.length() - 1);
      }
      if (TOKEN_KEYWORDS.contains(token)) {
        return true;
      }
    }
    return false;
  }
}
