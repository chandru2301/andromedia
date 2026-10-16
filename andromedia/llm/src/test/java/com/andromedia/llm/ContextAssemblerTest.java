package com.andromedia.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.ChunkUnitType;
import com.andromedia.common.IndexConstants;
import com.andromedia.common.RetrievalReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextAssemblerTest {

  @TempDir
  Path tempDirectory;

  @Test
  void expandsMethodHitWithHeaderAndNeighborWindow() throws Exception {
    Path sourceFile = tempDirectory.resolve("AuthService.java");
    Files.writeString(
        sourceFile,
        """
        package demo;

        import java.util.List;

        public class AuthService {
          private final List<String> roles;

          public boolean validateJwtToken(String token) {
            return token != null && token.startsWith("Bearer");
          }
        }
        """);

    ContextExpansionProperties properties = new ContextExpansionProperties();
    properties.setNeighborLineWindow(2);
    ContextAssembler assembler = new ContextAssembler(properties);

    RetrievalReference hit =
        new RetrievalReference(
            "chunk-1",
            sourceFile.toString(),
            "AuthService.java",
            "AuthService",
            "validateJwtToken",
            ChunkUnitType.METHOD,
            9,
            11);

    AssembledContext context = assembler.assemble(List.of(hit));

    assertTrue(context.blocks().size() >= 2);
    String prompt = context.toPromptText();
    assertTrue(prompt.contains("package demo"));
    assertTrue(prompt.contains("validateJwtToken"));
    assertTrue(prompt.contains("startsWith(\"Bearer\")"));

    ContextBlock methodBlock =
        context.blocks().stream()
            .filter(block -> "validateJwtToken".equals(block.methodName()))
            .findFirst()
            .orElseThrow();
    assertEquals("AuthService.java", methodBlock.fileName());
    assertEquals("AuthService", methodBlock.className());
    assertTrue(methodBlock.startLine() > 0);
    assertTrue(methodBlock.endLine() >= methodBlock.startLine());
  }

  @Test
  void assemblesGitHistoryFromIndexedContentWithoutReadingFile() {
    ContextAssembler assembler = new ContextAssembler(new ContextExpansionProperties());

    RetrievalReference gitHit =
        new RetrievalReference(
            "git-abc",
            "README.md",
            "abc123",
            null,
            null,
            ChunkUnitType.HISTORY,
            0,
            0,
            Optional.of(IndexConstants.SOURCE_TYPE_GIT),
            Optional.of("[GIT_COMMIT] abc123\nimplemented impact and plan options\n"));

    AssembledContext context = assembler.assemble(List.of(gitHit));

    assertEquals(1, context.blocks().size());
    assertTrue(context.blocks().getFirst().content().contains("implemented impact and plan options"));
  }
}
