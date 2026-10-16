package com.andromedia.cli;

import com.andromedia.cli.progress.CliPhase;
import com.andromedia.cli.progress.CliProgressReporter;
import com.andromedia.cli.progress.PhaseTiming;
import com.andromedia.indexing.GitHistoryIndexingOrchestrator;
import com.andromedia.indexing.GitHistoryIndexingStats;
import com.andromedia.ingestion.git.GitIngestionOptions;
import com.andromedia.llm.EmbeddingProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(
    name = "history",
    description = "Index git commit history into the project index",
    mixinStandardHelpOptions = true)
public class IndexHistoryCommand implements Runnable {

  private final GitHistoryIndexingOrchestrator orchestrator;
  private final EmbeddingProperties embeddingProperties;
  private final CliExecutionContext executionContext;

  @Parameters(
      index = "0",
      arity = "0..1",
      defaultValue = ".",
      description = "Repository root (default: current directory)")
  private Path repoPath;

  @Option(
      names = "--embed",
      description = "Generate OpenRouter embeddings for each git history chunk")
  private boolean embedChunks;

  @Option(
      names = "--max-commits",
      description = "Maximum number of commits to ingest (default: 1000)")
  private Integer maxCommits;

  @Option(
      names = "--since",
      description = "Only ingest commits after this ISO-8601 instant (e.g. 2024-01-01T00:00:00Z)")
  private String since;

  @Option(names = "--recreate", description = "Recreate git history documents in the index")
  private boolean recreateIndex;

  public IndexHistoryCommand(
      GitHistoryIndexingOrchestrator orchestrator,
      EmbeddingProperties embeddingProperties,
      CliExecutionContext executionContext) {
    this.orchestrator = orchestrator;
    this.embeddingProperties = embeddingProperties;
    this.executionContext = executionContext;
  }

  @Override
  public void run() {
    Path validatedRepo = validateRepoPath(repoPath);
    validateEmbedConfiguration(embedChunks);

    Instant sinceInstant = parseSince(since);
    int effectiveMaxCommits = maxCommits == null || maxCommits <= 0 ? 1000 : maxCommits;
    GitIngestionOptions options =
        new GitIngestionOptions(sinceInstant, effectiveMaxCommits, null, null);

    boolean plain = executionContext.isPlainProgress();
    CliProgressReporter progress = CliProgressReporter.create(plain, true);
    List<PhaseTiming> timings = new ArrayList<>();
    Instant startedAt = Instant.now();

    String detail = validatedRepo.getFileName().toString();
    if (embedChunks) {
      detail = detail + ", with embeddings";
    }
    if (recreateIndex) {
      detail = detail + ", recreate";
    }

    progress.startPhase(CliPhase.INDEXING_WORKSPACE);
    progress.updatePhase(CliPhase.INDEXING_WORKSPACE, "git history: " + detail);

    GitHistoryIndexingOrchestrator.GitHistoryIndexingResult result;
    try {
      result =
          orchestrator.indexRepositoryWithCursor(
              validatedRepo, options, embedChunks, recreateIndex);
    } catch (RuntimeException ex) {
      progress.fail(ex.getMessage());
      throw ex;
    }

    if (result.events().isEmpty()) {
      Duration duration = Duration.between(startedAt, Instant.now());
      progress.completePhase(CliPhase.INDEXING_WORKSPACE, duration);
      System.out.println(
          "Git history already up to date for "
              + validatedRepo
              + " (no new commits). Use --recreate to re-ingest.");
      return;
    }

    GitHistoryIndexingStats stats = result.stats();
    Duration duration = Duration.between(startedAt, Instant.now());
    timings.add(new PhaseTiming(CliPhase.INDEXING_WORKSPACE, duration));
    progress.completePhase(CliPhase.INDEXING_WORKSPACE, duration);

    System.out.printf(
        "Indexed git history: %d commits (%d chunks, %d embedded, %d embeddingRequests, %d symbols) from %s in %d ms%n",
        stats.commitsIndexed(),
        stats.chunksIndexed(),
        stats.chunksEmbedded(),
        stats.embeddingApiCalls(),
        stats.symbolsExtracted(),
        validatedRepo,
        stats.finishedAt() != null && stats.startedAt() != null
            ? Duration.between(stats.startedAt(), stats.finishedAt()).toMillis()
            : duration.toMillis());
  }

  private static Path validateRepoPath(Path path) {
    if (path == null) {
      throw new IllegalArgumentException("Repository path is required");
    }
    Path absolute = path.toAbsolutePath().normalize();
    if (!Files.exists(absolute)) {
      throw new IllegalArgumentException("Directory does not exist: " + absolute);
    }
    if (!Files.isDirectory(absolute)) {
      throw new IllegalArgumentException("Path is not a directory: " + absolute);
    }
    return absolute;
  }

  private void validateEmbedConfiguration(boolean embedChunks) {
    if (embedChunks && !embeddingProperties.hasApiKey()) {
      throw new IllegalArgumentException(
          "Embedding requires OPENROUTER_API_KEY (or andromedia.embedding.api-key).");
    }
  }

  private static Instant parseSince(String since) {
    if (since == null || since.isBlank()) {
      return null;
    }
    return Instant.parse(since.trim());
  }
}
