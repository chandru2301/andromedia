package com.andromedia.llm;

import java.util.List;

public record AssembledContext(List<ContextBlock> blocks) {

  public String toPromptText() {
    StringBuilder builder = new StringBuilder();
    for (ContextBlock block : blocks) {
      builder.append("--- ").append(block.label()).append(" ---\n");
      builder.append(block.content().trim()).append("\n\n");
    }
    return builder.toString().trim();
  }
}
