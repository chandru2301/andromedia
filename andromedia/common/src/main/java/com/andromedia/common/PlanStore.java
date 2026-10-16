package com.andromedia.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class PlanStore {

  private static final String PLANS_DIR = "plans";

  private PlanStore() {}

  public static Path plansDirectory() {
    return Path.of(System.getProperty("user.home"), ".andromedia", PLANS_DIR)
        .toAbsolutePath()
        .normalize();
  }

  public static Path writePlan(String slug, String markdown) throws IOException {
    Path dir = plansDirectory();
    Files.createDirectories(dir);
    Path target = dir.resolve(slug + ".md").normalize();
    Files.writeString(target, markdown, StandardCharsets.UTF_8);
    return target;
  }

  public static String generateSlug() {
    String[] words = {"amber", "coral", "delta", "ember", "frost", "grove", "haven", "ivory", "jade", "kite"};
    java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
    String slug = words[random.nextInt(words.length)] + "-" + words[random.nextInt(words.length)];
    Path candidate = plansDirectory().resolve(slug + ".md");
    if (!Files.exists(candidate)) {
      return slug;
    }
    return slug + "-" + random.nextInt(1000);
  }

  public static List<Path> listPlans() throws IOException {
    Path dir = plansDirectory();
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> stream = Files.list(dir)) {
      return stream
          .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .toList();
    }
  }
}
