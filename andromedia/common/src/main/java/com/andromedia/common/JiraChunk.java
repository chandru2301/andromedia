package com.andromedia.common;

import java.time.Instant;

public record JiraChunk(
    String chunkId,
    String issueKey,
    String issueType,
    String projectKey,
    String priority,
    String status,
    String author,
    Instant updated,
    String content,
    String sourceType) {

  public JiraChunk {
    issueKey = issueKey == null ? "" : issueKey;
    issueType = issueType == null ? "" : issueType;
    projectKey = projectKey == null ? "" : projectKey;
    priority = priority == null ? "" : priority;
    status = status == null ? "" : status;
    author = author == null ? "" : author;
    content = content == null ? "" : content;
    sourceType =
        sourceType == null || sourceType.isBlank() ? IndexConstants.SOURCE_TYPE_JIRA : sourceType;
  }

  public String path() {
    return "jira://" + (issueKey.isBlank() ? "unknown" : issueKey);
  }

  public String fileName() {
    return issueKey.isBlank() ? "jira-issue" : issueKey;
  }
}
