package com.andromedia.ingestion;

import com.andromedia.common.ChunkConstants;
import com.andromedia.common.ChunkIdGenerator;
import com.andromedia.common.ChunkUnitType;
import com.andromedia.common.CodeChunk;
import com.andromedia.common.CodeSymbol;
import com.andromedia.common.FileStructuralMetadata;
import com.andromedia.common.StructuralMetadata;
import com.andromedia.common.SymbolType;
import com.andromedia.ingestion.metadata.MetadataExtractorRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SemanticBoundaryChunker implements ChunkingService {

  private static final Pattern CLASS_BODY_PATTERN =
      Pattern.compile(
          "(?s)\\b(?:class|interface|enum|record)\\s+\\w+\\b[^{]*\\{([\\s\\S]*)\\}\\s*$");

  private final MetadataExtractorRegistry metadataExtractorRegistry;

  public SemanticBoundaryChunker() {
    this(new MetadataExtractorRegistry());
  }

  public SemanticBoundaryChunker(MetadataExtractorRegistry metadataExtractorRegistry) {
    this.metadataExtractorRegistry = metadataExtractorRegistry;
  }

  /**
   * Languages chunked per symbol rather than per file.
   *
   * <p>Java has its own method/class path below; these are the tree-sitter
   * languages whose extractor records accurate symbol spans. Python is excluded
   * deliberately: it is file-level by design and its reference resolver relies on
   * that.
   */
  private static final Set<String> SYMBOL_CHUNKED_LANGUAGES = Set.of("typescript", "javascript");

  @Override
  public List<CodeChunk> chunkFile(Path filePath, String content, String extension, String language) {
    String absolutePath = filePath.toAbsolutePath().normalize().toString();
    String fileName = filePath.getFileName().toString();
    FileStructuralMetadata fileMetadata =
        metadataExtractorRegistry.extractFile(filePath, content, language);

    if ("java".equalsIgnoreCase(language)) {
      List<CodeChunk> methodChunks =
          chunkJavaMethods(absolutePath, fileName, extension, language, content, fileMetadata);
      if (!methodChunks.isEmpty()) {
        return methodChunks;
      }
      Optional<CodeChunk> classChunk =
          chunkJavaClass(absolutePath, fileName, extension, language, content, fileMetadata);
      if (classChunk.isPresent()) {
        return List.of(classChunk.get());
      }
    } else if (SYMBOL_CHUNKED_LANGUAGES.contains(language == null ? "" : language.toLowerCase())) {
      // TypeScript/JavaScript: use the symbols the metadata extractor produced.
      // Without this they fell through to a single whole-file chunk, so every
      // symbol reported the file's full line span — `usages shortPath` would say
      // "file.tsx:1-358" for a function declared at line 51, making the line
      // numbers useless.
      //
      // Scoped to these languages on purpose. Python is deliberately file-level
      // (see SemanticBoundaryChunkerTest.attachesPythonMetadataToFileChunks) and
      // has a reference resolver that depends on that layout, so changing it here
      // would be an unverified behaviour change.
      List<CodeChunk> symbolChunks =
          chunkBySymbol(absolutePath, fileName, extension, language, content, fileMetadata);
      if (!symbolChunks.isEmpty()) {
        return symbolChunks;
      }
    }

    return List.of(buildFileChunk(absolutePath, fileName, extension, language, content, fileMetadata));
  }

  /**
   * One chunk per declared symbol, using the spans the extractor recorded.
   *
   * <p>Only METHOD and FUNCTION symbols become chunks. Type declarations are
   * skipped because their spans contain their members, which would duplicate the
   * same source lines across several chunks and skew ranking; a file chunk is
   * emitted instead when nothing else qualifies.
   */
  private List<CodeChunk> chunkBySymbol(
      String absolutePath,
      String fileName,
      String extension,
      String language,
      String content,
      FileStructuralMetadata fileMetadata) {
    List<String> lines = content.lines().toList();
    List<CodeChunk> chunks = new ArrayList<>();

    for (CodeSymbol symbol : fileMetadata.symbols()) {
      if (symbol.symbolType() != SymbolType.METHOD && symbol.symbolType() != SymbolType.FUNCTION) {
        continue;
      }
      int startLine = symbol.lineStart();
      int endLine = symbol.lineEnd();
      if (startLine <= 0 || endLine < startLine || startLine > lines.size()) {
        continue;
      }
      int clampedEnd = Math.min(endLine, lines.size());
      String body = String.join("\n", lines.subList(startLine - 1, clampedEnd));
      if (body.isBlank()) {
        continue;
      }
      chunks.add(
          buildChunk(
              absolutePath,
              fileName,
              extension,
              language,
              symbol.containerName(),
              symbol.symbolName(),
              ChunkUnitType.METHOD,
              startLine,
              clampedEnd,
              body,
              fileMetadata));
    }
    return chunks;
  }

  private List<CodeChunk> chunkJavaMethods(
      String absolutePath,
      String fileName,
      String extension,
      String language,
      String content,
      FileStructuralMetadata fileMetadata) {
    Optional<String> className = JavaMethodExtractor.findClassName(content);
    List<JavaMethodExtractor.MethodSpan> methods = JavaMethodExtractor.extractMethods(content);
    if (methods.isEmpty()) {
      return List.of();
    }

    String resolvedClassName = className.orElse("");
    List<CodeChunk> chunks = new ArrayList<>();
    for (JavaMethodExtractor.MethodSpan method : methods) {
      chunks.add(
          buildChunk(
              absolutePath,
              fileName,
              extension,
              language,
              resolvedClassName,
              method.methodName(),
              ChunkUnitType.METHOD,
              method.startLine(),
              method.endLine(),
              method.content(),
              fileMetadata));
    }
    return chunks;
  }

  private Optional<CodeChunk> chunkJavaClass(
      String absolutePath,
      String fileName,
      String extension,
      String language,
      String content,
      FileStructuralMetadata fileMetadata) {
    Optional<String> className = JavaMethodExtractor.findClassName(content);
    if (className.isEmpty()) {
      return Optional.empty();
    }

    Matcher matcher = CLASS_BODY_PATTERN.matcher(content.trim());
    if (!matcher.find()) {
      return Optional.empty();
    }

    int classStartLine = findLineNumber(content, matcher.start(1));
    int classEndLine = findLineNumber(content, matcher.end(1));
    String classBody = matcher.group(1).trim();
    if (classBody.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(
        buildChunk(
            absolutePath,
            fileName,
            extension,
            language,
            className.get(),
            null,
            ChunkUnitType.CLASS,
            classStartLine,
            classEndLine,
            classBody,
            fileMetadata));
  }

  private CodeChunk buildFileChunk(
      String absolutePath,
      String fileName,
      String extension,
      String language,
      String content,
      FileStructuralMetadata fileMetadata) {
    int lineCount = countLines(content);
    return buildChunk(
        absolutePath,
        fileName,
        extension,
        language,
        null,
        null,
        ChunkUnitType.FILE,
        1,
        lineCount,
        content,
        fileMetadata);
  }

  private CodeChunk buildChunk(
      String absolutePath,
      String fileName,
      String extension,
      String language,
      String className,
      String methodName,
      ChunkUnitType unitType,
      int startLine,
      int endLine,
      String content,
      FileStructuralMetadata fileMetadata) {
    int lineCount = Math.max(1, endLine - startLine + 1);
    boolean oversized = lineCount > ChunkConstants.OVERSIZED_LINE_THRESHOLD;
    String chunkId =
        ChunkIdGenerator.generate(absolutePath, unitType, className, methodName, startLine);
    StructuralMetadata metadata =
        metadataExtractorRegistry.forChunkSpan(fileMetadata, language, startLine, endLine);
    return new CodeChunk(
        chunkId,
        content,
        absolutePath,
        fileName,
        extension,
        language,
        emptyToNull(className),
        emptyToNull(methodName),
        unitType,
        startLine,
        endLine,
        oversized,
        metadata);
  }

  private static String emptyToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value;
  }

  private static int countLines(String content) {
    if (content.isEmpty()) {
      return 1;
    }
    return (int) content.lines().count();
  }

  private static int findLineNumber(String content, int charIndex) {
    int line = 1;
    for (int i = 0; i < charIndex && i < content.length(); i++) {
      if (content.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }
}
