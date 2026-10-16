package com.andromedia.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenRouterChatServiceTest {

  @Test
  void parsesSseDeltaContent() {
    List<String> tokens = new ArrayList<>();
    OpenRouterChatService.parseSseLine(
        "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}",
        tokens::add);
    assertEquals("Hello", tokens.getFirst());
  }

  @Test
  void ignoresDoneMarker() {
    List<String> tokens = new ArrayList<>();
    OpenRouterChatService.parseSseLine("data: [DONE]", tokens::add);
    assertTrue(tokens.isEmpty());
  }

  @Test
  void streamsTokensFromChatCompletionsEndpoint() throws Exception {
    ChatProperties properties = new ChatProperties();
    properties.setApiKey("test-key");
    properties.setBaseUrl("https://openrouter.ai/api/v1");
    properties.setModel("moonshotai/kimi-k2.6:free");

    RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl());
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-key"))
        .andExpect(jsonPath("$.model").value("moonshotai/kimi-k2.6:free"))
        .andExpect(jsonPath("$.stream").value(true))
        .andRespond(
            withSuccess(
                """
                data: {"choices":[{"delta":{"content":"Hi"}}]}

                data: {"choices":[{"delta":{"content":" there"}}]}

                data: [DONE]

                """,
                MediaType.TEXT_EVENT_STREAM));

    OpenRouterChatService service = new OpenRouterChatService(properties, builder.build());
    List<String> tokens = new ArrayList<>();
    service.streamAnswer(new RagPrompt("system", "user"), tokens::add);

    assertEquals("Hi there", String.join("", tokens));
    server.verify();
  }

  @Test
  void streamsTokensFromGroqEndpoint() throws Exception {
    ChatProperties properties = new ChatProperties();
    properties.setProvider("groq");
    properties.setApiKey("groq-test-key");
    properties.setBaseUrl("https://api.groq.com/openai/v1");
    properties.setModel("qwen/qwen3.8-27b");

    RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl());
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer groq-test-key"))
        .andExpect(jsonPath("$.model").value("qwen/qwen3.8-27b"))
        .andExpect(jsonPath("$.stream").value(true))
        .andRespond(
            withSuccess(
                """
                data: {"choices":[{"delta":{"content":"Fast"}}]}

                data: [DONE]

                """,
                MediaType.TEXT_EVENT_STREAM));

    OpenRouterChatService service = new OpenRouterChatService(properties, builder.build());
    List<String> tokens = new ArrayList<>();
    service.streamAnswer(new RagPrompt("system", "user"), tokens::add);

    assertEquals("Fast", tokens.getFirst());
    server.verify();
  }

  @Test
  void groqDefaultsUseGroqApiKeyEnvVar() {
    ChatProperties properties = new ChatProperties();
    assertEquals("groq", properties.provider());
    assertEquals("https://api.groq.com/openai/v1", properties.baseUrl());
    assertEquals("qwen/qwen3.8-27b", properties.model());
    assertEquals("GROQ_API_KEY", properties.apiKeyEnvVar());
    assertEquals("Groq", properties.providerLabel());
  }

  @Test
  void retriesOn429WithoutWritingToStderr() {
    java.io.PrintStream originalErr = System.err;
    java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
    System.setErr(new java.io.PrintStream(captured));
    try {
      retriesOn429ThenSucceeds();
      String stderr = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(stderr.isBlank(), "stderr should stay empty during retries: " + stderr);
    } finally {
      System.setErr(originalErr);
    }
  }

  private void retriesOn429ThenSucceeds() {
    ChatProperties properties = fastRetryProperties();
    properties.setMaxRetries(3);

    RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl());
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
    server
        .expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
        .andRespond(
            withSuccess(
                """
                data: {"choices":[{"delta":{"content":"OK"}}]}

                data: [DONE]

                """,
                MediaType.TEXT_EVENT_STREAM));

    OpenRouterChatService service = new OpenRouterChatService(properties, builder.build());
    List<String> tokens = new ArrayList<>();
    service.streamAnswer(new RagPrompt("system", "user"), tokens::add);

    assertEquals("OK", tokens.getFirst());
    server.verify();
  }

  @Test
  void givesUpAfterMaxRetriesOn429() {
    ChatProperties properties = fastRetryProperties();
    properties.setMaxRetries(2);

    RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl());
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
    server
        .expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
    server
        .expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

    OpenRouterChatService service = new OpenRouterChatService(properties, builder.build());
    ChatException ex =
        assertThrows(
            ChatException.class,
            () -> service.streamAnswer(new RagPrompt("system", "user"), token -> {}));

    assertTrue(ex.getMessage().contains("failed after 3 attempts"));
    assertTrue(ex.getMessage().contains("429"));
    server.verify();
  }

  @Test
  void doesNotRetryOn413AndSuggestsSmallerPrompt() {
    ChatProperties properties = fastRetryProperties();
    properties.setProvider("groq");
    properties.setMaxRetries(3);

    RestClient.Builder builder = RestClient.builder().baseUrl("https://api.groq.com/openai/v1");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
        .andRespond(withStatus(HttpStatus.PAYLOAD_TOO_LARGE));

    OpenRouterChatService service = new OpenRouterChatService(properties, builder.build());
    ChatException ex =
        assertThrows(
            ChatException.class,
            () -> service.streamAnswer(new RagPrompt("system", "user"), token -> {}));

    assertTrue(ex.getMessage().contains("413"));
    assertTrue(ex.getMessage().contains("max-prompt-chars"));
    assertTrue(ex.getMessage().contains("-n 4"));
    server.verify();
  }

  @Test
  void doesNotRetryOn401() {
    ChatProperties properties = fastRetryProperties();
    properties.setMaxRetries(3);

    RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl());
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

    OpenRouterChatService service = new OpenRouterChatService(properties, builder.build());
    ChatException ex =
        assertThrows(
            ChatException.class,
            () -> service.streamAnswer(new RagPrompt("system", "user"), token -> {}));

    assertTrue(ex.getMessage().contains("401"));
    server.verify();
  }

  @Test
  void parseSseStreamReadsMultipleLines() throws Exception {
    String payload =
        """
        data: {"choices":[{"delta":{"content":"A"}}]}

        data: [DONE]

        """;
    List<String> tokens = new ArrayList<>();
    OpenRouterChatService.parseSseStream(
        new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)), tokens::add);
    assertEquals("A", tokens.getFirst());
  }

  private static ChatProperties fastRetryProperties() {
    ChatProperties properties = new ChatProperties();
    properties.setApiKey("test-key");
    properties.setBaseUrl("https://openrouter.ai/api/v1");
    properties.setModel("moonshotai/kimi-k2.6:free");
    properties.setRetryInitialDelayMs(1);
    properties.setRetryMinDelayMsFor429(1);
    properties.setRetryMaxDelayMs(100);
    properties.setRetryBackoffMultiplier(2.0);
    return properties;
  }
}
