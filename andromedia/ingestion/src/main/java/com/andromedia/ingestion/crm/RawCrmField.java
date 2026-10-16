package com.andromedia.ingestion.crm;

/**
 * Vendor-neutral bag of schema metadata before classification into a {@link CrmField}.
 *
 * <p>Adapters map Zoho/Salesforce/etc. field payloads into this shape.
 */
public record RawCrmField(
    String apiName,
    String displayLabel,
    String dataType,
    int maxLength,
    boolean custom) {

  public RawCrmField {
    apiName = apiName == null ? "" : apiName.trim();
    displayLabel = displayLabel == null || displayLabel.isBlank() ? apiName : displayLabel.trim();
    dataType = dataType == null ? "" : dataType.trim().toLowerCase();
    maxLength = Math.max(0, maxLength);
  }
}
