package com.andromedia.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared cross-source entity normalization for ledger resolve, hybrid coverage, and conflict
 * grouping. Maps Zoho deal/account names, Notion section titles, and Slack mentions onto one stem.
 */
public final class CanonicalEntityKey {

  private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9\\s]");
  private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

  /** Org / deal suffixes stripped so "Al Habtoor Group" and "Al Habtoor" share a key. */
  private static final Set<String> SUFFIX_TOKENS =
      Set.of(
          "group",
          "holding",
          "holdings",
          "properties",
          "property",
          "realty",
          "limited",
          "ltd",
          "llc",
          "inc",
          "corp",
          "corporation",
          "company",
          "co",
          "partners",
          "partner",
          "solutions",
          "ventures",
          "retail",
          "bank",
          "media",
          "phase",
          "expansion",
          "pilot",
          "rollout",
          "renewal",
          "discussion",
          "package",
          "analytics",
          "annual",
          "contract",
          "option",
          "module",
          "platform",
          "upgrade",
          "migration",
          "transformation",
          "license",
          "quote",
          "proposal",
          "review",
          "q1",
          "q2",
          "q3",
          "q4");

  /**
   * Generic CRM deal-type / tier words stripped from the <em>tail</em> of a key so "Bright Horizon
   * - Enterprise License" and "Bright Horizon Realty" share {@code bright horizon}.
   */
  private static final Set<String> DEAL_TYPE_TOKENS =
      Set.of(
          "enterprise",
          "professional",
          "standard",
          "basic",
          "premium",
          "pilot",
          "poc",
          "trial",
          "renewal",
          "expansion",
          "upgrade",
          "downgrade",
          "license",
          "licence",
          "contract",
          "agreement",
          "deal",
          "opportunity",
          "phase",
          "project");

  /**
   * English function / question words only — not CRM domain stopwords. Keeps entity stems like
   * Habtoor / Damac while stripping "what's the current deal value for".
   */
  private static final Set<String> FUNCTION_WORDS =
      Set.of(
          "a",
          "an",
          "the",
          "of",
          "and",
          "or",
          "for",
          "to",
          "in",
          "on",
          "at",
          "by",
          "with",
          "from",
          "as",
          "is",
          "are",
          "was",
          "were",
          "be",
          "been",
          "being",
          "do",
          "does",
          "did",
          "doing",
          "how",
          "what",
          "whats",
          "which",
          "who",
          "whom",
          "whose",
          "where",
          "when",
          "why",
          "any",
          "our",
          "we",
          "us",
          "my",
          "your",
          "their",
          "this",
          "that",
          "these",
          "those",
          "it",
          "its",
          "them",
          "they",
          "i",
          "me",
          "so",
          "should",
          "can",
          "could",
          "would",
          "will",
          "about",
          "across",
          "all",
          "everything",
          "know",
          "known",
          "sources",
          "source",
          "deal",
          "deals",
          "account",
          "accounts",
          "current",
          "value",
          "values",
          "amount",
          "amounts",
          "status",
          "update",
          "updates",
          "happening",
          "moving",
          "stale",
          "number",
          "numbers",
          "tell",
          "client",
          "price",
          "change",
          "changed",
          "payment",
          "method",
          "contact",
          "contacts",
          "summarize",
          "summary",
          "proposal",
          "crm",
          "notion",
          "vs",
          "versus");

  private CanonicalEntityKey() {}

  /** Lowercase, strip punctuation, drop articles and org suffixes, collapse whitespace. */
  public static String key(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    String normalized =
        MULTI_SPACE
            .matcher(NON_ALNUM.matcher(value.trim().toLowerCase(Locale.ROOT)).replaceAll(" "))
            .replaceAll(" ")
            .trim();
    if (normalized.isBlank()) {
      return "";
    }
    List<String> parts = new ArrayList<>();
    for (String part : normalized.split(" ")) {
      if (part.isBlank()
          || part.length() < 2
          || part.chars().allMatch(Character::isDigit)
          || FUNCTION_WORDS.contains(part)
          || SUFFIX_TOKENS.contains(part)) {
        continue;
      }
      parts.add(part);
    }
    while (parts.size() > 1 && DEAL_TYPE_TOKENS.contains(parts.get(parts.size() - 1))) {
      parts.remove(parts.size() - 1);
    }
    return String.join(" ", parts);
  }

  /** Light normalize without suffix stripping (legacy ledger exact path). */
  public static String normalizeToken(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    return MULTI_SPACE
        .matcher(NON_ALNUM.matcher(value.trim().toLowerCase(Locale.ROOT)).replaceAll(" "))
        .replaceAll(" ")
        .trim();
  }

  /**
   * Tokens that distinguish a deal title from its account after dropping function words. Keeps
   * deal-type words ({@code enterprise}, {@code renewal}) so multi-deal accounts can disambiguate.
   */
  public static Set<String> discriminatorTokens(String dealName, String accountName) {
    Set<String> dealTokens = new LinkedHashSet<>(contentTokensKeepingDealType(dealName));
    if (dealTokens.isEmpty()) {
      return Set.of();
    }
    String accountKey = key(accountName);
    if (!accountKey.isBlank()) {
      for (String part : accountKey.split(" ")) {
        dealTokens.remove(part);
      }
    }
    return Set.copyOf(dealTokens);
  }

  public static Set<String> contentTokensKeepingDealType(String value) {
    String normalized = normalizeToken(value);
    if (normalized.isBlank()) {
      return Set.of();
    }
    Set<String> tokens = new LinkedHashSet<>();
    for (String part : normalized.split(" ")) {
      if (part.length() < 3 || FUNCTION_WORDS.contains(part)) {
        continue;
      }
      tokens.add(part);
    }
    return tokens;
  }

  public static Set<String> significantTokens(String value) {
    String normalized = normalizeToken(value);
    if (normalized.isBlank()) {
      return Set.of();
    }
    Set<String> tokens = new LinkedHashSet<>();
    for (String part : normalized.split(" ")) {
      if (part.length() < 3
          || FUNCTION_WORDS.contains(part)
          || SUFFIX_TOKENS.contains(part)
          || DEAL_TYPE_TOKENS.contains(part)) {
        continue;
      }
      tokens.add(part);
    }
    return tokens;
  }

  /**
   * True when {@code querySide} names the same entity as {@code recordSide}, or is a short-form
   * of it. Never treats a longer blended query as matching a shorter record (blocks "King Chapman
   * Mega Deal" → "King").
   */
  public static boolean matches(String querySide, String recordSide) {
    String query = key(querySide);
    String record = key(recordSide);
    if (query.isBlank() || record.isBlank()) {
      return false;
    }
    if (query.equals(record)) {
      return true;
    }
    int queryParts = query.split(" ").length;
    int recordParts = record.split(" ").length;
    // Short-form query against longer (or equal-length) record title only — never the reverse.
    if (query.length() >= 3 && record.contains(query) && queryParts <= recordParts) {
      return true;
    }
    Set<String> queryTokens = significantTokens(querySide);
    Set<String> recordTokens = significantTokens(recordSide);
    if (queryTokens.isEmpty() || recordTokens.isEmpty()) {
      return false;
    }
    return recordTokens.containsAll(queryTokens);
  }

  /**
   * Conversational query → ledger record: the query may add people/risk words ("Fatima",
   * "stalled") around a real deal/account name. Still rejects single-token short records that
   * would accept blended hallucinations ({@code King Chapman Mega Deal} → {@code King}).
   */
  public static boolean queryMentionsRecord(String querySide, String recordSide) {
    if (matches(querySide, recordSide)) {
      return true;
    }
    String query = key(querySide);
    String record = key(recordSide);
    if (query.isBlank() || record.isBlank()) {
      return false;
    }
    if (record.length() >= 5 && query.contains(record)) {
      return true;
    }
    Set<String> queryTokens = significantTokens(querySide);
    Set<String> recordTokens = significantTokens(recordSide);
    if (recordTokens.isEmpty() || !queryTokens.containsAll(recordTokens)) {
      return false;
    }
    if (recordTokens.size() >= 2) {
      return true;
    }
    String only = recordTokens.iterator().next();
    return only != null && only.length() >= 6;
  }

  /** Structural prefixes on non-CRM source names/URIs that carry no entity meaning. */
  private static final Pattern STRUCTURAL_PREFIX =
      Pattern.compile("(?i)^(?:slack|zoho|notion|jira|linear|gchat)://|^#|^spaces/|^all[-_]");

  private static String stripStructuralPrefixes(String value) {
    if (value == null) {
      return "";
    }
    String out = value.trim();
    String previous;
    do {
      previous = out;
      out = STRUCTURAL_PREFIX.matcher(out).replaceFirst("");
    } while (!out.equals(previous));
    return out;
  }

  /**
   * Looser cross-source match for non-CRM evidence names (Slack channels, Google Chat spaces,
   * Notion pages, ...) against a query entity hint. Unlike {@link #matches}, which deliberately
   * blocks a longer query from matching a shorter record (to avoid blended-name hallucination on
   * CRM deal/account resolution), evidence display names are frequently truncated or
   * prefixed/suffixed by source structure (e.g. Slack channel {@code #all-causr} for entity
   * "causr workspace"). This strips those structural markers and accepts substring or
   * significant-token overlap in either direction.
   */
  public static boolean matchesEvidenceName(String queryEntity, String evidenceName) {
    String strippedQuery = stripStructuralPrefixes(queryEntity);
    String strippedRecord = stripStructuralPrefixes(evidenceName);
    String query = key(strippedQuery);
    String record = key(strippedRecord);
    if (query.isBlank() || record.isBlank()) {
      return false;
    }
    if (query.equals(record) || record.contains(query) || query.contains(record)) {
      return true;
    }
    Set<String> queryTokens = new LinkedHashSet<>(significantTokens(strippedQuery));
    Set<String> recordTokens = significantTokens(strippedRecord);
    if (queryTokens.isEmpty() || recordTokens.isEmpty()) {
      return false;
    }
    queryTokens.retainAll(recordTokens);
    return !queryTokens.isEmpty();
  }

  public static String coveragePair(String sourceType, String displayName) {
    String source =
        sourceType == null || sourceType.isBlank()
            ? ""
            : sourceType.trim().toUpperCase(Locale.ROOT);
    String entity = key(displayName);
    if (source.isBlank() || entity.isBlank()) {
      return "";
    }
    return source + "|" + entity;
  }
}
