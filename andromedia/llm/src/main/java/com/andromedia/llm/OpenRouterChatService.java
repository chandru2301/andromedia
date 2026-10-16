package com.andromedia.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class OpenRouterChatService implements ChatCompletionService, StructuredCompletionService {

  private static final Logger log = LoggerFactory.getLogger(OpenRouterChatService.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(90);
  private static final Duration LOCAL_READ_TIMEOUT = Duration.ofMinutes(5);

  private final ChatProperties properties;
  private final RestClient restClient;

  public OpenRouterChatService(ChatProperties properties) {
    this(properties, buildRestClient(properties));
  }

  OpenRouterChatService(ChatProperties properties, RestClient restClient) {
    this.properties = properties;
    this.restClient = restClient;
    if (!properties.hasApiKey()) {
      throw new ChatException(
          properties.providerLabel()
              + " API key is required. Set "
              + properties.apiKeyEnvVar()
              + " or andromedia.chat.api-key.");
    }
    if (properties.model() == null || properties.model().isBlank()) {
      throw new ChatException("Chat model is required. Set andromedia.chat.model.");
    }
  }

  static RestClient buildRestClient(ChatProperties properties) {
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
    requestFactory.setReadTimeout(readTimeoutFor(properties));
    return RestClient.builder()
        .baseUrl(normalizeBaseUrl(properties.baseUrl()))
        .requestFactory(requestFactory)
        .build();
  }

  private static Duration readTimeoutFor(ChatProperties properties) {
    String provider =
        properties.provider() == null ? "" : properties.provider().trim().toLowerCase(Locale.ROOT);
    return "ollama".equals(provider) ? LOCAL_READ_TIMEOUT : DEFAULT_READ_TIMEOUT;
  }

  @Override
  public void streamAnswer(RagPrompt prompt, Consumer<String> onToken) {
    streamAnswer(prompt, onToken, ChatProgressListener.NOOP);
  }

  @Override
  public void streamAnswer(RagPrompt prompt, Consumer<String> onToken, ChatProgressListener listener) {
    ChatProgressListener progress = listener == null ? ChatProgressListener.NOOP : listener;
    progress.onRequestStarted(properties.model());

    ChatCompletionRequest request = buildRequest(prompt);
    int maxAttempts = properties.maxAttempts();
    RuntimeException lastFailure = null;

    for (int attempt = 0; attempt < maxAttempts; attempt++) {
      try {
        AtomicBoolean firstToken = new AtomicBoolean(true);
        executeStreamOnce(
            request,
            token -> {
              if (firstToken.compareAndSet(true, false)) {
                progress.onFirstToken();
              }
              onToken.accept(token);
            });
        return;
      } catch (RetryableApiException ex) {
        lastFailure = ex;
        if (!shouldRetry(attempt, maxAttempts)) {
          break;
        }
        long delayMs =
            TransientApiRetry.backoffMillis(
                attempt, properties, ex.retryAfterSeconds(), ex.statusCode());
        log.warn(
            "{} chat request failed with status {} (attempt {}/{}), retrying in {}ms",
            properties.providerLabel(),
            ex.statusCode() > 0 ? ex.statusCode() : "network",
            attempt + 1,
            maxAttempts,
            delayMs);
        progress.onRetry(attempt + 1, maxAttempts, delayMs, ex.statusCode());
        if (!progress.handlesRetryDelay()) {
          TransientApiRetry.sleep(delayMs);
        }
      } catch (RestClientException ex) {
        lastFailure = new RetryableApiException("Chat request failed", ex);
        if (!shouldRetry(attempt, maxAttempts)) {
          break;
        }
        long delayMs =
            TransientApiRetry.backoffMillis(attempt, properties, OptionalLong.empty(), 0);
        log.warn(
            "{} chat request failed (attempt {}/{}), retrying in {}ms: {}",
            properties.providerLabel(),
            attempt + 1,
            maxAttempts,
            delayMs,
            ex.getMessage());
        progress.onRetry(attempt + 1, maxAttempts, delayMs, 0);
        if (!progress.handlesRetryDelay()) {
          TransientApiRetry.sleep(delayMs);
        }
      }
    }

    throw finalFailure(properties, maxAttempts, lastFailure);
  }

  @Override
  public String complete(RagPrompt prompt) {
    ChatCompletionRequest request =
        new ChatCompletionRequest(
            properties.model(),
            List.of(
                new ChatMessage("system", prompt.systemMessage()),
                new ChatMessage("user", prompt.userMessage())),
            false,
            properties.maxOutputTokens(),
            properties.temperature());

    int maxAttempts = properties.maxAttempts();
    RuntimeException lastFailure = null;
    for (int attempt = 0; attempt < maxAttempts; attempt++) {
      try {
        return executeCompleteOnce(request);
      } catch (RetryableApiException ex) {
        lastFailure = ex;
        if (!shouldRetry(attempt, maxAttempts)) {
          break;
        }
        TransientApiRetry.sleep(
            TransientApiRetry.backoffMillis(
                attempt, properties, ex.retryAfterSeconds(), ex.statusCode()));
      } catch (RestClientException ex) {
        lastFailure = new RetryableApiException("Chat request failed", ex);
        if (!shouldRetry(attempt, maxAttempts)) {
          break;
        }
        TransientApiRetry.sleep(
            TransientApiRetry.backoffMillis(attempt, properties, OptionalLong.empty(), 0));
      }
    }
    throw finalFailure(properties, maxAttempts, lastFailure);
  }

  private String executeCompleteOnce(ChatCompletionRequest request) {
    return restClient
        .post()
        .uri("/chat/completions")
        .headers(this::applyHeaders)
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange(
            (httpRequest, response) -> {
              HttpStatusCode status = response.getStatusCode();
              if (status.isError()) {
                int statusCode = status.value();
                String message = properties.providerLabel() + " chat request failed with status " + status;
                if (TransientApiRetry.isRetryableStatus(statusCode)) {
                  throw new RetryableApiException(
                      message, statusCode, parseRetryAfterSeconds(response.getHeaders()));
                }
                throw new ChatException(message);
              }
              try (InputStream body = response.getBody()) {
                if (body == null) {
                  throw new ChatException(properties.providerLabel() + " chat response had no body");
                }
                return extractMessageContent(body);
              } catch (IOException ex) {
                throw new ChatException("Failed to read chat response", ex);
              }
            });
  }

  static String extractMessageContent(InputStream body) throws IOException {
    JsonNode root = OBJECT_MAPPER.readTree(body);
    JsonNode choices = root.path("choices");
    if (!choices.isArray() || choices.isEmpty()) {
      return "";
    }
    JsonNode content = choices.get(0).path("message").path("content");
    return content.isMissingNode() || content.isNull() ? "" : content.asText();
  }

  private void executeStreamOnce(ChatCompletionRequest request, Consumer<String> onToken) {
    restClient
        .post()
        .uri("/chat/completions")
        .headers(this::applyHeaders)
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_EVENT_STREAM)
        .body(request)
        .exchange(
            (httpRequest, response) -> {
              HttpStatusCode status = response.getStatusCode();
              if (status.isError()) {
                int statusCode = status.value();
                if (statusCode == 413) {
                  throw new ChatException(payloadTooLargeMessage(status));
                }
                String message = properties.providerLabel() + " chat request failed with status " + status;
                if (TransientApiRetry.isRetryableStatus(statusCode)) {
                  throw new RetryableApiException(
                      message, statusCode, parseRetryAfterSeconds(response.getHeaders()));
                }
                throw new ChatException(message);
              }
              try (InputStream body = response.getBody()) {
                if (body == null) {
                  throw new ChatException(properties.providerLabel() + " chat response had no body");
                }
                parseSseStream(body, onToken);
              } catch (IOException ex) {
                throw new ChatException("Failed to read chat stream", ex);
              }
              return null;
            });
  }

  private String payloadTooLargeMessage(HttpStatusCode status) {
    return properties.providerLabel()
        + " rejected the request as too large (HTTP "
        + status.value()
        + "). Try fewer chunks: andro ask -n 4 \"...\", lower andromedia.ask.max-prompt-chars"
        + " in application.yml, or andro --debug ask to inspect prompt size.";
  }

  private static boolean shouldRetry(int attempt, int maxAttempts) {
    return attempt < maxAttempts - 1;
  }

  private static ChatException finalFailure(
      ChatProperties properties, int maxAttempts, RuntimeException lastFailure) {
    String lastMessage =
        lastFailure == null ? "unknown error" : lastFailure.getMessage();
    boolean rateLimited = false;
    if (lastFailure instanceof RetryableApiException retryable && retryable.statusCode() > 0) {
      lastMessage = "status " + retryable.statusCode();
      rateLimited = retryable.statusCode() == 429;
    }
    StringBuilder message =
        new StringBuilder(
            properties.providerLabel()
                + " chat request failed after "
                + maxAttempts
                + " attempts (last: "
                + lastMessage
                + ")");
    if (rateLimited) {
      message.append(
          ". Rate limited — wait a minute, then retry, or set andromedia.chat.model in application.yml");
    }
    return new ChatException(
        message.toString(),
        lastFailure,
        lastFailure instanceof RetryableApiException);
  }

  private static OptionalLong parseRetryAfterSeconds(HttpHeaders headers) {
    if (headers == null) {
      return OptionalLong.empty();
    }
    String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
    if (value == null || value.isBlank()) {
      return OptionalLong.empty();
    }
    try {
      return OptionalLong.of(Long.parseLong(value.trim()));
    } catch (NumberFormatException ex) {
      return OptionalLong.empty();
    }
  }

  private ChatCompletionRequest buildRequest(RagPrompt prompt) {
    return new ChatCompletionRequest(
        properties.model(),
        List.of(
            new ChatMessage("system", prompt.systemMessage()),
            new ChatMessage("user", prompt.userMessage())),
        true,
        properties.maxOutputTokens(),
        properties.temperature());
  }

  static void parseSseStream(InputStream body, Consumer<String> onToken) throws IOException {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        parseSseLine(line, onToken);
      }
    }
  }

  static void parseSseLine(String line, Consumer<String> onToken) {
    if (line == null || line.isBlank()) {
      return;
    }
    if (!line.startsWith("data:")) {
      return;
    }
    String payload = line.substring("data:".length()).trim();
    if (payload.isEmpty() || "[DONE]".equals(payload)) {
      return;
    }
    String token = extractDeltaContent(payload);
    if (token != null && !token.isEmpty()) {
      onToken.accept(token);
    }
  }

  static String extractDeltaContent(String jsonPayload) {
    try {
      JsonNode root = OBJECT_MAPPER.readTree(jsonPayload);
      JsonNode choices = root.path("choices");
      if (!choices.isArray() || choices.isEmpty()) {
        return null;
      }
      JsonNode delta = choices.get(0).path("delta");
      JsonNode content = delta.path("content");
      if (content.isMissingNode() || content.isNull()) {
        return null;
      }
      return content.asText();
    } catch (IOException ex) {
      throw new ChatException("Failed to parse chat stream chunk", ex);
    }
  }

  private void applyHeaders(HttpHeaders headers) {
    headers.setBearerAuth(properties.apiKey());
    if (!properties.httpReferer().isBlank()) {
      headers.set("HTTP-Referer", properties.httpReferer());
    }
    if (!properties.appTitle().isBlank()) {
      headers.set("X-Title", properties.appTitle());
    }
  }

  private static String normalizeBaseUrl(String baseUrl) {
    if (baseUrl.endsWith("/")) {
      return baseUrl.substring(0, baseUrl.length() - 1);
    }
    return baseUrl;
  }

  private record ChatCompletionRequest(
      String model,
      List<ChatMessage> messages,
      boolean stream,
      int max_tokens,
      double temperature) {}

  private record ChatMessage(String role, String content) {}
}
