package com.andromedia.core.index;

import com.andromedia.core.credentials.ZohoConnectionVault;
import com.andromedia.core.credentials.ZohoConnectionVault.DecryptedZohoCredentials;
import com.andromedia.core.credentials.ZohoReauthRequiredException;
import com.andromedia.ingestion.zoho.ZohoApiClient;
import com.andromedia.ingestion.zoho.ZohoRegion;
import java.io.IOException;
import org.springframework.stereotype.Service;

@Service
public class ZohoConnectionTestService {

  private final ZohoIngestionProperties zohoIngestionProperties;
  private final ZohoConnectionVault zohoConnectionVault;

  public ZohoConnectionTestService(
      ZohoIngestionProperties zohoIngestionProperties, ZohoConnectionVault zohoConnectionVault) {
    this.zohoIngestionProperties = zohoIngestionProperties;
    this.zohoConnectionVault = zohoConnectionVault;
  }

  /** Preferred path: resolve secrets from the server-side vault by connectionId. */
  public ZohoApiClient.ZohoConnectionTestResult testByConnectionId(
      String ownerId, String connectionId) {
    DecryptedZohoCredentials creds =
        zohoConnectionVault.requireActiveCredentials(ownerId, connectionId);
    try {
      ZohoApiClient.ZohoConnectionTestResult result =
          test(creds.clientId(), creds.clientSecret(), creds.refreshToken(), creds.region());
      zohoConnectionVault.markRefreshed(ownerId, connectionId);
      return result;
    } catch (IllegalArgumentException ex) {
      if (looksLikeAuthFailure(ex.getMessage())) {
        zohoConnectionVault.markRevoked(ownerId, connectionId);
        throw new ZohoReauthRequiredException(
            connectionId,
            "Zoho refresh token is invalid or revoked. Exchange a new grant token to reconnect.");
      }
      throw ex;
    }
  }

  public ZohoApiClient.ZohoConnectionTestResult test(
      String clientId, String clientSecret, String refreshToken, String region) {
    if (refreshToken == null || refreshToken.isBlank()) {
      throw new IllegalArgumentException("Zoho refresh token is required");
    }
    ZohoApiClient client =
        new ZohoApiClient(
            resolve(clientId, ""),
            resolve(clientSecret, ""),
            refreshToken.trim(),
            ZohoRegion.fromToken(region),
            zohoIngestionProperties.httpRetryPolicy());
    try {
      return client.testConnection();
    } catch (IOException ex) {
      throw new IllegalArgumentException(
          ex.getMessage() == null ? "Zoho API call failed" : ex.getMessage());
    }
  }

  static boolean looksLikeAuthFailure(String message) {
    if (message == null || message.isBlank()) {
      return false;
    }
    String lower = message.toLowerCase();
    return lower.contains("invalid_token")
        || lower.contains("invalid oauth")
        || lower.contains("refresh token")
        || lower.contains("unauthorized")
        || lower.contains("authentication failed")
        || lower.contains("access denied")
        || lower.contains("invalid_client");
  }

  private static String resolve(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value.trim();
  }
}
