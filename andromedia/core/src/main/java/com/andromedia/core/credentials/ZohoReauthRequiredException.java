package com.andromedia.core.credentials;

/** Raised when a stored Zoho refresh token is revoked/invalid and the user must re-auth. */
public class ZohoReauthRequiredException extends IllegalArgumentException {

  public static final String CODE = "ZOHO_REAUTH_REQUIRED";

  private final String connectionId;

  public ZohoReauthRequiredException(String connectionId, String message) {
    super(message == null || message.isBlank() ? "Zoho re-authorization required" : message);
    this.connectionId = connectionId == null ? "" : connectionId.trim();
  }

  public String connectionId() {
    return connectionId;
  }

  public String code() {
    return CODE;
  }
}
