package com.andromedia.llm;

public record PromptAttachments(
    String projectRules,
    String projectMemory,
    String graphContext,
    String fileAttachments,
    String exploreContext) {

  public static PromptAttachments empty() {
    return new PromptAttachments("", "", "", "", "");
  }

  public boolean hasAny() {
    return isPresent(projectRules)
        || isPresent(projectMemory)
        || isPresent(graphContext)
        || isPresent(fileAttachments)
        || isPresent(exploreContext);
  }

  private static boolean isPresent(String value) {
    return value != null && !value.isBlank();
  }
}
