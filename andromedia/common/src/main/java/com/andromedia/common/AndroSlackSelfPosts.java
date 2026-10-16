package com.andromedia.common;

import java.util.Locale;

/**
 * Andro's own Slack posts (pipeline alerts, investigate recaps) must not re-enter search.
 * Matching is based on the text Andro publishes via SlackMessageService.
 */
public final class AndroSlackSelfPosts {

  private AndroSlackSelfPosts() {}

  public static boolean isSelfPostMessageText(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    String normalized = text.trim().toLowerCase(Locale.ROOT);
    return normalized.startsWith("*andro pipeline alert")
        || normalized.startsWith("andro pipeline alert")
        || normalized.startsWith("*andro investigate")
        || normalized.startsWith("andro investigate —")
        || normalized.startsWith("andro investigate -")
        || normalized.startsWith("andro investigate\n");
  }

  public static boolean isIndexedSelfPost(String sourceType, String content) {
    if (sourceType == null || !sourceType.equalsIgnoreCase("SLACK")) {
      return false;
    }
    if (content == null || content.isBlank()) {
      return false;
    }
    if (isSelfPostMessageText(content)) {
      return true;
    }
    String lower = content.toLowerCase(Locale.ROOT);
    return lower.contains(" · causr_alert")
        || lower.contains("\ncausr_alert:")
        || lower.contains("*andro pipeline alert")
        || lower.contains("*andro investigate")
        || lower.contains("andro pipeline alert —")
        || lower.contains("andro investigate —");
  }
}
