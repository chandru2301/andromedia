package com.andromedia.core.credentials;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Encrypted per-user credential vault backed by PostgreSQL when {@code
 * andromedia.credentials.postgres-enabled=true}.
 */
@Component
public class UserCredentialStore {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final CredentialCipher cipher;
  private final boolean enabled;

  @Autowired
  public UserCredentialStore(
      ObjectProvider<JdbcTemplate> jdbcTemplateProvider, CredentialCipher cipher) {
    this.jdbc = jdbcTemplateProvider.getIfAvailable();
    this.cipher = cipher;
    this.enabled = this.jdbc != null;
  }

  /** Test constructor. */
  UserCredentialStore(JdbcTemplate jdbc, CredentialCipher cipher) {
    this.jdbc = jdbc;
    this.cipher = cipher;
    this.enabled = jdbc != null;
  }

  public boolean enabled() {
    return enabled;
  }

  public void touchUser(String clerkUserId, String trialId) {
    if (!enabled) {
      return;
    }
    if (trialId == null || trialId.isBlank()) {
      throw new IllegalArgumentException("trialId is required");
    }
    Instant now = Instant.now();
    String clerk =
        clerkUserId == null || clerkUserId.isBlank() ? null : clerkUserId.trim();
    jdbc.update(
        """
        INSERT INTO users (trial_id, clerk_user_id, created_at, last_seen_at)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (trial_id) DO UPDATE
          SET clerk_user_id = COALESCE(EXCLUDED.clerk_user_id, users.clerk_user_id),
              last_seen_at = EXCLUDED.last_seen_at
        """,
        trialId.trim(),
        clerk,
        Timestamp.from(now),
        Timestamp.from(now));
  }

  public void ensureUser(String trialId) {
    touchUser(null, trialId);
  }

  public List<CredentialPublicView> listPublic(String trialId) {
    if (!enabled || trialId == null || trialId.isBlank()) {
      return List.of();
    }
    return jdbc.query(
        """
        SELECT provider, encrypted_payload, metadata_json, status, updated_at
        FROM user_credentials
        WHERE trial_id = ? AND status = 'active'
        ORDER BY provider
        """,
        (rs, rowNum) -> toPublicView(rs),
        trialId.trim());
  }

  public Optional<CredentialPublicView> findPublic(String trialId, String provider) {
    return findRow(trialId, provider).map(this::toPublicView);
  }

  public Optional<JsonNode> findDecryptedPayload(String trialId, String provider) {
    return findRow(trialId, provider)
        .filter(row -> "active".equals(row.status()))
        .map(
            row -> {
              try {
                return MAPPER.readTree(cipher.decrypt(row.encryptedPayload()));
              } catch (Exception ex) {
                throw new IllegalStateException("Failed to decrypt credential payload", ex);
              }
            });
  }

  public Optional<String> findSecretField(String trialId, String provider, String field) {
    return findDecryptedPayload(trialId, provider)
        .map(node -> text(node, field))
        .filter(value -> value != null && !value.isBlank());
  }

  /**
   * Upserts a credential. When {@code secretPayload} is null/empty and a row already exists, keeps
   * the previous encrypted secret and only updates metadata.
   */
  public CredentialPublicView upsert(
      String trialId,
      String provider,
      Map<String, Object> secretPayload,
      Map<String, Object> metadata,
      boolean replaceSecret) {
    requireEnabled();
    String normalizedProvider = CredentialProviders.normalize(provider);
    String owner = trialId.trim();
    ensureUser(owner);

    Optional<StoredRow> existing = findRow(owner, normalizedProvider);
    Instant now = Instant.now();
    Map<String, Object> safeMetadata = metadata == null ? Map.of() : metadata;
    String encrypted;
    if (replaceSecret) {
      if (secretPayload == null || secretPayload.isEmpty()) {
        throw new IllegalArgumentException("secret payload is required");
      }
      encrypted = cipher.encrypt(writeJson(secretPayload));
    } else if (existing.isPresent()) {
      encrypted = existing.get().encryptedPayload();
    } else {
      throw new IllegalArgumentException(
          "No saved secret for provider " + normalizedProvider + "; provide secrets to create");
    }

    UUID id = existing.map(StoredRow::id).orElse(UUID.randomUUID());
    String metadataJson = writeJson(safeMetadata);
    jdbc.update(
        """
        INSERT INTO user_credentials
          (id, trial_id, provider, encrypted_payload, metadata_json, status, updated_at)
        VALUES (?, ?, ?, ?, ?::jsonb, 'active', ?)
        ON CONFLICT (trial_id, provider) DO UPDATE SET
          encrypted_payload = EXCLUDED.encrypted_payload,
          metadata_json = EXCLUDED.metadata_json,
          status = 'active',
          updated_at = EXCLUDED.updated_at
        """,
        id,
        owner,
        normalizedProvider,
        encrypted,
        metadataJson,
        Timestamp.from(now));

    return findPublic(owner, normalizedProvider)
        .orElseThrow(() -> new IllegalStateException("Credential upsert failed"));
  }

