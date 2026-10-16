package com.andromedia.ingestion.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.FileStructuralMetadata;
import com.andromedia.common.SymbolType;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PythonMetadataExtractorTest {

  private final PythonMetadataExtractor extractor = new PythonMetadataExtractor();

  @Test
  void assignsPythonSymbolIdsWithoutDuplicatingImportsOnSymbols() {
    String source =
        """
        import json
        from typing import List

        class AnomalyHandler:
            def handle(self, event):
                return event

        def process_event(data):
            return data
        """;

    FileStructuralMetadata metadata =
        extractor.extractFile(Path.of("sample_module.py"), source, "python");

    assertTrue(metadata.imports().stream().anyMatch(value -> value.startsWith("import json")));
    assertTrue(
        metadata.imports().stream().anyMatch(value -> value.startsWith("from typing import")));
    assertTrue(
        metadata.symbols().stream()
            .anyMatch(
                symbol ->
                    "python:sample_module.process_event".equals(symbol.symbolId())
                        && symbol.symbolType() == SymbolType.FUNCTION));
    assertTrue(
        metadata.symbols().stream()
            .anyMatch(
                symbol ->
                    "python:sample_module.AnomalyHandler.handle".equals(symbol.symbolId())
                        && symbol.symbolType() == SymbolType.METHOD));
    assertEquals("sample_module", PythonMetadataExtractor.moduleNameFromPath(Path.of("sample_module.py")));
  }
}
