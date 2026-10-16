package com.andromedia.ingestion.zoho;

import java.util.Locale;

/** V1 single-region binding. Expand to multi-region map in v2. */
public enum ZohoRegion {
  COM("com"),
  IN("in");

  private final String suffix;

  ZohoRegion(String suffix) {
    this.suffix = suffix;
  }

  public String suffix() {
    return suffix;
  }

  public String accountsHost() {
    return "accounts.zoho." + suffix;
  }

  public String apiHost() {
    return "www.zohoapis." + suffix;
  }

  public String tokenUrl() {
    return "https://" + accountsHost() + "/oauth/v2/token";
  }

  public String crmBaseUrl() {
    return "https://" + apiHost() + "/crm/v3";
  }

  public static ZohoRegion fromToken(String region) {
    if (region == null || region.isBlank()) {
      return COM;
    }
    String normalized = region.trim().toLowerCase(Locale.ROOT);
    if ("in".equals(normalized) || "india".equals(normalized)) {
      return IN;
    }
    return COM;
  }
}
