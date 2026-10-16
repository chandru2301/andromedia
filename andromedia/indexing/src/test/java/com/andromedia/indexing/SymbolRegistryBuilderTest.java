package com.andromedia.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.ProjectSymbolRegistry;
import com.andromedia.ingestion.metadata.MetadataExtractorRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymbolRegistryBuilderTest {

  @TempDir Path tempDir;

  @Test
  void buildsRegistryWithSymbolsFromMultipleJavaFiles() throws Exception {
    Path project = tempDir.resolve("project");
    Path pkgFoo = project.resolve("com/foo");
    Path pkgBar = project.resolve("com/bar");
    Files.createDirectories(pkgFoo);
    Files.createDirectories(pkgBar);
    Files.writeString(
        pkgFoo.resolve("AnomalyEvent.java"),
        """
        package com.foo;

        public class AnomalyEvent {
        }
        """);
    Files.writeString(
        pkgBar.resolve("Consumer.java"),
        """
        package com.bar;

        import com.foo.AnomalyEvent;

        public class Consumer {
          public void handle(AnomalyEvent event) {
          }
        }
        """);

    SymbolRegistryBuilder builder = new SymbolRegistryBuilder(new MetadataExtractorRegistry());
    ProjectSymbolRegistry registry = builder.build(project, Set.of("java"));

    assertTrue(registry.findBySymbolId("java:com.foo.AnomalyEvent").isPresent());
    assertEquals(1, registry.findBySimpleName("AnomalyEvent").size());
    assertTrue(
        registry.findBySimpleName("Consumer").stream()
            .anyMatch(entry -> entry.symbolId().equals("java:com.bar.Consumer")));
  }
}
