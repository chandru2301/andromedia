package com.andromedia.ingestion.crm;

import java.util.Locale;

/** Vendor-neutral role inferred for a CRM module or database table. */
public enum CrmTableType {
  DEALS,
  CONTACTS,
  ACCOUNTS,
  ACTIVITIES,
  SUPPORT_CASES,
  UNKNOWN;

  public static CrmTableType fromRole(String role) {
    if (role == null || role.isBlank()) {
      return UNKNOWN;
    }
    try {
      return valueOf(role.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return UNKNOWN;
    }
  }
}
