package com.andromedia.ingestion.jira;

import java.time.Instant;

public record JiraComment(String author, Instant created, String body) {

  public JiraComment {
    author = author == null ? "" : author;
    body = body == null ? "" : body;
  }
}
