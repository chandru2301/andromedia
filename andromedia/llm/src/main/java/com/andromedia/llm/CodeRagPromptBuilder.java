package com.andromedia.llm;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CodeRagPromptBuilder {

  static final int JSON_REQUEST_OVERHEAD_CHARS = 512;

  private static final String SYSTEM_PROMPT =
      """
      You are a codebase assistant. Answer the user's question using ONLY the provided code excerpts.
      Cite sources with bracket references like [1], [2] that match the excerpt numbers (e.g. at end of a bullet: slf4j-api (see [1])).
      If the excerpts do not contain enough information, say so clearly. Do not invent files, classes, or methods.
      Be concise and specific. Prefer pointing to the exact file and method where logic lives.
      Format answers with short section headings and bullet lines using "•" (not markdown dashes). Example:
      Logging
      • slf4j-api (see [1])
      • slf4j-log4j12 (see [2])
      """;

  private final AskProperties askProperties;

  public CodeRagPromptBuilder(AskProperties askProperties) {
    this.askProperties = askProperties;
  }

  public RagPrompt build(String question, AssembledContext context) {
    return build(question, context, PromptAttachments.empty());
  }

  public RagPrompt build(String question, AssembledContext context, PromptAttachments attachments) {
    String trimmedQuestion = question == null ? "" : question.trim();
    PromptAttachments safe = attachments == null ? PromptAttachments.empty() : attachments;
    List<ContextBlock> selected =
        selectBlocksWithinBudget(
            context.blocks(), trimmedQuestion, safe, askProperties.maxPromptChars());
    String contextSection = formatContextSection(selected);
    String attachmentSection = formatAttachmentSection(safe);
    String userMessage =
        """
        Question:
        %s
        %s
        Code excerpts:
        %s
        """
            .formatted(
                trimmedQuestion,
                attachmentSection.isBlank() ? "" : "\n" + attachmentSection + "\n",
                contextSection);
    return new RagPrompt(SYSTEM_PROMPT.trim(), userMessage.trim());
  }

  List<ContextBlock> selectBlocksWithinBudget(
      List<ContextBlock> blocks, String question, int maxPromptChars) {
    return selectBlocksWithinBudget(blocks, question, PromptAttachments.empty(), maxPromptChars);
  }

  List<ContextBlock> selectBlocksWithinBudget(
      List<ContextBlock> blocks, String question, PromptAttachments attachments, int maxPromptChars) {
    if (blocks == null || blocks.isEmpty()) {
      return List.of();
    }
    List<ContextBlock> selected = new ArrayList<>(blocks);
    while (!selected.isEmpty()
        && estimatePromptChars(trimmedQuestion(question), attachments, selected) > maxPromptChars) {
      selected.remove(selected.size() - 1);
    }
    return List.copyOf(selected);
  }

  public static int estimatedRequestChars(RagPrompt prompt) {
    if (prompt == null) {
      return 0;
    }
    return prompt.systemMessage().length()
        + prompt.userMessage().length()
        + JSON_REQUEST_OVERHEAD_CHARS;
  }

  static int estimatePromptChars(String question, List<ContextBlock> selectedBlocks) {
    return estimatePromptChars(question, PromptAttachments.empty(), selectedBlocks);
  }

  static int estimatePromptChars(
      String question, PromptAttachments attachments, List<ContextBlock> selectedBlocks) {
    String contextSection = formatContextSectionStatic(selectedBlocks);
    String attachmentSection = formatAttachmentSectionStatic(attachments);
    String userMessage =
        """
        Question:
        %s
        %s
        Code excerpts:
        %s
        """
            .formatted(
                question,
                attachmentSection.isBlank() ? "" : "\n" + attachmentSection + "\n",
                contextSection);
    RagPrompt prompt = new RagPrompt(SYSTEM_PROMPT.trim(), userMessage.trim());
    return prompt.systemMessage().length() + prompt.userMessage().length() + JSON_REQUEST_OVERHEAD_CHARS;
  }

  public static int excerptChars(List<ContextBlock> blocks) {
    return formatContextSectionStatic(blocks).length();
  }

  String formatContextSection(List<ContextBlock> blocks) {
    return formatContextSectionStatic(blocks);
  }

  private static String trimmedQuestion(String question) {
    return question == null ? "" : question.trim();
  }

  private static String formatContextSectionStatic(List<ContextBlock> blocks) {
    if (blocks == null || blocks.isEmpty()) {
      return "(no code excerpts available)";
    }
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < blocks.size(); i++) {
      if (i > 0) {
        builder.append("\n\n");
      }
      builder.append(formatBlock(i + 1, blocks.get(i)));
    }
    return builder.toString();
  }

  private static String formatAttachmentSectionStatic(PromptAttachments attachments) {
    if (attachments == null || !attachments.hasAny()) {
      return "";
    }
    StringBuilder builder = new StringBuilder();
    appendAttachment(builder, "Project rules", attachments.projectRules());
    appendAttachment(builder, "Project memory", attachments.projectMemory());
    appendAttachment(builder, "Graph context (deterministic)", attachments.graphContext());
    appendAttachment(builder, "Attached files", attachments.fileAttachments());
    appendAttachment(builder, "Explore context", attachments.exploreContext());
    return builder.toString().trim();
  }

  private static void appendAttachment(StringBuilder builder, String title, String content) {
    if (content == null || content.isBlank()) {
      return;
    }
    builder.append("--- ").append(title).append(" ---\n").append(content.trim()).append("\n\n");
  }

  String formatAttachmentSection(PromptAttachments attachments) {
    return formatAttachmentSectionStatic(attachments);
  }

  private static String formatBlock(int index, ContextBlock block) {
    StringBuilder header = new StringBuilder();
    header.append('[').append(index).append("] ");
    if (block.fileName() != null && !block.fileName().isBlank()) {
      header.append("File: ").append(block.fileName()).append('\n');
    }
    if (block.className() != null && !block.className().isBlank()) {
      header.append("    Class: ").append(block.className()).append('\n');
    }
    if (block.methodName() != null && !block.methodName().isBlank()) {
      header.append("    Method: ").append(block.methodName()).append('\n');
    }
    if (block.startLine() > 0 && block.endLine() > 0) {
      header.append("    Lines: ").append(block.startLine()).append('-').append(block.endLine()).append('\n');
    }
    if (block.path() != null && !block.path().isBlank()) {
      header.append("    Path: ").append(block.path()).append('\n');
    }
    header.append("---\n");
    header.append(block.content().trim());
    return header.toString();
  }
}
