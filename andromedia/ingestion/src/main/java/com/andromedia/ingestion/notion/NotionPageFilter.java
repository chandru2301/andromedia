package com.andromedia.ingestion.notion;

import java.util.Locale;
import java.util.Set;

public final class NotionPageFilter {

  private NotionPageFilter() {}

  /**
   * Whether a retrieved page should be indexed. Callers already iterate configured page/database
   * IDs; do not require an exact ID string match (Notion returns dashed UUIDs; clients often paste
   * undashed ones).
   */
  public static boolean shouldIndex(NotionPage page) {
    if (page == null) {
      return false;
    }
    if (page.title() == null || page.title().isBlank()) {
      return false;
    }
    return !page.archived();
  }

  /** @deprecated Prefer {@link #shouldIndex(NotionPage)}; kept for call sites that still pass IDs. */
  public static boolean shouldIndex(NotionPage page, Set<String> configuredPageIds) {
    if (!shouldIndex(page)) {
      return false;
    }
    if (configuredPageIds == null || configuredPageIds.isEmpty()) {
      return false;
    }
    String pageKey = normalizeId(page.id());
    for (String configured : configuredPageIds) {
      if (pageKey.equals(normalizeId(configured))) {
        return true;
      }
    }
    return false;
  }

  /** Notion accepts IDs with or without dashes; compare on hex only. */
  public static String normalizeId(String id) {
    if (id == null) {
      return "";
    }
    return id.trim().toLowerCase(Locale.ROOT).replace("-", "");
  }
}
