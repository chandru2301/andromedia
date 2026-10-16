package com.andromedia.common;

import java.util.List;

public record StructuralMetadata(
    List<String> imports,
    List<String> annotations,
    List<CodeSymbol> symbols,
    List<String> referencedSymbolIds) {

  public StructuralMetadata {
    imports = imports == null ? List.of() : List.copyOf(imports);
    annotations = annotations == null ? List.of() : List.copyOf(annotations);
    symbols = symbols == null ? List.of() : List.copyOf(symbols);
    referencedSymbolIds =
        referencedSymbolIds == null ? List.of() : List.copyOf(referencedSymbolIds);
  }

  public static StructuralMetadata empty() {
    return new StructuralMetadata(List.of(), List.of(), List.of(), List.of());
  }

  public static StructuralMetadata forChunk(
      FileStructuralMetadata fileMeta, int startLine, int endLine) {
    if (fileMeta == null) {
      return empty();
    }
    List<CodeSymbol> filtered =
        fileMeta.symbols().stream()
            .filter(symbol -> overlapsSpan(symbol, startLine, endLine))
            .toList();
    return new StructuralMetadata(
        fileMeta.imports(), fileMeta.annotations(), filtered, List.of());
  }

  public StructuralMetadata withReferencedSymbolIds(List<String> referencedSymbolIds) {
    return new StructuralMetadata(imports, annotations, symbols, referencedSymbolIds);
  }

  private static boolean overlapsSpan(CodeSymbol symbol, int startLine, int endLine) {
    return symbol.lineStart() <= endLine && symbol.lineEnd() >= startLine;
  }
}
