package com.andromedia.ingestion.metadata;

import com.andromedia.common.CodeSymbol;
import com.andromedia.common.FileStructuralMetadata;
import com.andromedia.common.StructuralMetadata;
import com.andromedia.common.SymbolIdGenerator;
import com.andromedia.common.SymbolType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import org.treesitter.TSNode;
import org.treesitter.TSTree;

/**
 * Tree-sitter backed extractor for TypeScript, TSX and JavaScript.
 *
 * <p>Replaces the previous behaviour where these languages resolved to a language name but had no
 * registered extractor, so every {@code .ts}/{@code .tsx}/{@code .js}/{@code .jsx} file fell
 * through to {@link FileStructuralMetadata#empty()} — no symbols, and therefore no usages or
 * impact graph.
 *
 * <p>Symbols are emitted with the file's module name as their container, matching the
 * {@code python:} convention: {@code typescript:module.Class} and
 * {@code typescript:module.Class#method}. Module-level functions use
 * {@code typescript:module#function} to match the {@code java:Class#method} style used for
 * languages where the file is not itself the namespace.
 *
 * <p>If the grammar's native library cannot load, {@link TreeSitterSupport#parseOrNull} returns
 * null and this extractor degrades to imports-free, symbol-free file metadata rather than failing
 * the indexing run.
 */
public final class TypeScriptMetadataExtractor implements LanguageMetadataExtractor {

  private static final Set<String> CLASS_LIKE =
      Set.of("class_declaration", "abstract_class_declaration");

  private static final Set<String> TYPE_LIKE =
      Set.of(
          "class_declaration",
          "abstract_class_declaration",
          "interface_declaration",
          "enum_declaration",
          "type_alias_declaration");

  private static final Set<String> FUNCTION_LIKE =
      Set.of("function_declaration", "generator_function_declaration");

  private static final Set<String> METHOD_LIKE = Set.of("method_definition", "method_signature");

  public static String moduleNameFromPath(Path path) {
    String fileName = path.getFileName().toString();
    int dotIndex = fileName.lastIndexOf('.');
    return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
  }

  @Override
  public FileStructuralMetadata extractFile(Path path, String content, String language) {
    String absolutePath = path.toAbsolutePath().normalize().toString();
    String moduleName = moduleNameFromPath(path);
    TSTree tree = TreeSitterSupport.parseOrNull(content, language);
    if (tree == null) {
      return FileStructuralMetadata.empty();
    }
    TSNode root = tree.getRootNode();
    TreeSitterSupport.SourceText src = new TreeSitterSupport.SourceText(content);
    List<String> imports = extractImports(root, src);
    List<CodeSymbol> symbols = extractSymbols(root, src, absolutePath, language, moduleName);
    return new FileStructuralMetadata(imports, List.of(), symbols);
  }

  @Override
  public StructuralMetadata forChunkSpan(
      FileStructuralMetadata file, int startLine, int endLine) {
    return StructuralMetadata.forChunk(file, startLine, endLine);
  }

  private static List<String> extractImports(TSNode root, TreeSitterSupport.SourceText source) {
    Set<String> imports = new LinkedHashSet<>();
    for (TSNode node : TreeSitterSupport.collect(root, Set.of("import_statement"))) {
      TSNode sourceNode = node.getChildByFieldName("source");
      if (sourceNode != null && !sourceNode.isNull()) {
        imports.add(stripQuotes(source.textOf(sourceNode)));
      }
    }
    // CommonJS: const x = require('foo')
    for (TSNode node : TreeSitterSupport.collect(root, Set.of("call_expression"))) {
      TSNode fn = node.getChildByFieldName("function");
      if (fn == null || fn.isNull() || !"require".equals(source.textOf(fn))) {
        continue;
      }
      TSNode args = node.getChildByFieldName("arguments");
      if (args == null || args.isNull() || args.getNamedChildCount() == 0) {
        continue;
      }
      // Use the first *named* child: the raw child list includes the literal "(" token.
      String raw = stripQuotes(source.textOf(args.getNamedChild(0)));
      if (!raw.isBlank()) {
        imports.add(raw);
      }
    }
    return List.copyOf(imports);
  }

