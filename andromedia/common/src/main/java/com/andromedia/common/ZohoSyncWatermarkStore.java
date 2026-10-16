package com.andromedia.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable per-org, per-module Zoho sync watermarks under {@code
 * ~/.andromedia/zoho-watermarks/{orgId}.json}.
 */
public class ZohoSyncWatermarkStore {

  public static final String STATUS_SUCCESS = "success";
  public static final String STATUS_PARTIAL = "partial";
  public static final String STATUS_FAILED = "failed";

  private static final String FIELD_LAST_SYNCED_AT = "lastSyncedAt";
  private static final String FIELD_LAST_SYNC_STATUS = "lastSyncStatus";
  private static final String FIELD_RECORDS_FETCHED = "recordsFetched";

  private final Path watermarkDir;
  private final ObjectMapper objectMapper =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  public ZohoSyncWatermarkStore() {
    this(Path.of(System.getProperty("user.home"), ".andromedia", "zoho-watermarks"));
  }

  /** Package-visible for tests with an isolated directory. */
  ZohoSyncWatermarkStore(Path watermarkDir) {
    this.watermarkDir = Objects.requireNonNull(watermarkDir, "watermarkDir").toAbsolutePath().normalize();
  }

  public Optional<Instant> getWatermark(String orgId, String module) {
    Path file = watermarkPath(orgId);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      JsonNode root = objectMapper.readTree(file.toFile());
      if (root == null || !root.isObject()) {
        return Optional.empty();
      }
      JsonNode moduleNode = root.get(normalizeModule(module));
      if (moduleNode == null || !moduleNode.isObject()) {
        return Optional.empty();
      }
      String syncedAt = moduleNode.path(FIELD_LAST_SYNCED_AT).asText("").trim();
      if (syncedAt.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(Instant.parse(syncedAt));
    } catch (IOException | RuntimeException ex) {
      throw new IllegalStateException("Failed to read Zoho watermark at " + file, ex);
    }
  }

  public void updateWatermark(
      String orgId, String module, Instant syncedAt, int recordCount) {
    updateWatermark(orgId, module, syncedAt, STATUS_SUCCESS, recordCount);
  }

  public void updateWatermark(
      String orgId, String module, Instant syncedAt, String status, int recordCount) {
    if (syncedAt == null) {
      throw new IllegalArgumentException("syncedAt is required");
    }
    String normalizedModule = normalizeModule(module);
    if (normalizedModule.isEmpty()) {
      throw new IllegalArgumentException("module is required");
    }
    Path file = watermarkPath(orgId);
    try {
      Files.createDirectories(file.getParent());
      ObjectNode root = readOrCreate(file);
      ObjectNode moduleNode = root.putObject(normalizedModule);
      moduleNode.put(FIELD_LAST_SYNCED_AT, syncedAt.toString());
      moduleNode.put(
          FIELD_LAST_SYNC_STATUS,
          status == null || status.isBlank() ? STATUS_SUCCESS : status.trim());
      moduleNode.put(FIELD_RECORDS_FETCHED, Math.max(0, recordCount));
      writeJsonAtomically(file, root);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to write Zoho watermark at " + file, ex);
    }
  }

  public Path watermarkPath(String orgId) {
    return watermarkDir.resolve(sanitizeOrgId(orgId) + ".json");
  }

  private ObjectNode readOrCreate(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      return objectMapper.createObjectNode();
    }
    JsonNode existing = objectMapper.readTree(file.toFile());
    if (existing != null && existing.isObject()) {
      return (ObjectNode) existing;
    }
    return objectMapper.createObjectNode();
  }

  private void writeJsonAtomically(Path file, JsonNode data) throws IOException {
    Path temp = file.resolveSibling(file.getFileName() + ".tmp");
    objectMapper.writeValue(temp.toFile(), data);
    try {
      Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException atomicUnsupported) {
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  static String sanitizeOrgId(String orgId) {
    if (orgId == null || orgId.isBlank()) {
      throw new IllegalArgumentException("orgId is required");
    }
    return orgId.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
  }

  private static String normalizeModule(String module) {
    return module == null ? "" : module.trim();
  }
}
