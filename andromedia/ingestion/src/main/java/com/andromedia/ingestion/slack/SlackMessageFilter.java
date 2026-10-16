package com.andromedia.ingestion.slack;

import com.andromedia.common.AndroSlackSelfPosts;
import java.util.Locale;
import java.util.Set;

public final class SlackMessageFilter {

  private static final Set<String> SKIPPED_SUBTYPES =
      Set.of(
          "bot_message",
          "channel_join",
          "channel_leave",
          "channel_topic",
          "channel_purpose",
          "channel_name",
          "channel_archive",
          "channel_unarchive",
          "group_join",
          "group_leave",
          "message_deleted",
          "message_changed",
          "pinned_item",
          "unpinned_item",
          "ekm_access_denied");

  private SlackMessageFilter() {}

  public static boolean keep(SlackMessage message, boolean skipBotMessages) {
    if (message == null) {
      return false;
    }
    if (message.text() == null || message.text().isBlank()) {
      return false;
    }
    if (AndroSlackSelfPosts.isSelfPostMessageText(message.text())) {
      return false;
    }
    String userId = message.userId();
    if (skipBotMessages && userId != null && userId.toUpperCase(Locale.ROOT).startsWith("B")) {
      return false;
    }
    String subtype = message.subtype();
    if (subtype != null && !subtype.isBlank()) {
      String normalized = subtype.toLowerCase(Locale.ROOT);
      if (SKIPPED_SUBTYPES.contains(normalized)) {
        return false;
      }
      if (skipBotMessages && normalized.contains("bot")) {
        return false;
      }
    }
    return true;
  }
}
