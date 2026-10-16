package com.andromedia.common;

public record SymbolEntry(
    String symbolId,
    String symbolName,
    SymbolType symbolType,
    String filePath,
    int lineStart,
    int lineEnd,
    String language) {

  public static SymbolEntry from(CodeSymbol symbol) {
    return new SymbolEntry(
        symbol.symbolId(),
        symbol.symbolName(),
        symbol.symbolType(),
        symbol.filePath(),
        symbol.lineStart(),
        symbol.lineEnd(),
        symbol.language());
  }
}
