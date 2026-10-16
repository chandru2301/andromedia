package com.andromedia.common;

public record ChangedSymbol(
    String symbolId, String symbolName, String filePath, int lineStart, int lineEnd) {}
