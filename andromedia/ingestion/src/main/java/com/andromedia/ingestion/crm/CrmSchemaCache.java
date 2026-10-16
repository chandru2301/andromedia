package com.andromedia.ingestion.crm;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;

/**
 * Per-org, per-module schema cache with a default 24-hour TTL.
 *
 * <p>Caches classified {@link CrmField} lists (type, category, completeness) so schema discovery and
 * later LLM field verdicts are not repeated on every ingestion.
 */
public final class CrmSchemaCache {

  public static final Duration DEFAULT_TTL = Duration.ofHours(24);

  private final ConcurrentMap<String, CachedSchema> cache = new ConcurrentHashMap<>();
  private final Duration ttl;
  private final Clock clock;

  public CrmSchemaCache() {
    this(DEFAULT_TTL, Clock.systemUTC());
  }

  public CrmSchemaCache(Duration ttl) {
    this(ttl, Clock.systemUTC());
  }

  public CrmSchemaCache(Duration ttl, Clock clock) {
    this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? DEFAULT_TTL : ttl;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Return cached fields for {@code orgId:moduleName}, or discover and store them on a miss/expiry.
   */
  public List<CrmField> getOrDiscover(
      String orgId,
      String moduleName,
      CrmSchemaAdapter adapter,
      CrmConnection connection)
      throws IOException {
    Objects.requireNonNull(adapter, "adapter");
    Objects.requireNonNull(connection, "connection");
    String key = cacheKey(orgId, moduleName);
    CachedSchema cached = cache.get(key);
    Instant now = clock.instant();
    if (cached != null && !cached.isExpired(now)) {
      return cached.fields();
    }

    List<CrmField> fields = adapter.discoverFields(connection, moduleName);
    List<CrmField> immutable = fields == null ? List.of() : List.copyOf(fields);
    cache.put(key, new CachedSchema(immutable, now.plus(ttl)));
    return immutable;
  }

  /**
   * Store or replace fields for a key (used after Layer-2/3 reclassification with sample data).
   */
  public void put(String orgId, String moduleName, List<CrmField> fields) {
    List<CrmField> immutable = fields == null ? List.of() : List.copyOf(fields);
    cache.put(cacheKey(orgId, moduleName), new CachedSchema(immutable, clock.instant().plus(ttl)));
  }

  /** Compute-if-absent helper for callers that discover outside the adapter interface. */
  public List<CrmField> computeIfAbsent(
      String orgId,
      String moduleName,
      BiFunction<String, String, List<CrmField>> discoverer) {
    String key = cacheKey(orgId, moduleName);
    Instant now = clock.instant();
    CachedSchema cached = cache.get(key);
    if (cached != null && !cached.isExpired(now)) {
      return cached.fields();
    }
    List<CrmField> fields = discoverer.apply(orgId, moduleName);
    List<CrmField> immutable = fields == null ? List.of() : List.copyOf(fields);
    cache.put(key, new CachedSchema(immutable, now.plus(ttl)));
    return immutable;
  }

  public boolean containsFresh(String orgId, String moduleName) {
    CachedSchema cached = cache.get(cacheKey(orgId, moduleName));
    return cached != null && !cached.isExpired(clock.instant());
  }

  public void invalidate(String orgId, String moduleName) {
    cache.remove(cacheKey(orgId, moduleName));
  }

  public void clear() {
    cache.clear();
  }

  public int size() {
    return cache.size();
  }

  static String cacheKey(String orgId, String moduleName) {
    String org = orgId == null ? "" : orgId.trim();
    String module = moduleName == null ? "" : moduleName.trim();
    return org + ":" + module;
  }

  record CachedSchema(List<CrmField> fields, Instant expiresAt) {
    CachedSchema {
      fields = fields == null ? List.of() : List.copyOf(fields);
      expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    boolean isExpired(Instant now) {
      return !expiresAt.isAfter(now);
    }
  }
}
