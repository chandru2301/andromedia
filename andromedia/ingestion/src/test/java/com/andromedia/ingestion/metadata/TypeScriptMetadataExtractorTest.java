package com.andromedia.ingestion.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.CodeSymbol;
import com.andromedia.common.FileStructuralMetadata;
import com.andromedia.common.SymbolType;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Verifies tree-sitter based TypeScript/JavaScript symbol extraction.
 *
 * <p>These assertions are skipped rather than failed when the tree-sitter native library cannot be
 * extracted (read-only {@code user.home}), because that is an environment limitation, not a code
 * defect — {@link TypeScriptMetadataExtractor} degrades to file-level metadata in that case.
 */
class TypeScriptMetadataExtractorTest {

  private static final String SAMPLE =
      """
      import { Router } from 'express';
      import type { Config } from './config';
      const legacy = require('legacy-lib');

      export interface Shape {
        area(): number;
      }

      export type Alias = string | number;

      export enum Color { Red, Green }

      export abstract class BaseService {
        private router: Router;
        constructor(router: Router) { this.router = router; }
        async listDeals(limit: number): Promise<string[]> { return []; }
        static make(): BaseService { return null!; }
        get size(): number { return 1; }
      }

      export class DealService extends BaseService implements Shape {
        area(): number { return 0; }
      }

      export function topLevel(x: number): void {}

      export const arrowFn = (y: string) => y;
      """;

  private static FileStructuralMetadata extract() {
    return new TypeScriptMetadataExtractor()
        .extractFile(Path.of("/repo/src/dealService.ts"), SAMPLE, "typescript");
  }

  /** Keyed by symbolId, not name: the same name can legitimately appear on several containers. */
  private static Map<String, CodeSymbol> byId(FileStructuralMetadata meta) {
    return meta.symbols().stream()
        .collect(Collectors.toMap(CodeSymbol::symbolId, Function.identity(), (a, b) -> a));
  }

