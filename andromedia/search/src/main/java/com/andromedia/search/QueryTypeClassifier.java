package com.andromedia.search;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class QueryTypeClassifier {

  private static final Pattern CAMEL_CASE = Pattern.compile(".*[a-z][A-Z].*");
  private static final Pattern PASCAL_CASE = Pattern.compile("^[A-Z][a-zA-Z0-9]*$");
  private static final Pattern SNAKE_CASE = Pattern.compile("^[a-z][a-z0-9]*(_[a-z0-9]+)+$");
  private static final Pattern PATH_LIKE = Pattern.compile("[/\\\\]");
  private static final Pattern FILE_EXTENSION = Pattern.compile(".*\\.[a-zA-Z]{2,5}(\\b|$)");
  private static final Pattern NATURAL_LANGUAGE =
      Pattern.compile("\\b(the|and|how|what|where|when|why|logic|validation|handler|flow)\\b", Pattern.CASE_INSENSITIVE);

  public QueryType classify(String query) {
    if (query == null || query.isBlank()) {
      return QueryType.MIXED;
    }
    String trimmed = query.trim();
    int identifierSignals = countIdentifierSignals(trimmed);
    boolean naturalLanguage = looksLikeNaturalLanguage(trimmed);

    if (identifierSignals >= 2 || (identifierSignals == 1 && !naturalLanguage)) {
      return QueryType.IDENTIFIER;
    }
    if (naturalLanguage && identifierSignals == 0) {
      return QueryType.NATURAL_LANGUAGE;
    }
    if (identifierSignals > 0 && naturalLanguage) {
      return QueryType.MIXED;
    }
    return QueryType.MIXED;
  }

  private static int countIdentifierSignals(String query) {
    int signals = 0;
    if (FILE_EXTENSION.matcher(query).matches()) {
      signals++;
    }
    if (PATH_LIKE.matcher(query).find()) {
      signals++;
    }
    if (CAMEL_CASE.matcher(query).matches()) {
      signals++;
    }
    if (PASCAL_CASE.matcher(query).matches()) {
      signals++;
    }
    if (SNAKE_CASE.matcher(query).matches()) {
      signals++;
    }
    if (query.contains(".") && !query.contains(" ")) {
      signals++;
    }
    return signals;
  }

  private static boolean looksLikeNaturalLanguage(String query) {
    if (query.chars().filter(ch -> ch == ' ').count() >= 2) {
      return true;
    }
    return NATURAL_LANGUAGE.matcher(query).find();
  }
}
