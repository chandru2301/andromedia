package com.andromedia.ingestion.notion;

import java.time.Instant;

public record NotionPage(
    String id, String title, boolean archived, Instant lastEdited, String author) {

  public NotionPage {
    id = id == null ? "" : id;
    title = title == null ? "" : title;
    author = author == null ? "" : author;
  }
}
