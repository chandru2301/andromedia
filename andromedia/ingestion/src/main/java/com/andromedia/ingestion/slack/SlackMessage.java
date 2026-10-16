package com.andromedia.ingestion.slack;

import java.util.List;
import java.util.Map;

/** Raw Slack message used during ingestion before chunk assembly. */
public record SlackMessage(
    String ts,
    String threadTs,
    String userId,
    String text,
    String subtype,
    int reactionCount,
    int replyCount,
    Map<String, Integer> reactions) {

  public SlackMessage {
    text = text == null ? "" : text;
    reactions = reactions == null ? Map.of() : Map.copyOf(reactions);
  }

  public String effectiveThreadTs() {
    if (threadTs != null && !threadTs.isBlank()) {
      return threadTs;
    }
    return ts;
  }

  public boolean isThreadParent() {
    return threadTs == null || threadTs.isBlank() || threadTs.equals(ts);
  }

  public boolean hasReplies() {
    return replyCount > 0;
  }
}
