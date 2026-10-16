package com.andromedia.ingestion.crm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One CRM record as a module + id + raw field value map (including custom fields).
 *
 * <p>Typed Zoho DTOs remain the primary FDE path; this map carries discovered extras for narrative
 * enrichment.
 */
public record CrmRecord(String module, String id, Map<String, Object> values) {

  public CrmRecord {
    module = module == null ? "" : module.trim();
    id = id == null ? "" : id.trim();
    values =
        values == null || values.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  public Object get(String apiName) {
    if (apiName == null || apiName.isBlank()) {
      return null;
    }
    return values.get(apiName);
  }

  public String getString(String apiName) {
    Object value = get(apiName);
    return value == null ? "" : String.valueOf(value).trim();
  }
}
