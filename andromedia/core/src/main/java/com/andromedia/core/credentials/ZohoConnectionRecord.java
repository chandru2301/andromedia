package com.andromedia.core.credentials;

import java.time.Instant;

/** Persisted Zoho OAuth connection with encrypted secret columns. */
public record ZohoConnectionRecord(
    String id,
    String ownerId,
    String provider,
    String connectionKey,
    String region,
    String clientIdEnc,
    String clientSecretEnc,
    String refreshTokenEnc,
    Instant createdAt,
    Instant lastRefreshedAt,
    String status) {

  public static final String PROVIDER_ZOHO = "zoho";
  public static final String STATUS_ACTIVE = "active";
  public static final String STATUS_REVOKED = "revoked";

  public ZohoConnectionRecord {
    id = id == null ? "" : id.trim();
    ownerId = ownerId == null ? "" : ownerId.trim();
    provider = provider == null || provider.isBlank() ? PROVIDER_ZOHO : provider.trim();
    connectionKey = connectionKey == null ? "" : connectionKey.trim();
    region = region == null ? "" : region.trim();
    clientIdEnc = clientIdEnc == null ? "" : clientIdEnc;
    clientSecretEnc = clientSecretEnc == null ? "" : clientSecretEnc;
    refreshTokenEnc = refreshTokenEnc == null ? "" : refreshTokenEnc;
    createdAt = createdAt == null ? Instant.now() : createdAt;
    status =
        status == null || status.isBlank() ? STATUS_ACTIVE : status.trim().toLowerCase();
  }

  public boolean active() {
    return STATUS_ACTIVE.equalsIgnoreCase(status);
  }

  public ZohoConnectionRecord withLastRefreshedAt(Instant at) {
    return new ZohoConnectionRecord(
        id,
        ownerId,
        provider,
        connectionKey,
        region,
        clientIdEnc,
        clientSecretEnc,
        refreshTokenEnc,
        createdAt,
        at,
        status);
  }

  public ZohoConnectionRecord withStatus(String newStatus) {
    return new ZohoConnectionRecord(
        id,
        ownerId,
        provider,
        connectionKey,
        region,
        clientIdEnc,
        clientSecretEnc,
        refreshTokenEnc,
        createdAt,
        lastRefreshedAt,
        newStatus);
  }
}
