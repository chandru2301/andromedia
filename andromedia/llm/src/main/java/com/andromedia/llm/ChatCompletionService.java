package com.andromedia.llm;

import java.util.function.Consumer;

public interface ChatCompletionService {

  void streamAnswer(RagPrompt prompt, Consumer<String> onToken);

  default void streamAnswer(
      RagPrompt prompt, Consumer<String> onToken, ChatProgressListener listener) {
    streamAnswer(prompt, onToken);
  }
}
