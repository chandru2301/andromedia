package com.andromedia.search;

import com.andromedia.common.SymbolEntry;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

public final class SymbolQueryParser {

  private SymbolQueryParser() {}

  public static Optional<ClassMethodQuery> parseClassMethodQuery(String query) {
    if (query == null || query.isBlank()) {
      return Optional.empty();
    }
    String trimmed = query.trim();
    if (trimmed.startsWith("java:") || trimmed.startsWith("python:")) {
      int hashIndex = trimmed.indexOf('#');
      if (hashIndex <= 0) {
        return Optional.empty();
      }
      String qualified = trimmed.substring(trimmed.indexOf(':') + 1, hashIndex);
      int lastDot = qualified.lastIndexOf('.');
      String className = lastDot >= 0 ? qualified.substring(lastDot + 1) : qualified;
      String methodName = trimmed.substring(hashIndex + 1);
      if (className.isBlank() || methodName.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(new ClassMethodQuery(className, methodName));
    }
    int hashIndex = trimmed.indexOf('#');
    if (hashIndex <= 0 || hashIndex >= trimmed.length() - 1) {
      return Optional.empty();
    }
    String className = trimmed.substring(0, hashIndex);
    String methodName = trimmed.substring(hashIndex + 1);
    if (className.isBlank() || methodName.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new ClassMethodQuery(className, methodName));
  }

  public static List<String> resolveSymbolIdsFromIndex(String query, LuceneSymbolIndex symbolIndex) {
    if (query == null || query.isBlank()) {
      return List.of();
    }
    String trimmed = query.trim();
    if (trimmed.startsWith("java:") || trimmed.startsWith("python:")) {
      return symbolIndex.findBySymbolId(trimmed).stream().map(SymbolEntry::symbolId).toList();
    }
    Optional<ClassMethodQuery> classMethodQuery = parseClassMethodQuery(trimmed);
    if (classMethodQuery.isPresent()) {
      ClassMethodQuery parsed = classMethodQuery.get();
      return symbolIndex.findJavaMethod(parsed.className(), parsed.methodName()).stream()
          .map(SymbolEntry::symbolId)
          .toList();
    }
    LinkedHashSet<String> symbolIds = new LinkedHashSet<>();
    symbolIds.addAll(symbolIndex.findBySimpleName(trimmed).stream().map(SymbolEntry::symbolId).toList());
    symbolIds.addAll(symbolIndex.findByQualifiedSuffix(trimmed).stream().map(SymbolEntry::symbolId).toList());
    return List.copyOf(symbolIds);
  }

  public static List<MethodHint> methodHintsForMissedClassMethodQuery(
      String query, LuceneSymbolIndex symbolIndex) {
    return parseClassMethodQuery(query)
        .map(
            parsed ->
                symbolIndex.findMethodsForClass(parsed.className()).stream()
                    .map(entry -> new MethodHint(entry.symbolName(), entry.symbolId()))
                    .sorted(Comparator.comparing(MethodHint::symbolName))
                    .distinct()
                    .toList())
        .orElse(List.of());
  }

  public static String displayName(String query) {
    if (query == null || query.isBlank()) {
      return "";
    }
    return query.trim();
  }
}
