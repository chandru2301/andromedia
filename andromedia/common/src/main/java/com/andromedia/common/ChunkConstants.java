package com.andromedia.common;

public final class ChunkConstants {

  /** Lines above this threshold mark a chunk as oversized (still indexed as one unit in v1). */
  public static final int OVERSIZED_LINE_THRESHOLD = 500;

  public static final int CHUNK_ID_HEX_LENGTH = 16;

  private ChunkConstants() {}
}
