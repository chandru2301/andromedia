package com.andromedia.common;

import java.util.List;

public record FileStructuralMetadata(
    List<String> imports, List<String> annotations, List<CodeSymbol> symbols) {

  public static FileStructuralMetadata empty() {
    return new FileStructuralMetadata(List.of(), List.of(), List.of());
  }
}
