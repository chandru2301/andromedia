package com.andromedia.ingestion.linear;

import java.time.Instant;
import java.util.List;

public record LinearIssue(
    String id,
    String identifier,
    String title,
    String description,
    String state,
    String team,
    String teamName,
    String cycle,
    int priority,
    String author,
    Instant updatedAt,
    List<LinearComment> comments) {

  public LinearIssue {
    id = id == null ? "" : id;
    identifier = identifier == null ? "" : identifier;
    title = title == null ? "" : title;
    description = description == null ? "" : description;
    state = state == null ? "" : state;
    team = team == null ? "" : team;
    teamName = teamName == null ? "" : teamName;
    cycle = cycle == null ? "" : cycle;
    author = author == null ? "" : author;
    comments = comments == null ? List.of() : List.copyOf(comments);
  }
}
