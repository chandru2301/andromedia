package com.andromedia.cli.shell.agent;

import com.andromedia.search.TraversalDirection;
import java.util.Optional;

public record ShellIntent(
    IntentType intent,
    String symbol,
    TraversalDirection direction,
    Integer depth,
    Boolean explain,
    String searchQuery,
    Boolean graphContext,
    String planQuery) {

  public ShellIntent {
    if (intent == null) {
      intent = IntentType.ASK;
    }
  }

  public static ShellIntent of(IntentType intent, String line) {
    return new ShellIntent(intent, null, null, null, null, line, null, null);
  }

  public static ShellIntent ask(String line, boolean graphContext) {
    return new ShellIntent(IntentType.ASK, null, null, null, null, line, graphContext, null);
  }

  public static ShellIntent plan(String line) {
    return new ShellIntent(IntentType.PLAN, null, null, null, null, null, null, line);
  }

  public String effectiveQuery(String fallbackLine) {
    return switch (intent) {
      case SEARCH -> searchQuery != null && !searchQuery.isBlank() ? searchQuery : fallbackLine;
      case PLAN -> planQuery != null && !planQuery.isBlank() ? planQuery : fallbackLine;
      default -> fallbackLine;
    };
  }

  public Optional<String> effectiveSymbol() {
    return symbol == null || symbol.isBlank() ? Optional.empty() : Optional.of(symbol.trim());
  }
}
