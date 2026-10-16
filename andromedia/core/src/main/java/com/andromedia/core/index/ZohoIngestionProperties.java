package com.andromedia.core.index;

import com.andromedia.ingestion.zoho.ZohoHttpRetry;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "andromedia.zoho")
public class ZohoIngestionProperties {

  private String clientId = "";
  private String clientSecret = "";
  private String refreshToken = "";
  private String region = "com";
  private int lookbackDays = 90;
  private double minQualityScore = 0.35;
  /**
   * When true, classic Zoho sandbox account names (King, Chapman, …) are treated as demo
   * accounts. Disable for production orgs to avoid false positives on generic names.
   */
  private boolean sandboxDemoSignaturesEnabled = true;
  private List<String> sandboxDemoAccountNames = new ArrayList<>();
  private int maxRetries = 5;
  private long retryInitialDelayMs = 2000L;
  private double retryBackoffMultiplier = 2.0;
  private long retryMinDelayMsFor429 = 5000L;
  private long retryMaxDelayMs = 60_000L;

  public String clientId() {
    return clientId;
  }

  public void setClientId(String clientId) {
    this.clientId = clientId == null ? "" : clientId;
  }

  public String clientSecret() {
    return clientSecret;
  }

  public void setClientSecret(String clientSecret) {
    this.clientSecret = clientSecret == null ? "" : clientSecret;
  }

  public String refreshToken() {
    return refreshToken;
  }

  public void setRefreshToken(String refreshToken) {
    this.refreshToken = refreshToken == null ? "" : refreshToken;
  }

  public String region() {
    return region;
  }

  public void setRegion(String region) {
    this.region = region == null || region.isBlank() ? "com" : region.trim();
  }

  public int lookbackDays() {
    return lookbackDays;
  }

  public void setLookbackDays(int lookbackDays) {
    this.lookbackDays = lookbackDays <= 0 ? 90 : lookbackDays;
  }

  public double minQualityScore() {
    return minQualityScore;
  }

  public void setMinQualityScore(double minQualityScore) {
    this.minQualityScore = minQualityScore <= 0 ? 0.35 : minQualityScore;
  }

  public boolean sandboxDemoSignaturesEnabled() {
    return sandboxDemoSignaturesEnabled;
  }

  public void setSandboxDemoSignaturesEnabled(boolean sandboxDemoSignaturesEnabled) {
    this.sandboxDemoSignaturesEnabled = sandboxDemoSignaturesEnabled;
  }

  public List<String> sandboxDemoAccountNames() {
    return List.copyOf(sandboxDemoAccountNames);
  }

  public void setSandboxDemoAccountNames(List<String> sandboxDemoAccountNames) {
    this.sandboxDemoAccountNames =
        sandboxDemoAccountNames == null
            ? new ArrayList<>()
            : new ArrayList<>(sandboxDemoAccountNames);
  }

  public int maxRetries() {
    return maxRetries;
  }

  public void setMaxRetries(int maxRetries) {
    this.maxRetries = Math.max(0, maxRetries);
  }

  public long retryInitialDelayMs() {
    return retryInitialDelayMs;
  }

  public void setRetryInitialDelayMs(long retryInitialDelayMs) {
    this.retryInitialDelayMs = Math.max(0, retryInitialDelayMs);
  }

  public double retryBackoffMultiplier() {
    return retryBackoffMultiplier;
  }

  public void setRetryBackoffMultiplier(double retryBackoffMultiplier) {
    this.retryBackoffMultiplier = Math.max(1.0, retryBackoffMultiplier);
  }

  public long retryMinDelayMsFor429() {
    return retryMinDelayMsFor429;
  }

  public void setRetryMinDelayMsFor429(long retryMinDelayMsFor429) {
    this.retryMinDelayMsFor429 = Math.max(0, retryMinDelayMsFor429);
  }

  public long retryMaxDelayMs() {
    return retryMaxDelayMs;
  }

  public void setRetryMaxDelayMs(long retryMaxDelayMs) {
    this.retryMaxDelayMs = Math.max(0, retryMaxDelayMs);
  }

  public ZohoHttpRetry.Policy httpRetryPolicy() {
    return new ZohoHttpRetry.Policy(
        maxRetries,
        retryInitialDelayMs,
        retryBackoffMultiplier,
        retryMinDelayMsFor429,
        retryMaxDelayMs);
  }
}
