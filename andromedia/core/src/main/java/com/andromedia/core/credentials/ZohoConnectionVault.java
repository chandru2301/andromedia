package com.andromedia.core.credentials;

import com.andromedia.core.index.ActiveZohoIndexStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Zoho OAuth connection vault. When PostgreSQL credentials are enabled, secrets are stored there;
 * otherwise AES-GCM encrypted files under {@code ~/.andromedia/connections/}. File vault remains a
 * read fallback for migration.
 */
@Component
public class ZohoConnectionVault {

  private static final Logger log = LoggerFactory.getLogger(ZohoConnectionVault.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Path storeDir;
  private final CredentialCipher cipher;
  private final UserCredentialStore userCredentialStore;

  @Autowired
  public ZohoConnectionVault(CredentialCipher cipher, UserCredentialStore userCredentialStore) {
    this(
        cipher,
        userCredentialStore,
        Path.of(System.getProperty("user.home"), ".andromedia", "connections")
            .toAbsolutePath()
            .normalize());
  }

  /** Test/helper constructor. */
  ZohoConnectionVault(CredentialCipher cipher, Path storeDir) {
    this(cipher, null, storeDir);
  }

  ZohoConnectionVault(
      CredentialCipher cipher, UserCredentialStore userCredentialStore, Path storeDir) {
    this.cipher = cipher;
    this.userCredentialStore = userCredentialStore;
    this.storeDir = storeDir;
  }

  /** Decrypted Zoho credentials for server-side API clients. */
  public record DecryptedZohoCredentials(
      String connectionId,
      String connectionKey,
      String ownerId,
      String region,
      String clientId,
      String clientSecret,
      String refreshToken,
      String status) {}

  public ZohoConnectionRecord upsertZohoConnection(
      String ownerId,
      String clientId,
      String clientSecret,
      String refreshToken,
      String region) {
    if (ownerId == null || ownerId.isBlank()) {
      throw new IllegalArgumentException("service (ownerId) is required to vault Zoho credentials");
    }
    if (clientId == null || clientId.isBlank()) {
      throw new IllegalArgumentException("clientId is required");
    }
    if (clientSecret == null || clientSecret.isBlank()) {
      throw new IllegalArgumentException("clientSecret is required");
    }
    if (refreshToken == null || refreshToken.isBlank()) {
      throw new IllegalArgumentException("refreshToken is required");
    }
    String normalizedRegion = region == null ? "" : region.trim().toLowerCase(Locale.ROOT);
    String connectionKey =
        ActiveZohoIndexStore.connectionKey(clientId, refreshToken, normalizedRegion);
    Instant now = Instant.now();

    if (postgresEnabled()) {
      Optional<JsonNode> existingPayload =
          userCredentialStore.findDecryptedPayload(ownerId.trim(), CredentialProviders.ZOHO);
      String connectionId =
          existingPayload
              .map(node -> text(node, "connectionId"))
              .filter(id -> !id.isBlank())
              .orElse(UUID.randomUUID().toString());
      Map<String, Object> secrets = new LinkedHashMap<>();
      secrets.put("connectionId", connectionId);
      secrets.put("connectionKey", connectionKey);
      secrets.put("clientId", clientId.trim());
      secrets.put("clientSecret", clientSecret.trim());
      secrets.put("refreshToken", refreshToken.trim());
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("connectionId", connectionId);
      metadata.put("connectionKey", connectionKey);
      metadata.put("region", normalizedRegion);
      userCredentialStore.upsert(
          ownerId.trim(), CredentialProviders.ZOHO, secrets, metadata, true);
      log.info(
          "Vaulted Zoho connection id={} key={} owner={} region={} (postgres)",
          connectionId,
          connectionKey,
          ownerId.trim(),
          normalizedRegion);
      return new ZohoConnectionRecord(
          connectionId,
          ownerId.trim(),
          ZohoConnectionRecord.PROVIDER_ZOHO,
          connectionKey,
          normalizedRegion,
          cipher.encrypt(clientId.trim()),
          cipher.encrypt(clientSecret.trim()),
          cipher.encrypt(refreshToken.trim()),
          now,
          now,
          ZohoConnectionRecord.STATUS_ACTIVE);
    }

    List<ZohoConnectionRecord> records = readOwnerFile(ownerId);
    Optional<ZohoConnectionRecord> existing =
        records.stream()
            .filter(r -> connectionKey.equals(r.connectionKey()) && r.active())
            .findFirst();

    ZohoConnectionRecord saved;
    if (existing.isPresent()) {
      ZohoConnectionRecord prev = existing.get();
      saved =
          new ZohoConnectionRecord(
              prev.id(),
              ownerId.trim(),
              ZohoConnectionRecord.PROVIDER_ZOHO,
              connectionKey,
              normalizedRegion,
              cipher.encrypt(clientId.trim()),
              cipher.encrypt(clientSecret.trim()),
              cipher.encrypt(refreshToken.trim()),
              prev.createdAt(),
              now,
              ZohoConnectionRecord.STATUS_ACTIVE);
      records =
          records.stream().map(r -> r.id().equals(prev.id()) ? saved : r).toList();
    } else {
      saved =
          new ZohoConnectionRecord(
              UUID.randomUUID().toString(),
              ownerId.trim(),
              ZohoConnectionRecord.PROVIDER_ZOHO,
              connectionKey,
              normalizedRegion,
              cipher.encrypt(clientId.trim()),
              cipher.encrypt(clientSecret.trim()),
              cipher.encrypt(refreshToken.trim()),
              now,
              now,
              ZohoConnectionRecord.STATUS_ACTIVE);
      List<ZohoConnectionRecord> next = new ArrayList<>(records);
      next.add(saved);
      records = next;
    }
    writeOwnerFile(ownerId, records);
    log.info(
        "Vaulted Zoho connection id={} key={} owner={} region={}",
        saved.id(),
        saved.connectionKey(),
        saved.ownerId(),
        saved.region());
    return saved;
  }

  public Optional<ZohoConnectionRecord> findById(String ownerId, String connectionId) {
    if (ownerId == null || ownerId.isBlank() || connectionId == null || connectionId.isBlank()) {
      return Optional.empty();
    }
    if (postgresEnabled()) {
      Optional<ZohoConnectionRecord> fromPg = readPostgresRecord(ownerId.trim());
      if (fromPg.isPresent() && connectionId.trim().equals(fromPg.get().id())) {
        return fromPg;
      }
      // Fall back to file and import once.
      Optional<ZohoConnectionRecord> fromFile =
          readOwnerFile(ownerId).stream()
              .filter(r -> connectionId.trim().equals(r.id()))
              .findFirst();
      fromFile.ifPresent(record -> importFileRecordToPostgres(record));
      return fromFile;
    }
    return readOwnerFile(ownerId).stream()
        .filter(r -> connectionId.trim().equals(r.id()))
        .findFirst();
  }

  public DecryptedZohoCredentials requireActiveCredentials(String ownerId, String connectionId) {
    ZohoConnectionRecord record =
        findById(ownerId, connectionId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Unknown Zoho connectionId for this service. Re-connect Zoho."));
    if (!record.active()) {
      throw new ZohoReauthRequiredException(
          record.id(),
          "Zoho connection was revoked. Exchange a new grant token to reconnect.");
    }
    return decrypt(record);
  }

  public DecryptedZohoCredentials decrypt(ZohoConnectionRecord record) {
    if (record == null) {
      throw new IllegalArgumentException("connection record is required");
    }
    return new DecryptedZohoCredentials(
        record.id(),
        record.connectionKey(),
        record.ownerId(),
        record.region(),
        cipher.decrypt(record.clientIdEnc()),
        cipher.decrypt(record.clientSecretEnc()),
        cipher.decrypt(record.refreshTokenEnc()),
        record.status());
  }

  public void markRefreshed(String ownerId, String connectionId) {
    updateRecord(ownerId, connectionId, r -> r.withLastRefreshedAt(Instant.now()));
  }

  public void markRevoked(String ownerId, String connectionId) {
    updateRecord(
        ownerId, connectionId, r -> r.withStatus(ZohoConnectionRecord.STATUS_REVOKED));
    log.warn("Marked Zoho connection {} revoked for owner {}", connectionId, ownerId);
  }

  private void updateRecord(
      String ownerId,
      String connectionId,
      java.util.function.UnaryOperator<ZohoConnectionRecord> transform) {
    if (ownerId == null || ownerId.isBlank() || connectionId == null || connectionId.isBlank()) {
      return;
    }
    if (postgresEnabled()) {
      Optional<ZohoConnectionRecord> current = findById(ownerId, connectionId);
      if (current.isEmpty()) {
        return;
      }
      ZohoConnectionRecord updated = transform.apply(current.get());
      if (!updated.active()) {
        userCredentialStore.delete(ownerId.trim(), CredentialProviders.ZOHO);
        return;
      }
      DecryptedZohoCredentials decrypted = decrypt(updated);
      Map<String, Object> secrets = new LinkedHashMap<>();
      secrets.put("connectionId", updated.id());
      secrets.put("connectionKey", updated.connectionKey());
      secrets.put("clientId", decrypted.clientId());
      secrets.put("clientSecret", decrypted.clientSecret());
      secrets.put("refreshToken", decrypted.refreshToken());
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("connectionId", updated.id());
      metadata.put("connectionKey", updated.connectionKey());
      metadata.put("region", updated.region());
      userCredentialStore.upsert(
          ownerId.trim(), CredentialProviders.ZOHO, secrets, metadata, true);
      return;
    }
    List<ZohoConnectionRecord> records = new ArrayList<>(readOwnerFile(ownerId));
    boolean changed = false;
    for (int i = 0; i < records.size(); i++) {
      if (connectionId.trim().equals(records.get(i).id())) {
        records.set(i, transform.apply(records.get(i)));
        changed = true;
        break;
      }
    }
    if (changed) {
      writeOwnerFile(ownerId, records);
    }
  }

  private boolean postgresEnabled() {
    return userCredentialStore != null && userCredentialStore.enabled();
  }

  private Optional<ZohoConnectionRecord> readPostgresRecord(String ownerId) {
    Optional<JsonNode> payload =
        userCredentialStore.findDecryptedPayload(ownerId, CredentialProviders.ZOHO);
    if (payload.isEmpty()) {
      return Optional.empty();
    }
    JsonNode node = payload.get();
    Optional<CredentialPublicView> view =
        userCredentialStore.findPublic(ownerId, CredentialProviders.ZOHO);
    String region =
        view.map(v -> String.valueOf(v.metadata().getOrDefault("region", "")))
            .orElse(text(node, "region"));
    String status =
        view.map(CredentialPublicView::status).orElse(ZohoConnectionRecord.STATUS_ACTIVE);
    Instant updated = view.map(CredentialPublicView::updatedAt).orElse(Instant.now());
    return Optional.of(
        new ZohoConnectionRecord(
            text(node, "connectionId"),
            ownerId,
            ZohoConnectionRecord.PROVIDER_ZOHO,
            text(node, "connectionKey"),
            region == null ? "" : region,
            cipher.encrypt(text(node, "clientId")),
            cipher.encrypt(text(node, "clientSecret")),
            cipher.encrypt(text(node, "refreshToken")),
            updated,
            updated,
            status));
  }

  private void importFileRecordToPostgres(ZohoConnectionRecord record) {
    if (!postgresEnabled() || record == null || !record.active()) {
      return;
    }
    try {
      DecryptedZohoCredentials decrypted = decrypt(record);
      Map<String, Object> secrets = new LinkedHashMap<>();
      secrets.put("connectionId", record.id());
      secrets.put("connectionKey", record.connectionKey());
      secrets.put("clientId", decrypted.clientId());
      secrets.put("clientSecret", decrypted.clientSecret());
      secrets.put("refreshToken", decrypted.refreshToken());
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("connectionId", record.id());
      metadata.put("connectionKey", record.connectionKey());
      metadata.put("region", record.region());
      userCredentialStore.upsert(
          record.ownerId(), CredentialProviders.ZOHO, secrets, metadata, true);
      log.info("Imported Zoho connection {} from file vault into Postgres", record.id());
    } catch (Exception ex) {
      log.warn("Failed to import Zoho connection {} into Postgres: {}", record.id(), ex.getMessage());
    }
  }

  private List<ZohoConnectionRecord> readOwnerFile(String ownerId) {
    Path path = pathFor(ownerId);
    if (!Files.isRegularFile(path)) {
      return List.of();
    }
    try {
      JsonNode root = MAPPER.readTree(Files.readString(path));
      JsonNode connections = root.path("connections");
      if (!connections.isArray()) {
        return List.of();
      }
      List<ZohoConnectionRecord> out = new ArrayList<>();
      for (JsonNode node : connections) {
        out.add(fromJson(node));
      }
      return List.copyOf(out);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to read Zoho connection vault for " + ownerId, ex);
    }
  }

  private void writeOwnerFile(String ownerId, List<ZohoConnectionRecord> records) {
    try {
      Files.createDirectories(storeDir);
      ObjectNode root = MAPPER.createObjectNode();
      root.put("ownerId", ownerId.trim());
      root.put("updatedAt", Instant.now().toString());
      ArrayNode array = root.putArray("connections");
      for (ZohoConnectionRecord record : records) {
        array.add(toJson(record));
      }
      Files.writeString(
          pathFor(ownerId), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to write Zoho connection vault for " + ownerId, ex);
    }
  }

  private Path pathFor(String ownerId) {
    String safe =
        ownerId
            .trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._-]+", "_");
    if (safe.isBlank()) {
      safe = "unknown";
    }
    return storeDir.resolve(safe + ".json");
  }

  private ObjectNode toJson(ZohoConnectionRecord record) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("id", record.id());
    node.put("ownerId", record.ownerId());
    node.put("provider", record.provider());
    node.put("connectionKey", record.connectionKey());
    node.put("region", record.region());
    node.put("clientIdEnc", record.clientIdEnc());
    node.put("clientSecretEnc", record.clientSecretEnc());
    node.put("refreshTokenEnc", record.refreshTokenEnc());
    node.put("createdAt", record.createdAt().toString());
    if (record.lastRefreshedAt() != null) {
      node.put("lastRefreshedAt", record.lastRefreshedAt().toString());
    }
    node.put("status", record.status());
    return node;
  }

  private ZohoConnectionRecord fromJson(JsonNode node) {
    Instant created = parseInstant(text(node, "createdAt")).orElse(Instant.EPOCH);
    Instant refreshed = parseInstant(text(node, "lastRefreshedAt")).orElse(null);
    return new ZohoConnectionRecord(
        text(node, "id"),
        text(node, "ownerId"),
        text(node, "provider"),
        text(node, "connectionKey"),
        text(node, "region"),
        text(node, "clientIdEnc"),
        text(node, "clientSecretEnc"),
        text(node, "refreshTokenEnc"),
        created,
        refreshed,
        text(node, "status"));
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? "" : value.asText("");
  }

  private static Optional<Instant> parseInstant(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Instant.parse(value.trim()));
    } catch (Exception ex) {
      return Optional.empty();
    }
  }
}
