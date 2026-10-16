package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.IndexPathResolver;
import com.andromedia.indexing.IndexingRequest;
import com.andromedia.indexing.LuceneIndexingService;
import com.andromedia.ingestion.SemanticBoundaryChunker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UsagesLookupServiceTest {

  @TempDir Path tempDir;

  @Test
  void findsDefinitionsAndReferencesForSimpleName() throws Exception {
    indexSampleProject();
    UsageLookupResult result = lookup("AnomalyEvent");

    assertFalse(result.resolvedSymbolIds().isEmpty());
    assertTrue(result.resolvedSymbolIds().contains("java:com.foo.AnomalyEvent"));
    assertFalse(result.definitions().isEmpty());
    assertTrue(
        result.references().stream()
            .anyMatch(site -> site.path().endsWith("Consumer.java")));
  }

  @Test
  void returnsMethodHintsWhenClassMethodQueryMisses() throws Exception {
    indexSampleProject();
    UsageLookupResult result = lookup("Consumer#onMessage");

    assertTrue(result.resolvedSymbolIds().isEmpty());
    assertFalse(result.methodHints().isEmpty());
    assertTrue(
        result.methodHints().stream()
            .anyMatch(hint -> "handle".equals(hint.symbolName())));
  }

  @Test
  void resolvesClassMethodQueryWhenMethodExists() throws Exception {
    indexSampleProject();
    UsageLookupResult result = lookup("Consumer#handle");

    assertFalse(result.resolvedSymbolIds().isEmpty());
    assertTrue(result.resolvedSymbolIds().contains("java:com.bar.Consumer#handle"));
  }

  private void indexSampleProject() throws Exception {
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
    indexingService.indexPath(new IndexingRequest(projectRoot, Set.of("java"), true, false, false));
    Files.createDirectories(IndexPathResolver.workspaceMarkerPath(projectRoot));
  }

  private UsageLookupResult lookup(String symbolQuery) {
    Path projectRoot = tempDir.resolve("project");
    Path indexRoot = tempDir.resolve("index");
    String previousUserDir = System.getProperty("user.dir");
    System.setProperty("user.dir", projectRoot.toAbsolutePath().toString());
    try {
      UsagesLookupService lookupService = UsagesLookupService.forTest(indexRoot.toString());
      return lookupService.lookup(new UsageLookupRequest(symbolQuery, 20));
    } finally {
      System.setProperty("user.dir", previousUserDir);
    }
  }
}
