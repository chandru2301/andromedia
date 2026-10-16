package com.andromedia.ingestion.linear;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LinearIssueFilterTest {

  @Test
  void filterSkipsCancelledOutsideLookbackAndWrongTeam() {
    LinearIssue active =
        new LinearIssue(
            "1",
            "ENG-1",
            "JWT switch",
            "desc",
            "In Progress",
            "ENG",
            "Engineering",
            "Cycle 1",
            2,
            "alice",
            Instant.now().minus(200, ChronoUnit.DAYS),
            List.of());
    assertTrue(LinearIssueFilter.shouldIndex(active, 90, Set.of("ENG")));

    LinearIssue wrongTeam =
        new LinearIssue(
            "2",
            "OPS-1",
            "Ops task",
            "desc",
            "Todo",
            "OPS",
            "Operations",
            "",
            0,
            "bob",
            Instant.now(),
            List.of());
    assertFalse(LinearIssueFilter.shouldIndex(wrongTeam, 90, Set.of("ENG")));

    LinearIssue oldCancelled =
        new LinearIssue(
            "3",
            "ENG-9",
            "Cancelled old",
            "desc",
            "Cancelled",
            "ENG",
            "Engineering",
            "",
            0,
            "bob",
            Instant.now().minus(120, ChronoUnit.DAYS),
            List.of());
    assertFalse(LinearIssueFilter.shouldIndex(oldCancelled, 90, Set.of("ENG")));
  }

  @Test
  void filterMatchesTeamNameOrKey() {
    LinearIssue issue =
        new LinearIssue(
            "1",
            "SOF-1",
            "Task",
            "desc",
            "Todo",
            "SOF",
            "Softtwig",
            "",
            0,
            "bob",
            Instant.now(),
            List.of());
    assertTrue(LinearIssueFilter.shouldIndex(issue, 90, Set.of("Softtwig")));
    assertTrue(LinearIssueFilter.shouldIndex(issue, 90, Set.of("SOF")));
  }

  @Test
  void resolveTeamKeyMatchesNameOrKey() {
    List<LinearApiClient.LinearTeam> teams =
        List.of(new LinearApiClient.LinearTeam("1", "SOF", "Softtwig"));
    assertEquals("SOF", LinearApiClient.findTeamKey(teams, "Softtwig"));
    assertEquals("SOF", LinearApiClient.findTeamKey(teams, "SOF"));
    assertEquals(null, LinearApiClient.findTeamKey(teams, "MISSING"));
  }

  @Test
  void chunkBuilderPrefixesLinear() {
    LinearIssue issue =
        new LinearIssue(
            "1",
            "ENG-42",
            "Token rotation",
            "Rotate refresh tokens weekly",
            "Done",
            "ENG",
            "Engineering",
            "C12",
            1,
            "alice",
            Instant.parse("2026-05-01T00:00:00Z"),
            List.of(new LinearComment("bob", "LGTM")));
    var chunk = LinearChunkBuilder.build(issue);
    assertTrue(chunk.content().startsWith("[LINEAR] ENG-42"));
    assertTrue(chunk.content().contains("Rotate refresh tokens weekly"));
    assertTrue(chunk.content().contains("LGTM"));
    assertEquals("ENG-42", chunk.identifier());
  }
}
