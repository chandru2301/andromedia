package com.andromedia.llm;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CodeRagPromptBuilderTest {

  @Test
  void formatsCitationHeadersWithIndices() {
    AskProperties properties = new AskProperties();
    properties.setMaxPromptChars(50_000);
    CodeRagPromptBuilder builder = new CodeRagPromptBuilder(properties);

    ContextBlock block =
        new ContextBlock(
            "chunk-1",
            "/proj/src/AnomalyKafkaListener.java",
            "AnomalyKafkaListener.java:consumeAnomaly:30-40",
            "public void consumeAnomaly() {}",
            "AnomalyKafkaListener.java",
            "AnomalyKafkaListener",
            "consumeAnomaly",
            34,
            42);

    RagPrompt prompt = builder.build("where is anomaly detection?", new AssembledContext(List.of(block)));

    assertTrue(prompt.systemMessage().contains("ONLY the provided code excerpts"));
    assertTrue(prompt.userMessage().contains("Question:"));
    assertTrue(prompt.userMessage().contains("[1] File: AnomalyKafkaListener.java"));
    assertTrue(prompt.userMessage().contains("Method: consumeAnomaly"));
    assertTrue(prompt.userMessage().contains("Lines: 34-42"));
    assertTrue(prompt.userMessage().contains("consumeAnomaly() {}"));
  }

  @Test
  void truncatesLowestRankedBlocksWhenOverCharBudget() {
    AskProperties properties = new AskProperties();
    // Above fixed system + JSON overhead (~900 chars) but below two blocks with a large excerpt.
    properties.setMaxPromptChars(2_000);
    CodeRagPromptBuilder builder = new CodeRagPromptBuilder(properties);

    ContextBlock first =
        new ContextBlock("a", "/a.java", "a", "short", "a.java", "A", "m1", 1, 2);
    ContextBlock second =
        new ContextBlock(
            "b",
            "/b.java",
            "b",
            "x".repeat(5_000),
            "b.java",
            "B",
            "m2",
            3,
            4);

    List<ContextBlock> selected =
        builder.selectBlocksWithinBudget(List.of(first, second), "question?", properties.maxPromptChars());

    assertTrue(selected.size() < 2, "expected lowest-ranked block dropped");
    assertTrue(selected.contains(first));
    assertTrue(
        CodeRagPromptBuilder.estimatePromptChars("question?", selected)
            <= properties.maxPromptChars());
  }

  @Test
  void respectsMaxPromptCharsIncludingSystemAndQuestion() {
    AskProperties properties = new AskProperties();
    properties.setMaxPromptChars(2_000);
    CodeRagPromptBuilder builder = new CodeRagPromptBuilder(properties);

    ContextBlock large =
        new ContextBlock(
            "big",
            "/Big.java",
            "Big.java:run:1-100",
            "x".repeat(20_000),
            "Big.java",
            "Big",
            "run",
            1,
            100);

    RagPrompt prompt =
        builder.build(
            "where is anomaly detection implemented in this codebase?",
            new AssembledContext(List.of(large)));

    int estimated =
        CodeRagPromptBuilder.estimatePromptChars(
            "where is anomaly detection implemented in this codebase?",
            builder.selectBlocksWithinBudget(
                List.of(large),
                "where is anomaly detection implemented in this codebase?",
                properties.maxPromptChars()));

    assertTrue(estimated <= properties.maxPromptChars());
    assertTrue(prompt.userMessage().length() < 20_000);
  }
}