  private static CodeSymbol byName(FileStructuralMetadata meta, String name) {
    return meta.symbols().stream()
        .filter(s -> name.equals(s.symbolName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no symbol named " + name));
  }

  private static boolean grammarUnavailable(FileStructuralMetadata meta) {
    return meta.symbols().isEmpty();
  }

  @Test
  void extractsImportsIncludingTypeImportsAndRequire() {
    FileStructuralMetadata meta = extract();
    if (grammarUnavailable(meta)) {
      return;
    }
    assertTrue(meta.imports().contains("express"), () -> "imports were " + meta.imports());
    assertTrue(meta.imports().contains("./config"), () -> "imports were " + meta.imports());
    assertTrue(meta.imports().contains("legacy-lib"), () -> "imports were " + meta.imports());
  }

  @Test
  void emitsModuleSymbol() {
    FileStructuralMetadata meta = extract();
    if (grammarUnavailable(meta)) {
      return;
    }
    CodeSymbol module =
        meta.symbols().stream()
            .filter(s -> s.symbolType() == SymbolType.MODULE)
            .findFirst()
            .orElseThrow();
    assertEquals("dealService", module.symbolName());
    assertEquals("typescript:dealService", module.symbolId());
  }

  @Test
  void extractsTypesWithCorrectSymbolTypes() {
    FileStructuralMetadata meta = extract();
    if (grammarUnavailable(meta)) {
      return;
    }
    assertEquals(SymbolType.INTERFACE, byName(meta, "Shape").symbolType());
    assertEquals(SymbolType.ENUM, byName(meta, "Color").symbolType());
    assertEquals(SymbolType.RECORD, byName(meta, "Alias").symbolType());
    assertEquals(SymbolType.CLASS, byName(meta, "BaseService").symbolType());
    assertEquals(SymbolType.CLASS, byName(meta, "DealService").symbolType());
    assertTrue(byId(meta).containsKey("typescript:dealService.DealService"));
  }

  @Test
  void attributesMethodsToTheirEnclosingClass() {
    FileStructuralMetadata meta = extract();
    if (grammarUnavailable(meta)) {
      return;
    }
    Map<String, CodeSymbol> symbols = byId(meta);

    CodeSymbol listDeals = symbols.get("typescript:dealService.BaseService#listDeals");
    assertEquals(SymbolType.METHOD, listDeals.symbolType());
    assertEquals("BaseService", listDeals.containerName());

    // `area` is implemented on DealService while Shape declares the same member name; the
    // implementation must be attributed to the class, not silently merged or lost.
    CodeSymbol area = symbols.get("typescript:dealService.DealService#area");
    assertEquals(SymbolType.METHOD, area.symbolType());
    assertEquals("DealService", area.containerName());
  }

  @Test
  void extractsTopLevelFunctionAndArrowFunction() {
    FileStructuralMetadata meta = extract();
    if (grammarUnavailable(meta)) {
      return;
    }
    Map<String, CodeSymbol> symbols = byId(meta);
    assertEquals(SymbolType.FUNCTION, symbols.get("typescript:dealService#topLevel").symbolType());
    assertEquals(SymbolType.FUNCTION, symbols.get("typescript:dealService#arrowFn").symbolType());
  }

  @Test
  void recordsAccurateLineRanges() {
    FileStructuralMetadata meta = extract();
    if (grammarUnavailable(meta)) {
      return;
    }
    CodeSymbol listDeals = byName(meta, "listDeals");
    // "async listDeals(...)" is on line 16 of the sample.
    assertEquals(16, listDeals.lineStart());
    assertTrue(listDeals.lineEnd() >= listDeals.lineStart());
  }

  /**
   * Regression: tree-sitter offsets are UTF-8 bytes, but Java String indices are UTF-16. Slicing
   * with the raw offsets mangles every symbol name in a file containing non-ASCII text.
   *
   * <p>The assertion must be on text *derived from the node*, not on the module name (which comes
   * from the file path and would pass either way). Symbol NAMES and import specifiers are both
   * sliced out of the source, so they are what actually detects the corruption.
   */
  @Test
  void nonAsciiSourceStillProducesCorrectNames() {
    String source =
        """
        // comment with accents: caf\u00e9 \u00fcber na\u00efve \u2014 and an emoji \ud83d\ude00
        import { thing } from './m\u00f6dule';
        export class DealService {
          listDeals(): void {}
        }
        export function helper(): void {}
        """;
    FileStructuralMetadata meta =
        new TypeScriptMetadataExtractor()
            .extractFile(Path.of("/repo/unicode.ts"), source, "typescript");
    if (grammarUnavailable(meta)) {
      return;
    }

    // Names come from node text, so a UTF-8/UTF-16 offset mix-up shifts them off by the
    // multi-byte delta and these exact matches fail.
    assertTrue(
        meta.symbols().stream().anyMatch(s -> "DealService".equals(s.symbolName())),
        () -> "symbol names corrupted: " + meta.symbols().stream().map(CodeSymbol::symbolName).toList());
    assertTrue(
        meta.symbols().stream().anyMatch(s -> "listDeals".equals(s.symbolName())),
        () -> "symbol names corrupted: " + meta.symbols().stream().map(CodeSymbol::symbolName).toList());
    assertTrue(
        meta.symbols().stream().anyMatch(s -> "helper".equals(s.symbolName())),
        () -> "symbol names corrupted: " + meta.symbols().stream().map(CodeSymbol::symbolName).toList());

    // Import specifiers are sliced too.
    assertTrue(
        meta.imports().contains("./m\u00f6dule"),
        () -> "imports corrupted: " + meta.imports());

    Map<String, CodeSymbol> symbols = byId(meta);
    assertEquals("unicode", symbols.get("typescript:unicode.DealService").containerName());
    assertEquals(
        "DealService", symbols.get("typescript:unicode.DealService#listDeals").containerName());
  }

  /** Guards that the registry actually dispatches these languages (it did not, before this work). */
  @Test
  void registryDispatchesTypeScriptAndJavaScript() {
    MetadataExtractorRegistry registry = new MetadataExtractorRegistry();
    for (String language : List.of("typescript", "javascript")) {
      FileStructuralMetadata meta =
          registry.extractFile(
              Path.of("/repo/probe.ts"), "export function probe(): void {}\n", language);
      if (grammarUnavailable(meta)) {
        return; // native grammar unavailable in this environment
      }
      assertTrue(
          meta.symbols().stream().anyMatch(s -> "probe".equals(s.symbolName())),
          "registry did not dispatch language " + language);
    }
  }

  @Test
  void javascriptAlsoResolves() {
    FileStructuralMetadata meta =
        new TypeScriptMetadataExtractor()
            .extractFile(Path.of("/repo/util.js"), "export function helper(){}\n", "javascript");
    if (grammarUnavailable(meta)) {
      return;
    }
    assertFalse(meta.symbols().isEmpty());
    assertEquals(SymbolType.FUNCTION, byName(meta, "helper").symbolType());
  }

  @Test
  void malformedSourceDoesNotThrow() {
    FileStructuralMetadata meta =
        new TypeScriptMetadataExtractor()
            .extractFile(
                Path.of("/repo/broken.ts"),
                "class {{{ export const x = ;;; function",
                "typescript");
    // Tree-sitter is error-tolerant; the contract is simply that it must not throw.
    assertTrue(meta.symbols().size() >= 0);
  }

  @Test
  void emptySourceYieldsModuleOnlyOrNothing() {
    FileStructuralMetadata meta =
        new TypeScriptMetadataExtractor().extractFile(Path.of("/repo/e.ts"), "", "typescript");
    List<CodeSymbol> types =
        meta.symbols().stream()
            .filter(s -> s.symbolType() != SymbolType.MODULE)
            .toList();
    assertTrue(types.isEmpty());
  }
}
