package com.andromedia.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class SideQueryService {

  private static final Logger log = LoggerFactory.getLogger(SideQueryService.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Pattern JSON_BLOCK =
      Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

  private static final String SYSTEM_PROMPT =
      """
      You classify natural-language input for a codebase CLI into structured JSON.
      Respond with ONLY valid JSON (no markdown) using this schema:
      {
        "intent": "ASK|SEARCH|INDEX|USAGES|IMPACT|PLAN|FIX",
        "symbol": "optional ClassName or Class#method",
        "direction": "reverse|forward",
        "depth": 1-10,
        "explain": true|false,
        "searchQuery": "optional rewritten search query",
        "graphContext": true|false,
        "planQuery": "optional plan description"
      }
      Rules:
      - IMPACT: blast radius, what breaks, downstream, affected, consumers
      - USAGES: where is X used, who uses X
      - SEARCH: find, where is, show me (when not asking about usage)
      - PLAN: plan how to, implementation plan, design approach
      - ASK: explain, why, how does (default)
      - graphContext true when question involves change risk or symbol dependencies
      - direction reverse for blast radius, forward for dependencies
      """;

  private final StructuredCompletionService completionService;
  private final ChatProperties chatProperties;
  private final SideQueryProperties sideQueryProperties;

  public SideQueryService(
      @Qualifier("structuredCompletionService") StructuredCompletionService completionService,
      ChatProperties chatProperties,
      SideQueryProperties sideQueryProperties) {
    this.completionService = completionService;
    this.chatProperties = chatProperties;
    this.sideQueryProperties = sideQueryProperties;
  }

  public Optional<SideQueryIntent> resolveIntent(String input) {
    if (!sideQueryProperties.enabled() || !chatProperties.hasApiKey()) {
      return Optional.empty();
    }
    if (input == null || input.isBlank()) {
      return Optional.empty();
    }
    try {
      String userMessage = "Classify this shell input:\n" + input.trim();
      RagPrompt prompt = new RagPrompt(SYSTEM_PROMPT.trim(), userMessage);
      String raw = completionService.complete(prompt);
      return parseResponse(raw);
    } catch (RuntimeException ex) {
      log.debug("Side query intent resolution failed: {}", ex.getMessage());
      return Optional.empty();
    }
  }

  public Optional<String> selectMemoryFiles(String query, java.util.List<String> filenames) {
    if (!sideQueryProperties.enabled() || !chatProperties.hasApiKey() || filenames.isEmpty()) {
      return Optional.empty();
    }
    try {
      String userMessage =
          """
          User query:
          %s

          Available memory files:
          %s

          Return JSON: {"selected": ["filename1.md"]} with up to 3 most relevant filenames.
          """
              .formatted(query, String.join("\n", filenames));
      String raw =
          completionService.complete(
              new RagPrompt(
                  "Select memory files relevant to the query. Return JSON only.", userMessage));
      JsonNode root = parseJson(raw);
      if (root == null || !root.path("selected").isArray()) {
        return Optional.empty();
      }
      java.util.List<String> selected = new java.util.ArrayList<>();
      root.path("selected").forEach(node -> selected.add(node.asText()));
      return Optional.of(String.join(",", selected));
    } catch (RuntimeException ex) {
      log.debug("Side query memory selection failed: {}", ex.getMessage());
      return Optional.empty();
    }
  }

  Optional<SideQueryIntent> parseResponse(String raw) {
    JsonNode root = parseJson(raw);
    if (root == null) {
      return Optional.empty();
    }
    String intentStr = textOrNull(root, "intent");
    if (intentStr == null) {
      return Optional.empty();
    }
    SideQueryIntentType intent;
    try {
      intent = SideQueryIntentType.valueOf(intentStr.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
    String directionStr = textOrNull(root, "direction");
    Integer depth = root.path("depth").isInt() ? root.path("depth").asInt() : null;
    Boolean explain = root.path("explain").isBoolean() ? root.path("explain").asBoolean() : null;
    Boolean graphContext =
        root.path("graphContext").isBoolean() ? root.path("graphContext").asBoolean() : null;
    return Optional.of(
        new SideQueryIntent(
            intent,
            textOrNull(root, "symbol"),
            directionStr,
            depth,
            explain,
            textOrNull(root, "searchQuery"),
            graphContext,
            textOrNull(root, "planQuery")));
  }

  static JsonNode parseJson(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String trimmed = raw.trim();
    Matcher block = JSON_BLOCK.matcher(trimmed);
    if (block.find()) {
      trimmed = block.group(1).trim();
    }
    try {
      return OBJECT_MAPPER.readTree(trimmed);
    } catch (Exception ex) {
      int start = trimmed.indexOf('{');
      int end = trimmed.lastIndexOf('}');
      if (start >= 0 && end > start) {
        try {
          return OBJECT_MAPPER.readTree(trimmed.substring(start, end + 1));
        } catch (Exception ignored) {
          return null;
        }
      }
      return null;
    }
  }

  private static String textOrNull(JsonNode root, String field) {
    JsonNode node = root.path(field);
    if (node.isMissingNode() || node.isNull()) {
      return null;
    }
    String text = node.asText().trim();
    return text.isEmpty() ? null : text;
  }

  public enum SideQueryIntentType {
    ASK,
    SEARCH,
    INDEX,
    USAGES,
    IMPACT,
    PLAN,
    FIX
  }

  public record SideQueryIntent(
      SideQueryIntentType intent,
      String symbol,
      String direction,
      Integer depth,
      Boolean explain,
      String searchQuery,
      Boolean graphContext,
      String planQuery) {}
}
