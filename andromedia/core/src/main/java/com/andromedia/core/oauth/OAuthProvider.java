package com.andromedia.core.oauth;

import com.andromedia.core.credentials.CredentialProviders;
import java.util.Locale;

/**
 * Providers with a real OAuth authorization-code flow (as opposed to a pasted API token/secret).
 * Each entry carries what differs between providers; {@link OAuthService} carries the shared
 * exchange/state/storage logic.
 */
public enum OAuthProvider {
  SLACK(
      "slack",
      CredentialProviders.SLACK,
      "botToken",
      "https://slack.com/oauth/v2/authorize",
      "https://slack.com/api/oauth.v2.access",
      "channels:history,channels:read,groups:read,groups:history,users:read"),
  NOTION(
      "notion",
      CredentialProviders.NOTION,
      "apiKey",
      "https://api.notion.com/v1/oauth/authorize",
      "https://api.notion.com/v1/oauth/token",
      // Notion scopes live on the integration's capabilities in its dashboard, not the auth URL.
      ""),
  GOOGLE_CHAT(
      "google_chat",
      CredentialProviders.GOOGLE_CHAT,
      "accessToken",
      "https://accounts.google.com/o/oauth2/v2/auth",
      "https://oauth2.googleapis.com/token",
      "https://www.googleapis.com/auth/chat.spaces.readonly"
          + " https://www.googleapis.com/auth/chat.messages.readonly"),
  LINEAR(
      "linear",
      CredentialProviders.LINEAR,
      "apiKey",
      "https://linear.app/oauth/authorize",
      "https://api.linear.app/oauth/token",
      "read"),
  ZOHO(
      "zoho",
      CredentialProviders.ZOHO,
      "refreshToken",
      "https://accounts.zoho.com/oauth/v2/auth",
      "https://accounts.zoho.com/oauth/v2/token",
      "ZohoCRM.modules.ALL,ZohoCRM.settings.ALL"),
  JIRA(
      "jira",
      CredentialProviders.JIRA,
      "accessToken",
      "https://auth.atlassian.com/authorize",
      "https://auth.atlassian.com/oauth/token",
      "read:jira-work read:jira-user offline_access");

  private final String id;
  private final String credentialProvider;
  private final String secretField;
  private final String authorizeUrl;
  private final String tokenUrl;
  private final String scope;

  OAuthProvider(
      String id,
      String credentialProvider,
      String secretField,
      String authorizeUrl,
      String tokenUrl,
      String scope) {
    this.id = id;
    this.credentialProvider = credentialProvider;
    this.secretField = secretField;
    this.authorizeUrl = authorizeUrl;
    this.tokenUrl = tokenUrl;
    this.scope = scope;
  }

  public String id() {
    return id;
  }

  public String credentialProvider() {
    return credentialProvider;
  }

  /** The key this provider's access token is stored under in the credential payload. */
  public String secretField() {
    return secretField;
  }

  public String authorizeUrl() {
    return authorizeUrl;
  }

  public String tokenUrl() {
    return tokenUrl;
  }

  public String scope() {
    return scope;
  }

  public static OAuthProvider fromId(String id) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("provider is required");
    }
    String normalized = id.trim().toLowerCase(Locale.ROOT);
    for (OAuthProvider provider : values()) {
      if (provider.id.equals(normalized)) {
        return provider;
      }
    }
    throw new IllegalArgumentException("Unsupported OAuth provider: " + id);
  }
}
