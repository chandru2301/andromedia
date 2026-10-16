package com.andromedia.ingestion.metadata;

import com.andromedia.common.StructuralMetadata;
import com.andromedia.common.FileStructuralMetadata;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public final class MetadataExtractorRegistry {

  private final Map<String, LanguageMetadataExtractor> extractors;

  public MetadataExtractorRegistry() {
    this(
        Map.of(
            "java", new JavaMetadataExtractor(),
            "python", new PythonMetadataExtractor(),
            "typescript", new TypeScriptMetadataExtractor(),
            "javascript", new TypeScriptMetadataExtractor()));
  }

  MetadataExtractorRegistry(Map<String, LanguageMetadataExtractor> extractors) {
    this.extractors = Map.copyOf(extractors);
  }

  public FileStructuralMetadata extractFile(Path path, String content, String language) {
    return extractorFor(language)
        .map(extractor -> extractor.extractFile(path, content, language))
        .orElse(FileStructuralMetadata.empty());
  }

  public StructuralMetadata forChunkSpan(
      FileStructuralMetadata fileMeta, String language, int startLine, int endLine) {
    return extractorFor(language)
        .map(extractor -> extractor.forChunkSpan(fileMeta, startLine, endLine))
        .orElse(StructuralMetadata.empty());
  }

  private Optional<LanguageMetadataExtractor> extractorFor(String language) {
    if (language == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(extractors.get(language.toLowerCase()));
  }
}
