package com.andromedia.ingestion.references;

import com.andromedia.common.CodeSymbol;
import com.andromedia.common.ProjectSymbolRegistry;
import com.andromedia.common.SymbolEntry;
import com.andromedia.common.SymbolType;
import com.andromedia.ingestion.metadata.PythonMetadataExtractor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PythonReferenceResolver implements ReferenceResolver {

  private static final Pattern WORD = Pattern.compile("\\b([a-zA-Z_][\\w]*)\\b");

  @Override
  public List<String> resolveReferences(
      String chunkContent,
      int startLine,
      int endLine,
      List<String> imports,
      String packageName,
      List<CodeSymbol> localSymbols,
      ProjectSymbolRegistry registry) {
    if (chunkContent == null || chunkContent.isBlank() || registry == null) {
      return List.of();
    }

    Set<String> localSymbolIds = new HashSet<>();
    for (CodeSymbol symbol : localSymbols) {
      localSymbolIds.add(symbol.symbolId());
    }

    String moduleName = moduleNameFromPackage(packageName);
    Map<String, List<String>> aliases = buildImportAliasMap(imports, moduleName, registry);
    LinkedHashSet<String> references = new LinkedHashSet<>();

    Matcher matcher = WORD.matcher(chunkContent);
    while (matcher.find()) {
      String token = matcher.group(1);
      if (isPythonKeyword(token)) {
        continue;
      }
      List<String> resolved = aliases.get(token);
      if (resolved == null) {
        resolved = registry.findBySimpleName(token).stream().map(SymbolEntry::symbolId).toList();
      }
      for (String symbolId : resolved) {
        if (!localSymbolIds.contains(symbolId)) {
          references.add(symbolId);
        }
      }
    }
    return List.copyOf(references);
  }

  static Map<String, List<String>> buildImportAliasMap(
      List<String> imports, String moduleName, ProjectSymbolRegistry registry) {
    Map<String, List<String>> aliases = new HashMap<>();
    if (moduleName != null && !moduleName.isBlank()) {
      String prefix = "python:" + moduleName + ".";
      for (SymbolEntry entry : registry.allEntries()) {
        if (entry.symbolId().startsWith(prefix)) {
          aliases.computeIfAbsent(entry.symbolName(), ignored -> new ArrayList<>()).add(entry.symbolId());
        }
      }
    }
    if (imports == null) {
      return aliases;
    }
    for (String imp : imports) {
      if (imp.startsWith("import ")) {
        String remainder = imp.substring("import ".length()).trim();
        String[] parts = remainder.split("\\s+as\\s+");
        String module = parts[0].trim();
        String alias = parts.length > 1 ? parts[1].trim() : module.substring(module.lastIndexOf('.') + 1);
        aliases.computeIfAbsent(alias, ignored -> new ArrayList<>()).add("python:" + module);
        for (SymbolEntry entry : registry.findByQualifiedSuffix(module)) {
          aliases.computeIfAbsent(entry.symbolName(), ignored -> new ArrayList<>()).add(entry.symbolId());
        }
      } else if (imp.startsWith("from ")) {
        int importIndex = imp.indexOf(" import ");
        if (importIndex < 0) {
          continue;
        }
        String module = imp.substring("from ".length(), importIndex).trim();
        String names = imp.substring(importIndex + " import ".length()).trim();
        for (String namePart : names.split(",")) {
          String trimmed = namePart.trim();
          if (trimmed.equals("*")) {
            for (SymbolEntry entry : registry.allEntries()) {
              if (entry.symbolId().startsWith("python:" + module + ".")) {
                aliases.computeIfAbsent(entry.symbolName(), ignored -> new ArrayList<>()).add(entry.symbolId());
              }
            }
            continue;
          }
          String[] aliasParts = trimmed.split("\\s+as\\s+");
          String symbolName = aliasParts[0].trim();
          String alias = aliasParts.length > 1 ? aliasParts[1].trim() : symbolName;
          aliases.computeIfAbsent(alias, ignored -> new ArrayList<>()).add("python:" + module + "." + symbolName);
          for (SymbolEntry entry : registry.findByQualifiedSuffix(module + "." + symbolName)) {
            aliases.computeIfAbsent(alias, ignored -> new ArrayList<>()).add(entry.symbolId());
          }
        }
      }
    }
    return aliases;
  }

  private static String moduleNameFromPackage(String packageName) {
    if (packageName == null || packageName.isBlank()) {
      return "";
    }
    return packageName;
  }

  static String moduleNameFromPath(Path path) {
    return PythonMetadataExtractor.moduleNameFromPath(path);
  }

  private static boolean isPythonKeyword(String token) {
    return Set.of(
            "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
            "continue", "def", "del", "elif", "else", "except", "finally", "for", "from",
            "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass",
            "raise", "return", "try", "while", "with", "yield")
        .contains(token);
  }
}
