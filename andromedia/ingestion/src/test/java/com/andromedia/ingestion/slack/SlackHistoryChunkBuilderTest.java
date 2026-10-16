package com.andromedia.ingestion.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.SlackChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlackHistoryChunkBuilderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void assemblesThreadChunkWithDecisionSignal() throws Exception {
    JsonNode replies =
        MAPPER.readTree(
            Files.readString(
                Path.of("src/test/resources/fixtures/slack/conversations-replies.json")));
    List<SlackMessage> messages = new java.util.ArrayList<>();
    for (JsonNode node : replies.path("messages")) {
      messages.add(SlackApiClient.parseMessage(node));
    }

    List<SlackThreadEvent> threads =
        SlackThreadChunker.chunk(
            "C001",
            "backend-eng",
            messages,
            userId ->
                switch (userId) {
                  case "U001" -> "chandru";
                  case "U002" -> "ravi";
                  case "U003" -> "priya";
                  default -> userId;
                });

    assertEquals(1, threads.size());
    SlackChunk chunk =
        SlackHistoryChunkBuilder.build(
            threads.getFirst(),
            Map.of("U001", "chandru", "U002", "ravi", "U003", "priya"));

    assertTrue(chunk.content().startsWith("[SLACK] #backend-eng"));
    assertTrue(chunk.content().contains("we decided to switch to async Kafka"));
    assertTrue(chunk.content().contains("chandru:"));
    assertFalse(chunk.content().contains("U001:"));
    assertTrue(chunk.content().contains("reactions: 7"));
    assertEquals(7, chunk.reactionCount());
    assertEquals(3, chunk.messageCount());
    assertEquals("slack://#backend-eng/1718366400.000100", chunk.path());
  }

  @Test
  void filterDropsJoinAndBotMessages() {
    SlackMessage join =
        new SlackMessage(
            "1", null, "U1", "joined", "channel_join", 0, 0, Map.of());
    SlackMessage bot =
        new SlackMessage("2", null, "B1", "deployed", "bot_message", 0, 0, Map.of());
    SlackMessage empty = new SlackMessage("3", null, "U1", "  ", null, 0, 0, Map.of());
    SlackMessage keep =
        new SlackMessage("4", null, "U1", "real decision", null, 1, 0, Map.of("+1", 1));

    assertFalse(SlackMessageFilter.keep(join, true));
    assertFalse(SlackMessageFilter.keep(bot, true));
    assertFalse(SlackMessageFilter.keep(empty, true));
    assertTrue(SlackMessageFilter.keep(keep, true));

    SlackMessage alert =
        new SlackMessage(
            "5",
            null,
            "U9",
            "*Andro pipeline alert — local* 3 new signals after Zoho reindex",
            null,
            0,
            0,
            Map.of());
    SlackMessage investigate =
        new SlackMessage(
            "6",
            null,
            "U9",
            "*Andro investigate — Reliance Data Center Expansion*\nBased on the ledger",
            null,
            0,
            0,
            Map.of());
    SlackMessage botIdNoSubtype =
        new SlackMessage("7", null, "B012345", "deployed to prod", null, 0, 0, Map.of());
    assertFalse(SlackMessageFilter.keep(alert, true));
    assertFalse(SlackMessageFilter.keep(investigate, true));
    assertFalse(SlackMessageFilter.keep(botIdNoSubtype, true));
  }
}
