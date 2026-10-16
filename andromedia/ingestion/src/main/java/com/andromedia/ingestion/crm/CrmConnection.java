package com.andromedia.ingestion.crm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thin, vendor-neutral connection handle for schema discovery.
 *
 * <p>{@code attributes} holds opaque auth inputs (tokens, region, base URL). Adapters interpret
 * keys they understand; unused keys are ignored.
 *
 * <p>Product posture: only {@link #PROVIDER_ZOHO} is supported. Other providers fail fast with a
 * clear error rather than silently doing nothing.
 */
public record CrmConnection(String orgId, String provider, Map<String, String> attributes) {

  public static final String PROVIDER_ZOHO = "zoho";

  public static final String UNSUPPORTED_PROVIDER_MESSAGE =
      "Only Zoho CRM is supported in this version. Salesforce and HubSpot support is coming.";

  public CrmConnection {
    orgId = orgId == null ? "" : orgId.trim();
    provider = provider == null ? "" : provider.trim().toLowerCase();
    if (!PROVIDER_ZOHO.equals(provider)) {
      throw new IllegalArgumentException(UNSUPPORTED_PROVIDER_MESSAGE);
    }
    attributes =
        attributes == null || attributes.isEmpty()
            ? Map.of()
            : Map.copyOf(new LinkedHashMap<>(attributes));
  }

  public String attribute(String key) {
    if (key == null || key.isBlank()) {
      return "";
    }
    String value = attributes.get(key);
    return value == null ? "" : value;
  }
}
