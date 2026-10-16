package com.andromedia.common;

import java.time.Instant;

public record NotionChunk(
    String chunkId,
    String pageId,
    String pageTitle,
    String sectionHeading,
    String author,
    Instant lastEdited,
    String content,
    String sourceType) {

  public NotionChunk {
    pageTitle = pageTitle == null ? "" : pageTitle;
    sectionHeading = sectionHeading == null ? "" : sectionHeading;
    author = author == null ? "" : author;
    content = content == null ? "" : content;
    sourceType =
        sourceType == null || sourceType.isBlank() ? IndexConstants.SOURCE_TYPE_NOTION : sourceType;
  }

  public String path() {
    String section =
        sectionHeading == null || sectionHeading.isBlank() ? "intro" : slugify(sectionHeading);
    return "notion://" + pageId + "/" + section;
  }

  public String fileName() {
    return pageTitle == null || pageTitle.isBlank() ? pageId : pageTitle;
  }

  public static String slugify(String heading) {
    if (heading == null || heading.isBlank()) {
      return "intro";
    }
    String slug =
        heading
            .trim()
            .toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
    return slug.isBlank() ? "intro" : slug;
  }
}
