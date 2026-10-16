package com.andromedia.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.andromedia.indexing.GitHistoryIndexingOrchestrator;
import com.andromedia.indexing.GitHistoryIndexingStats;
import com.andromedia.ingestion.git.GitIngestionOptions;
import com.andromedia.llm.EmbeddingProperties;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;

class IndexHistoryCommandTest {

  @Test
  void parsesHistorySubcommandOptions() {
    GitHistoryIndexingOrchestrator orchestrator = mock(GitHistoryIndexingOrchestrator.class);
    EmbeddingProperties embeddingProperties = new EmbeddingProperties();
    CliExecutionContext context = new CliExecutionContext();
    context.setPlainProgress(true);

    IndexHistoryCommand historyCommand =
        new IndexHistoryCommand(orchestrator, embeddingProperties, context);
    CommandLine commandLine = new CommandLine(historyCommand);

    GitHistoryIndexingOrchestrator.GitHistoryIndexingResult result =
        new GitHistoryIndexingOrchestrator.GitHistoryIndexingResult(
            new GitHistoryIndexingStats(1, 1, 0, 0, 0, Instant.now(), Instant.now()),
            "abc123",
            List.of(mock(com.andromedia.common.GitChangeEvent.class)));
    when(orchestrator.indexRepositoryWithCursor(
            any(Path.class), any(GitIngestionOptions.class), anyBoolean(), anyBoolean()))
        .thenReturn(result);

    int exitCode =
        commandLine.execute(".", "--max-commits", "25", "--recreate");

    assertEquals(0, exitCode);
    verify(orchestrator)
        .indexRepositoryWithCursor(any(Path.class), any(GitIngestionOptions.class), anyBoolean(), anyBoolean());
  }

  @Test
  void parsesHistoryBeforePathOnIndexCommand() {
    GitHistoryIndexingOrchestrator orchestrator = mock(GitHistoryIndexingOrchestrator.class);
    EmbeddingProperties embeddingProperties = new EmbeddingProperties();
    CliExecutionContext context = new CliExecutionContext();
    context.setPlainProgress(true);

    IndexHistoryCommand historyCommand =
        new IndexHistoryCommand(orchestrator, embeddingProperties, context);
    CommandLine commandLine =
        new CommandLine(new IndexCommandParentStub()).addSubcommand("history", historyCommand);

    GitHistoryIndexingOrchestrator.GitHistoryIndexingResult result =
        new GitHistoryIndexingOrchestrator.GitHistoryIndexingResult(
            new GitHistoryIndexingStats(1, 1, 0, 0, 0, Instant.now(), Instant.now()),
            "abc123",
            List.of(mock(com.andromedia.common.GitChangeEvent.class)));
    when(orchestrator.indexRepositoryWithCursor(
            any(Path.class), any(GitIngestionOptions.class), anyBoolean(), anyBoolean()))
        .thenReturn(result);

    int exitCode = commandLine.execute("history", ".", "--max-commits", "25");

    assertEquals(0, exitCode);
    verify(orchestrator)
        .indexRepositoryWithCursor(any(Path.class), any(GitIngestionOptions.class), anyBoolean(), anyBoolean());
  }

  @Command(name = "index", mixinStandardHelpOptions = true)
  static final class IndexCommandParentStub implements Runnable {

    @picocli.CommandLine.Unmatched
    private List<String> unmatchedArguments = List.of();

    @Override
    public void run() {
      // no-op for parse-order test
    }
  }
}
