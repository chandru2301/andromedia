package com.andromedia.search.grounding;

import com.andromedia.search.ImpactLookupResult;
import com.andromedia.search.ImpactSite;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ArchitecturePatternDetector {

  public List<ArchitectureHint> detect(
      String description, GroundingBundle bundle, Optional<ImpactLookupResult> reverseImpact) {
    if (bundle == null || description == null || description.isBlank()) {
      return List.of();
    }
    String lower = description.toLowerCase(Locale.ROOT);
    Set<String> triggeredCategories = triggeredCategories(lower);
    if (triggeredCategories.isEmpty()) {
      return List.of();
    }

    Map<String, Candidate> candidates = new LinkedHashMap<>();
    collectFromBundle(bundle, candidates);
    reverseImpact.ifPresent(r -> collectFromImpact(r, candidates));

    List<ArchitectureHint> hints = new ArrayList<>();
    for (String category : triggeredCategories) {
      for (Candidate candidate : candidates.values()) {
        if (matchesCategory(category, candidate)) {
          hints.add(toHint(category, candidate));
        }
      }
    }
    return dedupeBySymbol(hints);
  }

  private static Set<String> triggeredCategories(String lower) {
    LinkedHashSet<String> categories = new LinkedHashSet<>();
    if (containsAny(lower, "cache", "caching", "redis", "memcache")) {
      categories.add("cache");
    }
    if (containsAny(lower, "kafka", "listener", "consumer", "messaging", "topic")) {
      categories.add("listener");
    }
    if (containsAny(lower, "repository", "jpa", "database", "persist", "entity")) {
      categories.add("repository");
    }
    if (containsAny(lower, "controller", "api", "endpoint", "rest", "http")) {
      categories.add("controller");
    }
    if (containsAny(lower, "service", "handler", "business logic")) {
      categories.add("service");
    }
    if (containsAny(lower, "publish", "producer", "emit", "send event")) {
      categories.add("publisher");
    }
    if (containsAny(lower, "retry", "retries", "backoff", "idempoten")) {
      categories.add("retry");
    }
    return categories;
  }

  private static boolean containsAny(String text, String... tokens) {
    for (String token : tokens) {
      if (text.contains(token)) {
        return true;
      }
    }
    return false;
  }

  private static void collectFromBundle(GroundingBundle bundle, Map<String, Candidate> candidates) {
    for (String symbolId : bundle.verifiedSymbolIds()) {
      addCandidate(candidates, symbolId, displayName(symbolId), fileNameForSymbol(symbolId, bundle));
    }
    for (String name : bundle.verifiedSymbolNames()) {
      addCandidate(candidates, name, name, fileNameForName(name, bundle));
    }
  }

  private static void collectFromImpact(ImpactLookupResult result, Map<String, Candidate> candidates) {
    for (ImpactSite site : result.affectedSymbols()) {
      addCandidate(
          candidates,
          site.symbolId() != null ? site.symbolId() : site.symbolName(),
          site.symbolName(),
          fileNameFromPath(site.path()));
    }
  }

  private static void addCandidate(
      Map<String, Candidate> candidates, String key, String displayName, String fileName) {
    if (displayName == null || displayName.isBlank()) {
      return;
    }
    String dedupeKey = key != null ? key : displayName;
    candidates.putIfAbsent(dedupeKey, new Candidate(displayName, fileName, classify(displayName)));
  }

  private static String classify(String name) {
    if (name == null) {
      return "other";
    }
    if (name.contains("Cache") || name.toLowerCase(Locale.ROOT).contains("cache")) {
      return "cache";
    }
    if (name.endsWith("Listener") || name.contains("Kafka")) {
      return "listener";
    }
    if (name.endsWith("Repository")) {
      return "repository";
    }
    if (name.endsWith("Controller")) {
      return "controller";
    }
    if (name.endsWith("Service") || name.endsWith("Handler")) {
      return "service";
    }
    if (name.contains("Publisher") || name.contains("Producer")) {
      return "publisher";
    }
    if (name.contains("Consumer")) {
      return "listener";
    }
    if (name.contains("Retry")) {
      return "retry";
    }
    return "other";
  }

  private static boolean matchesCategory(String category, Candidate candidate) {
    return category.equals(candidate.category())
        || (category.equals("listener") && candidate.category().equals("listener"))
        || (category.equals("publisher") && candidate.category().equals("publisher"));
  }

  private static ArchitectureHint toHint(String category, Candidate candidate) {
    String guidance =
        switch (category) {
          case "cache" ->
              "Reuse existing cache abstraction (" + candidate.displayName() + ") instead of creating a new cache type";
          case "listener" ->
              "Reuse existing messaging/listener pattern (" + candidate.displayName() + ")";
          case "repository" ->
              "Extend existing repository (" + candidate.displayName() + ") instead of adding a parallel data access type";
          case "controller" ->
              "Follow existing API/controller pattern (" + candidate.displayName() + ")";
          case "service" ->
              "Extend existing service layer (" + candidate.displayName() + ") instead of duplicating logic";
          case "publisher" ->
              "Reuse existing publisher/producer (" + candidate.displayName() + ")";
          case "retry" ->
              "Align with existing retry/idempotency pattern (" + candidate.displayName() + ")";
          default -> "Reuse existing abstraction (" + candidate.displayName() + ")";
        };
    return new ArchitectureHint(
        category,
        candidate.displayName(),
        candidate.fileName() != null ? candidate.fileName() : candidate.displayName() + ".java",
        guidance);
  }

  private static List<ArchitectureHint> dedupeBySymbol(List<ArchitectureHint> hints) {
    LinkedHashMap<String, ArchitectureHint> deduped = new LinkedHashMap<>();
    for (ArchitectureHint hint : hints) {
      deduped.putIfAbsent(hint.symbolId(), hint);
    }
    return List.copyOf(deduped.values());
  }

  public static String formatForPrompt(List<ArchitectureHint> hints) {
    if (hints == null || hints.isEmpty()) {
      return "";
    }
    StringBuilder builder = new StringBuilder();
    builder.append("--- REUSE EXISTING PATTERNS ---\n");
    for (ArchitectureHint hint : hints) {
      builder
          .append("- ")
          .append(capitalize(hint.category()))
          .append(": ")
          .append(hint.symbolId());
      if (hint.fileName() != null && !hint.fileName().isBlank()) {
        builder.append(" (").append(hint.fileName()).append(')');
      }
      builder.append(" — ").append(hint.reuseGuidance()).append('\n');
    }
    builder.append('\n');
    return builder.toString().trim();
  }

  private static String capitalize(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    return Character.toUpperCase(value.charAt(0)) + value.substring(1);
  }

  private static String displayName(String symbolId) {
    if (symbolId == null) {
      return "";
    }
    int colon = symbolId.indexOf(':');
    return colon >= 0 ? symbolId.substring(colon + 1) : symbolId;
  }

  private static String fileNameForSymbol(String symbolId, GroundingBundle bundle) {
    String simple = displayName(symbolId);
    int hash = simple.indexOf('#');
    String typeName = hash >= 0 ? simple.substring(0, hash) : simple;
    int lastDot = typeName.lastIndexOf('.');
    if (lastDot >= 0) {
      typeName = typeName.substring(lastDot + 1);
    }
    String candidate = typeName + ".java";
    if (bundle.verifiedFileNames().contains(candidate)) {
      return candidate;
    }
    return candidate;
  }

  private static String fileNameForName(String name, GroundingBundle bundle) {
    if (name == null) {
      return null;
    }
    int hash = name.indexOf('#');
    String typeName = hash >= 0 ? name.substring(0, hash) : name;
    String candidate = typeName + ".java";
    if (bundle.verifiedFileNames().contains(candidate)) {
      return candidate;
    }
    return candidate;
  }

  private static String fileNameFromPath(String path) {
    if (path == null || path.isBlank()) {
      return null;
    }
    int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return slash >= 0 ? path.substring(slash + 1) : path;
  }

  private record Candidate(String displayName, String fileName, String category) {}
}
