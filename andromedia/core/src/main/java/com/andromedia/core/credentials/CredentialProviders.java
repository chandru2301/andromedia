package com.andromedia.core.credentials;

import java.util.Locale;
import java.util.Set;

/** Supported credential provider keys stored in {@code user_credentials.provider}. */
public final class CredentialProviders {

  public static final String SLACK = "slack";
  public static final String NOTION = "notion";
  public static final String JIRA = "jira";
  public static final String LINEAR = "linear";
  public static final String ZOHO = "zoho";
  public static final String GOOGLE_CHAT = "google_chat";
  public static final String LLM_GROQ = "llm_groq";
  public static final String LLM_DEEPSEEK = "llm_deepseek";
  public static final String LLM_OPENROUTER = "llm_openrouter";
  public static final String LLM_OLLAMA = "llm_ollama";

  public static final Set<String> ALL =
      Set.of(
          SLACK,
          NOTION,
          JIRA,
          LINEAR,
          ZOHO,
          GOOGLE_CHAT,
          LLM_GROQ,
          LLM_DEEPSEEK,
          LLM_OPENROUTER,
          LLM_OLLAMA);

  private CredentialProviders() {}

  public static String normalize(String provider) {
    if (provider == null || provider.isBlank()) {
      throw new IllegalArgumentException("provider is required");
    }
    String normalized = provider.trim().toLowerCase(Locale.ROOT);
    if (!ALL.contains(normalized)) {
      throw new IllegalArgumentException(
          "Unsupported provider. Supported: " + String.join(", ", ALL.stream().sorted().toList()));
    }
    return normalized;
  }

  public static String llmProvider(String chatProvider) {
    if (chatProvider == null || chatProvider.isBlank()) {
      throw new IllegalArgumentException("chat provider is required");
    }
    return switch (chatProvider.trim().toLowerCase(Locale.ROOT)) {
      case "groq" -> LLM_GROQ;
      case "deepseek" -> LLM_DEEPSEEK;
      case "openrouter" -> LLM_OPENROUTER;
      case "ollama" -> LLM_OLLAMA;
      default -> throw new IllegalArgumentException("Unsupported chat provider: " + chatProvider);
    };
  }

  public static String chatProviderId(String credentialProvider) {
    return switch (normalize(credentialProvider)) {
      case LLM_GROQ -> "groq";
      case LLM_DEEPSEEK -> "deepseek";
      case LLM_OPENROUTER -> "openrouter";
      case LLM_OLLAMA -> "ollama";
      default -> throw new IllegalArgumentException("Not an LLM credential provider");
    };
  }

  public static boolean isLlm(String provider) {
    String normalized = normalize(provider);
    return LLM_GROQ.equals(normalized)
        || LLM_DEEPSEEK.equals(normalized)
        || LLM_OPENROUTER.equals(normalized)
        || LLM_OLLAMA.equals(normalized);
  }
}
