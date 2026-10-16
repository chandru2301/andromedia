package com.andromedia.ingestion.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.CodeSymbol;
import com.andromedia.common.FileStructuralMetadata;
import com.andromedia.common.SymbolType;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JavaMetadataExtractorTest {

  private final JavaMetadataExtractor extractor = new JavaMetadataExtractor();

  @Test
  void storesImportsOnMetadataOnlyAndAssignsSymbolIds() {
    String source =
        """
        package com.foo;

        import java.util.List;
        import org.springframework.stereotype.Service;

        @Service
        public class AnomalyEvent {
          public void handle() {
          }
        }
        """;

    FileStructuralMetadata metadata =
        extractor.extractFile(Path.of("AnomalyEvent.java"), source, "java");

    assertTrue(metadata.imports().contains("java.util.List"));
    assertTrue(metadata.imports().contains("org.springframework.stereotype.Service"));
    assertTrue(metadata.annotations().contains("Service"));
    assertTrue(
        metadata.symbols().stream()
            .anyMatch(
                symbol ->
                    "java:com.foo.AnomalyEvent".equals(symbol.symbolId())
                        && symbol.symbolType() == SymbolType.CLASS));
    assertTrue(
        metadata.symbols().stream()
            .anyMatch(
                symbol ->
                    "java:com.foo.AnomalyEvent#handle".equals(symbol.symbolId())
                        && symbol.symbolType() == SymbolType.METHOD));

    for (CodeSymbol symbol : metadata.symbols()) {
      assertFalse(symbol.symbolId().isBlank());
    }
  }

  @Test
  void extractsAutowiredFields() {
    String source =
        """
        package com.foo;

        import org.springframework.beans.factory.annotation.Autowired;
        import com.foo.repo.UserRepository;

        public class UserService {
          @Autowired
          private UserRepository userRepository;

          public void save() {
          }
        }
        """;

    FileStructuralMetadata metadata =
        extractor.extractFile(Path.of("UserService.java"), source, "java");

    assertTrue(
        metadata.symbols().stream()
            .anyMatch(
                symbol ->
                    "java:com.foo.UserService#userRepository".equals(symbol.symbolId())
                        && symbol.symbolType() == SymbolType.FIELD));
  }
}
