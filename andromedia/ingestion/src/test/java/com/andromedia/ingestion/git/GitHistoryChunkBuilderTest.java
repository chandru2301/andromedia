package com.andromedia.ingestion.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.ChangedFile;
import com.andromedia.common.ChangedSymbol;
import com.andromedia.common.ChangeType;
import com.andromedia.common.ChunkIdGenerator;
import com.andromedia.common.GitChangeEvent;
import com.andromedia.common.GitHistoryChunk;
import com.andromedia.common.PullRequestMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GitHistoryChunkBuilderTest {

  @Test
  void buildsFrontLoadedContentWithTruncation() {
    String longBody = "x".repeat(800);
    String longDescription = "y".repeat(500);
    GitChangeEvent event =
        new GitChangeEvent(
            "abc123def456",
            Instant.parse("2024-03-01T12:00:00Z"),
            "Alice",
            "Update user lookup",
            longBody,
            List.of("parent1"),
            List.of(new ChangedFile("src/UserService.java", ChangeType.MODIFY)),
            List.of(new ChangedSymbol("java:UserService#getUser", "UserService#getUser", "src/UserService.java", 10, 20)),
            Optional.of(new PullRequestMetadata(42, "Update user lookup", longDescription, "github-squash")));

    GitHistoryChunk chunk = GitHistoryChunkBuilder.build(event);
    String content = chunk.content();

    assertEquals(ChunkIdGenerator.generateGitEventId("abc123def456"), chunk.chunkId());
    assertTrue(content.startsWith("[GIT_COMMIT] abc123de"));
    assertTrue(content.contains("Update user lookup"));
    assertTrue(content.contains("Changed: src/UserService.java"));
    assertTrue(content.contains("Symbols: UserService#getUser"));
    assertTrue(content.contains("PR #42: Update user lookup"));
    assertTrue(content.length() <= 30_000);
    assertTrue(countOccurrences(content, 'x') <= 500);
    assertTrue(countOccurrences(content, 'y') <= 300);
  }

  private static int countOccurrences(String value, char ch) {
    int count = 0;
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) == ch) {
        count++;
      }
    }
    return count;
  }
}
