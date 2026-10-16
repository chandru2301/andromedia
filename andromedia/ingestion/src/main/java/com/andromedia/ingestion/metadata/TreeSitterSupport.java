package com.andromedia.ingestion.metadata;

import java.util.ArrayList;
import java.util.List;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterJavascript;
import org.treesitter.TreeSitterTypescript;

/**
 * Shared helpers for tree-sitter backed extractors.
 *
 * <p>The tree-sitter JVM binding loads native libraries from {@code ${user.home}/.tree-sitter},
 * extracting them from the jar on first use. That means HOME must be writable — true for normal
 * dev runs and for the Docker image (which sets {@code HOME=/home/andromedia}), but not for a
 * sandboxed read-only home. {@link #parseOrNull} therefore degrades to a null tree instead of
 * throwing, so indexing keeps working (at file-level granularity) if the grammar can't load.
 *
 * <p>{@link TSParser} is not thread-safe, so one is created per parse call. Parser creation is
 * cheap relative to parsing; the grammars themselves are cached in static finals.
 */
final class TreeSitterSupport {

  private static final TSLanguage TYPESCRIPT = loadGrammar("typescript", TreeSitterTypescript::new);
  private static final TSLanguage TSX = loadGrammar("tsx", TreeSitterTypescript::new);
  private static final TSLanguage JAVASCRIPT = loadGrammar("javascript", TreeSitterJavascript::new);

  private TreeSitterSupport() {}

  /** True when the requested grammar loaded and native libs are usable. */
  static boolean available(String language) {
    return languageFor(language) != null;
  }

  static TSTree parseOrNull(String source, String language) {
    TSLanguage grammar = languageFor(language);
    if (grammar == null) {
      return null;
    }
    try {
      TSParser parser = new TSParser();
      parser.setLanguage(grammar);
      return parser.parseString(null, source);
    } catch (RuntimeException | LinkageError ex) {
      // Missing/unloadable native library, or a parse failure — callers fall back to
      // file-level metadata rather than failing the whole indexing run.
      return null;
    }
  }

  private static TSLanguage languageFor(String language) {
    if (language == null) {
      return null;
    }
    return switch (language.toLowerCase()) {
      case "typescript" -> TYPESCRIPT;
      case "tsx" -> TSX;
      case "javascript" -> JAVASCRIPT;
      default -> null;
    };
  }

  private static TSLanguage loadGrammar(String name, java.util.function.Supplier<TSLanguage> factory) {
    try {
      return factory.get();
    } catch (RuntimeException | LinkageError ex) {
      System.getLogger(TreeSitterSupport.class.getName())
          .log(
              System.Logger.Level.WARNING,
              "tree-sitter grammar unavailable for {0}: {1}",
              name,
              ex.toString());
      return null;
    }
  }

  /** Depth-first walk collecting every node whose type is in {@code types}. */
  static List<TSNode> collect(TSNode root, java.util.Set<String> types) {
    List<TSNode> found = new ArrayList<>();
    walk(root, types, found);
    return found;
  }

  private static void walk(TSNode node, java.util.Set<String> types, List<TSNode> out) {
    if (types.contains(node.getType())) {
      out.add(node);
    }
    int childCount = node.getChildCount();
    for (int i = 0; i < childCount; i++) {
      walk(node.getChild(i), types, out);
    }
  }

  /** 1-based start line of a node. */
  static int startLine(TSNode node) {
    return node.getStartPoint().getRow() + 1;
  }

  /** 1-based end line of a node (tree-sitter end points are exclusive). */
  static int endLine(TSNode node) {
    return node.getEndPoint().getRow() + 1;
  }

  /**
   * The declared identifier for a declaration node, or null.
   *
   * <p>Looks at the {@code name} field first (the common case for classes, functions and
   * interfaces), then falls back to scanning direct children for an identifier/type_identifier —
   * which covers forms like {@code export default class} and variable declarators.
   */
  static String declaredName(TSNode node, TreeSitterSupport.SourceText source) {
    TSNode name = node.getChildByFieldName("name");
    if (name != null && !name.isNull()) {
      String text = source.textOf(name);
      if (!text.isBlank()) {
        return text;
      }
    }
    int childCount = node.getChildCount();
    for (int i = 0; i < childCount; i++) {
      TSNode child = node.getChild(i);
      String type = child.getType();
      if ("identifier".equals(type)
          || "type_identifier".equals(type)
          || "property_identifier".equals(type)) {
        String text = source.textOf(child);
        if (!text.isBlank()) {
          return text;
        }
      }
    }
    return null;
  }

  /**
   * Extracts the source text of a node.
   *
   * <p>Important: {@code getStartByte()}/{@code getEndByte()} are offsets into the <b>UTF-8</b>
   * encoding of the source, not Java {@code String} indices (which are UTF-16). Slicing a String
   * with them silently corrupts any file containing non-ASCII text — emoji, accented characters,
   * smart quotes in comments. So decode from the UTF-8 bytes instead.
   *
   * <p>There is deliberately no {@code (TSNode, String)} overload: passing a raw String is exactly
   * the mistake that produced mangled symbol names, so callers must go through {@link SourceText}.
   */
  private static String textOf(TSNode node, byte[] utf8) {
    int start = node.getStartByte();
    int end = node.getEndByte();
    if (start < 0 || end > utf8.length || start >= end) {
      return "";
    }
    return new String(utf8, start, end - start, java.nio.charset.StandardCharsets.UTF_8);
  }

  /**
   * Caches the UTF-8 encoding of a source file so repeated node slicing stays O(1) per lookup
   * instead of re-encoding the whole file for every node.
   */
  static final class SourceText {
    private final byte[] utf8;

    SourceText(String source) {
      this.utf8 = (source == null ? "" : source).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    String textOf(TSNode node) {
      return TreeSitterSupport.textOf(node, utf8);
    }
  }

  /** True when the node has a direct child of the given type (e.g. {@code export}). */
  static boolean hasChildOfType(TSNode node, String type) {
    int childCount = node.getChildCount();
    for (int i = 0; i < childCount; i++) {
      if (type.equals(node.getChild(i).getType())) {
        return true;
      }
    }
    return false;
  }
}
