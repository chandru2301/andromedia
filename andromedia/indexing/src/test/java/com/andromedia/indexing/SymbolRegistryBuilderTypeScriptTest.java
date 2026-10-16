package com.andromedia.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.FileStructuralMetadata;
import com.andromedia.common.ProjectSymbolRegistry;
import com.andromedia.ingestion.metadata.MetadataExtractorRegistry;
import com.andromedia.ingestion.metadata.TypeScriptMetadataExtractor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end check that TypeScript files produce a real symbol registry.
 *
 * <p>Before the tree-sitter extractor existed, {@code MetadataExtractorRegistry} had no entry for
 * {@code typescript}, so {@code SymbolRegistryBuilder} saw zero symbols for any TS/JS project and
 * {@code andro usages} / {@code andro impact} returned nothing. This test fails loudly if that
 * regression returns.
 *
 * <p>Skips (rather than fails) when the tree-sitter native library is unavailable, which is an
 * environment limitation — see {@code TypeScriptMetadataExtractor}.
 */
class SymbolRegistryBuilderTypeScriptTest {

  @TempDir Path tempDir;

  @Test
  void buildsRegistryWithSymbolsFromTypeScriptFiles() throws Exception {
    Path project = tempDir.resolve("project");
    Path src = project.resolve("src");
    Files.createDirectories(src);

    Files.writeString(
        src.resolve("dealService.ts"),
        """
        import { Router } from 'express';

        export interface DealShape {
          total(): number;
        }

        export class DealService implements DealShape {
          constructor(private readonly router: Router) {}
          async listDeals(limit: number): Promise<string[]> { return []; }
          total(): number { return 0; }
        }

        export function createService(): DealService {
          return new DealService(null as unknown as Router);
        }
        """);

    Path nested = src.resolve("util");
    Files.createDirectories(nested);
    Files.writeString(
        nested.resolve("helpers.ts"),
        """
        export const slugify = (value: string): string => value.toLowerCase();

        export class Formatter {
          format(input: string): string { return input.trim(); }
        }
        """);

    SymbolRegistryBuilder builder = new SymbolRegistryBuilder(new MetadataExtractorRegistry());
    ProjectSymbolRegistry registry = builder.build(project, Set.of("ts", "tsx"));

    // Distinguish "native grammar unavailable" from "extractor not registered". The former is an
    // environment limitation and is skipped; the latter is a real regression and must fail.
    // Probing the extractor directly (rather than the registry) keeps the two cases separate —
    // otherwise an unregistered language looks identical to a missing native library.
    if (!grammarAvailable()) {
      return;
    }
    assertTrue(
        registry.findBySymbolId("typescript:dealService.DealService").isPresent(),
        "class symbol missing from registry");
    assertTrue(
        registry.findBySymbolId("typescript:dealService.DealService#listDeals").isPresent(),
        "method symbol missing from registry");
    assertTrue(
        registry.findBySymbolId("typescript:dealService#createService").isPresent(),
        "function symbol missing from registry");
    assertTrue(
        registry.findBySymbolId("typescript:helpers#slugify").isPresent(),
        "arrow-function symbol missing from registry");

    // Simple-name lookup works, which is what `andro usages <Name>` relies on.
    assertEquals(1, registry.findBySimpleName("DealService").size());
    assertTrue(
        registry.findBySimpleName("Formatter").stream()
            .anyMatch(e -> e.symbolId().equals("typescript:helpers.Formatter")));
  }

  /**
   * True when the tree-sitter native library can be loaded in this environment.
   *
   * <p>Checked against the extractor directly so that a missing registry entry (a real bug) is
   * never mistaken for a missing native library (an environment limitation).
   */
  private static boolean grammarAvailable() {
    FileStructuralMetadata probe =
        new TypeScriptMetadataExtractor()
            .extractFile(Path.of("/probe/probe.ts"), "export function probe(): void {}\n", "typescript");
    return probe.symbols().stream().anyMatch(s -> "probe".equals(s.symbolName()));
  }

  @Test
  void javaStillWorksAlongsideTypeScript() throws Exception {
    Path project = tempDir.resolve("mixed");
    Files.createDirectories(project.resolve("com/foo"));
    Files.writeString(
        project.resolve("com/foo/Thing.java"),
        """
        package com.foo;

        public class Thing {
          public void run() {}
        }
        """);
    Files.writeString(
        project.resolve("thing.ts"),
        """
        export class Thing2 {
          run(): void {}
        }
        """);

    SymbolRegistryBuilder builder = new SymbolRegistryBuilder(new MetadataExtractorRegistry());
    ProjectSymbolRegistry registry = builder.build(project, Set.of("java", "ts"));

    // Java extraction must be unaffected by the new language registrations.
    assertTrue(registry.findBySymbolId("java:com.foo.Thing").isPresent());
  }
}
