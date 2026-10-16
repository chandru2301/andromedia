package com.andromedia.ingestion.crm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CrmSchemaCacheTest {

  @Test
  void getOrDiscoverCachesUntilTtlExpires() throws IOException {
    MutableClock clock = new MutableClock(Instant.parse("2026-07-20T00:00:00Z"));
    CrmSchemaCache cache = new CrmSchemaCache(Duration.ofHours(24), clock);
    AtomicInteger discoveries = new AtomicInteger();
    CrmSchemaAdapter adapter =
        new StubAdapter(
            () -> {
              discoveries.incrementAndGet();
              return List.of(
                  new CrmField(
                      "Deal_Name",
                      "Deal Name",
                      CrmFieldType.STRUCTURED,
                      CrmFieldCategory.IDENTITY,
                      false,
                      false,
                      0));
            });
    CrmConnection connection = new CrmConnection("org-1", "zoho", java.util.Map.of());

    List<CrmField> first = cache.getOrDiscover("org-1", "Deals", adapter, connection);
    List<CrmField> second = cache.getOrDiscover("org-1", "Deals", adapter, connection);

    assertEquals(1, discoveries.get());
    assertEquals(first, second);
    assertTrue(cache.containsFresh("org-1", "Deals"));

    clock.advance(Duration.ofHours(25));
    assertFalse(cache.containsFresh("org-1", "Deals"));

    cache.getOrDiscover("org-1", "Deals", adapter, connection);
    assertEquals(2, discoveries.get());
  }

  @Test
  void putReplacesCachedFields() {
    CrmSchemaCache cache = new CrmSchemaCache(Duration.ofHours(1));
    cache.put(
        "org-1",
        "Deals",
        List.of(
            new CrmField(
                "Field1",
                "Field1",
                CrmFieldType.NARRATIVE,
                CrmFieldCategory.CONTEXT,
                true,
                false,
                40)));
    assertTrue(cache.containsFresh("org-1", "Deals"));
    assertEquals(1, cache.size());
    cache.invalidate("org-1", "Deals");
    assertFalse(cache.containsFresh("org-1", "Deals"));
  }

  @Test
  void cacheKeyIsOrgAndModule() {
    assertEquals("acme:Deals", CrmSchemaCache.cacheKey("acme", "Deals"));
  }

  private static final class StubAdapter implements CrmSchemaAdapter {
    private final FieldSupplier supplier;

    StubAdapter(FieldSupplier supplier) {
      this.supplier = supplier;
    }

    @Override
    public List<CrmModule> discoverModules(CrmConnection connection) {
      return List.of();
    }

    @Override
    public List<CrmField> discoverFields(CrmConnection connection, String moduleName) {
      return supplier.get();
    }

    @Override
    public CrmField classifyField(RawCrmField rawField) {
      return new CrmField(
          rawField.apiName(),
          rawField.displayLabel(),
          CrmFieldType.SKIP,
          CrmFieldCategory.SYSTEM,
          rawField.custom(),
          false,
          0);
    }

    @Override
    public CrmFetchQuery buildFetchQuery(
        String moduleName, List<CrmField> fields, CrmFetchOptions options) {
      return new CrmFetchQuery("/crm/" + moduleName, "", null, options.maxRecords());
    }
  }

  @FunctionalInterface
  private interface FieldSupplier {
    List<CrmField> get();
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
