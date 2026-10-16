package com.andromedia.ingestion.crm;

/**
 * Vendor-neutral fetch instruction produced by {@link CrmSchemaAdapter#buildFetchQuery}.
 *
 * <p>Adapters map {@code path}/{@code fieldList}/{@code criteria} onto Zoho REST, Salesforce SOQL,
 * etc.
 */
public record CrmFetchQuery(String path, String fieldList, String criteria, int maxRecords) {

  public CrmFetchQuery {
    path = path == null ? "" : path.trim();
    fieldList = fieldList == null ? "" : fieldList.trim();
    criteria = criteria == null || criteria.isBlank() ? null : criteria.trim();
    maxRecords = maxRecords <= 0 ? CrmFetchOptions.DEFAULT_MAX_RECORDS : maxRecords;
  }
}
