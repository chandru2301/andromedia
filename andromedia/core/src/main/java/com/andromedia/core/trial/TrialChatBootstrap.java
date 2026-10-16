package com.andromedia.core.trial;

import com.andromedia.llm.ConfigurableChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class TrialChatBootstrap {

  private static final Logger log = LoggerFactory.getLogger(TrialChatBootstrap.class);

  private final TrialProperties properties;
  private final ConfigurableChatService chatService;

  public TrialChatBootstrap(TrialProperties properties, ConfigurableChatService chatService) {
    this.properties = properties;
    this.chatService = chatService;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void applyTrialChatDefaults() {
    if (!properties.enabled()) {
      return;
    }
    String model = properties.chatModel();
    if (model == null || model.isBlank()) {
      return;
    }
    try {
      chatService.update("openrouter", model, null);
      log.info("Trial mode: active chat model set to {}", model);
    } catch (IllegalArgumentException ex) {
      try {
        chatService.update("groq", model, null);
        log.info("Trial mode: active chat model set to {} via Groq", model);
      } catch (IllegalArgumentException groqEx) {
        log.warn(
            "Trial mode enabled but no configured LLM provider found. Set OPENROUTER_API_KEY or GROQ_API_KEY.");
      }
    }
  }
}
