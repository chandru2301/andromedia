package com.andromedia.llm;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "andromedia.chat")
public class ChatProperties {

  private static final String DEFAULT_BASE_URL = "https://api.groq.com/openai/v1";
  private static final String DEFAULT_MODEL = "qwen/qwen3.8-27b";

  private String provider = "groq";
  private String baseUrl = DEFAULT_BASE_URL;
  private String model = DEFAULT_MODEL;
  private String apiKey = "";
  private String httpReferer = "";
  private String appTitle = "Andromedia";
  private int maxOutputTokens = 2048;
  private double temperature = 0.2;
  private int maxRetries = 5;
  private long retryInitialDelayMs = 2000;
  private double retryBackoffMultiplier = 2.0;
  private long retryMinDelayMsFor429 = 5000;
  private long retryMaxDelayMs = 60_000;
  private Map<String, Provider> providers = new LinkedHashMap<>();

  public String provider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String baseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String model() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public String apiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey == null ? "" : apiKey;
  }

  public String httpReferer() {
    return httpReferer;
  }

  public void setHttpReferer(String httpReferer) {
    this.httpReferer = httpReferer == null ? "" : httpReferer;
  }

  public String appTitle() {
    return appTitle;
  }

  public void setAppTitle(String appTitle) {
    this.appTitle = appTitle == null ? "" : appTitle;
  }

  public int maxOutputTokens() {
    return maxOutputTokens;
  }

  public void setMaxOutputTokens(int maxOutputTokens) {
    this.maxOutputTokens = maxOutputTokens;
  }

  public double temperature() {
    return temperature;
  }

  public void setTemperature(double temperature) {
    this.temperature = temperature;
  }

  public int maxRetries() {
    return maxRetries;
  }

  public void setMaxRetries(int maxRetries) {
    this.maxRetries = Math.max(0, maxRetries);
  }

  public long retryInitialDelayMs() {
    return retryInitialDelayMs;
  }

  public void setRetryInitialDelayMs(long retryInitialDelayMs) {
    this.retryInitialDelayMs = Math.max(0, retryInitialDelayMs);
  }

  public double retryBackoffMultiplier() {
    return retryBackoffMultiplier;
  }

  public void setRetryBackoffMultiplier(double retryBackoffMultiplier) {
    this.retryBackoffMultiplier = retryBackoffMultiplier;
  }

  public long retryMinDelayMsFor429() {
    return retryMinDelayMsFor429;
  }

  public void setRetryMinDelayMsFor429(long retryMinDelayMsFor429) {
    this.retryMinDelayMsFor429 = Math.max(0, retryMinDelayMsFor429);
  }

  public long retryMaxDelayMs() {
    return retryMaxDelayMs;
  }

  public void setRetryMaxDelayMs(long retryMaxDelayMs) {
    this.retryMaxDelayMs = Math.max(0, retryMaxDelayMs);
  }

  public Map<String, Provider> providers() {
    return Map.copyOf(providers);
  }

  public Map<String, Provider> getProviders() {
    return providers;
  }

  public void setProviders(Map<String, Provider> providers) {
    this.providers = providers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(providers);
  }

  public int maxAttempts() {
    return maxRetries + 1;
  }

  public boolean hasApiKey() {
    Provider configuredProvider =
        providers.get(provider == null ? "" : provider.trim().toLowerCase());
    if (configuredProvider != null) {
      return configuredProvider.configured();
    }
    return apiKey != null && !apiKey.isBlank();
  }

  public String providerLabel() {
    if (provider == null || provider.isBlank()) {
      return "Chat";
    }
    return switch (provider.toLowerCase()) {
      case "groq" -> "Groq";
      case "deepseek" -> "DeepSeek";
      case "openrouter" -> "OpenRouter";
      case "ollama" -> "Ollama";
      default -> provider;
    };
  }

  public String apiKeyEnvVar() {
    if (provider != null && provider.equalsIgnoreCase("openrouter")) {
      return "OPENROUTER_API_KEY";
    }
    if (provider != null && provider.equalsIgnoreCase("ollama")) {
      return "OLLAMA_HOST";
    }
    if (provider != null && provider.equalsIgnoreCase("deepseek")) {
      return "DEEPSEEK_API_KEY";
    }
    return "GROQ_API_KEY";
  }

  public static class Provider {
    private String baseUrl = "";
    private String model = "";
    private String apiKey = "";
    private String httpReferer = "";
    private String appTitle = "Andromedia";

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl == null ? "" : baseUrl;
    }

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model == null ? "" : model;
    }

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey == null ? "" : apiKey;
    }

    public String getHttpReferer() {
      return httpReferer;
    }

    public void setHttpReferer(String httpReferer) {
      this.httpReferer = httpReferer == null ? "" : httpReferer;
    }

    public String getAppTitle() {
      return appTitle;
    }

    public void setAppTitle(String appTitle) {
      this.appTitle = appTitle == null ? "Andromedia" : appTitle;
    }

    boolean configured() {
      return !apiKey.isBlank() && !baseUrl.isBlank() && !model.isBlank();
    }
  }
}
