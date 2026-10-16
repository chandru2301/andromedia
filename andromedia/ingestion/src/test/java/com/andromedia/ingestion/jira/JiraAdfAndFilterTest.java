package com.andromedia.ingestion.jira;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class JiraAdfAndFilterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void adfToPlainTextExtractsNestedText() throws Exception {
    try (InputStream in =
        getClass().getResourceAsStream("/fixtures/jira/adf-description.json")) {
      JsonNode adf = MAPPER.readTree(in);
      String plain = JiraApiClient.adfToPlainText(adf);
      assertTrue(plain.contains("We switched from sessions to JWT"));
      assertTrue(plain.contains("AUTH-23"));
      assertFalse(plain.contains("\"type\""));
      assertFalse(plain.contains("\"content\""));
    }
  }

  @Test
  void fieldToPlainTextHandlesStringAndNull() {
    assertEquals("plain", JiraApiClient.fieldToPlainText(MAPPER.getNodeFactory().textNode("plain")));
    assertEquals("", JiraApiClient.fieldToPlainText(null));
  }

  @Test
  void filterKeepsOpenIssuesAndRecentClosed() {
    JiraIssue open =
        new JiraIssue(
            "AUTH-1",
            "Open bug",
            "body",
            "Bug",
            "AUTH",
            "High",
            "In Progress",
            "alice",
            Instant.now().minus(200, ChronoUnit.DAYS),
            Instant.EPOCH,
            List.of());
    assertTrue(JiraIssueFilter.shouldIndex(open, 90));

    JiraIssue oldClosed =
        new JiraIssue(
            "AUTH-2",
            "Old closed",
            "body",
            "Bug",
            "AUTH",
            "Low",
            "Closed",
            "bob",
            Instant.now().minus(120, ChronoUnit.DAYS),
            Instant.EPOCH,
            List.of());
    assertFalse(JiraIssueFilter.shouldIndex(oldClosed, 90));

    JiraIssue recentDone =
        new JiraIssue(
            "AUTH-3",
            "Recent done",
            "body",
            "Bug",
            "AUTH",
            "Low",
            "Done",
            "bob",
            Instant.now().minus(10, ChronoUnit.DAYS),
            Instant.EPOCH,
            List.of());
    assertTrue(JiraIssueFilter.shouldIndex(recentDone, 90));
  }

  @Test
  void chunkBuilderPrefixesJiraAndUsesPlainText() {
    JiraIssue issue =
        new JiraIssue(
            "AUTH-12",
            "JWT migration",
            "We switched from sessions to JWT",
            "Bug",
            "AUTH",
            "High",
            "Done",
            "alice",
            Instant.parse("2026-05-01T00:00:00Z"),
            Instant.parse("2026-04-01T00:00:00Z"),
            List.of(new JiraComment("bob", Instant.parse("2026-05-02T00:00:00Z"), "Looks good")));
    var chunk = JiraHistoryChunkBuilder.build(issue);
    assertTrue(chunk.content().startsWith("[JIRA] AUTH-12"));
    assertTrue(chunk.content().contains("We switched from sessions to JWT"));
    assertTrue(chunk.content().contains("Looks good"));
    assertEquals("AUTH-12", chunk.issueKey());
  }

  @Test
  void buildProjectJqlStripsWrappedQuotes() {
    assertEquals(
        "project in (\"KAN\") ORDER BY updated DESC",
        JiraApiClient.buildProjectJql(List.of("\"KAN\"")));
    assertEquals(
        "project in (\"KAN\") ORDER BY updated DESC",
        JiraApiClient.buildProjectJql(List.of("KAN")));
  }
}
