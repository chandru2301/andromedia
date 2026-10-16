package com.andromedia.ingestion.metadata;

import com.andromedia.common.FileStructuralMetadata;
import com.andromedia.common.StructuralMetadata;
import java.nio.file.Path;

public interface LanguageMetadataExtractor {

  FileStructuralMetadata extractFile(Path path, String content, String language);

  StructuralMetadata forChunkSpan(FileStructuralMetadata file, int startLine, int endLine);
}
