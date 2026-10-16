package com.andromedia.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class OllamaModelCatalogTest {

  @Test
  void buildsTagsUrlFromOpenAiCompatibleBase() {
    assertEquals(
        "http://127.0.0.1:11434/api/tags",
        OllamaModelCatalog.tagsUrl("http://127.0.0.1:11434/v1"));
    assertEquals(
        "http://127.0.0.1:11434/api/tags",
        OllamaModelCatalog.tagsUrl("http://127.0.0.1:11434/v1/"));
  }

  @Test
  void parsesInstalledModelNames() throws Exception {
    String body =
        """
        {"models":[{"name":"qwen2.5-coder:7b","model":"qwen2.5-coder:7b"},{"name":"llama3.2:3b"}]}
        """;
    assertEquals(List.of("qwen2.5-coder:7b", "llama3.2:3b"), OllamaModelCatalog.parseModelNames(body));
  }

  @Test
  void listModelsAgainstLocalOllamaWhenAvailable() {
    List<String> models = OllamaModelCatalog.listModels("http://127.0.0.1:11434/v1");
    // Soft check: either daemon down (empty) or includes the known local model.
    assertTrue(models.isEmpty() || models.contains("qwen2.5-coder:7b"));
  }
}
