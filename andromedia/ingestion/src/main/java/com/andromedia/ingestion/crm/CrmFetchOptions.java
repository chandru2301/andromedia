package com.andromedia.ingestion.crm;

import java.time.Instant;

/** Options controlling how an adapter builds a module fetch query. */
public record CrmFetchOptions(
    int lookbackDays, int maxRecords, Instant modifiedSince, boolean fullSync) {

  public static final int DEFAULT_LOOKBACK_DAYS = 90;
  public static final int DEFAULT_MAX_RECORDS = 10_000;

  public CrmFetchOptions(int lookbackDays, int maxRecords) {
    this(lookbackDays, maxRecords, null, false);
  }

  public CrmFetchOptions {
    lookbackDays = lookbackDays <= 0 ? DEFAULT_LOOKBACK_DAYS : lookbackDays;
    maxRecords = maxRecords <= 0 ? DEFAULT_MAX_RECORDS : maxRecords;
  }

  public static CrmFetchOptions defaults() {
    return new CrmFetchOptions(DEFAULT_LOOKBACK_DAYS, DEFAULT_MAX_RECORDS, null, false);
  }

  public CrmFetchOptions withModifiedSince(Instant modifiedSince) {
    return new CrmFetchOptions(lookbackDays, maxRecords, modifiedSince, fullSync);
  }

  public CrmFetchOptions withFullSync(boolean fullSync) {
    return new CrmFetchOptions(lookbackDays, maxRecords, modifiedSince, fullSync);
  }
}
