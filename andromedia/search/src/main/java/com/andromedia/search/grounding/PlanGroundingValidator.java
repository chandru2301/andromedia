package com.andromedia.search.grounding;

import com.andromedia.search.UsagesLookupService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class PlanGroundingValidator {

  private static final Pattern FILE_NAME =
      Pattern.compile("\\b([\\w./\\\\-]+\\.(?:java|kt|py|yml|yaml|properties))\\b");
  private static final Pattern CLASS_METHOD = Pattern.compile("\\b([A-Z][\\w]*)#([\\w]+)\\b");
  private static final Pattern PASCAL_TYPE = Pattern.compile("\\b([A-Z][\\w]{2,})\\b");
  private static final Set<String> STOP_WORDS =
      Set.of(
          "Requirements", "Implementation", "Critical", "Grounding", "Suggested", "Verified",
          "String", "Integer", "Boolean", "Override", "Kafka", "Spring", "Java");

  private final UsagesLookupService usagesLookupService;

  public PlanGroundingValidator(UsagesLookupService usagesLookupService) {
    this.usagesLookupService = usagesLookupService;
  }

  public GroundingValidationReport validate(
      String markdown, GroundingBundle bundle, List<ArchitectureHint> hints, Path workspaceRoot) {
    if (markdown == null || markdown.isBlank() || bundle == null) {
      return GroundingValidationReport.empty();
    }

    LinkedHashSet<String> fileCandidates = new LinkedHashSet<>();
    LinkedHashSet<String> symbolCandidates = new LinkedHashSet<>();
    extractCandidates(markdown, fileCandidates, symbolCandidates);

    List<String> verified = new ArrayList<>();
    List<String> proposed = new ArrayList<>();

    for (String file : fileCandidates) {
      if (isVerifiedFile(file, bundle, workspaceRoot)) {
        verified.add(file);
      } else {
        proposed.add(file + " — not in index");
      }
    }

    for (String symbol : symbolCandidates) {
      if (isVerifiedSymbol(symbol, bundle)) {
        verified.add(symbol);
      } else {
        proposed.add(symbol + " — no matching symbol found");
      }
    }

    List<String> reuseSuggestions = buildReuseSuggestions(proposed, hints);

    return new GroundingValidationReport(
        List.copyOf(verified),
        List.copyOf(proposed),
        List.copyOf(reuseSuggestions),
        verified.size(),
        proposed.size());
  }

  public String appendValidationSection(
      String markdown, GroundingValidationReport report, List<ArchitectureHint> hints) {
    if (report == null || (report.verifiedCount() == 0 && report.proposedCount() == 0)) {
      return markdown;
    }
    StringBuilder builder = new StringBuilder(markdown.trim()).append("\n\n## Grounding validation\n\n");
    if (!report.verifiedReferences().isEmpty()) {
      builder.append("### Verified\n");
      for (String item : report.verifiedReferences()) {
        builder.append("- ").append(item).append('\n');
      }
      builder.append('\n');
    }
    if (!report.proposedReferences().isEmpty()) {
      builder.append("### Suggested new (not in index)\n");
      for (String item : report.proposedReferences()) {
        builder.append("- ").append(item).append('\n');
      }
      builder.append('\n');
    }
    if (!report.reuseSuggestions().isEmpty()) {
      builder.append("### Reuse instead of inventing\n");
      for (String item : report.reuseSuggestions()) {
        builder.append("- ").append(item).append('\n');
      }
    }
    return builder.toString().trim();
  }

  private static void extractCandidates(
      String markdown, Set<String> fileCandidates, Set<String> symbolCandidates) {
    Matcher fileMatcher = FILE_NAME.matcher(markdown);
    while (fileMatcher.find()) {
      fileCandidates.add(fileMatcher.group(1));
    }

    Matcher classMethodMatcher = CLASS_METHOD.matcher(markdown);
    while (classMethodMatcher.find()) {
      symbolCandidates.add(classMethodMatcher.group(1) + "#" + classMethodMatcher.group(2));
    }

    Matcher pascalMatcher = PASCAL_TYPE.matcher(markdown);
    while (pascalMatcher.find()) {
      String candidate = pascalMatcher.group(1);
      if (!STOP_WORDS.contains(candidate) && !candidate.endsWith(".java")) {
        symbolCandidates.add(candidate);
      }
    }
  }

  private static boolean isVerifiedFile(
      String file, GroundingBundle bundle, Path workspaceRoot) {
    if (bundle.verifiedFilePaths().contains(file)) {
      return true;
    }
    String baseName = fileName(file);
    if (bundle.verifiedFileNames().contains(baseName)) {
      return true;
    }
    if (bundle.verifiedConfigPaths().contains(file)) {
      return true;
    }
    if (workspaceRoot != null) {
      Path relative = workspaceRoot.resolve(file);
      if (Files.isRegularFile(relative)) {
        return true;
      }
    }
    return Files.isRegularFile(Path.of(file));
  }

  private boolean isVerifiedSymbol(String symbol, GroundingBundle bundle) {
    if (bundle.verifiedSymbolNames().contains(symbol)) {
      return true;
    }
    for (String id : bundle.verifiedSymbolIds()) {
      if (id.endsWith(":" + symbol) || id.contains(symbol)) {
        return true;
      }
    }
    try {
      var result =
          usagesLookupService.lookup(
              new com.andromedia.search.UsageLookupRequest(symbol, 1, false, false));
      return !result.resolvedSymbolIds().isEmpty();
    } catch (RuntimeException ex) {
      return false;
    }
  }

  private static List<String> buildReuseSuggestions(
      List<String> proposed, List<ArchitectureHint> hints) {
    if (hints == null || hints.isEmpty() || proposed.isEmpty()) {
      return List.of();
    }
    List<String> suggestions = new ArrayList<>();
    for (String proposedItem : proposed) {
      String lower = proposedItem.toLowerCase(Locale.ROOT);
      for (ArchitectureHint hint : hints) {
        if (shouldSuggestReuse(lower, hint)) {
          suggestions.add("Prefer " + hint.symbolId() + " over " + extractName(proposedItem));
          break;
        }
      }
    }
    return suggestions;
  }

  private static boolean shouldSuggestReuse(String proposedLower, ArchitectureHint hint) {
    return switch (hint.category()) {
      case "cache" -> proposedLower.contains("cache");
      case "listener" -> proposedLower.contains("consumer") || proposedLower.contains("listener");
      case "repository" -> proposedLower.contains("repository");
      case "controller" -> proposedLower.contains("controller");
      case "service" -> proposedLower.contains("service");
      case "publisher" -> proposedLower.contains("publisher") || proposedLower.contains("producer");
      case "retry" -> proposedLower.contains("retry");
      default -> false;
    };
  }

  private static String extractName(String proposedItem) {
    int dash = proposedItem.indexOf(" — ");
    return dash >= 0 ? proposedItem.substring(0, dash) : proposedItem;
  }

  private static String fileName(String path) {
    int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return slash >= 0 ? path.substring(slash + 1) : path;
  }
}
