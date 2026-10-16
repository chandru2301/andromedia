package com.andromedia.ingestion.notion;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotionHistoryChunkBuilderTest {

  @Test
  void assemblesNotionPrefixAndCapsBody() {
    NotionPage page =
        new NotionPage(
            "page-1",
            "Auth Architecture",
            false,
            Instant.parse("2026-05-18T12:00:00Z"),
            "chandru");
    String longBody = "x".repeat(2500);
    NotionSection section = new NotionSection("Why JWT over Sessions", longBody);

    var chunk = NotionHistoryChunkBuilder.build(page, section);
    assertTrue(chunk.content().startsWith("[NOTION] Auth Architecture · updated 2026-05-18 · chandru"));
    assertTrue(chunk.content().contains("section: Why JWT over Sessions"));
    assertTrue(chunk.content().length() < 2500);
    assertTrue(chunk.sourceType().equals("NOTION"));
  }
}
