package com.andromedia.cli.shell.agent;

import com.andromedia.llm.SideQueryService;
import com.andromedia.search.TraversalDirection;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class ChainedIntentResolver {

  private final RuleBasedIntentResolver ruleResolver;
  private final SideQueryService sideQueryService;

  public ChainedIntentResolver(
      RuleBasedIntentResolver ruleResolver, SideQueryService sideQueryService) {
    this.ruleResolver = ruleResolver;
    this.sideQueryService = sideQueryService;
  }

  public ShellIntent resolve(String input) {
    IntentType ruleIntent = ruleResolver.resolve(input);
    if (ruleIntent != IntentType.ASK) {
      return toShellIntent(ruleIntent, input, null);
    }
    if (sideQueryService == null) {
      return ShellIntent.ask(input, inferGraphContext(input));
    }
    return sideQueryService
        .resolveIntent(input)
        .map(side -> toShellIntent(side, input))
        .orElseGet(() -> ShellIntent.ask(input, inferGraphContext(input)));
  }

  private static ShellIntent toShellIntent(
      IntentType intent, String input, SideQueryService.SideQueryIntent side) {
    if (side == null) {
      if (intent == IntentType.ASK) {
        return ShellIntent.ask(input, inferGraphContext(input));
      }
      return ShellIntent.of(intent, input);
    }
    return toShellIntent(side, input);
  }

  private static ShellIntent toShellIntent(SideQueryService.SideQueryIntent side, String input) {
    IntentType intent = mapIntent(side.intent());
    TraversalDirection direction =
        side.direction() != null && side.direction().equalsIgnoreCase("forward")
            ? TraversalDirection.FORWARD
            : TraversalDirection.REVERSE;
    return new ShellIntent(
        intent,
        side.symbol(),
        direction,
        side.depth(),
        side.explain(),
        side.searchQuery(),
        side.graphContext() != null ? side.graphContext() : inferGraphContext(input),
        side.planQuery());
  }

  private static IntentType mapIntent(SideQueryService.SideQueryIntentType type) {
    return switch (type) {
      case SEARCH -> IntentType.SEARCH;
      case INDEX -> IntentType.INDEX;
      case USAGES -> IntentType.USAGES;
      case IMPACT -> IntentType.IMPACT;
      case PLAN -> IntentType.PLAN;
      case FIX -> IntentType.FIX;
      case ASK -> IntentType.ASK;
    };
  }

  static boolean inferGraphContext(String input) {
    if (input == null) {
      return false;
    }
    String lower = input.toLowerCase();
    return lower.contains("change ")
        || lower.contains("break")
        || lower.contains("affect")
        || lower.contains("impact")
        || lower.contains("blast radius")
        || lower.contains("downstream")
        || lower.contains("depend");
  }
}
