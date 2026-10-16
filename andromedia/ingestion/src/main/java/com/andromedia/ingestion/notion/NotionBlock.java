package com.andromedia.ingestion.notion;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/** A Notion block node used for section chunking (plain text already extracted). */
public record NotionBlock(String id, String type, boolean hasChildren, String plainText) {

  public NotionBlock {
    id = id == null ? "" : id;
    type = type == null ? "" : type;
    plainText = plainText == null ? "" : plainText;
  }

  public boolean isHeading() {
    return "heading_1".equals(type) || "heading_2".equals(type) || "heading_3".equals(type);
  }

  public boolean isChildPageOrDatabase() {
    return "child_page".equals(type) || "child_database".equals(type);
  }

  static NotionBlock fromJson(JsonNode node) {
    String type = node.path("type").asText("");
    return new NotionBlock(
        node.path("id").asText(""),
        type,
        node.path("has_children").asBoolean(false),
        extractPlainText(node, type));
  }

  static String extractPlainText(JsonNode node, String type) {
    if (type == null || type.isBlank()) {
      return "";
    }
    JsonNode typed = node.path(type);
    if (typed.isMissingNode()) {
      return "";
    }
    if ("child_page".equals(type) || "child_database".equals(type)) {
      return typed.path("title").asText("");
    }
    JsonNode richText = typed.path("rich_text");
    if (!richText.isArray()) {
      richText = typed.path("text");
    }
    if (!richText.isArray()) {
      return "";
    }
    StringBuilder builder = new StringBuilder();
    for (JsonNode item : richText) {
      String plain = item.path("plain_text").asText("");
      if (!plain.isBlank()) {
        builder.append(plain);
      }
    }
    return builder.toString();
  }

  static List<NotionBlock> listFromResults(JsonNode results) {
    List<NotionBlock> blocks = new ArrayList<>();
    if (results == null || !results.isArray()) {
      return blocks;
    }
    for (JsonNode node : results) {
      blocks.add(fromJson(node));
    }
    return blocks;
  }
}