  private static List<CodeSymbol> extractSymbols(
      TSNode root,
      TreeSitterSupport.SourceText source,
      String absolutePath,
      String language,
      String moduleName) {
    List<CodeSymbol> symbols = new ArrayList<>();
    symbols.add(
        new CodeSymbol(
            SymbolIdGenerator.module(language, moduleName),
            moduleName,
            SymbolType.MODULE,
            absolutePath,
            1,
            TreeSitterSupport.endLine(root),
            language,
            null));

    // Type declarations, and the byte ranges of class-like ones so methods can be attributed.
    // NOTE: TSNode has no equals/hashCode, so a Set<TSNode> would compare by identity while
    // getChild(i) returns fresh wrapper instances — membership would silently never match.
    // Track byte spans instead.
    List<int[]> classLikeSpans = new ArrayList<>();
    Map<Integer, String> classLikeNames = new LinkedHashMap<>();
    for (TSNode node : TreeSitterSupport.collect(root, TYPE_LIKE)) {
      String name = TreeSitterSupport.declaredName(node, source);
      if (name == null || name.isBlank()) {
        continue;
      }
      SymbolType type =
          switch (node.getType()) {
            case "interface_declaration" -> SymbolType.INTERFACE;
            case "enum_declaration" -> SymbolType.ENUM;
            case "type_alias_declaration" -> SymbolType.RECORD;
            default -> SymbolType.CLASS;
          };
      symbols.add(
          new CodeSymbol(
              SymbolIdGenerator.type(language, moduleName, name),
              name,
              type,
              absolutePath,
              TreeSitterSupport.startLine(node),
              TreeSitterSupport.endLine(node),
              language,
              moduleName));
      if (CLASS_LIKE.contains(node.getType())) {
        classLikeSpans.add(new int[] {node.getStartByte(), node.getEndByte()});
        classLikeNames.put(node.getStartByte(), name);
      }
    }

    // Methods: attributed to the nearest enclosing class-like declaration, else module-level.
    for (TSNode method : TreeSitterSupport.collect(root, METHOD_LIKE)) {
      String name = TreeSitterSupport.declaredName(method, source);
      if (name == null || name.isBlank()) {
        continue;
      }
      String container = enclosingTypeName(method, classLikeSpans, classLikeNames);
      symbols.add(
          new CodeSymbol(
              SymbolIdGenerator.method(language, moduleName, container, name),
              name,
              SymbolType.METHOD,
              absolutePath,
              TreeSitterSupport.startLine(method),
              TreeSitterSupport.endLine(method),
              language,
              container == null ? moduleName : container));
    }

    // Module-level functions.
    for (TSNode fn : TreeSitterSupport.collect(root, FUNCTION_LIKE)) {
      String name = TreeSitterSupport.declaredName(fn, source);
      if (name == null || name.isBlank()) {
        continue;
      }
      symbols.add(
          new CodeSymbol(
              SymbolIdGenerator.function(language, moduleName, name),
              name,
              SymbolType.FUNCTION,
              absolutePath,
              TreeSitterSupport.startLine(fn),
              TreeSitterSupport.endLine(fn),
              language,
              moduleName));
    }

    // Arrow functions and function expressions assigned to a const/let/var.
    for (TSNode declarator : TreeSitterSupport.collect(root, Set.of("variable_declarator"))) {
      TSNode value = declarator.getChildByFieldName("value");
      if (value == null || value.isNull()) {
        continue;
      }
      String valueType = value.getType();
      if (!"arrow_function".equals(valueType) && !"function_expression".equals(valueType)) {
        continue;
      }
      String name = TreeSitterSupport.declaredName(declarator, source);
      if (name == null || name.isBlank()) {
        continue;
      }
      symbols.add(
          new CodeSymbol(
              SymbolIdGenerator.function(language, moduleName, name),
              name,
              SymbolType.FUNCTION,
              absolutePath,
              TreeSitterSupport.startLine(declarator),
              TreeSitterSupport.endLine(declarator),
              language,
              moduleName));
    }

    return List.copyOf(symbols);
  }

  /**
   * Name of the innermost class-like ancestor of {@code node}, or null if none.
   *
   * <p>Matches on byte ranges rather than node identity — see the note in {@code extractSymbols}.
   */
  private static String enclosingTypeName(
      TSNode node, List<int[]> classLikeSpans, Map<Integer, String> classLikeNames) {
    String best = null;
    int bestSpan = Integer.MAX_VALUE;
    int targetStart = node.getStartByte();
    for (int[] span : classLikeSpans) {
      if (span[0] <= targetStart && targetStart < span[1]) {
        int size = span[1] - span[0];
        if (size < bestSpan) {
          bestSpan = size;
          best = classLikeNames.get(span[0]);
        }
      }
    }
    return best;
  }

  private static String stripQuotes(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.length() >= 2) {
      char first = trimmed.charAt(0);
      char last = trimmed.charAt(trimmed.length() - 1);
      if ((first == '\'' || first == '"' || first == '`') && first == last) {
        return trimmed.substring(1, trimmed.length() - 1);
      }
    }
    return trimmed;
  }
}