  public void delete(String trialId, String provider) {
    requireEnabled();
    jdbc.update(
        """
        UPDATE user_credentials
        SET status = 'revoked', updated_at = ?
        WHERE trial_id = ? AND provider = ?
        """,
        Timestamp.from(Instant.now()),
        trialId.trim(),
        CredentialProviders.normalize(provider));
  }

  public Optional<String> resolveSlackBotToken(String trialId, String requestToken) {
    if (requestToken != null && !requestToken.isBlank()) {
      return Optional.of(requestToken.trim());
    }
    return findSecretField(trialId, CredentialProviders.SLACK, "botToken");
  }

  public Optional<String> resolveGoogleChatAccessToken(String trialId, String requestToken) {
    if (requestToken != null && !requestToken.isBlank()) {
      return Optional.of(requestToken.trim());
    }
    return findSecretField(trialId, CredentialProviders.GOOGLE_CHAT, "accessToken");
  }

  public Optional<String> resolveNotionApiKey(String trialId, String requestKey) {
    if (requestKey != null && !requestKey.isBlank()) {
      return Optional.of(requestKey.trim());
    }
    return findSecretField(trialId, CredentialProviders.NOTION, "apiKey");
  }

  public Optional<String> resolveLinearApiKey(String trialId, String requestKey) {
    if (requestKey != null && !requestKey.isBlank()) {
      return Optional.of(requestKey.trim());
    }
    return findSecretField(trialId, CredentialProviders.LINEAR, "apiKey");
  }

  public Optional<JiraOAuthCredentials> resolveJiraOAuthCredentials(String trialId) {
    return findDecryptedPayload(trialId, CredentialProviders.JIRA)
        .map(
            node ->
                new JiraOAuthCredentials(
                    text(node, "cloudId"), text(node, "accessToken"), text(node, "siteUrl")))
        .filter(
            credentials ->
                !credentials.cloudId().isBlank() && !credentials.accessToken().isBlank());
  }

  public Optional<String> resolveLlmApiKey(String trialId, String chatProvider) {
    if (!enabled || trialId == null || trialId.isBlank()) {
      return Optional.empty();
    }
    return findSecretField(trialId, CredentialProviders.llmProvider(chatProvider), "apiKey");
  }

  public record JiraOAuthCredentials(String cloudId, String accessToken, String siteUrl) {}

  private void requireEnabled() {
    if (!enabled) {
      throw new IllegalStateException(
          "PostgreSQL credential vault is disabled. Set andromedia.credentials.postgres-enabled=true "
              + "and ANDROMEDIA_DATABASE_URL.");
    }
  }

  private Optional<StoredRow> findRow(String trialId, String provider) {
    if (!enabled || trialId == null || trialId.isBlank()) {
      return Optional.empty();
    }
    String normalized = CredentialProviders.normalize(provider);
    List<StoredRow> rows =
        jdbc.query(
            """
            SELECT id, trial_id, provider, encrypted_payload, metadata_json, status, updated_at
            FROM user_credentials
            WHERE trial_id = ? AND provider = ?
            """,
            (rs, rowNum) ->
                new StoredRow(
                    (UUID) rs.getObject("id"),
                    rs.getString("trial_id"),
                    rs.getString("provider"),
                    rs.getString("encrypted_payload"),
                    rs.getString("metadata_json"),
                    rs.getString("status"),
                    rs.getTimestamp("updated_at").toInstant()),
            trialId.trim(),
            normalized);
    return rows.stream().findFirst();
  }

  private CredentialPublicView toPublicView(ResultSet rs) throws SQLException {
    return toPublicView(
        new StoredRow(
            null,
            null,
            rs.getString("provider"),
            rs.getString("encrypted_payload"),
            rs.getString("metadata_json"),
            rs.getString("status"),
            rs.getTimestamp("updated_at").toInstant()));
  }

  private CredentialPublicView toPublicView(StoredRow row) {
    Map<String, Object> metadata = readMap(row.metadataJson());
    boolean hasSecret =
        row.encryptedPayload() != null
            && !row.encryptedPayload().isBlank()
            && "active".equals(row.status());
    return new CredentialPublicView(
        row.provider(), hasSecret, row.status(), metadata, row.updatedAt());
  }

  private static Map<String, Object> readMap(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      Map<String, Object> map = MAPPER.readValue(json, MAP_TYPE);
      return map == null ? Map.of() : new LinkedHashMap<>(map);
    } catch (Exception ex) {
      return Map.of();
    }
  }

  private static String writeJson(Map<String, Object> map) {
    try {
      return MAPPER.writeValueAsString(map == null ? Map.of() : map);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to serialize credential JSON", ex);
    }
  }

  private static String text(JsonNode node, String field) {
    if (node == null || field == null) {
      return "";
    }
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? "" : value.asText("");
  }

  private record StoredRow(
      UUID id,
      String trialId,
      String provider,
      String encryptedPayload,
      String metadataJson,
      String status,
      Instant updatedAt) {}
}
