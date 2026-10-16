package com.andromedia.ingestion.git;

import com.andromedia.common.ChangedSymbol;
import com.andromedia.common.ChunkIdGenerator;
import com.andromedia.common.CodeSymbol;
import com.andromedia.common.GitChangeEvent;
import com.andromedia.common.GitHistoryChunk;
import com.andromedia.common.PullRequestMetadata;
import com.andromedia.common.StructuralMetadata;
import com.andromedia.common.SymbolType;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public final class GitHistoryChunkBuilder {

  private static final int MAX_FILES = 10;
  private static final int MAX_SYMBOLS = 10;
  private static final int MAX_BODY_CHARS = 500;
  private static final int MAX_DESCRIPTION_CHARS = 300;
  private static final int MAX_EMBEDDING_CHARS = 30_000;

  private static final DateTimeFormatter ISO_FORMAT =
      DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

  private GitHistoryChunkBuilder() {}

  public static GitHistoryChunk build(GitChangeEvent event) {
    String chunkId = ChunkIdGenerator.generateGitEventId(event.commitSha());
    String content = assembleContent(event);
    List<String> filePaths =
        event.changedFiles().stream().map(file -> file.path()).distinct().toList();
    StructuralMetadata metadata = toStructuralMetadata(event.changedSymbols());
    return new GitHistoryChunk(
        chunkId,
        content,
        event.commitSha(),
        event.committedAt(),
        event.author(),
        filePaths,
        metadata);
  }

  static String assembleContent(GitChangeEvent event) {
    String shaShort = event.commitSha().length() > 8 ? event.commitSha().substring(0, 8) : event.commitSha();
    String isoDate = ISO_FORMAT.format(event.committedAt());
    String author = nullToEmpty(event.author());
    String subject = nullToEmpty(event.subject());

    String files = formatList(
        event.changedFiles().stream().map(file -> file.path()).distinct().toList(), MAX_FILES);
    String symbols =
        formatList(
            event.changedSymbols().stream().map(ChangedSymbol::symbolName).distinct().toList(),
            MAX_SYMBOLS);

    String body = truncate(nullToEmpty(event.body()), MAX_BODY_CHARS);
    Optional<PullRequestMetadata> pullRequest = event.pullRequest();

    StringBuilder builder = new StringBuilder();
    builder.append("[GIT_COMMIT] ").append(shaShort).append(" · ").append(isoDate).append(" · ").append(author);
    builder.append('\n').append(subject).append("\n\n");
    builder.append("Changed: ").append(files).append('\n');
    builder.append("Symbols: ").append(symbols).append("\n\n");

    if (!body.isBlank()) {
      builder.append(body);
    }

    if (pullRequest.isPresent()) {
      PullRequestMetadata pr = pullRequest.get();
      if (!builder.isEmpty() && builder.charAt(builder.length() - 1) != '\n') {
        builder.append('\n');
      }
      builder.append("\nPR #").append(pr.number()).append(": ").append(nullToEmpty(pr.title()));
      String description = truncate(nullToEmpty(pr.description()), MAX_DESCRIPTION_CHARS);
      if (!description.isBlank()) {
        builder.append('\n').append(description);
      }
    }

    return enforceEmbeddingLimit(builder.toString(), body, pullRequest.map(PullRequestMetadata::description).orElse(""));
  }

  private static String enforceEmbeddingLimit(String content, String body, String description) {
    if (content.length() <= MAX_EMBEDDING_CHARS) {
      return content;
    }

    String header = content;
    int prIndex = content.indexOf("\nPR #");
    if (prIndex >= 0) {
      header = content.substring(0, prIndex);
    }

    String prSection = prIndex >= 0 ? content.substring(prIndex) : "";
    String trimmedDescription = truncate(description, Math.min(MAX_DESCRIPTION_CHARS, 100));
    String trimmedBody = truncate(body, Math.min(MAX_BODY_CHARS, 200));

    String rebuilt = rebuildWithTruncatedTail(header, trimmedBody, prSection, trimmedDescription);
    if (rebuilt.length() <= MAX_EMBEDDING_CHARS) {
      return rebuilt;
    }

    return truncate(rebuilt, MAX_EMBEDDING_CHARS);
  }

  private static String rebuildWithTruncatedTail(
      String header, String body, String prSection, String description) {
    StringBuilder builder = new StringBuilder(header.trim());
    if (!body.isBlank()) {
      builder.append("\n\n").append(body);
    }
    if (!prSection.isBlank()) {
      int titleEnd = prSection.indexOf('\n');
      String prTitleLine = titleEnd >= 0 ? prSection.substring(0, titleEnd) : prSection;
      builder.append('\n').append(prTitleLine);
      if (!description.isBlank()) {
        builder.append('\n').append(description);
      }
    }
    return builder.toString();
  }

  private static StructuralMetadata toStructuralMetadata(List<ChangedSymbol> changedSymbols) {
    List<CodeSymbol> symbols = new ArrayList<>();
    for (ChangedSymbol changed : changedSymbols) {
      symbols.add(
          new CodeSymbol(
              changed.symbolId(),
              changed.symbolName(),
              SymbolType.METHOD,
              changed.filePath(),
              changed.lineStart(),
              changed.lineEnd(),
              "git",
              null));
    }
    return new StructuralMetadata(List.of(), List.of(), symbols, List.of());
  }

  private static String formatList(List<String> values, int maxItems) {
    if (values.isEmpty()) {
      return "(none)";
    }
    if (values.size() <= maxItems) {
      return values.stream().collect(Collectors.joining(", "));
    }
    return values.stream().limit(maxItems).collect(Collectors.joining(", ")) + ", …";
  }

  private static String truncate(String value, int maxChars) {
    if (value == null || value.length() <= maxChars) {
      return value == null ? "" : value;
    }
    if (maxChars <= 1) {
      return value.substring(0, maxChars);
    }
    return value.substring(0, maxChars - 1) + "…";
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
