package com.andromedia.ingestion.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.GitChangeEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitIngestionServiceTest {

  @TempDir Path tempDir;

  @Test
  void walksCommitsIncludingInitialCommitWithoutNpe() throws Exception {
    Path repoDir = tempDir.resolve("repo");
    Files.createDirectories(repoDir.resolve("src"));
    Files.writeString(
        repoDir.resolve("src/UserService.java"),
        """
        package com.example;

        public class UserService {
          public String getUser() {
            return "user";
          }
        }
        """);

    try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
      git.add().addFilepattern(".").call();
      git.commit().setMessage("Initial commit").call();

      Files.writeString(
          repoDir.resolve("src/UserService.java"),
          """
          package com.example;

          public class UserService {
            public String getUser() {
              return "updated";
            }
          }
          """);
      git.add().addFilepattern(".").call();
      git.commit().setMessage("Update getUser").call();
    }

    GitIngestionService service =
        new GitIngestionService(List.of(new MergeCommitPullRequestParser()));
    List<GitChangeEvent> events =
        service.ingest(repoDir, new GitIngestionOptions(null, 10, null, null));

    assertEquals(2, events.size());
    assertTrue(events.stream().anyMatch(event -> event.subject().equals("Initial commit")));
    assertTrue(events.stream().anyMatch(event -> event.subject().equals("Update getUser")));
    GitChangeEvent update =
        events.stream()
            .filter(event -> event.subject().equals("Update getUser"))
            .findFirst()
            .orElseThrow();
    assertFalse(update.changedFiles().isEmpty());
    assertEquals("src/UserService.java", update.changedFiles().getFirst().path());
  }

  @Test
  void stopsAtCursorCommit() throws Exception {
    Path repoDir = tempDir.resolve("cursor-repo");
    Files.createDirectories(repoDir);
    String firstSha;
    try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
      Files.writeString(repoDir.resolve("README.md"), "one");
      git.add().addFilepattern(".").call();
      git.commit().setMessage("first").call();
      firstSha = git.log().setMaxCount(1).call().iterator().next().getName();

      Files.writeString(repoDir.resolve("README.md"), "two");
      git.add().addFilepattern(".").call();
      git.commit().setMessage("second").call();
    }

    GitIngestionService service = new GitIngestionService(List.of());
    List<GitChangeEvent> events =
        service.ingest(repoDir, new GitIngestionOptions(null, 10, null, firstSha));

    assertEquals(1, events.size());
    assertEquals("second", events.getFirst().subject());
  }
}
