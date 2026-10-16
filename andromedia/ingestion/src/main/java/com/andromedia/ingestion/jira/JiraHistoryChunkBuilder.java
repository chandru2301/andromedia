package com.andromedia.ingestion.jira;

import com.andromedia.common.ChunkIdGenerator;
import com.andromedia.common.IndexConstants;
import com.andromedia.common.JiraChunk;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class JiraHistoryChunkBuilder {

  static final int MAX_BODY_CHARS = 4000;

  private static final DateTimeFormatter DATE_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

  private JiraHistoryChunkBuilder() {}

  public static JiraChunk build(JiraIssue issue) {
    return new JiraChunk(
        ChunkIdGenerator.generateJiraIssueId(issue.key()),
        issue.key(),
        issue.issueType(),
        issue.projectKey(),
        issue.priority(),
        issue.status(),
        issue.author(),
        issue.updated(),
        assembleContent(issue),
        IndexConstants.SOURCE_TYPE_JIRA);
  }

  static String assembleContent(JiraIssue issue) {
    String date = DATE_FORMAT.format(issue.updated() == null ? java.time.Instant.EPOCH : issue.updated());
    StringBuilder builder = new StringBuilder();
    builder
        .append("[JIRA] ")
        .append(issue.key())
        .append(" · ")
        .append(blankToDash(issue.issueType()))
        .append(" · ")
        .append(blankToDash(issue.priority()))
        .append(" · ")
        .append(blankToDash(issue.status()))
        .append(" · updated ")
        .append(date)
        .append('\n');
    builder.append(issue.summary()).append("\n\n");
    if (issue.description() != null && !issue.description().isBlank()) {
      builder.append(issue.description().trim()).append("\n\n");
    }
    if (issue.comments() != null && !issue.comments().isEmpty()) {
      builder.append("Comments:\n");
      for (JiraComment comment : issue.comments()) {
        if (comment.body() == null || comment.body().isBlank()) {
          continue;
        }
        String author = comment.author().isBlank() ? "unknown" : comment.author();
        builder.append("- ").append(author).append(": ").append(comment.body().trim()).append('\n');
      }
    }
    return truncate(builder.toString().trim(), MAX_BODY_CHARS);
  }

  private static String blankToDash(String value) {
    return value == null || value.isBlank() ? "-" : value;
  }

  private static String truncate(String value, int maxChars) {
    if (value.length() <= maxChars) {
      return value;
    }
    if (maxChars <= 1) {
      return value.substring(0, maxChars);
    }
    return value.substring(0, maxChars - 1) + "…";
  }
}
