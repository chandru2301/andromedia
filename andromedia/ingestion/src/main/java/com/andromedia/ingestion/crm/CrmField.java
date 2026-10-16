package com.andromedia.ingestion.crm;

/**
 * Canonical CRM field descriptor shared across Zoho, Salesforce, and other adapters.
 *
 * <p>{@code completenessScore} is 0–100 based on sampled fill rate; 0 until sampling runs.
 */
public record CrmField(
    String apiName,
    String displayName,
    CrmFieldType type,
    CrmFieldCategory category,
    boolean isCustom,
    boolean isEmpty,
    int completenessScore) {

  public CrmField {
    apiName = apiName == null ? "" : apiName.trim();
    displayName = displayName == null || displayName.isBlank() ? apiName : displayName.trim();
    type = type == null ? CrmFieldType.SKIP : type;
    category = category == null ? CrmFieldCategory.SYSTEM : category;
    completenessScore = Math.max(0, Math.min(100, completenessScore));
  }

  public CrmField withCompleteness(boolean empty, int score) {
    return new CrmField(apiName, displayName, type, category, isCustom, empty, score);
  }

  public CrmField withType(CrmFieldType newType) {
    return new CrmField(apiName, displayName, newType, category, isCustom, isEmpty, completenessScore);
  }
}
