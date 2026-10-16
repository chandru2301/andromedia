package com.andromedia.cli.shell.agent;

import com.andromedia.search.GraphQuery;
import com.andromedia.search.ImpactCategory;
import com.andromedia.search.TraversalDirection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ImpactQueryParser {

  private static final Pattern CLASS_METHOD = Pattern.compile("\\b([A-Z][\\w]*)#([\\w]+)\\b");
  private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");
  private static final Pattern PASCAL = Pattern.compile("\\b([A-Z][\\w]{2,})\\b");
  private static final Pattern FOR_SYMBOL =
      Pattern.compile(
          "\\b(?:for|of|on|to)\\s+([A-Za-z][\\w]*(?:#[\\w]+)?)",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern CHANGE_SYMBOL =
      Pattern.compile(
          "\\b(?:change|modify|rename|update)\\s+([A-Za-z][\\w]*(?:#[\\w]+)?)",
          Pattern.CASE_INSENSITIVE);

  private ImpactQueryParser() {}

  public static Optional<GraphQuery> parse(String line) {
    if (line == null || line.isBlank()) {
      return Optional.empty();
    }
    String trimmed = line.trim();
    Optional<String> symbol = extractSymbol(trimmed);
    if (symbol.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new GraphQuery(
            symbol.get(),
            inferDirection(trimmed),
            inferCategoryFilters(trimmed),
            inferDepth(trimmed),
            20,
            inferGrouped(trimmed),
            inferExplain(trimmed)));
  }

  static TraversalDirection inferDirection(String line) {
    String lower = line.toLowerCase(Locale.ROOT);
    if (lower.contains("what does ")
        || lower.contains("what do ")
        || (lower.contains("what does ") && lower.contains(" use"))
        || lower.contains("dependencies")
        || lower.contains("depends on what")
        || lower.contains("upstream")
        || lower.contains(" calls ")
        || lower.contains(" requires ")
        || (lower.contains("what does ") && lower.matches(".*\\buse\\??$"))) {
      return TraversalDirection.FORWARD;
    }
    return TraversalDirection.REVERSE;
  }

  static Set<ImpactCategory> inferCategoryFilters(String line) {
    String lower = line.toLowerCase(Locale.ROOT);
    EnumSet<ImpactCategory> filters = EnumSet.noneOf(ImpactCategory.class);
    if (containsAny(lower, "kafka", "listener", "consumer", "messaging")) {
      filters.add(ImpactCategory.KAFKA_MESSAGING);
    }
    if (containsAny(lower, "controller", "rest endpoint", "endpoint", " api", "apis")) {
      filters.add(ImpactCategory.API);
    }
    if (containsAny(lower, "repository", "repositories", " jpa", "database", " db ")) {
      filters.add(ImpactCategory.REPOSITORY);
    }
    if (containsAny(lower, "test suite", "test suites", "tests", "test ", " rerun")) {
      filters.add(ImpactCategory.TEST);
    }
    if (containsAny(lower, "service", "services", "handler", "handlers")
        && !filters.contains(ImpactCategory.KAFKA_MESSAGING)) {
      filters.add(ImpactCategory.SERVICE);
    }
    return filters.isEmpty() ? Set.of() : Set.copyOf(filters);
  }

  static int inferDepth(String line) {
    String lower = line.toLowerCase(Locale.ROOT);
    if (containsAny(lower, "direct", "immediate", "first-order", "first order")) {
      return 1;
    }
    return 5;
  }

  static boolean inferGrouped(String line) {
    String lower = line.toLowerCase(Locale.ROOT);
    return lower.contains("grouped") || lower.contains("by category") || lower.contains("breakdown");
  }

  static boolean inferExplain(String line) {
    String lower = line.toLowerCase(Locale.ROOT);
    return lower.contains("summarize")
        || lower.contains("summary")
        || lower.contains("explain")
        || lower.contains("describe");
  }

  static Optional<String> extractSymbol(String line) {
    Matcher classMethod = CLASS_METHOD.matcher(line);
    if (classMethod.find()) {
      return Optional.of(classMethod.group(1) + "#" + classMethod.group(2));
    }
    Matcher quoted = QUOTED.matcher(line);
    String lastQuoted = null;
    while (quoted.find()) {
      lastQuoted = quoted.group(1).trim();
    }
    if (lastQuoted != null && !lastQuoted.isBlank()) {
      return Optional.of(lastQuoted);
    }
    Matcher forSymbol = FOR_SYMBOL.matcher(line);
    if (forSymbol.find()) {
      return Optional.of(forSymbol.group(1).trim());
    }
    Matcher changeSymbol = CHANGE_SYMBOL.matcher(line);
    if (changeSymbol.find()) {
      return Optional.of(changeSymbol.group(1).trim());
    }
    if (line.toLowerCase(Locale.ROOT).startsWith("impact of ")) {
      return Optional.of(line.substring("impact of ".length()).trim());
    }
    if (line.toLowerCase(Locale.ROOT).startsWith("impact ")) {
      return Optional.of(line.substring("impact ".length()).trim());
    }
    LinkedHashSet<String> candidates = new LinkedHashSet<>();
    Matcher pascal = PASCAL.matcher(line);
    while (pascal.find()) {
      String candidate = pascal.group(1);
      if (!isStopWord(candidate)) {
        candidates.add(candidate);
      }
    }
    if (candidates.isEmpty()) {
      String[] tokens = line.split("\\s+");
      for (int i = tokens.length - 1; i >= 0; i--) {
        String token = tokens[i].replaceAll("[^\\w#]", "");
        if (token.length() >= 3 && Character.isLetter(token.charAt(0))) {
          return Optional.of(token);
        }
      }
      return Optional.empty();
    }
    return Optional.of(candidates.stream().reduce((first, second) -> second).orElseThrow());
  }

  private static boolean isStopWord(String word) {
    return Set.of(
            "What", "Which", "Where", "When", "Show", "List", "Find", "Kafka", "Java", "Spring")
        .contains(word);
  }

  private static boolean containsAny(String haystack, String... needles) {
    for (String needle : needles) {
      if (haystack.contains(needle)) {
        return true;
      }
    }
    return false;
  }
}
