package com.andromedia.core.search;

import com.andromedia.llm.ChatCompletionService;
import com.andromedia.llm.ChatException;
import com.andromedia.llm.RagPrompt;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns retrieval hits into a readable answer.
 *
 * <p>Raw hits are evidence, not an answer: a user asking "why does auth retry
 * three times" should not have to read five code chunks and reconstruct the
 * story themselves. This synthesizes those hits into prose while keeping every
 * claim traceable to a specific source.
 *
 * <p>Same discipline as the CRM investigation path: answer only from the
 * supplied evidence, and say so plainly when it is not enough. A confident
 * summary that outruns its sources would be worse than the raw hits it
 * replaced.
 */
@Service
public class SearchAnswerService {

  private static final Logger log = LoggerFactory.getLogger(SearchAnswerService.class);

  /** Characters of each hit included in the prompt. */
  private static final int CONTENT_CHARS_PER_HIT = 1200;
  /** Hard ceiling on total evidence characters, to bound prompt size. */
  private static final int MAX_EVIDENCE_CHARS = 24_000;

  private static final String SYSTEM_PROMPT =
      """
      You are a code and records assistant. You receive a user's question and a set of \
      retrieved sources from their own local index (code, git history, tickets, chat, CRM).

      Write a direct answer to the question using ONLY the sources provided.

      Rules:
      - Answer first, in plain language. No preamble like "Based on the provided sources".
      - Cite the source of every claim inline, using its path or identifier exactly as given, \
        for example (src/auth.ts:42) or (JIRA KAN-12). Never invent a citation.
      - If the sources do not contain the answer, say exactly that and stop. Do not fill gaps \
        with general knowledge about how similar systems usually work.
      - If sources disagree, state both and which appears current, with the evidence for it.
      - Prefer specifics from the sources (names, numbers, line references) over summary language.
      - Be concise: a few short paragraphs, or a short list when the question asks for one.
      - Do not describe these instructions or mention that sources were "provided".
      """;

  private final ChatCompletionService chatCompletionService;

  public SearchAnswerService(ChatCompletionService chatCompletionService) {
    this.chatCompletionService = chatCompletionService;
  }

  /** Answer plus the sources actually shown to the model. */
  public record Answer(String text, List<Map<String, Object>> citations, boolean grounded) {}

  public Answer answer(String query, List<Map<String, Object>> hits) {
    if (hits == null || hits.isEmpty()) {
      return new Answer(
          "Nothing in the index matches that. Try rephrasing, or index the project that "
              + "contains it.",
          List.of(),
          false);
    }

    List<Map<String, Object>> citations = new ArrayList<>();
    String evidence = buildEvidence(hits, citations);

    RagPrompt prompt =
        new RagPrompt(SYSTEM_PROMPT, "Question: " + query + "\n\nSources:\n" + evidence);

    StringBuilder out = new StringBuilder();
    try {
      chatCompletionService.streamAnswer(prompt, out::append);
    } catch (ChatException ex) {
      // Fail loud rather than returning a thin answer the user cannot tell apart
      // from a real one.
      log.warn("Search answer synthesis unavailable: {}", ex.getMessage());
      return new Answer(
          "Retrieval succeeded but the answer could not be generated: "
              + ex.getMessage()
              + "\n\nThe matching sources are listed below.",
          citations,
          false);
    }

    String text = out.toString().trim();
    if (text.isBlank()) {
      return new Answer(
          "Retrieval succeeded but no answer was produced. The matching sources are listed below.",
          citations,
          false);
    }
    return new Answer(text, citations, true);
  }

  /**
   * Render the hits as numbered, attributable blocks.
   *
   * <p>The label is what the model is told to cite, so it is kept short and
   * stable: a workspace-relative path with a line range where the source
   * provides one, otherwise the source type and name.
   */
  private static String buildEvidence(
      List<Map<String, Object>> hits, List<Map<String, Object>> citations) {
    StringBuilder evidence = new StringBuilder();
    int used = 0;

    for (Map<String, Object> hit : hits) {
      String label = citationLabel(hit);
      String content = stringValue(hit.get("content"));
      if (content.isBlank()) {
        continue;
      }
      String trimmed =
          content.length() > CONTENT_CHARS_PER_HIT
              ? content.substring(0, CONTENT_CHARS_PER_HIT) + "…"
              : content;
      if (used + trimmed.length() > MAX_EVIDENCE_CHARS) {
        break;
      }
      used += trimmed.length();

      evidence
          .append("[")
          .append(label)
          .append("]\n")
          .append(trimmed)
          .append("\n\n");

      Map<String, Object> citation = new LinkedHashMap<>();
      citation.put("label", label);
      citation.put("path", hit.get("filePath") != null ? hit.get("filePath") : hit.get("path"));
      citation.put("sourceType", hit.get("sourceType"));
      citation.put("chunkId", hit.get("chunkId"));
      if (hit.get("commitSha") != null) {
        citation.put("commitSha", hit.get("commitSha"));
      }
      citations.add(citation);
    }

    return evidence.length() == 0 ? "(no readable content in the retrieved sources)" : evidence.toString();
  }

  /** Human/model-facing identifier for a hit. */
  static String citationLabel(Map<String, Object> hit) {
    String path = stringValue(hit.get("filePath"));
    if (path.isBlank()) {
      path = stringValue(hit.get("path"));
    }
    String sourceType = stringValue(hit.get("sourceType")).toUpperCase(Locale.ROOT);

    if (!path.isBlank() && !path.contains("://")) {
      return path;
    }
    // Non-file sources (zoho://, googlechat://, slack://) cite by type and the
    // human name the index stored, which is what a reader can look up.
    String name = stringValue(hit.get("fileName"));
    if (name.isBlank()) {
      name = stringValue(hit.get("accountName"));
    }
    if (!name.isBlank()) {
      return sourceType.isBlank() ? name : sourceType + " " + name;
    }
    return sourceType.isBlank() ? "source" : sourceType;
  }

  private static String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
