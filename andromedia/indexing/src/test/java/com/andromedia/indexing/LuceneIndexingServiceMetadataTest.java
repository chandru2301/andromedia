package com.andromedia.indexing;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.IndexConstants;
import com.andromedia.ingestion.SemanticBoundaryChunker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LuceneIndexingServiceMetadataTest {

  @TempDir Path tempDir;

  @Test
  void persistsStructuralMetadataFieldsInLuceneDocuments() throws IOException {
    Path projectRoot = tempDir.resolve("project");
    Path indexRoot = tempDir.resolve("index");
    Files.createDirectories(projectRoot);

    Path javaFile = projectRoot.resolve("AnomalyEvent.java");
    Files.writeString(
        javaFile,
        """
        package com.foo;

        import java.util.List;

        public class AnomalyEvent {
          public void handle() {
          }
        }
        """);

    LuceneIndexingService indexingService =
        new LuceneIndexingService(
            indexRoot.toString(), new SemanticBoundaryChunker(), new com.andromedia.llm.StubEmbeddingService(8));

    IndexingStats stats =
        indexingService.indexPath(
            new IndexingRequest(projectRoot, Set.of("java"), true, false, false));

    assertTrue(stats.chunksIndexed() > 0);
    assertTrue(stats.symbolsExtracted() > 0);
    assertTrue(stats.importsExtracted() > 0);

    Path projectIndexPath =
        com.andromedia.common.IndexPathResolver.resolveProjectIndexPath(
            indexRoot.toString(), projectRoot);
    try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(projectIndexPath))) {
      IndexSearcher searcher = new IndexSearcher(reader);
      TopDocs topDocs =
          searcher.search(new TermQuery(new Term(IndexConstants.FIELD_CLASS_NAME, "AnomalyEvent")), 10);
      assertTrue(topDocs.totalHits.value > 0);

      var document = searcher.storedFields().document(topDocs.scoreDocs[0].doc);
      assertTrue(Arrays.asList(document.getValues(IndexConstants.FIELD_IMPORTS)).contains("java.util.List"));
      assertTrue(
          Arrays.stream(document.getValues(IndexConstants.FIELD_SYMBOL_IDS))
              .anyMatch(value -> value.startsWith("java:com.foo.")));
      assertTrue(document.getValues(IndexConstants.FIELD_SYMBOL_NAMES).length > 0);
      assertTrue(document.getValues(IndexConstants.FIELD_SYMBOL_TYPES).length > 0);
    }
  }

  @Test
  void persistsCrossFileReferencedSymbolIds() throws IOException {
    Path projectRoot = tempDir.resolve("project");
    Path indexRoot = tempDir.resolve("index");
    Path pkgFoo = projectRoot.resolve("com/foo");
    Path pkgBar = projectRoot.resolve("com/bar");
    Files.createDirectories(pkgFoo);
    Files.createDirectories(pkgBar);
    Files.writeString(
        pkgFoo.resolve("AnomalyEvent.java"),
        """
        package com.foo;

        public class AnomalyEvent {
          public String getId() {
            return "id";
          }
        }
        """);
    Files.writeString(
        pkgBar.resolve("Consumer.java"),
        """
        package com.bar;

        import com.foo.AnomalyEvent;

        public class Consumer {
          public void handle(AnomalyEvent event) {
            event.getId();
          }
        }
        """);

    LuceneIndexingService indexingService =
        new LuceneIndexingService(
            indexRoot.toString(), new SemanticBoundaryChunker(), new com.andromedia.llm.StubEmbeddingService(8));

    IndexingStats stats =
        indexingService.indexPath(
            new IndexingRequest(projectRoot, Set.of("java"), true, false, false));

    assertTrue(stats.referencesExtracted() > 0);

    Path projectIndexPath =
        com.andromedia.common.IndexPathResolver.resolveProjectIndexPath(
            indexRoot.toString(), projectRoot);
    try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(projectIndexPath))) {
      IndexSearcher searcher = new IndexSearcher(reader);
      TopDocs topDocs =
          searcher.search(new TermQuery(new Term(IndexConstants.FIELD_CLASS_NAME, "Consumer")), 10);
      assertTrue(topDocs.totalHits.value > 0);
      var document = searcher.storedFields().document(topDocs.scoreDocs[0].doc);
      assertTrue(
          Arrays.stream(document.getValues(IndexConstants.FIELD_REFERENCED_SYMBOL_IDS))
              .anyMatch(value -> value.equals("java:com.foo.AnomalyEvent")));
    }
  }

  /**
   * Regression: `toDocument(CodeChunk, ...)` was the only overload that never
   * wrote FIELD_SOURCE_TYPE. Search hits therefore came back with a null source
   * type — the UI could not label them, and a source_types=["CODE"] filter
   * excluded every code chunk, because the filter requires the field to match.
   *
   * The bug was invisible to symbol/metadata tests: everything else about the
   * document was correct.
   */
  @Test
  void codeChunksRecordTheirSourceType() throws IOException {
    Path projectRoot = tempDir.resolve("srctypes");
    Path indexRoot = tempDir.resolve("index-srctypes");
    Files.createDirectories(projectRoot);
    Files.writeString(
        projectRoot.resolve("Service.java"),
        """
        package demo;

        public class Service {
          public void run() {
          }
        }
        """);

    LuceneIndexingService indexingService =
        new LuceneIndexingService(
            indexRoot.toString(),
            new SemanticBoundaryChunker(),
            new com.andromedia.llm.StubEmbeddingService(8));

    indexingService.indexPath(new IndexingRequest(projectRoot, Set.of("java"), true, false, false));

    Path projectIndexPath =
        com.andromedia.common.IndexPathResolver.resolveProjectIndexPath(
            indexRoot.toString(), projectRoot);
    try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(projectIndexPath))) {
      IndexSearcher searcher = new IndexSearcher(reader);
      TopDocs topDocs =
          searcher.search(
              new TermQuery(
                  new Term(IndexConstants.FIELD_SOURCE_TYPE, IndexConstants.SOURCE_TYPE_CODE)),
              10);
      assertTrue(
          topDocs.totalHits.value > 0,
          "code chunks must be findable by source type CODE");
    }
  }

  /**
   * Build output and dependency trees must not be indexed.
   *
   * Indexing a project with a dist/ folder put minified bundles into the index
   * alongside real source, where they outranked it in search — 552 chunks for
   * andro-UI where only 8 were source. The skip list covered
   * target/build/node_modules but not dist, which Vite and Next both emit.
   *
   * Asserts on the indexed *path* rather than a derived field: Java chunks carry
   * a null className when the class-level path does not fire, so a class-name
   * assertion passes even when the file was indexed.
   */
  @Test
  void skipsBuildOutputAndDependencyDirectories() throws IOException {
    Path projectRoot = tempDir.resolve("withdist");
    Path indexRoot = tempDir.resolve("index-dist");
    Files.createDirectories(projectRoot.resolve("src"));
    Files.createDirectories(projectRoot.resolve("dist/assets"));
    Files.createDirectories(projectRoot.resolve("node_modules/pkg"));

    Files.writeString(
        projectRoot.resolve("src/App.java"),
        """
        package demo;

        public class App {
        }
        """);
    Files.writeString(
        projectRoot.resolve("dist/Bundle.java"),
        """
        package dist;

        public class Bundle {
        }
        """);
    Files.writeString(
        projectRoot.resolve("dist/assets/Chunk.java"),
        """
        package dist;

        public class Chunk {
        }
        """);
    Files.writeString(
        projectRoot.resolve("node_modules/pkg/Dep.java"),
        """
        package dep;

        public class Dep {
        }
        """);

    LuceneIndexingService indexingService =
        new LuceneIndexingService(
            indexRoot.toString(),
            new SemanticBoundaryChunker(),
            new com.andromedia.llm.StubEmbeddingService(8));

    indexingService.indexPath(new IndexingRequest(projectRoot, Set.of("java"), true, false, false));

    Path projectIndexPath =
        com.andromedia.common.IndexPathResolver.resolveProjectIndexPath(
            indexRoot.toString(), projectRoot);
    try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(projectIndexPath))) {
      List<String> indexedPaths = new ArrayList<>();
      for (int i = 0; i < reader.maxDoc(); i++) {
        indexedPaths.add(reader.storedFields().document(i).get(IndexConstants.FIELD_PATH));
      }

      assertTrue(
          indexedPaths.stream().anyMatch(p -> p != null && p.endsWith("src/App.java")),
          "real source should be indexed, got: " + indexedPaths);

      List<String> leaked =
          indexedPaths.stream()
              .filter(p -> p != null && (p.contains("/dist/") || p.contains("/node_modules/")))
              .toList();
      assertTrue(leaked.isEmpty(), "build/dependency output was indexed: " + leaked);
    }
  }
}
