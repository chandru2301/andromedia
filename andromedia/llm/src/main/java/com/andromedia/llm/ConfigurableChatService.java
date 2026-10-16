package com.andromedia.llm;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime-selectable chat provider for OpenAI-compatible APIs.
 *
 * <p>Provider credentials may be seeded from environment/config and updated at runtime. API keys
 * are never returned by {@link #selection()}. Requests already in progress continue with the
 * provider client they started on.
 */
public class ConfigurableChatService implements ChatCompletionService, StructuredCompletionService {

  private static final Logger log = LoggerFactory.getLogger(ConfigurableChatService.class);
  public static final List<String> SUPPORTED_PROVIDERS =
      List.of("groq", "deepseek", "openrouter", "ollama");

  private static final ThreadLocal<String> API_KEY_OVERRIDE = new ThreadLocal<>();

  private final ChatProperties shared;
  private final ConcurrentHashMap<String, ChatProperties.Provider> providers;
  private final ConcurrentHashMap<String, OpenRouterChatService> clients = new ConcurrentHashMap<>();
  private final Function<ChatProperties, OpenRouterChatService> clientFactory;
  private final AtomicReference<String> activeProvider;

  public ConfigurableChatService(ChatProperties properties) {
    this(properties, OpenRouterChatService::new);
  }

  ConfigurableChatService(
      ChatProperties properties, Function<ChatProperties, OpenRouterChatService> clientFactory) {
    this.shared = properties;
    this.clientFactory = clientFactory;
    this.providers = configuredProviders(properties);
    String initial = normalize(properties.provider());
    if (!SUPPORTED_PROVIDERS.contains(initial)) {
      initial = "groq";
    }
    if (!providers.containsKey(initial)) {
      providers.put(initial, defaultProvider(initial));
    }
    this.activeProvider = new AtomicReference<>(initial);
  }

  @Override
  public void streamAnswer(RagPrompt prompt, Consumer<String> onToken) {
    streamAnswer(prompt, onToken, ChatProgressListener.NOOP);
  }

  @Override
  public void streamAnswer(
      RagPrompt prompt, Consumer<String> onToken, ChatProgressListener listener) {
    String provider = activeProvider.get();
    AtomicBoolean emittedToken = new AtomicBoolean(false);
    try {
      client(provider).streamAnswer(
          prompt,
          token -> {
            emittedToken.set(true);
            onToken.accept(token);
          },
          listener);
    } catch (ChatException ex) {
      if (!shouldFallback(provider, ex, emittedToken.get())) {
        throw ex;
      }
      logFallback(provider);
      client("deepseek", false).streamAnswer(prompt, onToken, listener);
    }
  }

  @Override
  public String complete(RagPrompt prompt) {
    String provider = activeProvider.get();
    try {
      return client(provider).complete(prompt);
    } catch (ChatException ex) {
      if (!shouldFallback(provider, ex, false)) {
        throw ex;
      }
      logFallback(provider);
      return client("deepseek", false).complete(prompt);
    }
  }

  public ProviderSelection selection() {
    return new ProviderSelection(activeProvider.get(), statuses());
  }

  public ProviderSelection select(String requestedProvider) {
    return update(requestedProvider, null, null);
  }

  /**
   * Runs {@code action} with a per-request API key override (ThreadLocal). Cleared afterward so
   * multi-tenant requests do not leak keys into the shared provider config.
   */
  public <T> T withApiKeyOverride(String apiKey, java.util.function.Supplier<T> action) {
    if (apiKey == null || apiKey.isBlank()) {
      return action.get();
    }
    API_KEY_OVERRIDE.set(apiKey.trim());
    try {
      return action.get();
    } finally {
      API_KEY_OVERRIDE.remove();
    }
  }

  /**
   * Updates the active provider and optionally its model / API key.
   *
   * <p>Blank or null {@code apiKey} keeps the existing key. Blank or null {@code model} keeps the
   * existing model.
   */
  public ProviderSelection update(String requestedProvider, String model, String apiKey) {
    String provider = normalize(requestedProvider);
    if (!SUPPORTED_PROVIDERS.contains(provider)) {
      throw new IllegalArgumentException(
          "Unsupported chat provider. Supported providers: " + SUPPORTED_PROVIDERS);
    }

    ChatProperties.Provider config =
        providers.computeIfAbsent(provider, ConfigurableChatService::defaultProvider);

    if (model != null && !model.isBlank()) {
      config.setModel(model.trim());
    }
    if (apiKey != null && !apiKey.isBlank()) {
      config.setApiKey(apiKey.trim());
    }
    if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
      config.setBaseUrl(defaultProvider(provider).getBaseUrl());
    }
    if (config.getModel() == null || config.getModel().isBlank()) {
      config.setModel(defaultProvider(provider).getModel());
    }

    List<String> localModels = List.of();
    if (isLocalProvider(provider)) {
      localModels = OllamaModelCatalog.listModels(config.getBaseUrl());
      if (localModels.isEmpty()) {
        throw new IllegalArgumentException(
            "Ollama is not reachable or has no installed models. Start `ollama serve` and pull a model "
                + "(e.g. ollama pull qwen2.5-coder:7b).");
      }
      if (config.getModel() == null
          || config.getModel().isBlank()
          || !localModels.contains(config.getModel())) {
        config.setModel(localModels.getFirst());
      }
    }

    if (!isProviderConfigured(provider, config, localModels)) {
      throw new IllegalArgumentException(
          providerLabel(provider)
              + " requires an API key and model. Provide an apiKey or set "
              + apiKeyEnvVar(provider)
              + " on the backend.");
    }

    clients.remove(provider);
    client(provider);
    activeProvider.set(provider);
    return selection();
  }

  private OpenRouterChatService client() {
    return client(activeProvider.get());
  }

  private OpenRouterChatService client(String provider) {
    return client(provider, true);
  }

  private OpenRouterChatService client(String provider, boolean useApiKeyOverride) {
    ChatProperties.Provider config = providers.get(provider);
    String override = useApiKeyOverride ? API_KEY_OVERRIDE.get() : null;
    boolean hasOverride = override != null && !override.isBlank();
    List<String> localModels =
        isLocalProvider(provider)
            ? OllamaModelCatalog.listModels(config == null ? null : config.getBaseUrl())
            : List.of();
    boolean configured =
        hasOverride
            || (config != null && isProviderConfigured(provider, config, localModels));
    if (!configured) {
      throw new ChatException(
          isLocalProvider(provider)
              ? "Ollama is not configured. Start the local Ollama server and select an installed model."
              : providerLabel(provider)
                  + " API key is required. Set "
                  + apiKeyEnvVar(provider)
                  + " on the backend, configure it in settings, or select another configured provider.");
    }
    if (useApiKeyOverride && override != null && !override.isBlank()) {
      return clientFactory.apply(clientProperties(provider, true));
    }
    return clients.computeIfAbsent(
        provider, key -> clientFactory.apply(clientProperties(key, false)));
  }

  private ChatProperties clientProperties(String provider, boolean useApiKeyOverride) {
    ChatProperties.Provider config = providers.get(provider);
    ChatProperties result = new ChatProperties();
    result.setProvider(provider);
    result.setBaseUrl(config.getBaseUrl());
    result.setModel(config.getModel());
    String apiKey = config.getApiKey();
    String override = useApiKeyOverride ? API_KEY_OVERRIDE.get() : null;
    if (override != null && !override.isBlank()) {
      apiKey = override;
    }
    if ((apiKey == null || apiKey.isBlank()) && isLocalProvider(provider)) {
      // Ollama's OpenAI-compatible endpoint accepts any bearer token; keep RestClient happy.
      apiKey = "ollama";
    }
    result.setApiKey(apiKey);
    result.setHttpReferer(config.getHttpReferer());
    result.setAppTitle(config.getAppTitle());
    result.setMaxOutputTokens(shared.maxOutputTokens());
    result.setTemperature(shared.temperature());
    result.setMaxRetries(shared.maxRetries());
    result.setRetryInitialDelayMs(shared.retryInitialDelayMs());
    result.setRetryBackoffMultiplier(shared.retryBackoffMultiplier());
    result.setRetryMinDelayMsFor429(shared.retryMinDelayMsFor429());
    result.setRetryMaxDelayMs(shared.retryMaxDelayMs());
    return result;
  }

  private boolean shouldFallback(String provider, ChatException failure, boolean emittedToken) {
    return "groq".equals(provider)
        && !emittedToken
        && failure.isTransientFailure()
        && isProviderConfigured("deepseek", providers.get("deepseek"), List.of());
  }

  private void logFallback(String provider) {
    log.warn(
        "Primary chat provider {} failed transiently; falling back to DeepSeek", provider);
  }

  private List<ProviderStatus> statuses() {
    return SUPPORTED_PROVIDERS.stream()
        .map(
            provider -> {
              ChatProperties.Provider config =
                  providers.getOrDefault(provider, defaultProvider(provider));
              List<String> availableModels =
                  isLocalProvider(provider)
                      ? OllamaModelCatalog.listModels(config.getBaseUrl())
                      : List.of();
              boolean configured = isProviderConfigured(provider, config, availableModels);
              boolean apiKeyConfigured =
                  isLocalProvider(provider)
                      || (config.getApiKey() != null && !config.getApiKey().isBlank());
              String model = config.getModel() == null ? "" : config.getModel();
              if (isLocalProvider(provider)
                  && !availableModels.isEmpty()
                  && (model.isBlank() || !availableModels.contains(model))) {
                model = availableModels.getFirst();
              }
              return new ProviderStatus(
                  provider,
                  providerLabel(provider),
                  model,
                  configured,
                  apiKeyConfigured,
                  provider.equals(activeProvider.get()),
                  availableModels);
            })
        .toList();
  }

  private static boolean isProviderConfigured(
      String provider, ChatProperties.Provider config, List<String> localModels) {
    if (config == null) {
      return false;
    }
    if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
      return false;
    }
    if (isLocalProvider(provider)) {
      return localModels != null
          && !localModels.isEmpty()
          && config.getModel() != null
          && !config.getModel().isBlank();
    }
    return config.getModel() != null
        && !config.getModel().isBlank()
        && config.getApiKey() != null
        && !config.getApiKey().isBlank();
  }

  private static ConcurrentHashMap<String, ChatProperties.Provider> configuredProviders(
      ChatProperties properties) {
    ConcurrentHashMap<String, ChatProperties.Provider> result = new ConcurrentHashMap<>();
    properties
        .providers()
        .forEach(
            (name, config) -> {
              String provider = normalize(name);
              if (SUPPORTED_PROVIDERS.contains(provider)) {
                result.put(provider, copyProvider(config, provider));
              }
            });

    String legacyProvider = normalize(properties.provider());
    if (SUPPORTED_PROVIDERS.contains(legacyProvider)) {
      result.computeIfAbsent(
          legacyProvider,
          ignored -> {
            ChatProperties.Provider config = defaultProvider(legacyProvider);
            if (properties.baseUrl() != null && !properties.baseUrl().isBlank()) {
              config.setBaseUrl(properties.baseUrl());
            }
            if (properties.model() != null && !properties.model().isBlank()) {
              config.setModel(properties.model());
            }
            if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
              config.setApiKey(properties.apiKey());
            }
            if (properties.httpReferer() != null) {
              config.setHttpReferer(properties.httpReferer());
            }
            if (properties.appTitle() != null) {
              config.setAppTitle(properties.appTitle());
            }
            return config;
          });
    }

    for (String provider : SUPPORTED_PROVIDERS) {
      result.computeIfAbsent(provider, ConfigurableChatService::defaultProvider);
    }
    return result;
  }

  private static ChatProperties.Provider copyProvider(
      ChatProperties.Provider source, String provider) {
    ChatProperties.Provider defaults = defaultProvider(provider);
    ChatProperties.Provider copy = new ChatProperties.Provider();
    copy.setBaseUrl(
        source.getBaseUrl() == null || source.getBaseUrl().isBlank()
            ? defaults.getBaseUrl()
            : source.getBaseUrl());
    copy.setModel(
        source.getModel() == null || source.getModel().isBlank()
            ? defaults.getModel()
            : source.getModel());
    copy.setApiKey(source.getApiKey() == null ? "" : source.getApiKey());
    copy.setHttpReferer(source.getHttpReferer() == null ? "" : source.getHttpReferer());
    copy.setAppTitle(
        source.getAppTitle() == null || source.getAppTitle().isBlank()
            ? defaults.getAppTitle()
            : source.getAppTitle());
    return copy;
  }

  private static ChatProperties.Provider defaultProvider(String provider) {
    ChatProperties.Provider config = new ChatProperties.Provider();
    if ("openrouter".equals(provider)) {
      config.setBaseUrl("https://openrouter.ai/api/v1");
      config.setModel("meta-llama/llama-3.3-70b-instruct");
    } else if ("deepseek".equals(provider)) {
      config.setBaseUrl("https://api.deepseek.com/v1");
      config.setModel("deepseek-v4-flash");
    } else if ("ollama".equals(provider)) {
      config.setBaseUrl("http://127.0.0.1:11434/v1");
      config.setModel("qwen2.5-coder:7b");
    } else {
      config.setBaseUrl("https://api.groq.com/openai/v1");
      config.setModel("qwen/qwen3.8-27b");
    }
    config.setAppTitle("Andromedia");
    return config;
  }

  private static boolean isLocalProvider(String provider) {
    return "ollama".equals(provider);
  }

  private static String normalize(String provider) {
    if (provider == null) {
      return "";
    }
    String normalized = provider.trim().toLowerCase(Locale.ROOT);
    return "grog".equals(normalized) ? "groq" : normalized;
  }

  private static String providerLabel(String provider) {
    return switch (provider) {
      case "deepseek" -> "DeepSeek";
      case "openrouter" -> "OpenRouter";
      case "ollama" -> "Ollama (local)";
      default -> "Groq";
    };
  }

  private static String apiKeyEnvVar(String provider) {
    return switch (provider) {
      case "deepseek" -> "DEEPSEEK_API_KEY";
      case "openrouter" -> "OPENROUTER_API_KEY";
      case "ollama" -> "OLLAMA_HOST";
      default -> "GROQ_API_KEY";
    };
  }

  public record ProviderStatus(
      String id,
      String label,
      String model,
      boolean configured,
      boolean apiKeyConfigured,
      boolean active,
      List<String> availableModels) {
    public ProviderStatus {
      availableModels = availableModels == null ? List.of() : List.copyOf(availableModels);
    }
  }

  public record ProviderSelection(String activeProvider, List<ProviderStatus> providers) {}
}
