package com.andromedia.ingestion.notion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotionPageChunkerTest {

  @Test
  void splitsOnHeadingsAndKeepsBody() {
    List<NotionBlock> blocks =
        List.of(
            new NotionBlock("1", "heading_1", false, "Why JWT"),
            new NotionBlock("2", "paragraph", false, "Session store OOM"),
            new NotionBlock("3", "bulleted_list_item", false, "15min expiry"),
            new NotionBlock("4", "heading_2", false, "Token Config"),
            new NotionBlock("5", "paragraph", false, "Access token rotates"));

    List<NotionSection> sections = NotionPageChunker.chunk(blocks);
    assertEquals(2, sections.size());
    assertEquals("Why JWT", sections.get(0).heading());
    assertTrue(sections.get(0).body().contains("Session store OOM"));
    assertTrue(sections.get(0).body().contains("15min expiry"));
    assertEquals("Token Config", sections.get(1).heading());
    assertTrue(sections.get(1).body().contains("Access token rotates"));
  }

  @Test
  void usesIntroForContentBeforeFirstHeading() {
    List<NotionBlock> blocks =
        List.of(
            new NotionBlock("1", "paragraph", false, "Preamble"),
            new NotionBlock("2", "heading_1", false, "Main"),
            new NotionBlock("3", "paragraph", false, "Body"));

    List<NotionSection> sections = NotionPageChunker.chunk(blocks);
    assertEquals(2, sections.size());
    assertEquals("intro", sections.get(0).heading());
    assertEquals("Preamble", sections.get(0).body());
    assertEquals("Main", sections.get(1).heading());
  }

  @Test
  void filterSkipsBlankAndArchived() {
    NotionPage ok =
        new NotionPage("p1", "Auth", false, Instant.parse("2024-01-01T00:00:00Z"), "chandru");
    assertTrue(NotionPageFilter.shouldIndex(ok));
    assertFalse(
        NotionPageFilter.shouldIndex(new NotionPage("p1", "", false, Instant.EPOCH, "")));
    assertFalse(
        NotionPageFilter.shouldIndex(new NotionPage("p1", "Auth", true, Instant.EPOCH, "")));
  }

  @Test
  void chunksRealAuthArchitecturePayloadShape() {
    List<NotionBlock> blocks =
        List.of(
            new NotionBlock("a", "paragraph", false, "Title: Auth Architecture"),
            new NotionBlock("b", "heading_2", false, "Why JWT"),
            new NotionBlock(
                "c", "paragraph", false, "We switched from sessions to JWT in May 2026"),
            new NotionBlock("d", "heading_2", false, "Token Config"),
            new NotionBlock("e", "paragraph", false, "Access token: 15min expiry"),
            new NotionBlock("f", "heading_2", false, "Key Files"),
            new NotionBlock("g", "paragraph", false, "AuthService.java, SecurityConfig.java"),
            new NotionBlock("h", "paragraph", false, ""));

    List<NotionSection> sections = NotionPageChunker.chunk(blocks);
    assertEquals(4, sections.size());
    assertEquals("intro", sections.get(0).heading());
    assertEquals("Why JWT", sections.get(1).heading());
    assertEquals("Token Config", sections.get(2).heading());
    assertEquals("Key Files", sections.get(3).heading());
  }
}
