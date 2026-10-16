package com.andromedia.ingestion.references;

import com.andromedia.common.CodeSymbol;
import com.andromedia.common.ProjectSymbolRegistry;
import com.andromedia.common.SymbolEntry;
import com.andromedia.common.SymbolType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaReferenceResolver implements ReferenceResolver {

  private static final Set<String> JAVA_KEYWORDS =
      Set.of(
          "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
          "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
          "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
          "interface", "long", "native", "new", "package", "private", "protected", "public",
          "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
          "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
          "null", "var", "record", "sealed", "permits", "yield");

  private static final Pattern EXTENDS_PATTERN = Pattern.compile("\\bextends\\s+([A-Z][\\w]*)");
  private static final Pattern IMPLEMENTS_PATTERN = Pattern.compile("\\bimplements\\s+([^{;]+)");
  private static final Pattern NEW_INSTANCE = Pattern.compile("\\bnew\\s+([A-Z][\\w]*)\\b");
  private static final Pattern CAST_PATTERN = Pattern.compile("\\(\\s*([A-Z][\\w]*)\\s*\\)");
  private static final Pattern TYPE_BINDING =
      Pattern.compile(
          "\\b([A-Z][\\w]*(?:\\.[A-Z][\\w]*)*(?:<[^>]*>)?(?:\\[\\])?)\\s+(\\w+)\\s*(?:[,)=;]|=)");
  private static final Pattern QUALIFIED_CALL =
      Pattern.compile("\\b([\\w]+)\\.([a-zA-Z_][\\w]*)\\s*\\(");
  private static final Pattern UNQUALIFIED_CALL =
      Pattern.compile("(?<![.\\w])([a-zA-Z_][\\w]*)\\s*\\(");

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

    Map<String, List<String>> importAliases = buildImportAliasMap(imports, packageName, registry);
    Map<String, String> staticImports = buildStaticImportMap(imports, registry);
    Map<String, String> scope = buildScopeMap(chunkContent, localSymbols, packageName);
    String enclosingClass = findEnclosingClassQualifiedName(localSymbols, packageName);
    LinkedHashSet<String> references = new LinkedHashSet<>();

    resolveScopedTypeReferences(chunkContent, importAliases, registry, references, localSymbolIds);
    resolveMethodCalls(
        chunkContent,
        importAliases,
        staticImports,
        scope,
        enclosingClass,
        registry,
        references,
        localSymbolIds);

    return List.copyOf(references);
  }

  private static void resolveScopedTypeReferences(
      String chunkContent,
      Map<String, List<String>> importAliases,
      ProjectSymbolRegistry registry,
      Set<String> references,
      Set<String> localSymbolIds) {
    Matcher extendsMatcher = EXTENDS_PATTERN.matcher(chunkContent);
    while (extendsMatcher.find()) {
      addReferences(
          references,
          resolveTypeName(extendsMatcher.group(1), importAliases, registry),
          localSymbolIds);
    }

    Matcher implementsMatcher = IMPLEMENTS_PATTERN.matcher(chunkContent);
    while (implementsMatcher.find()) {
      for (String token : implementsMatcher.group(1).split(",")) {
        String typeName = extractSimpleTypeName(token.trim());
        if (typeName != null && !typeName.isBlank()) {
          addReferences(references, resolveTypeName(typeName, importAliases, registry), localSymbolIds);
        }
      }
    }

    Matcher newMatcher = NEW_INSTANCE.matcher(chunkContent);
    while (newMatcher.find()) {
      addReferences(
          references,
          resolveTypeName(newMatcher.group(1), importAliases, registry),
          localSymbolIds);
    }

    Matcher castMatcher = CAST_PATTERN.matcher(chunkContent);
    while (castMatcher.find()) {
      addReferences(
          references,
          resolveTypeName(castMatcher.group(1), importAliases, registry),
          localSymbolIds);
    }

    Matcher bindingMatcher = TYPE_BINDING.matcher(chunkContent);
    while (bindingMatcher.find()) {
      String typeName = extractSimpleTypeName(bindingMatcher.group(1));
      if (typeName != null) {
        addReferences(references, resolveTypeName(typeName, importAliases, registry), localSymbolIds);
      }
    }
  }

  private static void resolveMethodCalls(
      String chunkContent,
      Map<String, List<String>> importAliases,
      Map<String, String> staticImports,
      Map<String, String> scope,
      String enclosingClass,
      ProjectSymbolRegistry registry,
      Set<String> references,
      Set<String> localSymbolIds) {
    Matcher qualifiedMatcher = QUALIFIED_CALL.matcher(chunkContent);
    while (qualifiedMatcher.find()) {
      String receiver = qualifiedMatcher.group(1);
      String methodName = qualifiedMatcher.group(2);
      if (JAVA_KEYWORDS.contains(methodName)) {
        continue;
      }
      if (Character.isUpperCase(receiver.charAt(0))) {
        addReferences(
            references,
            resolveMethodOnType(receiver, methodName, importAliases, registry),
            localSymbolIds);
      } else {
        addReferences(
            references,
            resolveInstanceMethod(
                receiver, methodName, scope, importAliases, enclosingClass, registry),
            localSymbolIds);
      }
    }

    Matcher unqualifiedMatcher = UNQUALIFIED_CALL.matcher(chunkContent);
    while (unqualifiedMatcher.find()) {
      String methodName = unqualifiedMatcher.group(1);
      if (JAVA_KEYWORDS.contains(methodName)) {
        continue;
      }
      addReferences(
          references,
          resolveUnqualifiedMethod(methodName, staticImports, enclosingClass, registry),
          localSymbolIds);
    }
  }

  static Map<String, List<String>> buildImportAliasMap(
      List<String> imports, String packageName, ProjectSymbolRegistry registry) {
    Map<String, List<String>> aliases = new HashMap<>();
    if (imports != null) {
      for (String imp : imports) {
        if (isStaticImport(imp)) {
          continue;
        }
        if (imp.endsWith(".*")) {
          String pkg = imp.substring(0, imp.length() - 2);
          for (SymbolEntry entry : registry.allEntries()) {
            if (!entry.symbolId().startsWith("java:")) {
              continue;
            }
            String qualified = entry.symbolId().substring("java:".length());
            if (qualified.startsWith(pkg + ".") && !qualified.contains("#")) {
              aliases.computeIfAbsent(entry.symbolName(), ignored -> new ArrayList<>()).add(entry.symbolId());
            }
          }
          continue;
        }
        int lastDot = imp.lastIndexOf('.');
        String simpleName = lastDot >= 0 ? imp.substring(lastDot + 1) : imp;
        aliases.computeIfAbsent(simpleName, ignored -> new ArrayList<>()).add("java:" + imp);
        for (SymbolEntry entry : registry.findByQualifiedSuffix(imp)) {
          List<String> ids = aliases.computeIfAbsent(simpleName, ignored -> new ArrayList<>());
          if (!ids.contains(entry.symbolId())) {
            ids.add(entry.symbolId());
          }
        }
      }
    }
    if (packageName != null && !packageName.isBlank()) {
      String prefix = "java:" + packageName + ".";
      for (SymbolEntry entry : registry.allEntries()) {
        if (!entry.symbolId().startsWith(prefix) || entry.symbolId().contains("#")) {
          continue;
        }
        if (entry.symbolType() == SymbolType.CLASS
            || entry.symbolType() == SymbolType.INTERFACE
            || entry.symbolType() == SymbolType.ENUM
            || entry.symbolType() == SymbolType.RECORD) {
          aliases.computeIfAbsent(entry.symbolName(), ignored -> new ArrayList<>()).add(entry.symbolId());
        }
      }
    }
    return aliases;
  }

  static Map<String, String> buildStaticImportMap(
      List<String> imports, ProjectSymbolRegistry registry) {
    Map<String, String> staticImports = new LinkedHashMap<>();
    if (imports == null) {
      return staticImports;
    }
    for (String imp : imports) {
      if (!isStaticImport(imp)) {
        continue;
      }
      if (imp.endsWith(".*")) {
        String classFqn = imp.substring(0, imp.length() - 2);
        String typePrefix = "java:" + classFqn + "#";
        for (SymbolEntry entry : registry.allEntries()) {
          if (entry.symbolId().startsWith(typePrefix)
              && (entry.symbolType() == SymbolType.METHOD
                  || entry.symbolType() == SymbolType.FIELD)) {
            staticImports.putIfAbsent(entry.symbolName(), entry.symbolId());
          }
        }
        continue;
      }
      int lastDot = imp.lastIndexOf('.');
      if (lastDot <= 0) {
        continue;
      }
      String member = imp.substring(lastDot + 1);
      String classFqn = imp.substring(0, lastDot);
      staticImports.put(member, "java:" + classFqn + "#" + member);
    }
    return staticImports;
  }

  static Map<String, String> buildScopeMap(
      String chunkContent, List<CodeSymbol> localSymbols, String packageName) {
    Map<String, String> scope = new LinkedHashMap<>();
    for (CodeSymbol symbol : localSymbols) {
      if (symbol.symbolType() == SymbolType.FIELD && symbol.containerName() != null) {
        scope.putIfAbsent(symbol.symbolName(), symbol.containerName());
      }
    }
    Matcher bindingMatcher = TYPE_BINDING.matcher(chunkContent);
    while (bindingMatcher.find()) {
      String typeName = extractSimpleTypeName(bindingMatcher.group(1));
      String name = bindingMatcher.group(2);
      if (typeName != null && !JAVA_KEYWORDS.contains(name)) {
        scope.put(name, typeName);
      }
    }
    return scope;
  }

  static boolean isStaticImport(String imp) {
    if (imp == null || imp.isBlank()) {
      return false;
    }
    if (imp.endsWith(".*")) {
      int classEnd = imp.length() - 2;
      int lastDot = imp.lastIndexOf('.', classEnd - 1);
      if (lastDot < 0) {
        return false;
      }
      String className = imp.substring(lastDot + 1, classEnd);
      return !className.isEmpty() && Character.isUpperCase(className.charAt(0));
    }
    int lastDot = imp.lastIndexOf('.');
    if (lastDot <= 0 || lastDot >= imp.length() - 1) {
      return false;
    }
    String member = imp.substring(lastDot + 1);
    return Character.isLowerCase(member.charAt(0));
  }

  private static List<String> resolveTypeName(
      String simpleName, Map<String, List<String>> importAliases, ProjectSymbolRegistry registry) {
    List<String> fromImports = importAliases.get(simpleName);
    if (fromImports != null && !fromImports.isEmpty()) {
      return distinctTypeIds(fromImports);
    }
    return List.of();
  }

  private static List<String> distinctTypeIds(List<String> candidates) {
    LinkedHashSet<String> typeIds = new LinkedHashSet<>();
    for (String candidate : candidates) {
      if (candidate.contains("#")) {
        continue;
      }
      typeIds.add(candidate);
    }
    if (typeIds.size() == 1) {
      return List.copyOf(typeIds);
    }
    return List.of();
  }

  private static List<String> resolveMethodOnType(
      String typeSimpleName,
      String methodName,
      Map<String, List<String>> importAliases,
      ProjectSymbolRegistry registry) {
    List<String> typeIds = resolveTypeName(typeSimpleName, importAliases, registry);
    if (typeIds.size() != 1) {
      return List.of();
    }
    return resolveMethodOnQualifiedType(typeIds.get(0), methodName, registry);
  }

  private static List<String> resolveInstanceMethod(
      String receiver,
      String methodName,
      Map<String, String> scope,
      Map<String, List<String>> importAliases,
      String enclosingClass,
      ProjectSymbolRegistry registry) {
    String typeSimpleName;
    if ("this".equals(receiver)) {
      if (enclosingClass == null) {
        return List.of();
      }
      return resolveMethodOnQualifiedType("java:" + enclosingClass, methodName, registry);
    }
    typeSimpleName = scope.get(receiver);
    if (typeSimpleName == null) {
      return List.of();
    }
    List<String> typeIds = resolveTypeName(typeSimpleName, importAliases, registry);
    if (typeIds.size() != 1) {
      return List.of();
    }
    List<String> matches = resolveMethodOnQualifiedType(typeIds.get(0), methodName, registry);
    if (!matches.isEmpty()) {
      return matches;
    }
    return resolveFieldOnQualifiedType(typeIds.get(0), methodName, registry);
  }

  private static List<String> resolveUnqualifiedMethod(
      String methodName,
      Map<String, String> staticImports,
      String enclosingClass,
      ProjectSymbolRegistry registry) {
    List<String> matches = new ArrayList<>();
    String staticSymbol = staticImports.get(methodName);
    if (staticSymbol != null) {
      matches.add(staticSymbol);
    }
    if (enclosingClass != null) {
      matches.addAll(resolveMethodOnQualifiedType("java:" + enclosingClass, methodName, registry));
    }
    return matches;
  }

  private static List<String> resolveMethodOnQualifiedType(
      String typeId, String methodName, ProjectSymbolRegistry registry) {
    String typeQualified = typeId.startsWith("java:") ? typeId.substring("java:".length()) : typeId;
    String methodId = "java:" + typeQualified + "#" + methodName;
    if (registry.findBySymbolId(methodId).filter(e -> e.symbolType() == SymbolType.METHOD).isPresent()) {
      return List.of(methodId);
    }
    return List.of();
  }

  private static List<String> resolveFieldOnQualifiedType(
      String typeId, String fieldName, ProjectSymbolRegistry registry) {
    String typeQualified = typeId.startsWith("java:") ? typeId.substring("java:".length()) : typeId;
    String fieldId = "java:" + typeQualified + "#" + fieldName;
    if (registry.findBySymbolId(fieldId).filter(e -> e.symbolType() == SymbolType.FIELD).isPresent()) {
      return List.of(fieldId);
    }
    return List.of();
  }

  private static String findEnclosingClassQualifiedName(
      List<CodeSymbol> localSymbols, String packageName) {
    for (CodeSymbol symbol : localSymbols) {
      if (symbol.symbolType() == SymbolType.CLASS
          || symbol.symbolType() == SymbolType.INTERFACE
          || symbol.symbolType() == SymbolType.ENUM
          || symbol.symbolType() == SymbolType.RECORD) {
        return symbol.symbolId().startsWith("java:")
            ? symbol.symbolId().substring("java:".length())
            : symbol.symbolId();
      }
    }
    for (CodeSymbol symbol : localSymbols) {
      if (symbol.containerName() != null && !symbol.containerName().isBlank()) {
        if (packageName == null || packageName.isBlank()) {
          return symbol.containerName();
        }
        return packageName + "." + symbol.containerName();
      }
    }
    return null;
  }

  private static String extractSimpleTypeName(String rawType) {
    if (rawType == null || rawType.isBlank()) {
      return null;
    }
    String trimmed = rawType.trim();
    int genericStart = trimmed.indexOf('<');
    if (genericStart >= 0) {
      trimmed = trimmed.substring(0, genericStart);
    }
    int arrayStart = trimmed.indexOf('[');
    if (arrayStart >= 0) {
      trimmed = trimmed.substring(0, arrayStart);
    }
    int lastDot = trimmed.lastIndexOf('.');
    String simple = lastDot >= 0 ? trimmed.substring(lastDot + 1) : trimmed;
    if (simple.isBlank() || !Character.isUpperCase(simple.charAt(0))) {
      return null;
    }
    return simple;
  }

  private static void addReferences(
      Set<String> target, List<String> candidateIds, Set<String> localSymbolIds) {
    for (String symbolId : candidateIds) {
      if (symbolId == null || symbolId.isBlank() || localSymbolIds.contains(symbolId)) {
        continue;
      }
      target.add(symbolId);
    }
  }
}
