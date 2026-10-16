package com.andromedia.search;

import com.andromedia.common.SymbolEntry;
import com.andromedia.common.SymbolType;

public record ImpactSite(
    String symbolId,
    String symbolName,
    SymbolType symbolType,
    String path,
    int startLine,
    int hop,
    ImpactCategory category) {

  static ImpactSite from(SymbolEntry entry, int hop, ImpactCategory category) {
    return new ImpactSite(
        entry.symbolId(),
        entry.symbolName(),
        entry.symbolType(),
        entry.filePath(),
        entry.lineStart(),
        hop,
        category);
  }
}
