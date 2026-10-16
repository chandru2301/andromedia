package com.andromedia.cli;

import com.andromedia.cli.progress.CliPhase;
import com.andromedia.cli.progress.CliProgressReporter;
import com.andromedia.cli.progress.PhaseTiming;
import com.andromedia.common.IndexConstants;
import com.andromedia.indexing.IndexingProgressListener;
import com.andromedia.indexing.IndexingRequest;
import com.andromedia.indexing.IndexingService;
import com.andromedia.indexing.IndexingStats;
import com.andromedia.llm.EmbeddingProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Unmatched;

@Component
@Command(
    name = "index",
    description = "Index source files with Lucene (defaults to the current directory)",
    subcommands = {IndexHistoryCommand.class},
    mixinStandardHelpOptions = true)
public class IndexCommand implements Runnable {

  private final IndexingService indexingService;
  private final EmbeddingProperties embeddingProperties;
  private final CliExecutionContext executionContext;

  @Unmatched
  private List<String> unmatchedArguments = new ArrayList<>();

  @Option(
      names = {"-e", "--ext"},
      split = ",",
      description = "File extensions to include (e.g. java,kt,ts)")
  private Set<String> includedExtensions = new LinkedHashSet<>();

  @Option(names = "--recreate", description = "Recreate index (delete existing)")
  private boolean recreateIndex;

  @Option(
      names = "--dry-run",
      description = "Do not write index, just log what would be indexed")
  private boolean dryRun;

  @Option(
      names = "--embed",
      description = "Generate OpenRouter embeddings (openai/text-embedding-ada-002) for each chunk")
  private boolean embedChunks;

  public IndexCommand(
      IndexingService indexingService,
      EmbeddingProperties embeddingProperties,
      CliExecutionContext executionContext) {
    this.indexingService = indexingService;
    this.embeddingProperties = embeddingProperties;
    this.executionContext = executionContext;
  }

  @Override
  public void run() {
    // Default to the current directory, matching git/npm/cargo. Requiring an
    // explicit path made a bare `index` throw "Root directory is required",
    // which is a confusing response to the most obvious thing to type.
    Path root =
        (unmatchedArguments == null || unmatchedArguments.isEmpty())
            ? Path.of("")
            : Path.of(unmatchedArguments.getFirst());
    Path validatedRoot = validateRootDirectory(root);
    Set<String> extensions = normalizeExtensions(includedExtensions);

    validateEmbedConfiguration(embedChunks);

    boolean plain = executionContext.isPlainProgress();
    CliProgressReporter progress = CliProgressReporter.create(plain, !dryRun);
    List<PhaseTiming> timings = new ArrayList<>();
    Instant workspaceStartedAt = Instant.now();
    Instant[] embeddingStartedAt = {null};

    String indexDetail = validatedRoot.getFileName().toString();
    if (embedChunks) {
      indexDetail = indexDetail + ", with embeddings";
    }
    if (dryRun) {
      indexDetail = indexDetail + ", dry-run";
    }

    IndexingProgressListener indexingProgress =
        new IndexingProgressListener() {
          private boolean embeddingPhaseStarted;

          @Override
          public void onProgress(
              long filesVisited, long filesIndexed, long chunksIndexed, long embeddingApiCalls) {
            String detail =
                String.format(
                    "%d files, %d chunks%s",
                    filesIndexed,
                    chunksIndexed,
                    embedChunks && embeddingApiCalls > 0
                        ? ", " + embeddingApiCalls + " embed calls"
                        : "");
            progress.updatePhase(CliPhase.INDEXING_WORKSPACE, detail);
          }

          @Override
          public void onEmbeddingBatch(long embeddingApiCalls, long chunksEmbedded) {
            if (!embeddingPhaseStarted) {
              embeddingPhaseStarted = true;
              Duration workspaceDuration = Duration.between(workspaceStartedAt, Instant.now());
              timings.add(new PhaseTiming(CliPhase.INDEXING_WORKSPACE, workspaceDuration));
              progress.completePhase(CliPhase.INDEXING_WORKSPACE, workspaceDuration);
              embeddingStartedAt[0] = Instant.now();
              progress.startPhase(CliPhase.EMBEDDING_CHUNKS);
            }
            progress.updatePhase(
                CliPhase.EMBEDDING_CHUNKS,
                String.format("%d chunks, %d API calls", chunksEmbedded, embeddingApiCalls));
          }
        };

    IndexingRequest request =
        new IndexingRequest(
            validatedRoot,
            extensions,
            recreateIndex,
            dryRun,
            embedChunks,
            indexingProgress);

    progress.startPhase(CliPhase.INDEXING_WORKSPACE);
    progress.updatePhase(CliPhase.INDEXING_WORKSPACE, indexDetail);

    IndexingStats stats;
    try {
      stats = indexingService.indexPath(request);
    } catch (RuntimeException ex) {
      progress.fail(ex.getMessage());
      throw ex;
    }

    Instant finishedAt = Instant.now();
    if (embeddingStartedAt[0] != null) {
      Duration embeddingDuration = Duration.between(embeddingStartedAt[0], finishedAt);
      timings.add(new PhaseTiming(CliPhase.EMBEDDING_CHUNKS, embeddingDuration));
      progress.completePhase(CliPhase.EMBEDDING_CHUNKS, embeddingDuration);
    } else {
      Duration workspaceDuration = Duration.between(workspaceStartedAt, finishedAt);
      timings.add(new PhaseTiming(CliPhase.INDEXING_WORKSPACE, workspaceDuration));
      progress.completePhase(CliPhase.INDEXING_WORKSPACE, workspaceDuration);
    }
    System.out.printf(
        "Indexed %d / %d files (%d chunks, %d embedded, %d embeddingRequests, %d oversized, %d symbols, %d imports, %d references, %d bytes) from %s in %d ms%n",
        stats.filesIndexed(),
        stats.filesVisited(),
        stats.chunksIndexed(),
        stats.chunksEmbedded(),
        stats.embeddingApiCalls(),
        stats.oversizedChunks(),
        stats.symbolsExtracted(),
        stats.importsExtracted(),
        stats.referencesExtracted(),
        stats.bytesIndexed(),
        validatedRoot,
        stats.duration().toMillis());
  }

  private static Path validateRootDirectory(Path root) {
    if (root == null) {
      throw new IllegalArgumentException("Root directory is required");
    }
    Path absolute = root.toAbsolutePath().normalize();
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

  private static Set<String> normalizeExtensions(Set<String> extensions) {
    if (extensions == null || extensions.isEmpty()) {
      return IndexConstants.DEFAULT_EXTENSIONS;
    }
    return extensions.stream()
        .map(IndexCommand::normalizeExtension)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static String normalizeExtension(String extension) {
    String normalized = extension.trim().toLowerCase();
    if (normalized.startsWith(".")) {
      normalized = normalized.substring(1);
    }
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("Extension cannot be empty");
    }
    return normalized;
  }
}
