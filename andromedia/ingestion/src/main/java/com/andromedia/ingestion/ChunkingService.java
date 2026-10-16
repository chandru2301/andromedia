package com.andromedia.ingestion;

import com.andromedia.common.CodeChunk;
import java.nio.file.Path;
import java.util.List;

public interface ChunkingService {

  List<CodeChunk> chunkFile(Path filePath, String content, String extension, String language);
}
