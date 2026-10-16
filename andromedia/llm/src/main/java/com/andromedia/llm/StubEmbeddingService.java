package com.andromedia.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public class StubEmbeddingService implements EmbeddingService {

  private final int dimensions;

  public StubEmbeddingService(int dimensions) {
    if (dimensions <= 0) {
      throw new IllegalArgumentException("dimensions must be positive");
    }
    this.dimensions = dimensions;
  }

  @Override
  public float[] embed(String text) {
    float[] vector = new float[dimensions];
    for (int i = 0; i < dimensions; i++) {
      String seed = text + "|" + i;
      byte[] hash = sha256(seed.getBytes(StandardCharsets.UTF_8));
      int value = ((hash[0] & 0xFF) << 8) | (hash[1] & 0xFF);
      vector[i] = (value / 65535.0f) * 2.0f - 1.0f;
    }
    normalize(vector);
    return vector;
  }

  @Override
  public List<float[]> embedAll(List<String> texts) {
    if (texts == null || texts.isEmpty()) {
      throw new IllegalArgumentException("texts must not be empty");
    }
    return texts.stream().map(this::embed).toList();
  }

  @Override
  public int dimensions() {
    return dimensions;
  }

  private static void normalize(float[] vector) {
    double sumSquares = 0.0;
    for (float value : vector) {
      sumSquares += value * value;
    }
    if (sumSquares == 0.0) {
      return;
    }
    float magnitude = (float) Math.sqrt(sumSquares);
    for (int i = 0; i < vector.length; i++) {
      vector[i] /= magnitude;
    }
  }

  private static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }
}
