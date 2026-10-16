package com.andromedia.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ProjectSymbolRegistry {

  private final Map<String, SymbolEntry> bySymbolId = new LinkedHashMap<>();
  private final Map<String, List<String>> bySimpleName = new LinkedHashMap<>();
  private final Map<String, List<String>> byQualifiedSuffix = new LinkedHashMap<>();

  public void register(CodeSymbol symbol) {
    if (symbol == null || symbol.symbolId() == null || symbol.symbolId().isBlank()) {
      return;
    }
    SymbolEntry entry = SymbolEntry.from(symbol);
    bySymbolId.putIfAbsent(entry.symbolId(), entry);
    bySimpleName.computeIfAbsent(entry.symbolName(), ignored -> new ArrayList<>()).add(entry.symbolId());
    registerQualifiedSuffix(entry.symbolId());
  }

  private void registerQualifiedSuffix(String symbolId) {
    int colon = symbolId.indexOf(':');
    if (colon < 0 || colon >= symbolId.length() - 1) {
      return;
    }
    String qualified = symbolId.substring(colon + 1);
    byQualifiedSuffix.computeIfAbsent(qualified, ignored -> new ArrayList<>()).add(symbolId);
    int hash = qualified.indexOf('#');
    if (hash > 0) {
      String typeQualified = qualified.substring(0, hash);
      byQualifiedSuffix.computeIfAbsent(typeQualified, ignored -> new ArrayList<>()).add(symbolId);
    }
    int lastDot = qualified.lastIndexOf('.');
    if (lastDot >= 0 && lastDot < qualified.length() - 1) {
      String suffix = qualified.substring(lastDot + 1);
      if (!suffix.contains("#")) {
        byQualifiedSuffix.computeIfAbsent(suffix, ignored -> new ArrayList<>()).add(symbolId);
      }
    }
  }

  public Optional<SymbolEntry> findBySymbolId(String symbolId) {
    return Optional.ofNullable(bySymbolId.get(symbolId));
  }

  public List<SymbolEntry> findBySimpleName(String simpleName) {
    return resolveEntries(bySimpleName.get(simpleName));
  }

  public List<SymbolEntry> findByQualifiedSuffix(String qualifiedSuffix) {
    return resolveEntries(byQualifiedSuffix.get(qualifiedSuffix));
  }

  public List<SymbolEntry> findJavaMethod(String classSimpleName, String methodName) {
    if (classSimpleName == null || methodName == null) {
      return List.of();
    }
    String suffix = "#" + methodName;
    List<SymbolEntry> matches = new ArrayList<>();
    for (SymbolEntry entry : bySymbolId.values()) {
      if (!entry.symbolId().startsWith("java:")) {
        continue;
      }
      if (!entry.symbolId().endsWith(suffix)) {
        continue;
      }
      String qualified = entry.symbolId().substring("java:".length());
      int hashIndex = qualified.indexOf('#');
      if (hashIndex <= 0) {
        continue;
      }
      String typeQualified = qualified.substring(0, hashIndex);
      if (typeQualified.endsWith("." + classSimpleName) || typeQualified.equals(classSimpleName)) {
        matches.add(entry);
      }
    }
    return List.copyOf(matches);
  }

  public List<SymbolEntry> findMethodsForClass(String classSimpleName) {
    if (classSimpleName == null || classSimpleName.isBlank()) {
      return List.of();
    }
    List<SymbolEntry> matches = new ArrayList<>();
    for (SymbolEntry entry : bySymbolId.values()) {
      if (!entry.symbolId().startsWith("java:") || !entry.symbolId().contains("#")) {
        continue;
      }
      String qualified = entry.symbolId().substring("java:".length());
      int hashIndex = qualified.indexOf('#');
      if (hashIndex <= 0) {
        continue;
      }
      String typeQualified = qualified.substring(0, hashIndex);
      if (typeQualified.endsWith("." + classSimpleName) || typeQualified.equals(classSimpleName)) {
        matches.add(entry);
      }
    }
    return List.copyOf(matches);
  }

  public List<SymbolEntry> allEntries() {
    return List.copyOf(bySymbolId.values());
  }

  public Set<String> symbolIdsForSimpleName(String simpleName) {
    List<String> ids = bySimpleName.get(simpleName);
    if (ids == null || ids.isEmpty()) {
      return Set.of();
    }
    return Set.copyOf(ids);
  }

  private List<SymbolEntry> resolveEntries(List<String> symbolIds) {
    if (symbolIds == null || symbolIds.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<SymbolEntry> entries = new LinkedHashSet<>();
    for (String symbolId : symbolIds) {
      SymbolEntry entry = bySymbolId.get(symbolId);
      if (entry != null) {
        entries.add(entry);
      }
    }
    return List.copyOf(entries);
  }
}
