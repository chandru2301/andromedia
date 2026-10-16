package com.andromedia.core.alerts;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.core.alerts.SlackMessageService.InvestigateSlackMessage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlackMessageServiceTest {

  @Test
  void investigateFallbackIncludesReasonsFieldsAndFlags() {
    String text =
        SlackMessageService.formatInvestigateFallback(
            new InvestigateSlackMessage(
                "xoxb-test",
                "C123",
                "Reliance Data Center Expansion",
                "Follow up this week on Reliance.",
                List.of("Close date within 7 days", "Next Step is set"),
                Map.of(
                    "Stage",
                    "Negotiation/Review",
                    "Probability",
                    "80%",
                    "Close Date",
                    "2026-07-28",
                    "Next Step",
                    "Confirm PO"),
                List.of("stale_record"),
                "http://localhost:3000/app/investigate"));

    assertTrue(text.contains("Reliance Data Center Expansion"));
    assertTrue(text.contains("Follow up this week"));
    assertTrue(text.contains("Close date within 7 days"));
    assertTrue(text.contains("Probability: 80%"));
    assertTrue(text.contains("Flags: stale_record"));
    assertTrue(text.contains("Open in Andro"));
  }

  @Test
  void investigateBlocksJsonContainsHeaderAndWhy() {
    String blocks =
        SlackMessageService.formatInvestigateBlocks(
            new InvestigateSlackMessage(
                "xoxb-test",
                "C123",
                "King ERP",
                "King ERP is the top follow-up.",
                List.of("Highest qualification score"),
                Map.of("Stage", "Id. Decision Makers", "Amount", "60000"),
                List.of(),
                null));
    assertTrue(blocks != null);
    assertTrue(blocks.contains("header"));
    assertTrue(blocks.contains("Why this answer?"));
    assertTrue(blocks.contains("King ERP"));
  }
}
