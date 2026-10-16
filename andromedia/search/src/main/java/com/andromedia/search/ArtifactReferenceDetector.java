package com.andromedia.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects and verifies artifacts a question names outright: tracker ticket ids and exact document
 * titles.
 *
 * <p>Motivation (QA 2026-09-05): "what did we decide in SOF-4 / where's the auth architecture doc"
 * missed both the Linear ticket and the Notion page even though both were indexed — the hybrid
 * ranker spent its top-k on chat chatter that scored better against the whole sentence. Detection
 * gives us a token to retrieve on directly; {@link #verifies} is the post-retrieval check that
 * keeps the pin honest — a hit is only pinned when it actually contains the id or the title, so a
 * near-miss can never be presented as the named artifact.
 *
 * <p>This is retrieval targeting, not answer dispatch: nothing here selects an answer shape, so
 * the "structured answers are keyed only by the classified intent enum" invariant is untouched.
 */
public final class ArtifactReferenceDetector {

  /** PROJ-123 shaped keys. Prefix is letters only so ISO dates ("2026-09-04") cannot match. */
  private static final Pattern TICKET_PATTERN =
      Pattern.compile("\\b([A-Za-z]{2,10})-(\\d{1,6})\\b");

  /**
   * English words that legitimately precede "-<number>" in prose ("phase-2", "top-10"). Without
   * this, ordinary phrasing would manufacture a ticket id that no source can verify.
   */
  private static final Set<String> NON_TICKET_PREFIXES =
      Set.of(
          "phase", "top", "page", "week", "day", "year", "part", "step", "tier", "chapter",
          "section", "item", "num", "no", "version", "quarter", "half", "level", "round", "line",
          "row", "col", "figure", "table", "note", "covid", "utf", "iso", "sha", "base", "http",
          "ipv", "mp", "x", "en", "es", "de", "fr");

  private static final Pattern QUOTED_PATTERN =
      Pattern.compile("[\"“‘']([^\"”’']{3,80})[\"”’']");

  /**
   * "... the auth architecture doc", "the billing runbook", "our onboarding spec" — up to five
   * words before a document noun, from which stop-words are then stripped.
   */
  private static final Pattern DOC_ANCHOR_PATTERN =
      Pattern.compile(
          "((?:[A-Za-z0-9][A-Za-z0-9&./+']*\\s+){1,5}?)"
              + "(docs?|documents?|pages?|specs?|specification|rfcs?|adrs?|runbooks?|playbooks?"
              + "|design|designs|writeup|write-up|one-pager|onepager)\\b",
          Pattern.CASE_INSENSITIVE);

  private static final Set<String> STOP_WORDS =
      Set.of(
          "the", "a", "an", "our", "my", "your", "their", "his", "her", "its", "this", "that",
          "these", "those", "is", "are", "was", "were", "be", "been", "where", "what", "which",
          "who", "whom", "when", "why", "how", "did", "do", "does", "done", "in", "on", "of",
          "for", "about", "at", "to", "from", "with", "we", "i", "you", "they", "it", "find",
          "show", "me", "us", "said", "say", "says", "get", "give", "read", "see", "look", "up",
          "latest", "current", "there", "here", "any", "some", "all", "and", "or", "but", "please",
          "can", "could", "should", "would", "will", "have", "has", "had", "again", "link", "s",
          // Request verbs: "open the doc", "pull up the spec" — the verb is never part of a title.
          "open", "pull", "fetch", "send", "share", "check", "review", "need", "want", "bring",
          "grab", "surface", "recall", "remind", "tell", "explain", "summarize", "summarise");

  private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]+");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private static final int MAX_REFERENCES = 4;

  private ArtifactReferenceDetector() {}

  /** Ticket ids and document titles named in {@code query}, in first-seen order, capped. */
  public static List<ArtifactReference> detect(String query) {
    if (query == null || query.isBlank()) {
      return List.of();
    }
    Map<String, ArtifactReference> byKey = new LinkedHashMap<>();
    for (ArtifactReference ref : detectTicketIds(query)) {
      byKey.putIfAbsent(ref.kind() + "|" + ref.token().toLowerCase(Locale.ROOT), ref);
    }
    for (ArtifactReference ref : detectDocTitles(query)) {
      byKey.putIfAbsent(ref.kind() + "|" + ref.token().toLowerCase(Locale.ROOT), ref);
    }
    return byKey.values().stream().limit(MAX_REFERENCES).toList();
  }

  /** Tracker keys such as {@code SOF-4} or {@code KAN-12}, normalized to upper case. */
  public static List<ArtifactReference> detectTicketIds(String query) {
    if (query == null || query.isBlank()) {
      return List.of();
    }
    List<ArtifactReference> refs = new ArrayList<>();
    Matcher matcher = TICKET_PATTERN.matcher(query);
    while (matcher.find()) {
      String prefix = matcher.group(1);
      if (NON_TICKET_PREFIXES.contains(prefix.toLowerCase(Locale.ROOT))) {
        continue;
      }
      String id = prefix.toUpperCase(Locale.ROOT) + "-" + matcher.group(2);
      refs.add(new ArtifactReference(ArtifactReference.Kind.TICKET, id, id));
    }
    return List.copyOf(refs);
  }

  /** Quoted phrases and document-noun-anchored phrases ("the auth architecture doc"). */
  public static List<ArtifactReference> detectDocTitles(String query) {
    if (query == null || query.isBlank()) {
      return List.of();
    }
    List<ArtifactReference> refs = new ArrayList<>();
    Matcher quoted = QUOTED_PATTERN.matcher(query);
    while (quoted.find()) {
      addDocTitle(refs, quoted.group(1));
    }
    Matcher anchored = DOC_ANCHOR_PATTERN.matcher(query);
    while (anchored.find()) {
      addDocTitle(refs, anchored.group(1));
    }
    return List.copyOf(refs);
  }

  private static void addDocTitle(List<ArtifactReference> refs, String rawPhrase) {
    String title = stripStopWords(rawPhrase);
    if (title.isBlank()) {
      return;
    }
    // A single short word ("the notes doc" -> "notes") is too generic to pin on; it would match
    // half the corpus and the verification step could not tell a real title from a coincidence.
    List<String> tokens = significantTokens(title);
    if (tokens.isEmpty() || (tokens.size() == 1 && tokens.getFirst().length() < 4)) {
      return;
    }
    refs.add(new ArtifactReference(ArtifactReference.Kind.DOC, title, title));
  }

  private static String stripStopWords(String phrase) {
    if (phrase == null || phrase.isBlank()) {
      return "";
    }
    List<String> kept = new ArrayList<>();
    for (String word : WHITESPACE.split(phrase.trim())) {
      String cleaned = word.replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "");
      if (cleaned.isBlank()) {
        continue;
      }
      if (kept.isEmpty() && STOP_WORDS.contains(cleaned.toLowerCase(Locale.ROOT))) {
        continue;
      }
      kept.add(cleaned);
    }
    // Trailing stop-words ("auth architecture is") carry no title signal either.
    while (!kept.isEmpty() && STOP_WORDS.contains(kept.getLast().toLowerCase(Locale.ROOT))) {
      kept.removeLast();
    }
    return String.join(" ", kept);
  }

  static List<String> significantTokens(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    Set<String> tokens = new LinkedHashSet<>();
    for (String token : NON_WORD.split(value.toLowerCase(Locale.ROOT))) {
      if (token.isBlank() || STOP_WORDS.contains(token)) {
        continue;
      }
      tokens.add(token);
    }
    return List.copyOf(tokens);
  }

  /**
   * Post-retrieval verification: true only when the candidate really is the named artifact.
   *
   * @param reference the artifact the question named
   * @param name candidate display name (ticket title, page title, channel name)
   * @param content candidate body text
   */
  public static boolean verifies(ArtifactReference reference, String name, String content) {
    if (reference == null || reference.token().isBlank()) {
      return false;
    }
    return switch (reference.kind()) {
      case TICKET -> containsTicketId(reference.token(), name) || containsTicketId(reference.token(), content);
      case DOC -> containsDocTitle(reference.token(), name) || titleLineMatches(reference.token(), content);
    };
  }

  static boolean containsTicketId(String id, String text) {
    if (id == null || id.isBlank() || text == null || text.isBlank()) {
      return false;
    }
    return Pattern.compile("(?i)(?<![A-Za-z0-9])" + Pattern.quote(id) + "(?![A-Za-z0-9])")
        .matcher(text)
        .find();
  }

  /** Every significant title word must appear as a whole word in the candidate name. */
  static boolean containsDocTitle(String title, String text) {
    List<String> tokens = significantTokens(title);
    if (tokens.isEmpty() || text == null || text.isBlank()) {
      return false;
    }
    Set<String> textTokens = new LinkedHashSet<>(List.of(NON_WORD.split(text.toLowerCase(Locale.ROOT))));
    return textTokens.containsAll(tokens);
  }

  /**
   * Notion/Confluence chunks carry the page title on a leading {@code Title: ...} line; the body
   * below it may mention many other pages, so only the title line is allowed to verify a match.
   */
  static boolean titleLineMatches(String title, String content) {
    if (content == null || content.isBlank()) {
      return false;
    }
    return content
        .lines()
        .limit(5)
        .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("title:"))
        .anyMatch(line -> containsDocTitle(title, line.substring(line.indexOf(':') + 1)));
  }
}
