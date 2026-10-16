package com.andromedia.core.credentials;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "andromedia.credentials")
public record CredentialsProperties(
    /** Base64-encoded 32-byte AES key. Prefer env ANDROMEDIA_CREDENTIALS_ENCRYPTION_KEY. */
    String encryptionKey,
    /**
     * When true and encryptionKey is blank, generate an ephemeral in-memory key (local/dev only).
     * Connections encrypted with an ephemeral key are lost on restart.
     */
    boolean allowEphemeralKey,
    /**
     * When true, persist user credentials in PostgreSQL (requires {@code spring.datasource.url} /
     * {@code ANDROMEDIA_DATABASE_URL}).
     */
    boolean postgresEnabled) {

  public CredentialsProperties {
    encryptionKey = encryptionKey == null ? "" : encryptionKey.trim();
  }
}
