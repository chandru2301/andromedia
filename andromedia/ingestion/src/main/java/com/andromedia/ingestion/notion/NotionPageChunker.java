package com.andromedia.ingestion.notion;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a flattened Notion block list into sections on heading_1 / heading_2 / heading_3.
 * Expects children already inlined by {@link NotionApiClient#fetchAllBlocks(String)}.
 */
public final class NotionPageChunker {

  private NotionPageChunker() {}

  public static List<NotionSection> chunk(List<NotionBlock> blocks) {
    List<NotionSection> sections = new ArrayList<>();
    if (blocks == null || blocks.isEmpty()) {
      return sections;
    }

    String currentHeading = "intro";
    StringBuilder currentBody = new StringBuilder();

    for (NotionBlock block : blocks) {
      if (block.isChildPageOrDatabase()) {
        continue;
      }
      if (block.isHeading()) {
        if (currentBody.length() > 0) {
          sections.add(new NotionSection(currentHeading, currentBody.toString().trim()));
          currentBody = new StringBuilder();
        }
        String heading = block.plainText().trim();
        currentHeading = heading.isBlank() ? "intro" : heading;
      } else {
        String text = block.plainText();
        if (text != null && !text.isBlank()) {
          currentBody.append(text.trim()).append('\n');
        }
      }
    }

    if (currentBody.length() > 0) {
      sections.add(new NotionSection(currentHeading, currentBody.toString().trim()));
    }
    return sections;
  }
}
