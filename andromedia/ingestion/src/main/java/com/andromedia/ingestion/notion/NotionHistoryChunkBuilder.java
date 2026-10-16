package com.andromedia.ingestion.notion;

import com.andromedia.common.ChunkIdGenerator;
import com.andromedia.common.IndexConstants;
import com.andromedia.common.NotionChunk;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class NotionHistoryChunkBuilder {

  static final int MAX_SECTION_BODY_CHARS = 2000;

  private static final DateTimeFormatter DATE_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

  private NotionHistoryChunkBuilder() {}

  public static NotionChunk build(NotionPage page, NotionSection section) {
    String heading = section.heading();
    String slug = NotionChunk.slugify(heading);
    String content = assembleContent(page, section);
    return new NotionChunk(
        ChunkIdGenerator.generateNotionSectionId(page.id(), slug),
        page.id(),
        page.title(),
        heading,
        page.author(),
        page.lastEdited(),
        content,
        IndexConstants.SOURCE_TYPE_NOTION);
  }

  static String assembleContent(NotionPage page, NotionSection section) {
    String date = DATE_FORMAT.format(page.lastEdited());
    String author = page.author() == null || page.author().isBlank() ? "unknown" : page.author();
    String body = truncate(section.body().trim(), MAX_SECTION_BODY_CHARS);

    StringBuilder builder = new StringBuilder();
    builder
        .append("[NOTION] ")
        .append(page.title())
        .append(" · updated ")
        .append(date)
        .append(" · ")
        .append(author)
        .append('\n');
    builder.append("section: ").append(section.heading()).append("\n\n");
    builder.append(body);
    return builder.toString().trim();
  }

  private static String truncate(String value, int maxChars) {
    if (value.length() <= maxChars) {
      return value;
    }
    if (maxChars <= 1) {
      return value.substring(0, maxChars);
    }
    return value.substring(0, maxChars - 1) + "…";
  }
}
