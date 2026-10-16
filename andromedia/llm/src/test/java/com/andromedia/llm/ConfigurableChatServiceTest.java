package com.andromedia.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ConfigurableChatServiceTest {

  @Test
  void exposesConfiguredProvidersAndSwitchesAtRuntime() {
    ChatProperties properties = properties();
    ConfigurableChatService service = new ConfigurableChatService(properties);

    assertEquals("groq", service.selection().activeProvider());
    assertTrue(
        service.selection().providers().stream()
            .filter(provider -> provider.id().equals("groq"))
            .findFirst()
            .orElseThrow()
            .configured());
    ConfigurableChatService.ProviderStatus deepseek =
        service.selection().providers().stream()
            .filter(provider -> provider.id().equals("deepseek"))
            .findFirst()
            .orElseThrow();
    assertEquals("DeepSeek", deepseek.label());
    assertEquals("deepseek-v4-flash", deepseek.model());
    assertTrue(deepseek.configured());

    ConfigurableChatService.ProviderSelection selection = service.select("openrouter");

    assertEquals("openrouter", selection.activeProvider());
    assertTrue(
        selection.providers().stream()
            .filter(provider -> provider.id().equals("openrouter"))
            .findFirst()
            .orElseThrow()
            .active());
  }

  @Test
  void acceptsCommonGrogTypoAsGroq() {
    ConfigurableChatService service = new ConfigurableChatService(properties());

    assertEquals("groq", service.select("grog").activeProvider());
  }

  @Test
  void mapsDeepSeekApiKeyEnvironmentVariable() {
    ChatProperties properties = new ChatProperties();
    properties.setProvider("deepseek");

    assertEquals("DEEPSEEK_API_KEY", properties.apiKeyEnvVar());
    assertEquals("DeepSeek", properties.providerLabel());
  }

  @Test
  void updatesModelAndApiKeyWithoutReturningSecret() {
    ChatProperties properties = properties();
    properties.getProviders().get("openrouter").setApiKey("");
    ConfigurableChatService service = new ConfigurableChatService(properties);

    ConfigurableChatService.ProviderSelection selection =
        service.update("openrouter", "openai/gpt-4o-mini", "new-openrouter-key");

    assertEquals("openrouter", selection.activeProvider());
    ConfigurableChatService.ProviderStatus openrouter =
        selection.providers().stream()
            .filter(provider -> provider.id().equals("openrouter"))
            .findFirst()
            .orElseThrow();
    assertEquals("openai/gpt-4o-mini", openrouter.model());
    assertTrue(openrouter.apiKeyConfigured());
    assertTrue(openrouter.configured());
    assertFalse(selection.toString().contains("new-openrouter-key"));
  }

  @Test
  void blankApiKeyKeepsExistingSecret() {
    ConfigurableChatService service = new ConfigurableChatService(properties());

    service.update("groq", "llama-3.1-8b-instant", null);
    ConfigurableChatService.ProviderSelection selection = service.update("groq", null, "   ");

    ConfigurableChatService.ProviderStatus groq =
        selection.providers().stream()
            .filter(provider -> provider.id().equals("groq"))
            .findFirst()
            .orElseThrow();
    assertEquals("llama-3.1-8b-instant", groq.model());
    assertTrue(groq.apiKeyConfigured());
  }

  @Test
  void rejectsUnavailableAndUnsupportedProviders() {
    ChatProperties properties = properties();
    properties.getProviders().get("openrouter").setApiKey("");
    ConfigurableChatService service = new ConfigurableChatService(properties);

    IllegalArgumentException unavailable =
        assertThrows(IllegalArgumentException.class, () -> service.select("openrouter"));
    assertTrue(unavailable.getMessage().toLowerCase().contains("api key"));

    assertThrows(IllegalArgumentException.class, () -> service.select("unknown"));
    assertFalse(
        service.selection().providers().stream()
            .filter(provider -> provider.id().equals("openrouter"))
            .findFirst()
            .orElseThrow()
            .configured());
  }

  @Test
  void exposesOllamaProviderAndUsesInstalledModelWhenReachable() {
    ConfigurableChatService service = new ConfigurableChatService(properties());
    assertTrue(
        service.selection().providers().stream().anyMatch(provider -> provider.id().equals("ollama")));

    List<String> installed = OllamaModelCatalog.listModels("http://127.0.0.1:11434/v1");
    if (installed.isEmpty()) {
      return;
    }

    ConfigurableChatService.ProviderSelection selection = service.select("ollama");
    assertEquals("ollama", selection.activeProvider());
    ConfigurableChatService.ProviderStatus ollama =
        selection.providers().stream()
            .filter(provider -> provider.id().equals("ollama"))
            .findFirst()
            .orElseThrow();
    assertTrue(ollama.configured());
    assertTrue(ollama.apiKeyConfigured());
    assertFalse(ollama.availableModels().isEmpty());
    assertTrue(installed.contains(ollama.model()));
  }

  @Test
  void withApiKeyOverrideDoesNotRequireEnvKey() {
    ChatProperties properties = properties();
    properties.getProviders().get("openrouter").setApiKey("");
    properties.setProvider("openrouter");
    ConfigurableChatService service = new ConfigurableChatService(properties);

    ChatException missing =
        assertThrows(ChatException.class, () -> service.complete(new RagPrompt("s", "u")));
    assertTrue(missing.getMessage().contains("OPENROUTER_API_KEY"));

    try {
      service.withApiKeyOverride("sk-from-vault", () -> service.complete(new RagPrompt("s", "u")));
    } catch (ChatException ex) {
      assertFalse(
          ex.getMessage().contains("API key is required"),
          () -> "vault override should satisfy the missing-key check: " + ex.getMessage());
    }
  }

  @Test
  void fallsBackToDeepSeekAfterTransientGroqFailureForComplete() {
    ChatProperties properties = properties();
    properties.setMaxRetries(0);

    RestClient.Builder groqBuilder = RestClient.builder().baseUrl("https://api.groq.com/openai/v1");
    RestClient.Builder deepseekBuilder =
        RestClient.builder().baseUrl("https://api.deepseek.com/v1");
    MockRestServiceServer groqServer = MockRestServiceServer.bindTo(groqBuilder).build();
    MockRestServiceServer deepseekServer = MockRestServiceServer.bindTo(deepseekBuilder).build();
    ConfigurableChatService service = testService(properties, groqBuilder, deepseekBuilder);
    groqServer
        .expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    deepseekServer
        .expect(requestTo("https://api.deepseek.com/v1/chat/completions"))
        .andRespond(
            withSuccess(
                """
                {"choices":[{"message":{"content":"DeepSeek answer"}}]}
                """,
                MediaType.APPLICATION_JSON));

    String answer = service.complete(new RagPrompt("system", "user"));

    assertEquals("DeepSeek answer", answer);
    groqServer.verify();
    deepseekServer.verify();
  }

  @Test
  void fallsBackToDeepSeekForTokenlessTransientStream() {
    ChatProperties properties = properties();
    properties.setMaxRetries(0);

    RestClient.Builder groqBuilder = RestClient.builder().baseUrl("https://api.groq.com/openai/v1");
    RestClient.Builder deepseekBuilder =
        RestClient.builder().baseUrl("https://api.deepseek.com/v1");
    MockRestServiceServer groqServer = MockRestServiceServer.bindTo(groqBuilder).build();
    MockRestServiceServer deepseekServer = MockRestServiceServer.bindTo(deepseekBuilder).build();
    ConfigurableChatService service = testService(properties, groqBuilder, deepseekBuilder);
    groqServer
        .expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
    deepseekServer
        .expect(requestTo("https://api.deepseek.com/v1/chat/completions"))
        .andRespond(
            withSuccess(
                """
                data: {"choices":[{"delta":{"content":"Fallback"}}]}

                data: [DONE]
                """,
                MediaType.TEXT_EVENT_STREAM));

    List<String> tokens = new java.util.ArrayList<>();
    service.streamAnswer(new RagPrompt("system", "user"), tokens::add);

    assertEquals("Fallback", String.join("", tokens));
    groqServer.verify();
    deepseekServer.verify();
  }

  @Test
  void doesNotFallbackAfterPartialGroqStream() {
    ChatProperties properties = properties();
    properties.setMaxRetries(0);

    RestClient.Builder groqBuilder = RestClient.builder().baseUrl("https://api.groq.com/openai/v1");
    RestClient.Builder deepseekBuilder =
        RestClient.builder().baseUrl("https://api.deepseek.com/v1");
    MockRestServiceServer groqServer = MockRestServiceServer.bindTo(groqBuilder).build();
    MockRestServiceServer deepseekServer = MockRestServiceServer.bindTo(deepseekBuilder).build();
    ConfigurableChatService service = testService(properties, groqBuilder, deepseekBuilder);
    groqServer
        .expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
        .andRespond(
            withSuccess(
                """
                data: {"choices":[{"delta":{"content":"Partial"}}]}

                data: {invalid-json}
                """,
                MediaType.TEXT_EVENT_STREAM));

    List<String> tokens = new java.util.ArrayList<>();
    assertThrows(
        ChatException.class,
        () -> service.streamAnswer(new RagPrompt("system", "user"), tokens::add));

    assertEquals(List.of("Partial"), tokens);
    groqServer.verify();
    deepseekServer.verify();
  }

  @Test
  void doesNotFallbackForGroqAuthenticationFailure() {
    ChatProperties properties = properties();

    RestClient.Builder groqBuilder = RestClient.builder().baseUrl("https://api.groq.com/openai/v1");
    RestClient.Builder deepseekBuilder =
        RestClient.builder().baseUrl("https://api.deepseek.com/v1");
    MockRestServiceServer groqServer = MockRestServiceServer.bindTo(groqBuilder).build();
    MockRestServiceServer deepseekServer = MockRestServiceServer.bindTo(deepseekBuilder).build();
    ConfigurableChatService service = testService(properties, groqBuilder, deepseekBuilder);
    groqServer
        .expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

    ChatException failure =
        assertThrows(
            ChatException.class, () -> service.complete(new RagPrompt("system", "user")));

    assertTrue(failure.getMessage().contains("401"));
    groqServer.verify();
    deepseekServer.verify();
  }

  @Test
  void doesNotFallbackForGroqPayloadFailure() {
    ChatProperties properties = properties();

    RestClient.Builder groqBuilder = RestClient.builder().baseUrl("https://api.groq.com/openai/v1");
    RestClient.Builder deepseekBuilder =
        RestClient.builder().baseUrl("https://api.deepseek.com/v1");
    MockRestServiceServer groqServer = MockRestServiceServer.bindTo(groqBuilder).build();
    MockRestServiceServer deepseekServer = MockRestServiceServer.bindTo(deepseekBuilder).build();
    ConfigurableChatService service = testService(properties, groqBuilder, deepseekBuilder);
    groqServer
        .expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
        .andRespond(withStatus(HttpStatus.PAYLOAD_TOO_LARGE));

    ChatException failure =
        assertThrows(
            ChatException.class, () -> service.complete(new RagPrompt("system", "user")));

    assertTrue(failure.getMessage().contains("413"));
    groqServer.verify();
    deepseekServer.verify();
  }

  private static ChatProperties properties() {
    ChatProperties properties = new ChatProperties();
    properties.setProvider("groq");
    properties.setProviders(
        Map.of(
            "groq",
            provider(
                "https://api.groq.com/openai/v1", "qwen/qwen3.8-27b", "groq-key"),
            "deepseek",
            provider("https://api.deepseek.com/v1", "deepseek-v4-flash", "deepseek-key"),
            "openrouter",
            provider(
                "https://openrouter.ai/api/v1",
                "meta-llama/llama-3.3-70b-instruct",
                "openrouter-key"),
            "ollama",
            provider("http://127.0.0.1:11434/v1", "qwen2.5-coder:7b", "")));
    return properties;
  }

  private static ConfigurableChatService testService(
      ChatProperties properties,
      RestClient.Builder groqBuilder,
      RestClient.Builder deepseekBuilder) {
    return new ConfigurableChatService(
        properties,
        config -> {
          RestClient.Builder builder =
              config.baseUrl().contains("deepseek") ? deepseekBuilder : groqBuilder;
          return new OpenRouterChatService(config, builder.build());
        });
  }

  private static ChatProperties.Provider provider(String baseUrl, String model, String apiKey) {
    ChatProperties.Provider provider = new ChatProperties.Provider();
    provider.setBaseUrl(baseUrl);
    provider.setModel(model);
    provider.setApiKey(apiKey);
    return provider;
  }
}
