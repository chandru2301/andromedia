package com.andromedia.cli.shell.agent;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class RuleBasedIntentResolver implements IntentResolver {

  private static final Pattern PATH_LIKE =
      Pattern.compile("(?:^|[\\s\"'])([/~.]|[A-Za-z]:\\\\)[^\\s\"']*");

  @Override
  public IntentType resolve(String input) {
    if (input == null || input.isBlank()) {
      return IntentType.ASK;
    }
    String lower = input.toLowerCase().trim();

    if (matchesIndexIntent(lower)) {
      return IntentType.INDEX;
    }
    if (matchesFixIntent(lower)) {
      return IntentType.FIX;
    }
    if (matchesPlanIntent(lower)) {
      return IntentType.PLAN;
    }
    if (matchesImpactIntent(lower)) {
      return IntentType.IMPACT;
    }
    if (matchesUsagesIntent(lower)) {
      return IntentType.USAGES;
    }
    if (matchesSearchIntent(lower)) {
      return IntentType.SEARCH;
    }
    return IntentType.ASK;
  }

  private static boolean matchesPlanIntent(String lower) {
    return lower.startsWith("plan how to")
        || lower.startsWith("plan to ")
        || lower.contains("implementation plan")
        || lower.contains("design approach")
        || lower.startsWith("plan ")
        || lower.contains(" how should i implement ");
  }

  private static boolean matchesImpactIntent(String lower) {
    if (lower.startsWith("impact ")
        || lower.startsWith("impact of ")
        || lower.contains("what breaks if ")
        || lower.contains("blast radius")
        || lower.contains("downstream")
        || lower.contains("affected if ")
        || lower.contains("consumers are affected")
        || lower.contains("what services break")
        || lower.contains("services break")
        || lower.contains("depend on this")
        || lower.contains("downstream components")
        || lower.contains("test suites")
        || lower.contains("rerun tests")
        || lower.contains("should i rerun")) {
      return true;
    }
    return lower.contains("what does ") && lower.contains(" use");
  }

  private static boolean matchesUsagesIntent(String lower) {
    return lower.contains("usages of ")
        || lower.startsWith("usages of ")
        || (lower.contains("where is ") && lower.contains(" used"))
        || (lower.contains("where are ") && lower.contains(" used"))
        || lower.startsWith("who uses ")
        || lower.contains(" who uses ");
  }

  private static boolean matchesSearchIntent(String lower) {
    return lower.contains("find ")
        || lower.startsWith("find ")
        || lower.contains(" search ")
        || lower.startsWith("search ")
        || lower.contains("lookup ")
        || lower.contains("list ")
        || lower.contains("show me ")
        || lower.startsWith("show me ")
        || lower.contains("where is ")
        || lower.startsWith("where is ")
        || lower.contains("where are ")
        || lower.startsWith("where are ");
  }

  private static boolean matchesFixIntent(String lower) {
    return lower.contains("fix ")
        || lower.startsWith("fix ")
        || lower.contains(" bug")
        || lower.startsWith("bug ")
        || lower.contains("debug this")
        || lower.contains("repair ");
  }

  private static boolean matchesIndexIntent(String lower) {
    boolean mentionsIndex =
        lower.contains("index ")
            || lower.startsWith("index ")
            || lower.contains("re-index")
            || lower.contains("reindex");
    return mentionsIndex && PATH_LIKE.matcher(lower).find();
  }
}
