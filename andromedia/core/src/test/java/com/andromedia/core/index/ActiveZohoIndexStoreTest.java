package com.andromedia.core.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.ServiceIndexPathResolver;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActiveZohoIndexStoreTest {

  @TempDir Path tempDir;

  @Test
  void differentCredentialsGetDifferentKeysAndIndexPaths() {
    String a = ActiveZohoIndexStore.connectionKey("cid", "refresh-a", "in");
    String b = ActiveZohoIndexStore.connectionKey("cid", "refresh-b", "in");
    assertNotEquals(a, b);
    Path pathA = ServiceIndexPathResolver.resolveZohoConnectionIndexPath("user_trial", a);
    Path pathB = ServiceIndexPathResolver.resolveZohoConnectionIndexPath("user_trial", b);
    assertNotEquals(pathA, pathB);
    assertTrue(pathA.getFileName().toString().startsWith("user_trial__z_"));
  }

  @Test
  void activateSwitchesActiveBucket() {
    ActiveZohoIndexStore store = new ActiveZohoIndexStore(tempDir);
    String keyA = ActiveZohoIndexStore.connectionKey("c", "tok-a", "in");
    String keyB = ActiveZohoIndexStore.connectionKey("c", "tok-b", "in");
    assertTrue(store.activate("svc1", keyA, "in"));
    assertEquals(keyA, store.getActiveConnectionKey("svc1").orElseThrow());
    assertTrue(store.activate("svc1", keyB, "in"));
    assertEquals(keyB, store.getActiveConnectionKey("svc1").orElseThrow());
    assertEquals(
        ServiceIndexPathResolver.resolveZohoConnectionIndexPath("svc1", keyB),
        store.resolveIndexPath("svc1"));
    assertFalse(store.activate("svc1", keyB, "in"));
  }

  @Test
  void resolveIndexPathsAlwaysIncludesBasePlusActiveZohoOnly() {
    ActiveZohoIndexStore store = new ActiveZohoIndexStore(tempDir);
    Path base = ServiceIndexPathResolver.resolveIndexPath("svc1");
    assertEquals(List.of(base), store.resolveIndexPaths("svc1"));

    String keyA = ActiveZohoIndexStore.connectionKey("c", "tok-a", "in");
    String keyB = ActiveZohoIndexStore.connectionKey("c", "tok-b", "in");
    store.activate("svc1", keyA, "in");
    Path zohoA = ServiceIndexPathResolver.resolveZohoConnectionIndexPath("svc1", keyA);
    assertEquals(List.of(base, zohoA), store.resolveIndexPaths("svc1"));
    assertEquals(zohoA, store.resolveIndexPath("svc1"));

    store.activate("svc1", keyB, "in");
    Path zohoB = ServiceIndexPathResolver.resolveZohoConnectionIndexPath("svc1", keyB);
    assertEquals(List.of(base, zohoB), store.resolveIndexPaths("svc1"));
    assertFalse(store.resolveIndexPaths("svc1").contains(zohoA));
  }
}
