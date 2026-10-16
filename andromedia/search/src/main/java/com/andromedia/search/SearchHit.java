package com.andromedia.search;

import com.andromedia.common.ChunkUnitType;
import com.andromedia.common.RetrievalReference;
import java.util.List;
import java.util.Optional;

public record SearchHit(
    String path,
    String fileName,
    float score,
    String chunkId,
    String className,
    String methodName,
    ChunkUnitType unitType,
    int startLine,
    int endLine,
    Float bm25Score,
    Float semanticScore,
    List<String> imports,
    List<String> symbolIds,
    List<String> symbolNames,
    Optional<String> sourceType,
    Optional<String> commitSha,
    Optional<String> timestamp,
    Optional<String> content,
    Optional<ZohoHitMetadata> zoho) {

  public SearchHit {
    imports = imports == null ? List.of() : List.copyOf(imports);
    symbolIds = symbolIds == null ? List.of() : List.copyOf(symbolIds);
    symbolNames = symbolNames == null ? List.of() : List.copyOf(symbolNames);
    sourceType = sourceType == null ? Optional.empty() : sourceType;
    commitSha = commitSha == null ? Optional.empty() : commitSha;
    timestamp = timestamp == null ? Optional.empty() : timestamp;
    content = content == null ? Optional.empty() : content;
    zoho = zoho == null ? Optional.empty() : zoho;
  }

  public SearchHit(
      String path,
      String fileName,
      float score,
      String chunkId,
      String className,
      String methodName,
      ChunkUnitType unitType,
      int startLine,
      int endLine) {
    this(
        path,
        fileName,
        score,
        chunkId,
        className,
        methodName,
        unitType,
        startLine,
        endLine,
        null,
        null,
        null,
        null,
        null,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  public SearchHit(
      String path,
      String fileName,
      float score,
      String chunkId,
      String className,
      String methodName,
      ChunkUnitType unitType,
      int startLine,
      int endLine,
      Float bm25Score,
      Float semanticScore) {
    this(
        path,
        fileName,
        score,
        chunkId,
        className,
        methodName,
        unitType,
        startLine,
        endLine,
        bm25Score,
        semanticScore,
        null,
        null,
        null,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  public SearchHit(
      String path,
      String fileName,
      float score,
      String chunkId,
      String className,
      String methodName,
      ChunkUnitType unitType,
      int startLine,
      int endLine,
      Float bm25Score,
      Float semanticScore,
      List<String> imports,
      List<String> symbolIds,
      List<String> symbolNames) {
    this(
        path,
        fileName,
        score,
        chunkId,
        className,
        methodName,
        unitType,
        startLine,
        endLine,
        bm25Score,
        semanticScore,
        imports,
        symbolIds,
        symbolNames,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  public SearchHit(
      String path,
      String fileName,
      float score,
      String chunkId,
      String className,
      String methodName,
      ChunkUnitType unitType,
      int startLine,
      int endLine,
      Float bm25Score,
      Float semanticScore,
      List<String> imports,
      List<String> symbolIds,
      List<String> symbolNames,
      Optional<String> sourceType,
      Optional<String> commitSha,
      Optional<String> timestamp) {
    this(
        path,
        fileName,
        score,
        chunkId,
        className,
        methodName,
        unitType,
        startLine,
        endLine,
        bm25Score,
        semanticScore,
        imports,
        symbolIds,
        symbolNames,
        sourceType,
        commitSha,
        timestamp,
        Optional.empty(),
        Optional.empty());
  }

  public SearchHit(
      String path,
      String fileName,
      float score,
      String chunkId,
      String className,
      String methodName,
      ChunkUnitType unitType,
      int startLine,
      int endLine,
      Float bm25Score,
      Float semanticScore,
      List<String> imports,
      List<String> symbolIds,
      List<String> symbolNames,
      Optional<String> sourceType,
      Optional<String> commitSha,
      Optional<String> timestamp,
      Optional<String> content) {
    this(
        path,
        fileName,
        score,
        chunkId,
        className,
        methodName,
        unitType,
        startLine,
        endLine,
        bm25Score,
        semanticScore,
        imports,
        symbolIds,
        symbolNames,
        sourceType,
        commitSha,
        timestamp,
        content,
        Optional.empty());
  }

  public String locationLabel() {
    if (startLine > 0 && endLine > 0) {
      return fileName + ":" + startLine + "-" + endLine;
    }
    return fileName;
  }

  public String symbolLabel() {
    if (methodName != null && !methodName.isBlank()) {
      return methodName;
    }
    if (className != null && !className.isBlank()) {
      return className;
    }
    return "";
  }

  public SearchHit withScore(float newScore) {
    return new SearchHit(
        path,
        fileName,
        newScore,
        chunkId,
        className,
        methodName,
        unitType,
        startLine,
        endLine,
        bm25Score,
        semanticScore,
        imports,
        symbolIds,
        symbolNames,
        sourceType,
        commitSha,
        timestamp,
        content,
        zoho);
  }

  public RetrievalReference toRetrievalReference() {
    return new RetrievalReference(
        chunkId,
        path,
        fileName,
        className,
        methodName,
        unitType,
        startLine,
        endLine,
        sourceType,
        content);
  }
}
