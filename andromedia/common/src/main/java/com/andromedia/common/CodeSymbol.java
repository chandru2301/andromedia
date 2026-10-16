package com.andromedia.common;

public record CodeSymbol(
    String symbolId,
    String symbolName,
    SymbolType symbolType,
    String filePath,
    int lineStart,
    int lineEnd,
    String language,
    String containerName) {}
