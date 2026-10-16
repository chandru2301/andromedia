package com.andromedia.ingestion.zoho;

import java.time.Instant;
import java.util.List;

public record ZohoIngestionOptions(
    int lookbackDays,
    double minQualityScore,
    boolean enableSandboxDemoSignatures,
    List<String> sandboxDemoAccountNames,
    Instant modifiedSince,
    boolean fullSync) {

  public static final String SYNC_TYPE_FULL = "full";
  public static final String SYNC_TYPE_INCREMENTAL = "incremental";

  public ZohoIngestionOptions(int lookbackDays, double minQualityScore) {
    this(
        lookbackDays,
        minQualityScore,
        false,
        ZohoDemoAccountDetector.DEFAULT_SANDBOX_SIGNATURES,
        null,
        true);
  }

  public ZohoIngestionOptions(
      int lookbackDays,
      double minQualityScore,
      boolean enableSandboxDemoSignatures,
      List<String> sandboxDemoAccountNames) {
    this(
        lookbackDays,
        minQualityScore,
        enableSandboxDemoSignatures,
        sandboxDemoAccountNames,
        null,
        true);
  }

  public ZohoIngestionOptions {
    lookbackDays = lookbackDays <= 0 ? 90 : lookbackDays;
    minQualityScore = minQualityScore <= 0 ? 0.35 : minQualityScore;
    sandboxDemoAccountNames =
        sandboxDemoAccountNames == null || sandboxDemoAccountNames.isEmpty()
            ? ZohoDemoAccountDetector.DEFAULT_SANDBOX_SIGNATURES
            : List.copyOf(sandboxDemoAccountNames);
  }

  public boolean isIncremental() {
    return !fullSync && modifiedSince != null;
  }

  public String syncType() {
    return isIncremental() ? SYNC_TYPE_INCREMENTAL : SYNC_TYPE_FULL;
  }
}
