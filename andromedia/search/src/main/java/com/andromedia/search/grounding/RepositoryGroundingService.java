package com.andromedia.search.grounding;

import com.andromedia.common.IndexPathResolver;
import com.andromedia.search.GraphContextEnricher;
import com.andromedia.search.ImpactLookupResult;
import com.andromedia.search.ImpactSite;
import com.andromedia.search.MethodHint;
import com.andromedia.search.SearchHit;
import com.andromedia.search.SearchResult;
import com.andromedia.search.SymbolQueryParser;
import com.andromedia.search.UsageLookupRequest;
import com.andromedia.search.UsageLookupResult;
import com.andromedia.search.UsagesLookupService;
import com.andromedia.search.UsageSite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RepositoryGroundingService {

  private final UsagesLookupService usagesLookupService;
  private final String configuredIndexPath;

  public RepositoryGroundingService(
      UsagesLookupService usagesLookupService,
      @Value("${" + com.andromedia.common.IndexConstants.INDEX_PATH_PROPERTY + ":}")
          String configuredIndexPath) {
    this.usagesLookupService = usagesLookupService;
    this.configuredIndexPath = configuredIndexPath;
  }

  static RepositoryGroundingService forTest(
      UsagesLookupService usagesLookupService, String configuredIndexPath) {
    return new RepositoryGroundingService(usagesLookupService, configuredIndexPath);
  }

  public GroundingBundle build(PlanGroundingContext context) {
    LinkedHashSet<String> filePaths = new LinkedHashSet<>();
    LinkedHashSet<String> fileNames = new LinkedHashSet<>();
    LinkedHashSet<String> symbolIds = new LinkedHashSet<>();
    LinkedHashSet<String> symbolNames = new LinkedHashSet<>();
    LinkedHashSet<String> packages = new LinkedHashSet<>();
    LinkedHashSet<String> configPaths = new LinkedHashSet<>();
    List<String> unresolved = new ArrayList<>();

    addSearchHits(context.searchResult(), filePaths, fileNames, symbolIds, symbolNames, packages, configPaths);
    context.reverseImpact().ifPresent(r -> addImpact(r, filePaths, fileNames, symbolIds, symbolNames, packages));
    context.forwardImpact().ifPresent(f -> addImpact(f, filePaths, fileNames, symbolIds, symbolNames, packages));
    context
        .graphBlock()
        .ifPresent(
            block -> {
              for (String seedId : block.seedSymbolIds()) {
                symbolIds.add(seedId);
                symbolNames.add(displaySymbolName(seedId));
                packages.addAll(extractPackages(seedId));
              }
            });
    context.usagesResult().ifPresent(u -> addUsages(u, filePaths, fileNames, symbolIds, symbolNames, packages));

    resolveDescriptionSeeds(context.description(), symbolIds, symbolNames, packages);
    if (context.symbolQuery() != null && !context.symbolQuery().isBlank()) {
      resolveSymbolQuery(
          context.symbolQuery(), filePaths, fileNames, symbolIds, symbolNames, packages, unresolved, context);
    }

    return new GroundingBundle(
        Set.copyOf(filePaths),
        Set.copyOf(fileNames),
        Set.copyOf(symbolIds),
        Set.copyOf(symbolNames),
        Set.copyOf(packages),
        Set.copyOf(configPaths),
        List.copyOf(unresolved));
  }

  static void addSearchHits(
      SearchResult searchResult,
      Set<String> filePaths,
      Set<String> fileNames,
      Set<String> symbolIds,
      Set<String> symbolNames,
      Set<String> packages,
      Set<String> configPaths) {
    if (searchResult == null || searchResult.hits().isEmpty()) {
      return;
    }
    for (SearchHit hit : searchResult.hits()) {
      addFilePath(hit.path(), hit.fileName(), filePaths, fileNames, configPaths);
      if (hit.className() != null && !hit.className().isBlank()) {
        symbolNames.add(hit.className());
      }
      if (hit.methodName() != null && !hit.methodName().isBlank()) {
        if (hit.className() != null && !hit.className().isBlank()) {
          symbolNames.add(hit.className() + "#" + hit.methodName());
        }
        symbolNames.add(hit.methodName());
      }
      for (String id : hit.symbolIds()) {
        symbolIds.add(id);
        symbolNames.add(displaySymbolName(id));
        packages.addAll(extractPackages(id));
      }
      for (String name : hit.symbolNames()) {
        if (name != null && !name.isBlank()) {
          symbolNames.add(name);
        }
      }
    }
  }

  private static void addImpact(
      ImpactLookupResult result,
      Set<String> filePaths,
      Set<String> fileNames,
      Set<String> symbolIds,
      Set<String> symbolNames,
      Set<String> packages) {
    if (result == null) {
      return;
    }
    for (String seedId : result.seedSymbolIds()) {
      symbolIds.add(seedId);
      symbolNames.add(displaySymbolName(seedId));
      packages.addAll(extractPackages(seedId));
    }
    for (ImpactSite site : result.affectedSymbols()) {
      addFilePath(site.path(), fileNameFromPath(site.path()), filePaths, fileNames, null);
      if (site.symbolId() != null) {
        symbolIds.add(site.symbolId());
        packages.addAll(extractPackages(site.symbolId()));
      }
      if (site.symbolName() != null && !site.symbolName().isBlank()) {
        symbolNames.add(site.symbolName());
      }
    }
  }

  private static void addUsages(
      UsageLookupResult result,
      Set<String> filePaths,
      Set<String> fileNames,
      Set<String> symbolIds,
      Set<String> symbolNames,
      Set<String> packages) {
    if (result == null) {
      return;
    }
    for (String id : result.resolvedSymbolIds()) {
      symbolIds.add(id);
      symbolNames.add(displaySymbolName(id));
      packages.addAll(extractPackages(id));
    }
    for (UsageSite site : result.definitions()) {
      addUsageSite(site, filePaths, fileNames, symbolIds, symbolNames, packages);
    }
    for (UsageSite site : result.references()) {
      addUsageSite(site, filePaths, fileNames, symbolIds, symbolNames, packages);
    }
  }

  private static void addUsageSite(
      UsageSite site,
      Set<String> filePaths,
      Set<String> fileNames,
      Set<String> symbolIds,
      Set<String> symbolNames,
      Set<String> packages) {
    addFilePath(site.path(), site.fileName(), filePaths, fileNames, null);
    if (site.symbolId() != null) {
      symbolIds.add(site.symbolId());
      packages.addAll(extractPackages(site.symbolId()));
    }
    if (site.symbolName() != null && !site.symbolName().isBlank()) {
      symbolNames.add(site.symbolName());
    }
  }

  private void resolveDescriptionSeeds(
      String description, Set<String> symbolIds, Set<String> symbolNames, Set<String> packages) {
    if (description == null || description.isBlank()) {
      return;
    }
    for (String seed : GraphContextEnricher.extractSeedQueries(description)) {
      resolveViaUsages(seed, symbolIds, symbolNames, packages);
    }
  }

  private void resolveSymbolQuery(
      String symbolQuery,
      Set<String> filePaths,
      Set<String> fileNames,
      Set<String> symbolIds,
      Set<String> symbolNames,
      Set<String> packages,
      List<String> unresolved,
      PlanGroundingContext context) {
    UsageLookupResult lookup =
        usagesLookupService.lookup(new UsageLookupRequest(symbolQuery, 5, true, true));
    if (!lookup.resolvedSymbolIds().isEmpty()) {
      addUsages(lookup, filePaths, fileNames, symbolIds, symbolNames, packages);
      symbolNames.add(symbolQuery.trim());
      return;
    }
    unresolved.add(formatMethodHints(symbolQuery, lookup, context));
  }

  private void resolveViaUsages(
      String query, Set<String> symbolIds, Set<String> symbolNames, Set<String> packages) {
    UsageLookupResult lookup = usagesLookupService.lookup(new UsageLookupRequest(query, 3, true, true));
    if (lookup.resolvedSymbolIds().isEmpty()) {
      return;
    }
    addUsages(lookup, new LinkedHashSet<>(), new LinkedHashSet<>(), symbolIds, symbolNames, packages);
  }

  private static String formatMethodHints(
      String symbolQuery, UsageLookupResult lookup, PlanGroundingContext context) {
    List<MethodHint> hints =
        context.reverseImpact()
            .map(ImpactLookupResult::methodHints)
            .filter(list -> !list.isEmpty())
            .orElse(lookup.methodHints());
    if (hints.isEmpty()) {
      return symbolQuery + " (not found in index)";
    }
    StringBuilder builder = new StringBuilder(symbolQuery).append(" (not found; suggest closest: ");
    for (int i = 0; i < Math.min(3, hints.size()); i++) {
      if (i > 0) {
        builder.append(", ");
      }
      MethodHint hint = hints.get(i);
      var parsed = SymbolQueryParser.parseClassMethodQuery(symbolQuery);
      if (parsed.isPresent()) {
        builder.append(parsed.get().className()).append('#').append(hint.symbolName());
      } else {
        builder.append(hint.symbolName());
      }
    }
    builder.append(')');
    return builder.toString();
  }

  static void addFilePath(
      String path,
      String fileName,
      Set<String> filePaths,
      Set<String> fileNames,
      Set<String> configPaths) {
    if (path != null && !path.isBlank()) {
      filePaths.add(path);
      if (isConfigPath(path) && configPaths != null) {
        configPaths.add(path);
      }
    }
    String name = fileName != null && !fileName.isBlank() ? fileName : fileNameFromPath(path);
    if (name != null && !name.isBlank()) {
      fileNames.add(name);
    }
  }

  static boolean isConfigPath(String path) {
    if (path == null) {
      return false;
    }
    String lower = path.toLowerCase(Locale.ROOT);
    return lower.endsWith(".yml")
        || lower.endsWith(".yaml")
        || lower.endsWith(".properties")
        || lower.contains("/resources/application")
        || lower.contains("\\resources\\application");
  }

  static String fileNameFromPath(String path) {
    if (path == null || path.isBlank()) {
      return null;
    }
    int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return slash >= 0 ? path.substring(slash + 1) : path;
  }

  static Set<String> extractPackages(String symbolId) {
    LinkedHashSet<String> packages = new LinkedHashSet<>();
    if (symbolId == null || symbolId.isBlank()) {
      return packages;
    }
    int colon = symbolId.indexOf(':');
    if (colon < 0) {
      return packages;
    }
    String qualified = symbolId.substring(colon + 1);
    int hash = qualified.indexOf('#');
    if (hash >= 0) {
      qualified = qualified.substring(0, hash);
    }
    int lastDot = qualified.lastIndexOf('.');
    if (lastDot > 0) {
      packages.add(qualified.substring(0, lastDot));
    }
    return packages;
  }

  static String displaySymbolName(String symbolId) {
    if (symbolId == null) {
      return "";
    }
    int colon = symbolId.indexOf(':');
    return colon >= 0 ? symbolId.substring(colon + 1) : symbolId;
  }

  public static Path resolveWorkspaceRoot() {
    String userDirectory = System.getProperty("user.dir", ".");
    return IndexPathResolver.resolveWorkspaceRoot(Path.of(userDirectory).toAbsolutePath().normalize());
  }

  public String formatForPrompt(GroundingBundle bundle) {
    StringBuilder builder = new StringBuilder();
    appendListSection(builder, "EXISTING FILES (verified)", sortedLimit(bundle.verifiedFilePaths(), 40));
    appendListSection(builder, "KNOWN SYMBOLS (verified)", sortedLimit(bundle.verifiedSymbolNames(), 50));
    if (!bundle.verifiedPackages().isEmpty()) {
      appendListSection(builder, "KNOWN PACKAGES (verified)", sortedLimit(bundle.verifiedPackages(), 20));
    }
    if (!bundle.verifiedConfigPaths().isEmpty()) {
      appendListSection(builder, "CONFIG FILES (verified)", sortedLimit(bundle.verifiedConfigPaths(), 15));
    }
    if (!bundle.unresolvedQueries().isEmpty()) {
      appendListSection(builder, "UNRESOLVED SYMBOLS", bundle.unresolvedQueries());
    }
    return builder.toString().trim();
  }

  private static List<String> sortedLimit(Set<String> values, int limit) {
    return values.stream().sorted().limit(limit).map(v -> "- " + v).toList();
  }

  private static void appendListSection(StringBuilder builder, String title, List<String> lines) {
    if (lines.isEmpty()) {
      return;
    }
    builder.append("--- ").append(title).append(" ---\n");
    for (String line : lines) {
      builder.append(line.startsWith("- ") ? line : "- " + line).append('\n');
    }
    builder.append('\n');
  }
}
