package com.andromedia.core.index;

import com.andromedia.common.ServiceIndexPathResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Remembers which Zoho credential set is active per Andro {@code service}. Each distinct
 * connection (clientId + refreshToken + region) gets its own Lucene directory so switching Zoho
 * accounts does not mix CRM data. Trial {@code service}/{@code X-Trial-Id} stay the same.
 */
@Component
public class ActiveZohoIndexStore {

  private static final Logger log = LoggerFactory.getLogger(ActiveZohoIndexStore.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Path storeDir;

  public ActiveZohoIndexStore() {
    this(
        Path.of(System.getProperty("user.home"), ".andromedia", "zoho-active")
            .toAbsolutePath()
            .normalize());
  }

  ActiveZohoIndexStore(Path storeDir) {
    this.storeDir = storeDir;
  }

  /** Stable short key derived from Zoho OAuth credentials. */
  public static String connectionKey(String clientId, String refreshToken, String region) {
    String raw =
        (clientId == null ? "" : clientId.trim())
            + "|"
            + (refreshToken == null ? "" : refreshToken.trim())
            + "|"
            + (region == null ? "" : region.trim().toLowerCase());
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, 16);
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to fingerprint Zoho connection", ex);
    }
  }

  public Optional<String> getActiveConnectionKey(String service) {
    return read(service).map(node -> text(node, "connectionKey")).filter(s -> !s.isBlank());
  }

  /**
   * @return true when this is a brand-new active connection or a switch from a different Zoho
   *     account (caller should treat as a new Lucene bucket)
   */
  public boolean activate(String service, String connectionKey, String region) {
    if (service == null || service.isBlank() || connectionKey == null || connectionKey.isBlank()) {
      throw new IllegalArgumentException("service and connectionKey are required");
    }
    Optional<String> previous = getActiveConnectionKey(service);
    boolean newBucket = previous.isEmpty() || !previous.get().equals(connectionKey);
    try {
      Files.createDirectories(storeDir);
      ObjectNode node = MAPPER.createObjectNode();
      node.put("connectionKey", connectionKey.trim());
      node.put("region", region == null ? "" : region.trim());
      node.put("updatedAt", java.time.Instant.now().toString());
      Files.writeString(
          pathFor(service), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node));
      if (previous.isPresent() && !previous.get().equals(connectionKey)) {
        log.info(
            "Zoho active index for service {} switched {} -> {} (new Lucene bucket)",
            service,
            previous.get(),
            connectionKey);
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to persist active Zoho index pointer", ex);
    }
    return newBucket;
  }

  /**
   * CRM-primary path: active Zoho {@code __z_} bucket when set, otherwise the base service index.
   * Use for Zoho ledger / alerts only — not for hybrid explore/search.
   */
  public Path resolveIndexPath(String service) {
    return getActiveConnectionKey(service)
        .map(key -> ServiceIndexPathResolver.resolveZohoConnectionIndexPath(service, key))
        .orElseGet(() -> ServiceIndexPathResolver.resolveIndexPath(service));
  }

  /**
   * Hybrid read paths: always the base service index (Slack/Git/etc.), plus the active Zoho bucket
   * when one is set. Inactive Zoho buckets are never included.
   */
  public List<Path> resolveIndexPaths(String service) {
    if (service == null || service.isBlank()) {
      throw new IllegalArgumentException("service is required");
    }
    Path base = ServiceIndexPathResolver.resolveIndexPath(service);
    List<Path> paths = new ArrayList<>();
    paths.add(base);
    getActiveConnectionKey(service)
        .map(key -> ServiceIndexPathResolver.resolveZohoConnectionIndexPath(service, key))
        .ifPresent(
            zohoPath -> {
              if (!zohoPath.equals(base)) {
                paths.add(zohoPath);
              }
            });
    return List.copyOf(paths);
  }

  private Optional<JsonNode> read(String service) {
    Path file = pathFor(service);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(MAPPER.readTree(Files.readString(file)));
    } catch (Exception ex) {
      log.warn("Corrupt Zoho active pointer {}: {}", file, ex.getMessage());
      return Optional.empty();
    }
  }

  private Path pathFor(String service) {
    return storeDir.resolve(ServiceIndexPathResolver.sanitizeServiceName(service) + ".json");
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? "" : value.asText("");
  }
}
