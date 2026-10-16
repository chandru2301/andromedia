package com.andromedia.core.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.ingestion.slack.SlackApiClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlackConnectionTestServiceTest {

  @Test
  void resolvesConfiguredChannelsAndReportsMissing() {
    List<SlackApiClient.SlackChannel> available =
        List.of(
            new SlackApiClient.SlackChannel("C001", "backend-eng", true),
            new SlackApiClient.SlackChannel("C002", "incidents", true));

    SlackConnectionTestService.ChannelMatch match =
        SlackConnectionTestService.resolveChannels(
            available, List.of("#backend-eng", "missing-channel", "C002"));

    assertEquals(List.of("#backend-eng", "#incidents"), match.resolved());
    assertEquals(List.of("missing-channel"), match.missing());
  }

  @Test
  void parseChannelsAcceptsCsvAndList() {
    assertEquals(
        List.of("#eng", "#ops"), SlackConnectionTestService.parseChannels("#eng, #ops"));
    assertEquals(
        List.of("a", "b"), SlackConnectionTestService.parseChannels(List.of("a", " b ")));
    assertTrue(SlackConnectionTestService.parseChannels(null).isEmpty());
  }
}
