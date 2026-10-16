package com.andromedia.cli.output;

import java.util.ArrayList;
import java.util.List;

public final class BoxRenderer {

  private BoxRenderer() {}

  public static List<String> box(String title, List<String> lines) {
    if (lines == null || lines.isEmpty()) {
      return List.of();
    }
    int contentWidth = lines.stream().mapToInt(String::length).max().orElse(0);
    int innerWidth = contentWidth;
    if (title != null && !title.isBlank()) {
      innerWidth = Math.max(innerWidth, title.length() + 2);
    }

    List<String> result = new ArrayList<>();
    if (title != null && !title.isBlank()) {
      int dashCount = Math.max(0, innerWidth - title.length());
      result.add("┌─ " + title + " " + "─".repeat(dashCount) + "┐");
    } else {
      result.add("┌" + "─".repeat(innerWidth + 2) + "┐");
    }
    for (String line : lines) {
      result.add("│ " + padRight(line, innerWidth) + " │");
    }
    result.add("└" + "─".repeat(innerWidth + 2) + "┘");
    return result;
  }

  private static String padRight(String text, int width) {
    if (text.length() >= width) {
      return text;
    }
    return text + " ".repeat(width - text.length());
  }
}
