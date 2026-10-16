package com.andromedia.search;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public final class ImpactCategoryFilters {

  private ImpactCategoryFilters() {}

  public static Set<ImpactCategory> parseCsv(String raw) {
    if (raw == null || raw.isBlank()) {
      return Set.of();
    }
    EnumSet<ImpactCategory> filters = EnumSet.noneOf(ImpactCategory.class);
    for (String token : raw.split(",")) {
      parseToken(token.trim()).ifPresent(filters::add);
    }
    return filters.isEmpty() ? Set.of() : Collections.unmodifiableSet(filters);
  }

  static java.util.Optional<ImpactCategory> parseToken(String token) {
    if (token.isBlank()) {
      return java.util.Optional.empty();
    }
    String lower = token.toLowerCase(Locale.ROOT);
    return switch (lower) {
      case "kafka", "messaging", "listener", "consumer" -> java.util.Optional.of(ImpactCategory.KAFKA_MESSAGING);
      case "api", "controller", "rest", "endpoint" -> java.util.Optional.of(ImpactCategory.API);
      case "repository", "repo", "db", "jpa" -> java.util.Optional.of(ImpactCategory.REPOSITORY);
      case "test", "tests" -> java.util.Optional.of(ImpactCategory.TEST);
      case "service", "handler" -> java.util.Optional.of(ImpactCategory.SERVICE);
      case "other" -> java.util.Optional.of(ImpactCategory.OTHER);
      default -> {
        try {
          yield java.util.Optional.of(ImpactCategory.valueOf(token.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
          yield java.util.Optional.empty();
        }
      }
    };
  }
}
