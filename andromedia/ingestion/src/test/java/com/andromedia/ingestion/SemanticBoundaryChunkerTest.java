package com.andromedia.ingestion;

import com.andromedia.common.ChunkUnitType;
import com.andromedia.ingestion.metadata.MetadataExtractorRegistry;
import com.andromedia.common.CodeChunk;
import com.andromedia.common.FileStructuralMetadata;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticBoundaryChunkerTest {

  private final SemanticBoundaryChunker chunker = new SemanticBoundaryChunker();

  @Test
  void emitsOneChunkPerMethodForJavaSource() {
    String source =
        """
        package demo;

        public class TokenService {
          public void validateToken() {
            String token = "alphaUniqueToken";
          }

          public void refreshToken() {
            String token = "betaUniqueToken";
          }
        }
        """;

    List<CodeChunk> chunks =
        chunker.chunkFile(Path.of("TokenService.java"), source, "java", "java");

    assertEquals(2, chunks.size());
    assertTrue(chunks.stream().allMatch(chunk -> chunk.unitType() == ChunkUnitType.METHOD));
    assertTrue(chunks.stream().anyMatch(chunk -> "validateToken".equals(chunk.methodName())));
    assertTrue(chunks.stream().anyMatch(chunk -> "refreshToken".equals(chunk.methodName())));
    assertTrue(chunks.stream().noneMatch(CodeChunk::oversized));
  }

  @Test
  void attachesStructuralMetadataToJavaChunks() {
    String source =
        """
        package com.foo;

        import java.util.List;

        @Service
        public class AnomalyEvent {
          public void handle() {
          }
        }
        """;

    List<CodeChunk> chunks =
        chunker.chunkFile(Path.of("AnomalyEvent.java"), source, "java", "java");

    assertFalse(chunks.isEmpty());
    CodeChunk chunk = chunks.getFirst();
    assertFalse(chunk.metadata().imports().isEmpty());
    assertTrue(chunk.metadata().imports().contains("java.util.List"));
    assertFalse(chunk.metadata().symbols().isEmpty());
    assertTrue(
        chunk.metadata().symbols().stream()
            .anyMatch(symbol -> symbol.symbolId().startsWith("java:com.foo.")));
  }

  @Test
  void doesNotSplitNormalMethodIntoArbitraryFragments() {
    String source =
        """
        package demo;

        public class AuthService {
          public boolean validateJwtToken(String token) {
            if (token == null) {
              return false;
            }
            return token.startsWith("Bearer");
          }
        }
        """;

    List<CodeChunk> chunks =
        chunker.chunkFile(Path.of("AuthService.java"), source, "java", "java");

    assertEquals(1, chunks.size());
    assertEquals("validateJwtToken", chunks.getFirst().methodName());
    assertEquals(ChunkUnitType.METHOD, chunks.getFirst().unitType());
  }

  @Test
  void fallsBackToFileChunkForNonJavaLanguages() {
    String source =
        """
        fun main() {
          println("hello")
        }
        """;

    List<CodeChunk> chunks = chunker.chunkFile(Path.of("App.kt"), source, "kt", "kotlin");

    assertEquals(1, chunks.size());
    assertEquals(ChunkUnitType.FILE, chunks.getFirst().unitType());
    assertFalse(chunks.getFirst().oversized());
  }

  @Test
  void attachesPythonMetadataToFileChunks() {
    String source =
        """
        import json
        from typing import List

        def process_event(data):
            return data
        """;

    List<CodeChunk> chunks =
        chunker.chunkFile(Path.of("sample_module.py"), source, "py", "python");

    assertEquals(1, chunks.size());
    CodeChunk chunk = chunks.getFirst();
    assertEquals(ChunkUnitType.FILE, chunk.unitType());
    assertTrue(chunk.metadata().imports().stream().anyMatch(value -> value.contains("json")));
    assertTrue(
        chunk.metadata().symbols().stream()
            .anyMatch(symbol -> "python:sample_module.process_event".equals(symbol.symbolId())));
  }

  /**
   * Regression: TypeScript previously fell through to a single whole-file chunk,
   * so every symbol reported the file's full line span and `andro usages` printed
   * "file.tsx:1-358" for a function declared at line 10. Symbols are extracted
   * correctly either way, which is why the bug was invisible in symbol tests — it
   * only showed up in the reported line numbers.
   */
  @Test
  void emitsOneChunkPerSymbolForTypeScriptWithAccurateLineSpans() {
    String source =
        """
        import { thing } from './dep';

        export function first(): void {
          const a = 1;
        }

        export class Holder {
          second(): number {
            return 2;
          }
        }
        """;

    List<CodeChunk> chunks = chunker.chunkFile(Path.of("sample.ts"), source, "ts", "typescript");

    if (!typeScriptGrammarAvailable()) {
      // No tree-sitter native library: the extractor yields no symbols, so the
      // chunker correctly falls back to a file chunk. Probing the extractor
      // directly (rather than inspecting the chunks) keeps this distinct from
      // "symbol chunking was switched off", which must fail loudly.
      return;
    }

    CodeChunk first = chunks.stream()
        .filter(c -> "first".equals(c.methodName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no chunk for first()"));
    assertEquals(3, first.startLine(), "first() is declared on line 3");
    assertEquals(5, first.endLine(), "first() body ends on line 5");

    CodeChunk second = chunks.stream()
        .filter(c -> "second".equals(c.methodName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no chunk for second()"));
    assertEquals("Holder", second.className());
    assertEquals(8, second.startLine(), "second() is declared on line 8");
  }

  /**
   * Python must stay file-level: its reference resolver depends on that layout,
   * and silently changing it here would alter usages/impact behaviour.
   */
  @Test
  void keepsPythonChunkedAtFileLevel() {
    String source =
        """
        def alpha():
            return 1

        def beta():
            return 2
        """;

    List<CodeChunk> chunks = chunker.chunkFile(Path.of("mod.py"), source, "py", "python");

    assertEquals(1, chunks.size(), "python should remain a single file chunk");
    assertEquals(ChunkUnitType.FILE, chunks.getFirst().unitType());
  }

  /**
   * True when the tree-sitter TypeScript grammar loaded, so symbol extraction is
   * possible. Checked against the extractor so a missing grammar (environment)
   * is never confused with symbol chunking being disabled (a real bug).
   */
  private static boolean typeScriptGrammarAvailable() {
    FileStructuralMetadata probe =
        new MetadataExtractorRegistry()
            .extractFile(Path.of("probe.ts"), "export function probe(): void {}\n", "typescript");
    return probe.symbols().stream().anyMatch(s -> "probe".equals(s.symbolName()));
  }
}
