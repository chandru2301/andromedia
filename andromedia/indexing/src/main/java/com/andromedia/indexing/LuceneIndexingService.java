package com.andromedia.indexing;

import com.andromedia.common.CodeChunk;
import com.andromedia.common.CodeSymbol;
import com.andromedia.common.GitHistoryChunk;
import com.andromedia.common.GoogleChatChunk;
import com.andromedia.common.IndexConstants;
import com.andromedia.common.IndexPathHolder;
import com.andromedia.common.IndexPathResolver;
import com.andromedia.common.ProjectSymbolRegistry;
import com.andromedia.common.ChunkUnitType;
import com.andromedia.common.JiraChunk;
import com.andromedia.common.LinearChunk;
import com.andromedia.common.NotionChunk;
import com.andromedia.common.SlackChunk;
import com.andromedia.common.StructuralMetadata;
import com.andromedia.common.ZohoDealContextChunk;
import com.andromedia.common.SymbolGraphStore;
import com.andromedia.ingestion.ChunkingService;
import com.andromedia.ingestion.references.ReferenceResolverRegistry;
import com.andromedia.llm.EmbeddingException;
import com.andromedia.llm.EmbeddingService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LuceneIndexingService implements IndexingService {

  private static final Logger log = LoggerFactory.getLogger(LuceneIndexingService.class);

  private static final Set<String> SKIPPED_DIRECTORY_NAMES = SkippedDirectories.NAMES;

  
  private static final int PROGRESS_FILE_INTERVAL = 25;

  private final String configuredIndexPath;
  private final ChunkingService chunkingService;
  private final EmbeddingService embeddingService;
  private final SymbolRegistryBuilder symbolRegistryBuilder;
  private final ReferenceResolverRegistry referenceResolverRegistry;
  private final int embeddingBatchSize;

  @Autowired
  public LuceneIndexingService(
      @Value("${" + IndexConstants.INDEX_PATH_PROPERTY + ":}") String configuredIndexPath,
      ChunkingService chunkingService,
      EmbeddingService embeddingService,
      SymbolRegistryBuilder symbolRegistryBuilder,
      ReferenceResolverRegistry referenceResolverRegistry,
      @Value("${andromedia.embedding.batch-size:20}") int embeddingBatchSize) {
    this.configuredIndexPath = configuredIndexPath;
    this.chunkingService = chunkingService;
    this.embeddingService = embeddingService;
    this.symbolRegistryBuilder = symbolRegistryBuilder;
    this.referenceResolverRegistry = referenceResolverRegistry;
    this.embeddingBatchSize = Math.max(1, embeddingBatchSize);
  }

  public LuceneIndexingService(
      String configuredIndexPath, ChunkingService chunkingService, EmbeddingService embeddingService) {
    this(
        configuredIndexPath,
        chunkingService,
        embeddingService,
        new SymbolRegistryBuilder(new com.andromedia.ingestion.metadata.MetadataExtractorRegistry()),
        new ReferenceResolverRegistry(),
        20);
  }

  @Override
  public IndexingStats indexPath(IndexingRequest request) {
    Instant startedAt = Instant.now();
    Counter counter = new Counter();

    Path rootDirectory = request.rootDirectory().toAbsolutePath().normalize();
    Path workspaceRoot = IndexPathResolver.resolveWorkspaceRoot(rootDirectory);
    Path projectIndexPath = IndexPathResolver.resolveProjectIndexPath(configuredIndexPath, rootDirectory);

    if (request.dryRun()) {
      log.info(
          "Dry run — workspace root {} would write index to {}{}",
          workspaceRoot,
          projectIndexPath,
          request.embedChunks() ? " (with embeddings)" : "");
      processFiles(rootDirectory, request, counter, request.progressListener(), this::logDryRunFile);
    } else {
      log.info(
          "Indexing workspace root {} into {}{}",
          workspaceRoot,
          projectIndexPath,
          request.embedChunks() ? " (with embeddings)" : "");
      try {
        // The marker lets a workspace root be rediscovered from a subdirectory.
        // It is an optimisation, not a precondition, and it lives inside the
        // source tree — so indexing a read-only mount (the Docker setup mounts
        // ./workspace read-only on purpose) must not fail because of it.
        try {
          Files.createDirectories(IndexPathResolver.workspaceMarkerPath(workspaceRoot));
        } catch (IOException markerEx) {
          log.warn(
              "Could not write workspace marker under {} ({}). Continuing without it;"
                  + " indexing is unaffected.",
              workspaceRoot,
              markerEx.getMessage());
        }
        Files.createDirectories(projectIndexPath);
        try (Directory directory = FSDirectory.open(projectIndexPath);
            IndexWriter writer =
                createWriter(directory, request.recreateIndex(), request.embedChunks())) {
          ProjectSymbolRegistry symbolRegistry =
              symbolRegistryBuilder.build(rootDirectory, request.includedExtensions());
          SymbolGraphBuilder graphBuilder = new SymbolGraphBuilder();
          indexAllFiles(
              writer,
              rootDirectory,
              request,
              counter,
              request.progressListener(),
              symbolRegistry,
              graphBuilder);
          writer.commit();
          SymbolGraphStore.save(projectIndexPath, graphBuilder.build());
          log.info(
              "Saved symbol graph with {} edges to {}",
              graphBuilder.pendingEdgeCount(),
              SymbolGraphStore.graphDirectory(projectIndexPath));
        }
      } catch (IOException ex) {
        throw new IndexingException("Failed to index path: " + rootDirectory, ex);
      }
    }

    request
        .progressListener()
        .onProgress(
            counter.filesVisited,
            counter.filesIndexed,
            counter.chunksIndexed,
            counter.embeddingApiCalls);

    Instant finishedAt = Instant.now();
    return new IndexingStats(
        counter.filesVisited,
        counter.filesIndexed,
        counter.chunksIndexed,
        counter.chunksEmbedded,
        counter.embeddingApiCalls,
        counter.oversizedChunks,
        counter.bytesIndexed,
        counter.symbolsExtracted,
        counter.importsExtracted,
        counter.referencesExtracted,
        startedAt,
        finishedAt);
  }

  @Override
  public GitHistoryIndexingStats indexGitHistory(GitHistoryIndexingRequest request) {
    Instant startedAt = Instant.now();
    GitHistoryCounter counter = new GitHistoryCounter();

    Path projectIndexPath = resolveGitHistoryIndexPath(request.workspaceRoot());

    if (request.chunks().isEmpty()) {
      return new GitHistoryIndexingStats(0, 0, 0, 0, 0, startedAt, Instant.now());
    }

    try {
      if (request.workspaceRoot() != null) {
        Path workspaceRoot = IndexPathResolver.resolveWorkspaceRoot(request.workspaceRoot());
        Files.createDirectories(IndexPathResolver.workspaceMarkerPath(workspaceRoot));
      }
      Files.createDirectories(projectIndexPath);
      try (Directory directory = FSDirectory.open(projectIndexPath);
          IndexWriter writer =
              createWriter(directory, request.recreateIndex(), request.embedChunks())) {
        GitHistoryEmbeddingBatch batch =
            request.embedChunks()
                ? new GitHistoryEmbeddingBatch(writer, counter)
                : null;
        for (GitHistoryChunk chunk : request.chunks()) {
          counter.commitsIndexed++;
          counter.symbolsExtracted += chunk.metadata().symbols().size();
          if (batch != null) {
            batch.add(chunk);
          } else {
            Document document = toDocument(chunk, null);
            writer.updateDocument(new Term(IndexConstants.FIELD_CHUNK_ID, chunk.chunkId()), document);
            counter.chunksIndexed++;
          }
        }
        if (batch != null) {
          batch.flush();
        }
        writer.commit();
      }
    } catch (IOException ex) {
      throw new IndexingException("Failed to index git history", ex);
    }

    Instant finishedAt = Instant.now();
    return new GitHistoryIndexingStats(
        counter.commitsIndexed,
        counter.chunksIndexed,
        counter.chunksEmbedded,
        counter.embeddingApiCalls,
        counter.symbolsExtracted,
        startedAt,
        finishedAt);
  }

  @Override
  public SlackHistoryIndexingStats indexSlackHistory(SlackHistoryIndexingRequest request) {
    Instant startedAt = Instant.now();
    SlackHistoryCounter counter = new SlackHistoryCounter();

    Path projectIndexPath = resolveGitHistoryIndexPath(request.workspaceRoot());

    if (request.chunks().isEmpty()) {
      return new SlackHistoryIndexingStats(0, 0, 0, 0, startedAt, Instant.now());
    }

    try {
      if (request.workspaceRoot() != null) {
        Path workspaceRoot = IndexPathResolver.resolveWorkspaceRoot(request.workspaceRoot());
        Files.createDirectories(IndexPathResolver.workspaceMarkerPath(workspaceRoot));
      }
      Files.createDirectories(projectIndexPath);
      try (Directory directory = FSDirectory.open(projectIndexPath);
          IndexWriter writer =
              createWriter(directory, request.recreateIndex(), request.embedChunks())) {
        SlackHistoryEmbeddingBatch batch =
            request.embedChunks() ? new SlackHistoryEmbeddingBatch(writer, counter) : null;
        for (SlackChunk chunk : request.chunks()) {
          counter.threadsIndexed++;
          if (batch != null) {
            batch.add(chunk);
          } else {
            Document document = toDocument(chunk, null);
            writer.updateDocument(new Term(IndexConstants.FIELD_CHUNK_ID, chunk.chunkId()), document);
            counter.chunksIndexed++;
          }
        }
        if (batch != null) {
          batch.flush();
        }
        writer.commit();
      }
    } catch (IOException ex) {
      throw new IndexingException("Failed to index Slack history", ex);
    }

    Instant finishedAt = Instant.now();
    return new SlackHistoryIndexingStats(
        counter.threadsIndexed,
        counter.chunksIndexed,
        counter.chunksEmbedded,
        counter.embeddingApiCalls,
        startedAt,
        finishedAt);
  }

  @Override
  public GoogleChatHistoryIndexingStats indexGoogleChatHistory(
      GoogleChatHistoryIndexingRequest request) {
    Instant startedAt = Instant.now();
    long threadsIndexed = 0;
    long chunksIndexed = 0;

    Path projectIndexPath = resolveGitHistoryIndexPath(request.workspaceRoot());

    if (request.chunks().isEmpty()) {
      return new GoogleChatHistoryIndexingStats(0, 0, 0, 0, startedAt, Instant.now());
    }

    try {
      if (request.workspaceRoot() != null) {
        Path workspaceRoot = IndexPathResolver.resolveWorkspaceRoot(request.workspaceRoot());
        Files.createDirectories(IndexPathResolver.workspaceMarkerPath(workspaceRoot));
      }
      Files.createDirectories(projectIndexPath);
      try (Directory directory = FSDirectory.open(projectIndexPath);
          IndexWriter writer = createWriter(directory, request.recreateIndex(), false)) {
        // No embedding batch yet for Google Chat — chunks are still fully searchable via the
        // lexical index; semantic-search enrichment can be added the same way Slack's was.
        for (GoogleChatChunk chunk : request.chunks()) {
          threadsIndexed++;
          Document document = toDocument(chunk, null);
          writer.updateDocument(new Term(IndexConstants.FIELD_CHUNK_ID, chunk.chunkId()), document);
          chunksIndexed++;
        }
        writer.commit();
      }
    } catch (IOException ex) {
      throw new IndexingException("Failed to index Google Chat history", ex);
    }

    Instant finishedAt = Instant.now();
    return new GoogleChatHistoryIndexingStats(threadsIndexed, chunksIndexed, 0, 0, startedAt, finishedAt);
  }

  @Override
  public NotionHistoryIndexingStats indexNotionHistory(NotionHistoryIndexingRequest request) {
    Instant startedAt = Instant.now();
    NotionHistoryCounter counter = new NotionHistoryCounter();

    Path projectIndexPath = resolveGitHistoryIndexPath(request.workspaceRoot());

    if (request.chunks().isEmpty()) {
      return new NotionHistoryIndexingStats(0, 0, 0, 0, startedAt, Instant.now());
    }

    try {
      if (request.workspaceRoot() != null) {
        Path workspaceRoot = IndexPathResolver.resolveWorkspaceRoot(request.workspaceRoot());
        Files.createDirectories(IndexPathResolver.workspaceMarkerPath(workspaceRoot));
      }
      Files.createDirectories(projectIndexPath);
      try (Directory directory = FSDirectory.open(projectIndexPath);
          IndexWriter writer =
              createWriter(directory, request.recreateIndex(), request.embedChunks())) {
        NotionHistoryEmbeddingBatch batch =
            request.embedChunks() ? new NotionHistoryEmbeddingBatch(writer, counter) : null;
        for (NotionChunk chunk : request.chunks()) {
          if (batch != null) {
            batch.add(chunk);
          } else {
            Document document = toDocument(chunk, null);
            writer.updateDocument(new Term(IndexConstants.FIELD_CHUNK_ID, chunk.chunkId()), document);
            counter.chunksIndexed++;
          }
        }
        if (batch != null) {
          batch.flush();
        }
        writer.commit();
      }
    } catch (IOException ex) {
      throw new IndexingException("Failed to index Notion history", ex);
    }

    Instant finishedAt = Instant.now();
    return new NotionHistoryIndexingStats(
        0,
        (int) counter.chunksIndexed,
        (int) counter.chunksEmbedded,
        (int) counter.embeddingApiCalls,
        startedAt,
        finishedAt);
  }

  @Override
  public JiraHistoryIndexingStats indexJiraHistory(JiraHistoryIndexingRequest request) {
    Instant startedAt = Instant.now();
    long chunksIndexed = 0;
    long chunksEmbedded = 0;
    long embeddingApiCalls = 0;
    Path projectIndexPath = resolveGitHistoryIndexPath(request.workspaceRoot());

    if (request.chunks().isEmpty()) {
      return new JiraHistoryIndexingStats(0, 0, 0, 0, startedAt, Instant.now());
    }

    try {
      if (request.workspaceRoot() != null) {
        Path workspaceRoot = IndexPathResolver.resolveWorkspaceRoot(request.workspaceRoot());
        Files.createDirectories(IndexPathResolver.workspaceMarkerPath(workspaceRoot));
      }
      Files.createDirectories(projectIndexPath);
      try (Directory directory = FSDirectory.open(projectIndexPath);
          IndexWriter writer =
              createWriter(directory, request.recreateIndex(), request.embedChunks())) {
        List<float[]> embeddings = null;
        if (request.embedChunks()) {
          List<String> texts = request.chunks().stream().map(JiraChunk::content).toList();
          embeddings = embeddingService.embedAll(texts);
          embeddingApiCalls++;
        }
        for (int i = 0; i < request.chunks().size(); i++) {
          JiraChunk chunk = request.chunks().get(i);
          float[] embedding = embeddings == null ? null : embeddings.get(i);
          Document document = toDocument(chunk, embedding);
          writer.updateDocument(new Term(IndexConstants.FIELD_CHUNK_ID, chunk.chunkId()), document);
          chunksIndexed++;
          if (embedding != null) {
            chunksEmbedded++;
          }
        }
        writer.commit();
      }
    } catch (IOException | EmbeddingException ex) {
      throw new IndexingException("Failed to index Jira history", ex);
    }

    return new JiraHistoryIndexingStats(
        (int) chunksIndexed,
        (int) chunksIndexed,
        (int) chunksEmbedded,
        (int) embeddingApiCalls,
        startedAt,
        Instant.now());
  }

  @Override
  public LinearHistoryIndexingStats indexLinearHistory(LinearHistoryIndexingRequest request) {
    Instant startedAt = Instant.now();
    long chunksIndexed = 0;
    long chunksEmbedded = 0;
    long embeddingApiCalls = 0;
    Path projectIndexPath = resolveGitHistoryIndexPath(request.workspaceRoot());

    if (request.chunks().isEmpty()) {
      return new LinearHistoryIndexingStats(0, 0, 0, 0, startedAt, Instant.now());
    }

    try {
      if (request.workspaceRoot() != null) {
        Path workspaceRoot = IndexPathResolver.resolveWorkspaceRoot(request.workspaceRoot());
        Files.createDirectories(IndexPathResolver.workspaceMarkerPath(workspaceRoot));
      }
      Files.createDirectories(projectIndexPath);
      try (Directory directory = FSDirectory.open(projectIndexPath);
          IndexWriter writer =
              createWriter(directory, request.recreateIndex(), request.embedChunks())) {
        List<float[]> embeddings = null;
        if (request.embedChunks()) {
          List<String> texts = request.chunks().stream().map(LinearChunk::content).toList();
          embeddings = embeddingService.embedAll(texts);
          embeddingApiCalls++;
        }
        for (int i = 0; i < request.chunks().size(); i++) {
          LinearChunk chunk = request.chunks().get(i);
          float[] embedding = embeddings == null ? null : embeddings.get(i);
          Document document = toDocument(chunk, embedding);
          writer.updateDocument(new Term(IndexConstants.FIELD_CHUNK_ID, chunk.chunkId()), document);
          chunksIndexed++;
          if (embedding != null) {
            chunksEmbedded++;
          }
        }
        writer.commit();
      }
    } catch (IOException | EmbeddingException ex) {
      throw new IndexingException("Failed to index Linear history", ex);
    }

    return new LinearHistoryIndexingStats(
        (int) chunksIndexed,
        (int) chunksIndexed,
        (int) chunksEmbedded,
        (int) embeddingApiCalls,
        startedAt,
        Instant.now());
  }

  @Override
  public ZohoHistoryIndexingStats indexZohoHistory(ZohoHistoryIndexingRequest request) {
    Instant startedAt = Instant.now();
    long chunksIndexed = 0;
    long chunksEmbedded = 0;
    long embeddingApiCalls = 0;
    Path projectIndexPath = resolveGitHistoryIndexPath(request.workspaceRoot());

    boolean hasDeletes =
        request.deletedChunkIds() != null && !request.deletedChunkIds().isEmpty();
    if (request.chunks().isEmpty() && !request.fullRefresh() && !hasDeletes) {
      return new ZohoHistoryIndexingStats(0, 0, 0, 0, startedAt, Instant.now());
    }

    try {
      if (request.workspaceRoot() != null) {
        Path workspaceRoot = IndexPathResolver.resolveWorkspaceRoot(request.workspaceRoot());
        Files.createDirectories(IndexPathResolver.workspaceMarkerPath(workspaceRoot));
      }
      Files.createDirectories(projectIndexPath);
      try (Directory directory = FSDirectory.open(projectIndexPath);
          IndexWriter writer =
              createWriter(directory, request.recreateIndex(), request.embedChunks())) {
        if (request.fullRefresh()) {
          // Full Zoho refresh: drop prior ZOHO docs in the same writer session so deleted CRM
          // records do not linger, then write the fresh set and commit once.
          writer.deleteDocuments(
              new Term(IndexConstants.FIELD_SOURCE_TYPE, IndexConstants.SOURCE_TYPE_ZOHO));
          log.info("Deleted existing ZOHO docs before full refresh into {}", projectIndexPath);
        } else if (hasDeletes) {
          for (String chunkId : request.deletedChunkIds()) {
            if (chunkId != null && !chunkId.isBlank()) {
              writer.deleteDocuments(new Term(IndexConstants.FIELD_CHUNK_ID, chunkId.trim()));
            }
          }
          log.info(
              "Deleted {} Zoho chunk(s) for incremental sync into {}",
              request.deletedChunkIds().size(),
              projectIndexPath);
        }
        List<float[]> embeddings = null;
        if (request.embedChunks() && !request.chunks().isEmpty()) {
          List<String> texts = request.chunks().stream().map(ZohoDealContextChunk::content).toList();
          embeddings = embeddingService.embedAll(texts);
          embeddingApiCalls++;
        }
        for (int i = 0; i < request.chunks().size(); i++) {
          ZohoDealContextChunk chunk = request.chunks().get(i);
          float[] embedding = embeddings == null ? null : embeddings.get(i);
          Document document = toDocument(chunk, embedding);
          writer.updateDocument(new Term(IndexConstants.FIELD_CHUNK_ID, chunk.chunkId()), document);
          chunksIndexed++;
          if (embedding != null) {
            chunksEmbedded++;
          }
        }
        writer.commit();
      }
    } catch (IOException | EmbeddingException ex) {
      throw new IndexingException("Failed to index Zoho history", ex);
    }

    return new ZohoHistoryIndexingStats(
        (int) chunksIndexed,
        (int) chunksEmbedded,
        (int) embeddingApiCalls,
        0,
        startedAt,
        Instant.now());
  }

  private void logDryRunFile(Path filePath, Counter counter) {
    try {
      String extension = extractExtension(filePath);
      String content = Files.readString(filePath, StandardCharsets.UTF_8);
      String language = LanguageResolver.resolve(extension);
      List<CodeChunk> chunks = chunkingService.chunkFile(filePath, content, extension, language);
      counter.filesIndexed++;
      counter.chunksIndexed += chunks.size();
      counter.oversizedChunks += chunks.stream().filter(CodeChunk::oversized).count();
      counter.symbolsExtracted += countSymbols(chunks);
      counter.importsExtracted += countImports(chunks);
      counter.bytesIndexed += content.getBytes(StandardCharsets.UTF_8).length;
      log.info("[dry-run] would index {} chunks from: {}", chunks.size(), filePath);
    } catch (IOException ex) {
      throw new IndexingException("Failed to read file: " + filePath, ex);
    }
  }

  private void indexAllFiles(
      IndexWriter writer,
      Path rootDirectory,
      IndexingRequest request,
      Counter counter,
      IndexingProgressListener progressListener,
      ProjectSymbolRegistry symbolRegistry,
      SymbolGraphBuilder graphBuilder) {
    EmbeddingBatch batch =
        request.embedChunks()
            ? new EmbeddingBatch(writer, counter, progressListener)
            : null;
    try (var paths = Files.walk(rootDirectory)) {
      paths
          .filter(Files::isRegularFile)
          .filter(filePath -> !shouldSkipPath(filePath, rootDirectory))
          .forEach(
              filePath -> {
                counter.filesVisited++;
                if (request.includedExtensions().contains(extractExtension(filePath))) {
                  indexSingleFile(
                      writer,
                      filePath,
                      request.embedChunks(),
                      batch,
                      counter,
                      symbolRegistry,
                      graphBuilder);
                }
                maybeReportProgress(progressListener, counter);
              });
    } catch (IOException ex) {
      throw new IndexingException("Failed to walk directory: " + rootDirectory, ex);
    }
    if (batch != null) {
      batch.flush();
    }
  }

  private void indexSingleFile(
      IndexWriter writer,
      Path filePath,
      boolean embedChunks,
      EmbeddingBatch batch,
      Counter counter,
      ProjectSymbolRegistry symbolRegistry,
      SymbolGraphBuilder graphBuilder) {
    try {
      String extension = extractExtension(filePath);
      String content = Files.readString(filePath, StandardCharsets.UTF_8);
      String language = LanguageResolver.resolve(extension);
      List<CodeChunk> chunks = chunkingService.chunkFile(filePath, content, extension, language);

      for (CodeChunk chunk : chunks) {
        CodeChunk enrichedChunk = enrichReferences(filePath, content, language, chunk, symbolRegistry);
        graphBuilder.addChunk(enrichedChunk);
        if (enrichedChunk.oversized()) {
          counter.oversizedChunks++;
          log.warn(
              "Oversized chunk {} ({}:{}-{}, {} lines) — indexed as single unit",
              enrichedChunk.chunkId(),
              enrichedChunk.fileName(),
              enrichedChunk.startLine(),
              enrichedChunk.endLine(),
              enrichedChunk.endLine() - enrichedChunk.startLine() + 1);
        }
        counter.symbolsExtracted += enrichedChunk.metadata().symbols().size();
        counter.importsExtracted += enrichedChunk.metadata().imports().size();
        counter.referencesExtracted += enrichedChunk.metadata().referencedSymbolIds().size();
        if (embedChunks) {
          batch.add(enrichedChunk);
        } else {
          Document document = toDocument(enrichedChunk, null);
          writer.updateDocument(
              new Term(IndexConstants.FIELD_CHUNK_ID, enrichedChunk.chunkId()), document);
          counter.chunksIndexed++;
        }
      }

      counter.filesIndexed++;
      counter.bytesIndexed += content.getBytes(StandardCharsets.UTF_8).length;
    } catch (IOException ex) {
      throw new IndexingException("Failed to index file: " + filePath, ex);
    }
  }

  private CodeChunk enrichReferences(
      Path filePath,
      String fileContent,
      String language,
      CodeChunk chunk,
      ProjectSymbolRegistry symbolRegistry) {
    List<String> references =
        referenceResolverRegistry.resolveReferences(
            filePath,
            language,
            chunk.content(),
            chunk.startLine(),
            chunk.endLine(),
            chunk.metadata().imports(),
            fileContent,
            chunk.metadata().symbols(),
            symbolRegistry);
    StructuralMetadata metadata = chunk.metadata().withReferencedSymbolIds(references);
    return CodeChunk.withMetadata(chunk, metadata);
  }

  private IndexWriter createWriter(Directory directory, boolean recreateIndex, boolean embedChunks)
      throws IOException {
    IndexWriterConfig config =
        new IndexWriterConfig(new StandardAnalyzer())
            .setOpenMode(
                recreateIndex
                    ? IndexWriterConfig.OpenMode.CREATE
                    : IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
    if (embedChunks) {
      config.setCodec(new HighDimensionLucene912Codec(embeddingService.dimensions()));
    }
    return new IndexWriter(directory, config);
  }

  @FunctionalInterface
  private interface FileHandler {
    void handle(Path filePath, Counter counter);
  }

  private void processFiles(
      Path rootDirectory,
      IndexingRequest request,
      Counter counter,
      IndexingProgressListener progressListener,
      FileHandler handler) {
    try (var paths = Files.walk(rootDirectory)) {
      paths
          .filter(Files::isRegularFile)
          .filter(filePath -> !shouldSkipPath(filePath, rootDirectory))
          .forEach(
              filePath -> {
                counter.filesVisited++;
                if (request.includedExtensions().contains(extractExtension(filePath))) {
                  handler.handle(filePath, counter);
                }
                maybeReportProgress(progressListener, counter);
              });
    } catch (IOException ex) {
      throw new IndexingException("Failed to walk directory: " + rootDirectory, ex);
    }
  }

  private static final class Counter {
    private long filesVisited;
    private long filesIndexed;
    private long chunksIndexed;
    private long chunksEmbedded;
    private long embeddingApiCalls;
    private long oversizedChunks;
    private long bytesIndexed;
    private long symbolsExtracted;
    private long importsExtracted;
    private long referencesExtracted;
  }

  private static void maybeReportProgress(IndexingProgressListener progressListener, Counter counter) {
    if (progressListener == null || counter.filesVisited % PROGRESS_FILE_INTERVAL != 0) {
      return;
    }
    progressListener.onProgress(
        counter.filesVisited,
        counter.filesIndexed,
        counter.chunksIndexed,
        counter.embeddingApiCalls);
  }

  private final class EmbeddingBatch {
    private final IndexWriter writer;
    private final Counter counter;
    private final IndexingProgressListener progressListener;
    private final List<CodeChunk> pendingChunks = new ArrayList<>();
    private final List<String> pendingTexts = new ArrayList<>();

    private EmbeddingBatch(
        IndexWriter writer, Counter counter, IndexingProgressListener progressListener) {
      this.writer = writer;
      this.counter = counter;
      this.progressListener = progressListener;
    }

    void add(CodeChunk chunk) {
      pendingChunks.add(chunk);
      pendingTexts.add(chunk.content());
      if (pendingChunks.size() >= embeddingBatchSize) {
        flush();
      }
    }

    void flush() {
      if (pendingChunks.isEmpty()) {
        return;
      }
      List<float[]> embeddings;
      try {
        embeddings = embeddingService.embedAll(List.copyOf(pendingTexts));
      } catch (EmbeddingException ex) {
        throw new IndexingException("Failed to embed chunk batch", ex);
      }
      if (embeddings.size() != pendingChunks.size()) {
        throw new IndexingException(
            "Embedding batch size mismatch: expected "
                + pendingChunks.size()
                + " but got "
                + embeddings.size(),
            new IllegalStateException("Invalid embedding service response"));
      }
      counter.embeddingApiCalls++;
      for (int i = 0; i < pendingChunks.size(); i++) {
        CodeChunk chunk = pendingChunks.get(i);
        float[] embedding = embeddings.get(i);
        if (embedding.length != embeddingService.dimensions()) {
          throw new IndexingException(
              "Embedding dimension mismatch for chunk "
                  + chunk.chunkId()
                  + ": expected "
                  + embeddingService.dimensions()
                  + " but got "
                  + embedding.length,
              new IllegalStateException("Invalid embedding dimension"));
        }
        Document document = toDocument(chunk, embedding);
        try {
          writer.updateDocument(new Term(IndexConstants.FIELD_CHUNK_ID, chunk.chunkId()), document);
        } catch (IOException ex) {
          throw new IndexingException("Failed to write embedded chunk " + chunk.chunkId(), ex);
        }
        counter.chunksIndexed++;
        counter.chunksEmbedded++;
      }
      progressListener.onEmbeddingBatch(counter.embeddingApiCalls, counter.chunksEmbedded);
      pendingChunks.clear();
      pendingTexts.clear();
    }
  }

  private static final class GitHistoryCounter {
    private long commitsIndexed;
    private long chunksIndexed;
    private long chunksEmbedded;
    private long embeddingApiCalls;
    private long symbolsExtracted;
  }

  private final class GitHistoryEmbeddingBatch {
    private final IndexWriter writer;
    private final GitHistoryCounter counter;
    private final List<GitHistoryChunk> pendingChunks = new ArrayList<>();
    private final List<String> pendingTexts = new ArrayList<>();

    private GitHistoryEmbeddingBatch(IndexWriter writer, GitHistoryCounter counter) {
      this.writer = writer;
      this.counter = counter;
    }

    void add(GitHistoryChunk chunk) {
      pendingChunks.add(chunk);
      pendingTexts.add(chunk.content());
      if (pendingChunks.size() >= embeddingBatchSize) {
        flush();
      }
    }

    void flush() {
      if (pendingChunks.isEmpty()) {
        return;
      }
      List<float[]> embeddings;
      try {
        embeddings = embeddingService.embedAll(List.copyOf(pendingTexts));
      } catch (EmbeddingException ex) {
        throw new IndexingException("Failed to embed git history chunk batch", ex);
      }
      if (embeddings.size() != pendingChunks.size()) {
        throw new IndexingException(
            "Embedding batch size mismatch: expected "
                + pendingChunks.size()
                + " but got "
                + embeddings.size(),
            new IllegalStateException("Invalid embedding service response"));
      }
      counter.embeddingApiCalls++;
      for (int i = 0; i < pendingChunks.size(); i++) {
        GitHistoryChunk chunk = pendingChunks.get(i);
        float[] embedding = embeddings.get(i);
        if (embedding.length != embeddingService.dimensions()) {
          throw new IndexingException(
              "Embedding dimension mismatch for git chunk "
                  + chunk.chunkId()
                  + ": expected "
                  + embeddingService.dimensions()
                  + " but got "
                  + embedding.length,
              new IllegalStateException("Invalid embedding dimension"));
        }
        Document document = toDocument(chunk, embedding);
        try {
          writer.updateDocument(new Term(IndexConstants.FIELD_CHUNK_ID, chunk.chunkId()), document);
        } catch (IOException ex) {
          throw new IndexingException("Failed to write embedded git chunk " + chunk.chunkId(), ex);
        }
        counter.chunksIndexed++;
        counter.chunksEmbedded++;
      }
      pendingChunks.clear();
      pendingTexts.clear();
    }
  }

  private static final class SlackHistoryCounter {
    private long threadsIndexed;
    private long chunksIndexed;
    private long chunksEmbedded;
    private long embeddingApiCalls;
  }

  private final class SlackHistoryEmbeddingBatch {
    private final IndexWriter writer;
    private final SlackHistoryCounter counter;
    private final List<SlackChunk> pendingChunks = new ArrayList<>();
    private final List<String> pendingTexts = new ArrayList<>();

    private SlackHistoryEmbeddingBatch(IndexWriter writer, SlackHistoryCounter counter) {
      this.writer = writer;
      this.counter = counter;
    }

    void add(SlackChunk chunk) {
      pendingChunks.add(chunk);
      pendingTexts.add(chunk.content());
      if (pendingChunks.size() >= embeddingBatchSize) {
        flush();
      }
    }

    void flush() {
      if (pendingChunks.isEmpty()) {
        return;
      }
      List<float[]> embeddings;
      try {
        embeddings = embeddingService.embedAll(List.copyOf(pendingTexts));
      } catch (EmbeddingException ex) {
        throw new IndexingException("Failed to embed Slack history chunk batch", ex);
      }
      if (embeddings.size() != pendingChunks.size()) {
        throw new IndexingException(
            "Embedding batch size mismatch: expected "
                + pendingChunks.size()
                + " but got "
                + embeddings.size(),
            new IllegalStateException("Invalid embedding service response"));
      }
      counter.embeddingApiCalls++;
      for (int i = 0; i < pendingChunks.size(); i++) {
        SlackChunk chunk = pendingChunks.get(i);
        float[] embedding = embeddings.get(i);
        if (embedding.length != embeddingService.dimensions()) {
          throw new IndexingException(
              "Embedding dimension mismatch for Slack chunk "
                  + chunk.chunkId()
                  + ": expected "
                  + embeddingService.dimensions()
                  + " but got "
                  + embedding.length,
              new IllegalStateException("Invalid embedding dimension"));
        }
        Document document = toDocument(chunk, embedding);
        try {
          writer.updateDocument(new Term(IndexConstants.FIELD_CHUNK_ID, chunk.chunkId()), document);
        } catch (IOException ex) {
          throw new IndexingException("Failed to write embedded Slack chunk " + chunk.chunkId(), ex);
        }
        counter.chunksIndexed++;
        counter.chunksEmbedded++;
      }
      pendingChunks.clear();
      pendingTexts.clear();
    }
  }

  private static final class NotionHistoryCounter {
    private long chunksIndexed;
    private long chunksEmbedded;
    private long embeddingApiCalls;
  }

  private final class NotionHistoryEmbeddingBatch {
    private final IndexWriter writer;
    private final NotionHistoryCounter counter;
    private final List<NotionChunk> pendingChunks = new ArrayList<>();
    private final List<String> pendingTexts = new ArrayList<>();

    private NotionHistoryEmbeddingBatch(IndexWriter writer, NotionHistoryCounter counter) {
      this.writer = writer;
      this.counter = counter;
    }

    void add(NotionChunk chunk) {
      pendingChunks.add(chunk);
      pendingTexts.add(chunk.content());
      if (pendingChunks.size() >= embeddingBatchSize) {
        flush();
      }
    }

    void flush() {
      if (pendingChunks.isEmpty()) {
        return;
      }
      List<float[]> embeddings;
      try {
        embeddings = embeddingService.embedAll(List.copyOf(pendingTexts));
      } catch (EmbeddingException ex) {
        throw new IndexingException("Failed to embed Notion history chunk batch", ex);
      }
      if (embeddings.size() != pendingChunks.size()) {
        throw new IndexingException(
            "Embedding batch size mismatch: expected "
                + pendingChunks.size()
                + " but got "
                + embeddings.size(),
            new IllegalStateException("Invalid embedding service response"));
      }
      counter.embeddingApiCalls++;
      for (int i = 0; i < pendingChunks.size(); i++) {
        NotionChunk chunk = pendingChunks.get(i);
        float[] embedding = embeddings.get(i);
        if (embedding.length != embeddingService.dimensions()) {
          throw new IndexingException(
              "Embedding dimension mismatch for Notion chunk "
                  + chunk.chunkId()
                  + ": expected "
                  + embeddingService.dimensions()
                  + " but got "
                  + embedding.length,
              new IllegalStateException("Invalid embedding dimension"));
        }
        Document document = toDocument(chunk, embedding);
        try {
          writer.updateDocument(new Term(IndexConstants.FIELD_CHUNK_ID, chunk.chunkId()), document);
        } catch (IOException ex) {
          throw new IndexingException("Failed to write embedded Notion chunk " + chunk.chunkId(), ex);
        }
        counter.chunksIndexed++;
        counter.chunksEmbedded++;
      }
      pendingChunks.clear();
      pendingTexts.clear();
    }
  }

  private static Document toDocument(CodeChunk chunk, float[] embedding) {
    Document document = new Document();
    document.add(new StringField(IndexConstants.FIELD_CHUNK_ID, chunk.chunkId(), Field.Store.YES));
    // Every other chunk type records its source. Code did not, so search hits
    // came back with a null sourceType: the UI could not label them, and a
    // source_types filter of ["CODE"] excluded all code, because the filter
    // requires the field to match.
    document.add(
        new StringField(
            IndexConstants.FIELD_SOURCE_TYPE, IndexConstants.SOURCE_TYPE_CODE, Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_PATH, chunk.path(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_FILE_NAME, chunk.fileName(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_EXTENSION, chunk.extension(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_LANGUAGE, chunk.language(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_UNIT_TYPE, chunk.unitType().name(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_START_LINE, Integer.toString(chunk.startLine()), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_END_LINE, Integer.toString(chunk.endLine()), Field.Store.YES));
    if (chunk.className() != null) {
      document.add(new StringField(IndexConstants.FIELD_CLASS_NAME, chunk.className(), Field.Store.YES));
    }
    if (chunk.methodName() != null) {
      document.add(new StringField(IndexConstants.FIELD_METHOD_NAME, chunk.methodName(), Field.Store.YES));
    }
    document.add(new TextField(IndexConstants.FIELD_CONTENT, chunk.content(), Field.Store.YES));
    addStructuralMetadata(document, chunk.metadata());
    if (embedding != null) {
      document.add(
          new KnnFloatVectorField(
              IndexConstants.FIELD_EMBEDDING, embedding, VectorSimilarityFunction.COSINE));
    }
    return document;
  }

  private static Document toDocument(GitHistoryChunk chunk, float[] embedding) {
    Document document = new Document();
    document.add(new StringField(IndexConstants.FIELD_CHUNK_ID, chunk.chunkId(), Field.Store.YES));
    document.add(
        new StringField(IndexConstants.FIELD_SOURCE_TYPE, IndexConstants.SOURCE_TYPE_GIT, Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_COMMIT_SHA, chunk.commitSha(), Field.Store.YES));
    document.add(
        new StringField(
            IndexConstants.FIELD_TIMESTAMP, chunk.committedAt().toString(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_AUTHOR, chunk.author(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_UNIT_TYPE, ChunkUnitType.HISTORY.name(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_START_LINE, "0", Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_END_LINE, "0", Field.Store.YES));

    String primaryPath = chunk.filePaths().isEmpty() ? chunk.commitSha() : chunk.filePaths().getFirst();
    document.add(new StringField(IndexConstants.FIELD_PATH, primaryPath, Field.Store.YES));
    String fileName =
        primaryPath.contains("/")
            ? primaryPath.substring(primaryPath.lastIndexOf('/') + 1)
            : primaryPath;
    document.add(new StringField(IndexConstants.FIELD_FILE_NAME, fileName, Field.Store.YES));
    document.add(new TextField(IndexConstants.FIELD_CONTENT, chunk.content(), Field.Store.YES));

    for (String filePath : chunk.filePaths()) {
      document.add(new StringField(IndexConstants.FIELD_FILE_PATHS, filePath, Field.Store.YES));
    }

    addStructuralMetadata(document, chunk.metadata());
    if (embedding != null) {
      document.add(
          new KnnFloatVectorField(
              IndexConstants.FIELD_EMBEDDING, embedding, VectorSimilarityFunction.COSINE));
    }
    return document;
  }

  private static Document toDocument(SlackChunk chunk, float[] embedding) {
    Document document = new Document();
    document.add(new StringField(IndexConstants.FIELD_CHUNK_ID, chunk.chunkId(), Field.Store.YES));
    document.add(
        new StringField(
            IndexConstants.FIELD_SOURCE_TYPE, IndexConstants.SOURCE_TYPE_SLACK, Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_CHANNEL_ID, chunk.channelId(), Field.Store.YES));
    document.add(
        new StringField(IndexConstants.FIELD_CHANNEL_NAME, chunk.channelName(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_THREAD_TS, chunk.threadTs(), Field.Store.YES));
    document.add(
        new StringField(IndexConstants.FIELD_TIMESTAMP, chunk.timestamp().toString(), Field.Store.YES));
    document.add(
        new StringField(
            IndexConstants.FIELD_REACTION_COUNT,
            Integer.toString(chunk.reactionCount()),
            Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_UNIT_TYPE, ChunkUnitType.SLACK.name(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_START_LINE, "0", Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_END_LINE, "0", Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_PATH, chunk.path(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_FILE_NAME, chunk.fileName(), Field.Store.YES));
    document.add(new TextField(IndexConstants.FIELD_CONTENT, chunk.content(), Field.Store.YES));

    for (String participant : chunk.participants()) {
      document.add(new StringField(IndexConstants.FIELD_PARTICIPANTS, participant, Field.Store.YES));
    }

    if (embedding != null) {
      document.add(
          new KnnFloatVectorField(
              IndexConstants.FIELD_EMBEDDING, embedding, VectorSimilarityFunction.COSINE));
    }
    return document;
  }

  private static Document toDocument(GoogleChatChunk chunk, float[] embedding) {
    Document document = new Document();
    document.add(new StringField(IndexConstants.FIELD_CHUNK_ID, chunk.chunkId(), Field.Store.YES));
    document.add(
        new StringField(
            IndexConstants.FIELD_SOURCE_TYPE,
            IndexConstants.SOURCE_TYPE_GOOGLE_CHAT,
            Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_CHANNEL_ID, chunk.spaceId(), Field.Store.YES));
    document.add(
        new StringField(IndexConstants.FIELD_CHANNEL_NAME, chunk.spaceName(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_THREAD_TS, chunk.threadName(), Field.Store.YES));
    document.add(
        new StringField(IndexConstants.FIELD_TIMESTAMP, chunk.timestamp().toString(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_REACTION_COUNT, "0", Field.Store.YES));
    document.add(
        new StringField(IndexConstants.FIELD_UNIT_TYPE, ChunkUnitType.GOOGLE_CHAT.name(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_START_LINE, "0", Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_END_LINE, "0", Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_PATH, chunk.path(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_FILE_NAME, chunk.fileName(), Field.Store.YES));
    document.add(new TextField(IndexConstants.FIELD_CONTENT, chunk.content(), Field.Store.YES));

    for (String participant : chunk.participants()) {
      document.add(new StringField(IndexConstants.FIELD_PARTICIPANTS, participant, Field.Store.YES));
    }

    if (embedding != null) {
      document.add(
          new KnnFloatVectorField(
              IndexConstants.FIELD_EMBEDDING, embedding, VectorSimilarityFunction.COSINE));
    }
    return document;
  }

  private static Document toDocument(NotionChunk chunk, float[] embedding) {
    Document document = new Document();
    document.add(new StringField(IndexConstants.FIELD_CHUNK_ID, chunk.chunkId(), Field.Store.YES));
    document.add(
        new StringField(
            IndexConstants.FIELD_SOURCE_TYPE, IndexConstants.SOURCE_TYPE_NOTION, Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_PAGE_ID, chunk.pageId(), Field.Store.YES));
    document.add(
        new StringField(IndexConstants.FIELD_PAGE_TITLE, chunk.pageTitle(), Field.Store.YES));
    if (chunk.lastEdited() != null) {
      document.add(
          new StringField(
              IndexConstants.FIELD_LAST_EDITED, chunk.lastEdited().toString(), Field.Store.YES));
      document.add(
          new StringField(
              IndexConstants.FIELD_TIMESTAMP, chunk.lastEdited().toString(), Field.Store.YES));
    }
    if (chunk.author() != null && !chunk.author().isBlank()) {
      document.add(new StringField(IndexConstants.FIELD_AUTHOR, chunk.author(), Field.Store.YES));
    }
    document.add(
        new StringField(IndexConstants.FIELD_UNIT_TYPE, ChunkUnitType.NOTION.name(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_START_LINE, "0", Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_END_LINE, "0", Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_PATH, chunk.path(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_FILE_NAME, chunk.fileName(), Field.Store.YES));
    document.add(new TextField(IndexConstants.FIELD_CONTENT, chunk.content(), Field.Store.YES));

    if (embedding != null) {
      document.add(
          new KnnFloatVectorField(
              IndexConstants.FIELD_EMBEDDING, embedding, VectorSimilarityFunction.COSINE));
    }
    return document;
  }

  private static Document toDocument(JiraChunk chunk, float[] embedding) {
    Document document = new Document();
    document.add(new StringField(IndexConstants.FIELD_CHUNK_ID, chunk.chunkId(), Field.Store.YES));
    document.add(
        new StringField(
            IndexConstants.FIELD_SOURCE_TYPE, IndexConstants.SOURCE_TYPE_JIRA, Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_ISSUE_KEY, chunk.issueKey(), Field.Store.YES));
    document.add(
        new StringField(IndexConstants.FIELD_ISSUE_TYPE, chunk.issueType(), Field.Store.YES));
    document.add(
        new StringField(IndexConstants.FIELD_PROJECT_KEY, chunk.projectKey(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_PRIORITY, chunk.priority(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_STATUS, chunk.status(), Field.Store.YES));
    if (chunk.updated() != null) {
      document.add(
          new StringField(
              IndexConstants.FIELD_TIMESTAMP, chunk.updated().toString(), Field.Store.YES));
    }
    if (chunk.author() != null && !chunk.author().isBlank()) {
      document.add(new StringField(IndexConstants.FIELD_AUTHOR, chunk.author(), Field.Store.YES));
    }
    document.add(
        new StringField(IndexConstants.FIELD_UNIT_TYPE, ChunkUnitType.JIRA.name(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_START_LINE, "0", Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_END_LINE, "0", Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_PATH, chunk.path(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_FILE_NAME, chunk.fileName(), Field.Store.YES));
    document.add(new TextField(IndexConstants.FIELD_CONTENT, chunk.content(), Field.Store.YES));
    if (embedding != null) {
      document.add(
          new KnnFloatVectorField(
              IndexConstants.FIELD_EMBEDDING, embedding, VectorSimilarityFunction.COSINE));
    }
    return document;
  }

  private static Document toDocument(LinearChunk chunk, float[] embedding) {
    Document document = new Document();
    document.add(new StringField(IndexConstants.FIELD_CHUNK_ID, chunk.chunkId(), Field.Store.YES));
    document.add(
        new StringField(
            IndexConstants.FIELD_SOURCE_TYPE, IndexConstants.SOURCE_TYPE_LINEAR, Field.Store.YES));
    document.add(
        new StringField(
            IndexConstants.FIELD_ISSUE_IDENTIFIER, chunk.identifier(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_TEAM, chunk.team(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_CYCLE, chunk.cycle(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_STATE, chunk.state(), Field.Store.YES));
    if (chunk.updated() != null) {
      document.add(
          new StringField(
              IndexConstants.FIELD_TIMESTAMP, chunk.updated().toString(), Field.Store.YES));
    }
    if (chunk.author() != null && !chunk.author().isBlank()) {
      document.add(new StringField(IndexConstants.FIELD_AUTHOR, chunk.author(), Field.Store.YES));
    }
    document.add(
        new StringField(
            IndexConstants.FIELD_UNIT_TYPE, ChunkUnitType.LINEAR.name(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_START_LINE, "0", Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_END_LINE, "0", Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_PATH, chunk.path(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_FILE_NAME, chunk.fileName(), Field.Store.YES));
    document.add(new TextField(IndexConstants.FIELD_CONTENT, chunk.content(), Field.Store.YES));
    if (embedding != null) {
      document.add(
          new KnnFloatVectorField(
              IndexConstants.FIELD_EMBEDDING, embedding, VectorSimilarityFunction.COSINE));
    }
    return document;
  }

  private static Document toDocument(ZohoDealContextChunk chunk, float[] embedding) {
    Document document = new Document();
    document.add(new StringField(IndexConstants.FIELD_CHUNK_ID, chunk.chunkId(), Field.Store.YES));
    document.add(
        new StringField(
            IndexConstants.FIELD_SOURCE_TYPE, IndexConstants.SOURCE_TYPE_ZOHO, Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_DEAL_NAME, chunk.dealName(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_DEAL_STAGE, chunk.stage(), Field.Store.YES));
    document.add(
        new StringField(IndexConstants.FIELD_DEAL_AMOUNT, chunk.amount(), Field.Store.YES));
    document.add(
        new StringField(
            IndexConstants.FIELD_EXPECTED_REVENUE, chunk.expectedRevenue(), Field.Store.YES));
    document.add(
        new StringField(IndexConstants.FIELD_PROBABILITY, chunk.probability(), Field.Store.YES));
    document.add(
        new StringField(IndexConstants.FIELD_CLOSING_DATE, chunk.closingDate(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_DEAL_OWNER, chunk.owner(), Field.Store.YES));
    document.add(
        new StringField(IndexConstants.FIELD_LEAD_SOURCE, chunk.leadSource(), Field.Store.YES));
    document.add(
        new StringField(IndexConstants.FIELD_NEXT_STEP, chunk.nextStep(), Field.Store.YES));
    document.add(
        new StringField(IndexConstants.FIELD_ACCOUNT_NAME, chunk.accountName(), Field.Store.YES));
    document.add(
        new StringField(
            IndexConstants.FIELD_ACCOUNT_DESCRIPTION,
            chunk.accountDescription(),
            Field.Store.YES));
    document.add(
        new StringField(IndexConstants.FIELD_ENTITY_TYPE, chunk.entityType(), Field.Store.YES));
    document.add(
        new StringField(
            IndexConstants.FIELD_QUALITY_SCORE, String.valueOf(chunk.qualityScore()), Field.Store.YES));
    for (String signal : chunk.qualitySignals()) {
      if (signal != null && !signal.isBlank()) {
        document.add(
            new StringField(IndexConstants.FIELD_QUALITY_SIGNALS, signal, Field.Store.YES));
      }
    }
    for (ZohoDealContextChunk.ZohoContactRef contact : chunk.contacts()) {
      document.add(
          new StringField(
              IndexConstants.FIELD_ZOHO_CONTACTS, contact.displayLabel(), Field.Store.YES));
    }
    for (String note : chunk.noteSnippets()) {
      if (note != null && !note.isBlank()) {
        document.add(new StringField(IndexConstants.FIELD_ZOHO_NOTES, note, Field.Store.YES));
      }
    }
    if (chunk.modifiedTime() != null) {
      document.add(
          new StringField(
              IndexConstants.FIELD_TIMESTAMP, chunk.modifiedTime().toString(), Field.Store.YES));
    }
    document.add(
        new StringField(IndexConstants.FIELD_UNIT_TYPE, ChunkUnitType.ZOHO.name(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_START_LINE, "0", Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_END_LINE, "0", Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_PATH, chunk.path(), Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_FILE_NAME, chunk.fileName(), Field.Store.YES));
    document.add(new TextField(IndexConstants.FIELD_CONTENT, chunk.content(), Field.Store.YES));
    if (embedding != null) {
      document.add(
          new KnnFloatVectorField(
              IndexConstants.FIELD_EMBEDDING, embedding, VectorSimilarityFunction.COSINE));
    }
    return document;
  }

  private static void addStructuralMetadata(Document document, StructuralMetadata metadata) {
    if (metadata == null) {
      return;
    }
    for (String imp : metadata.imports()) {
      document.add(new StringField(IndexConstants.FIELD_IMPORTS, imp, Field.Store.YES));
    }
    for (String annotation : metadata.annotations()) {
      document.add(new StringField(IndexConstants.FIELD_ANNOTATIONS, annotation, Field.Store.YES));
    }
    for (CodeSymbol symbol : metadata.symbols()) {
      document.add(new StringField(IndexConstants.FIELD_SYMBOL_IDS, symbol.symbolId(), Field.Store.YES));
      document.add(new StringField(IndexConstants.FIELD_SYMBOL_NAMES, symbol.symbolName(), Field.Store.YES));
      document.add(
          new StringField(
              IndexConstants.FIELD_SYMBOL_TYPES, symbol.symbolType().name(), Field.Store.YES));
    }
    for (String referencedSymbolId : metadata.referencedSymbolIds()) {
      document.add(
          new StringField(
              IndexConstants.FIELD_REFERENCED_SYMBOL_IDS, referencedSymbolId, Field.Store.YES));
    }
  }

  private static long countSymbols(List<CodeChunk> chunks) {
    return chunks.stream().mapToLong(chunk -> chunk.metadata().symbols().size()).sum();
  }

  private static long countImports(List<CodeChunk> chunks) {
    return chunks.stream().mapToLong(chunk -> chunk.metadata().imports().size()).sum();
  }

  private static boolean shouldSkipPath(Path filePath, Path rootDirectory) {
    Path normalizedFile = filePath.toAbsolutePath().normalize();
    Path normalizedRoot = rootDirectory.toAbsolutePath().normalize();
    if (!normalizedFile.startsWith(normalizedRoot)) {
      return true;
    }
    for (Path component : normalizedRoot.relativize(normalizedFile)) {
      if (SKIPPED_DIRECTORY_NAMES.contains(component.toString())) {
        return true;
      }
    }
    return false;
  }

  private static String extractExtension(Path filePath) {
    String fileName = filePath.getFileName().toString();
    int dotIndex = fileName.lastIndexOf('.');
    if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
      return "";
    }
    return fileName.substring(dotIndex + 1).toLowerCase();
  }

  private Path resolveGitHistoryIndexPath(Path workspaceRoot) {
    Optional<Path> direct = IndexPathHolder.directIndexPath();
    if (direct.isPresent()) {
      return direct.get();
    }
    Path root =
        workspaceRoot != null
            ? workspaceRoot
            : Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    return IndexPathResolver.resolveProjectIndexPath(configuredIndexPath, root);
  }
}
