package com.andromedia.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.llm.ChatCompletionService;
import com.andromedia.llm.ChatException;
import com.andromedia.llm.RagPrompt;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Synthesis behaviour for search results.
 *
 * The service must never present a failure as an answer, and must keep every
 * claim attributable — a summary that outruns its sources would be worse than
 * the raw hits it replaced.
 */
class SearchAnswerServiceTest {

  /** Captures the prompt and returns a fixed reply. */
  private static final class StubChat implements ChatCompletionService {
    RagPrompt lastPrompt;
    String reply;

    StubChat(String reply) {
      this.reply = reply;
    }

    @Override
    public void streamAnswer(RagPrompt prompt, Consumer<String> onToken) {
      this.lastPrompt = prompt;
      onToken.accept(reply);
    }
  }

  private static final class FailingChat implements ChatCompletionService {
    @Override
    public void streamAnswer(RagPrompt prompt, Consumer<String> onToken) {
      throw new ChatException("DeepSeek API key is required");
    }
  }

  private static Map<String, Object> fileHit(String path, String content) {
    Map<String, Object> hit = new LinkedHashMap<>();
    hit.put("path", path);
    hit.put("filePath", path);
    hit.put("fileName", path.substring(path.lastIndexOf('/') + 1));
    hit.put("sourceType", "CODE");
    hit.put("chunkId", "c-" + path);
    hit.put("content", content);
    return hit;
  }

  private static Map<String, Object> chatHit(String name, String content) {
    Map<String, Object> hit = new LinkedHashMap<>();
    hit.put("path", "googlechat://spaces/abc/threads/xyz");
    hit.put("filePath", "googlechat://spaces/abc/threads/xyz");
    hit.put("fileName", name);
    hit.put("sourceType", "GOOGLE_CHAT");
    hit.put("chunkId", "c-" + name);
    hit.put("content", content);
    return hit;
  }

  @Test
  void returnsTheModelAnswerAndCitations() {
    StubChat chat = new StubChat("Auth retries three times (src/auth.ts).");
    SearchAnswerService service = new SearchAnswerService(chat);

    SearchAnswerService.Answer answer =
        service.answer("why does auth retry?", List.of(fileHit("src/auth.ts", "maxRetries = 3")));

    assertTrue(answer.grounded());
    assertTrue(answer.text().contains("retries three times"));
    assertEquals(1, answer.citations().size());
    assertEquals("src/auth.ts", answer.citations().getFirst().get("label"));
  }

  @Test
  void evidenceBlocksAreLabelledSoTheModelCanCite() {
    StubChat chat = new StubChat("ok");
    SearchAnswerService service = new SearchAnswerService(chat);
    service.answer("q", List.of(fileHit("src/auth.ts", "maxRetries = 3")));

    assertTrue(
        chat.lastPrompt.userMessage().contains("[src/auth.ts]"),
        () -> "evidence should carry a citable label, got: " + chat.lastPrompt.userMessage());
    assertTrue(chat.lastPrompt.userMessage().contains("maxRetries = 3"));
  }

  @Test
  void nonFileSourcesAreLabelledByTypeAndName() {
    // zoho:// and googlechat:// paths are not readable by a person, so the label
    // falls back to the stored human name.
    assertEquals("GOOGLE_CHAT Salesioo", SearchAnswerService.citationLabel(chatHit("Salesioo", "hi")));
  }

  @Test
  void reportsUngroundedWhenThereAreNoHits() {
    SearchAnswerService service = new SearchAnswerService(new StubChat("should not be used"));
    SearchAnswerService.Answer answer = service.answer("anything", List.of());

    assertFalse(answer.grounded());
    assertTrue(answer.citations().isEmpty());
  }

  @Test
  void surfacesAModelFailureInsteadOfReturningAThinAnswer() {
    // A swallowed error would look like a real reply, which is the failure mode
    // this guards: the user must be able to tell "no answer" from "no match".
    SearchAnswerService service = new SearchAnswerService(new FailingChat());

    SearchAnswerService.Answer answer =
        service.answer("q", List.of(fileHit("src/auth.ts", "code")));

    assertFalse(answer.grounded());
    assertTrue(answer.text().contains("could not be generated"));
    // The evidence is still reported, so the user can read the sources directly.
    assertEquals(1, answer.citations().size());
  }

  @Test
  void treatsABlankReplyAsUngrounded() {
    SearchAnswerService service = new SearchAnswerService(new StubChat("   "));
    SearchAnswerService.Answer answer =
        service.answer("q", List.of(fileHit("src/auth.ts", "code")));

    assertFalse(answer.grounded());
  }

  @Test
  void boundsTheEvidenceSentToTheModel() {
    StubChat chat = new StubChat("ok");
    SearchAnswerService service = new SearchAnswerService(chat);

    List<Map<String, Object>> many = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      many.add(fileHit("src/file" + i + ".ts", "x".repeat(1200)));
    }
    service.answer("q", many);

    assertTrue(
        chat.lastPrompt.userMessage().length() < 40_000,
        "prompt should be capped, was " + chat.lastPrompt.userMessage().length());
  }
}
