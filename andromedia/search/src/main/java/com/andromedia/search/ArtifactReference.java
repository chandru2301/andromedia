package com.andromedia.search;

/**
 * A concrete artifact named directly in a user question — a tracker ticket id ("SOF-4", "KAN-7")
 * or an exact document title ("Auth Architecture"). Query-biased top-k retrieval routinely drowns
 * these under high-volume chat noise, so they are resolved and verified separately from the
 * hybrid search pool.
 *
 * @param kind ticket id or document title
 * @param token the literal search token (upper-cased id, or the title phrase)
 * @param label human-facing label used in progress/metadata copy
 */
public record ArtifactReference(Kind kind, String token, String label) {

  public enum Kind {
    TICKET,
    DOC
  }

  public ArtifactReference {
    token = token == null ? "" : token.trim();
    label = label == null || label.isBlank() ? token : label.trim();
  }
}
