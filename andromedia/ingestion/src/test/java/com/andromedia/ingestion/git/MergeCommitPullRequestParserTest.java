package com.andromedia.ingestion.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.PullRequestMetadata;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MergeCommitPullRequestParserTest {

  private final MergeCommitPullRequestParser parser = new MergeCommitPullRequestParser();

  @Test
  void parsesGithubMergeCommitSubject() {
    Optional<PullRequestMetadata> metadata =
        parser.resolve("Merge pull request #123 from feature/login", "Adds login flow");

    assertTrue(metadata.isPresent());
    assertEquals(123, metadata.get().number());
    assertEquals("github-merge", metadata.get().provider());
    assertEquals("Adds login flow", metadata.get().description());
  }

  @Test
  void parsesGithubSquashCommitSubject() {
    Optional<PullRequestMetadata> metadata =
        parser.resolve("Fix null pointer in UserService (#456)", "Detailed body");

    assertTrue(metadata.isPresent());
    assertEquals(456, metadata.get().number());
    assertEquals("Fix null pointer in UserService", metadata.get().title());
    assertEquals("github-squash", metadata.get().provider());
  }
}
