package com.andromedia.llm;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties({
  EmbeddingProperties.class,
  ContextExpansionProperties.class,
  ChatProperties.class,
  SideQueryProperties.class,
  AskProperties.class
})
public class LlmConfiguration {

  @Bean
  StubEmbeddingService stubEmbeddingService(EmbeddingProperties properties) {
    return new StubEmbeddingService(properties.dimensions());
  }

  @Bean
  @Primary
  EmbeddingService embeddingService(EmbeddingProperties properties, StubEmbeddingService stubEmbeddingService) {
    if (properties.hasApiKey()) {
      return new OpenRouterEmbeddingService(properties);
    }
    return stubEmbeddingService;
  }

  @Bean
  @Primary
  ConfigurableChatService chatCompletionService(ChatProperties properties) {
    return new ConfigurableChatService(properties);
  }

  @Bean("structuredCompletionService")
  StructuredCompletionService structuredCompletionService(
      ConfigurableChatService chatCompletionService) {
    return chatCompletionService;
  }
}
