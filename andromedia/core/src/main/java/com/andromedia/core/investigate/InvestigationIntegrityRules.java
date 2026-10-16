package com.andromedia.core.investigate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads the canonical Investigate integrity rules from the classpath so every answer-synthesis
 * call can prepend the same written instruction set.
 */
public final class InvestigationIntegrityRules {

  static final String RESOURCE_PATH = "/investigate/integrity-rules.md";
  private static final String FALLBACK =
      """
      # Andro Investigate — Reasoning Integrity Rules

      - Never invent accounts, deals, or people not present in retrieved evidence.
      - Do not contradict your own stated reasoning.
      - Do not invent sort order; narrate the code-provided order when present.
      - Enumeration/aggregate answers must not claim completeness from top-k retrieval alone.
      - Use fuzzy entity matching for named deals/accounts.
      - Exclude demo/test/sample records from user-facing answers and state exclusion counts.
      - Surface multi-source conflicts with sources and timestamps.
      - Label partial or ambiguous answers explicitly.
      - Fail loudly on upstream errors; never disguise failures as thin answers.
      """;

  private static final Object LOCK = new Object();
  private static volatile String cached;

  private InvestigationIntegrityRules() {}

  /** Returns the integrity rules markdown (cached after first successful load). */
  public static String load() {
    String local = cached;
    if (local != null) {
      return local;
    }
    synchronized (LOCK) {
      if (cached != null) {
        return cached;
      }
      cached = readResource();
      return cached;
    }
  }

  /** System-prompt block prepended ahead of path-specific instructions. */
  public static String systemBlock() {
    return "INVESTIGATE INTEGRITY RULES\n" + load().trim();
  }

  /** Test helper to clear the cache between loads if needed. */
  static void clearCacheForTests() {
    synchronized (LOCK) {
      cached = null;
    }
  }

  private static String readResource() {
    try (InputStream in = InvestigationIntegrityRules.class.getResourceAsStream(RESOURCE_PATH)) {
      if (in == null) {
        return FALLBACK.trim();
      }
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
      return text.isBlank() ? FALLBACK.trim() : text;
    } catch (IOException ex) {
      return FALLBACK.trim();
    }
  }
}
