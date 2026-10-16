package com.andromedia.ingestion.linear;

public record LinearComment(String author, String body) {

  public LinearComment {
    author = author == null ? "" : author;
    body = body == null ? "" : body;
  }
}
