package com.andromedia.search;

import com.andromedia.common.ChunkUnitType;

public record UsageSite(
    String symbolId,
    String symbolName,
    String path,
    String fileName,
    int startLine,
    int endLine,
    ChunkUnitType unitType,
    String chunkId,
    UsageRole role) {}
