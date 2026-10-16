package com.andromedia.search;

import com.andromedia.common.IndexConstants;
import com.andromedia.common.SymbolEntry;
import com.andromedia.common.SymbolType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

final class LuceneSymbolIndex {

  private final Map<String, SymbolEntry> bySymbolId = new LinkedHashMap<>();
  private final Map<String, List<String>> bySimpleName = new LinkedHashMap<>();
  private final Map<String, List<String>> byQualifiedSuffix = new LinkedHashMap<>();

  static LuceneSymbolIndex load(java.nio.file.Path indexDirectoryPath) {
    LuceneSymbolIndex index = new LuceneSymbolIndex();
    try (Directory directory = FSDirectory.open(indexDirectoryPath);
        DirectoryReader reader = DirectoryReader.open(directory)) {
      for (int docId = 0; docId < reader.maxDoc(); docId++) {
        var document = reader.storedFields().document(docId);
        String[] symbolIds = document.getValues(IndexConstants.FIELD_SYMBOL_IDS);
        String[] symbolNames = document.getValues(IndexConstants.FIELD_SYMBOL_NAMES);
        String[] symbolTypes = document.getValues(IndexConstants.FIELD_SYMBOL_TYPES);
        if (symbolIds == null || symbolIds.length == 0) {
          continue;
        }
        String path = document.get(IndexConstants.FIELD_PATH);
        String language = document.get(IndexConstants.FIELD_LANGUAGE);
        int startLine = parseLine(document.get(IndexConstants.FIELD_START_LINE));
        int endLine = parseLine(document.get(IndexConstants.FIELD_END_LINE));
        for (int i = 0; i < symbolIds.length; i++) {
          String symbolId = symbolIds[i];
          String symbolName = symbolNames != null && i < symbolNames.length ? symbolNames[i] : "";
          SymbolType symbolType =
              symbolTypes != null && i < symbolTypes.length
                  ? SymbolType.valueOf(symbolTypes[i])
                  : SymbolType.CLASS;
          SymbolEntry entry =
              new SymbolEntry(symbolId, symbolName, symbolType, path, startLine, endLine, language);
          index.register(entry);
        }
      }
    } catch (IOException ex) {
      throw new SearchException("Failed to load symbol index from: " + indexDirectoryPath, ex);
    }
    return index;
  }

  private void register(SymbolEntry entry) {
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
      byQualifiedSuffix
          .computeIfAbsent(qualified.substring(0, hash), ignored -> new ArrayList<>())
          .add(symbolId);
    }
  }

  List<SymbolEntry> findBySymbolId(String symbolId) {
    SymbolEntry entry = bySymbolId.get(symbolId);
    return entry == null ? List.of() : List.of(entry);
  }

  List<SymbolEntry> findBySimpleName(String simpleName) {
    return resolveEntries(bySimpleName.get(simpleName));
  }

  List<SymbolEntry> findByQualifiedSuffix(String qualifiedSuffix) {
    return resolveEntries(byQualifiedSuffix.get(qualifiedSuffix));
  }

  List<SymbolEntry> findJavaMethod(String classSimpleName, String methodName) {
    List<SymbolEntry> matches = new ArrayList<>();
    String suffix = "#" + methodName;
    for (SymbolEntry entry : bySymbolId.values()) {
      if (!entry.symbolId().startsWith("java:") || !entry.symbolId().endsWith(suffix)) {
        continue;
      }
      String qualified = entry.symbolId().substring("java:".length(), entry.symbolId().length() - suffix.length());
      if (qualified.endsWith("." + classSimpleName) || qualified.equals(classSimpleName)) {
        matches.add(entry);
      }
    }
    return matches;
  }

  List<SymbolEntry> findMethodsForClass(String classSimpleName) {
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
    return matches;
  }

  private List<SymbolEntry> resolveEntries(List<String> symbolIds) {
    if (symbolIds == null || symbolIds.isEmpty()) {
      return List.of();
    }
    List<SymbolEntry> entries = new ArrayList<>();
    for (String symbolId : symbolIds) {
      SymbolEntry entry = bySymbolId.get(symbolId);
      if (entry != null) {
        entries.add(entry);
      }
    }
    return entries;
  }

  private static int parseLine(String value) {
    if (value == null || value.isBlank()) {
      return 0;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ex) {
      return 0;
    }
  }
}
