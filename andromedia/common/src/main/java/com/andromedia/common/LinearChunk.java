package com.andromedia.common;

import java.time.Instant;

public record LinearChunk(
    String chunkId,
    String identifier,
    String title,
    String team,
    String cycle,
    String state,
    String author,
    Instant updated,
    String content,
    String sourceType) {

  public LinearChunk {
    identifier = identifier == null ? "" : identifier;
    title = title == null ? "" : title;
    team = team == null ? "" : team;
    cycle = cycle == null ? "" : cycle;
    state = state == null ? "" : state;
    author = author == null ? "" : author;
    content = content == null ? "" : content;
    sourceType =
        sourceType == null || sourceType.isBlank()
            ? IndexConstants.SOURCE_TYPE_LINEAR
            : sourceType;
  }

  public String path() {
    return "linear://" + (identifier.isBlank() ? "unknown" : identifier);
  }

  public String fileName() {
    return identifier.isBlank() ? "linear-issue" : identifier;
  }
}
