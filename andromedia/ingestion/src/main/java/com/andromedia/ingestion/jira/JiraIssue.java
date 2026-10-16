package com.andromedia.ingestion.jira;

import java.time.Instant;
import java.util.List;

public record JiraIssue(
    String key,
    String summary,
    String description,
    String issueType,
    String projectKey,
    String priority,
    String status,
    String author,
    Instant updated,
    Instant created,
    List<JiraComment> comments) {

  public JiraIssue {
    key = key == null ? "" : key;
    summary = summary == null ? "" : summary;
    description = description == null ? "" : description;
    issueType = issueType == null ? "" : issueType;
    projectKey = projectKey == null ? "" : projectKey;
    priority = priority == null ? "" : priority;
    status = status == null ? "" : status;
    author = author == null ? "" : author;
    comments = comments == null ? List.of() : List.copyOf(comments);
  }
}
