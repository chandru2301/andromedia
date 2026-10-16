package com.andromedia.ingestion.notion;

public record NotionSection(String heading, String body) {

  public NotionSection {
    heading = heading == null || heading.isBlank() ? "intro" : heading.trim();
    body = body == null ? "" : body;
  }
}
