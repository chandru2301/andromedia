package com.andromedia.ingestion.crm;

/**
 * Discovered CRM module (Deals, Accounts, or a custom/unknown module).
 *
 * <p>{@code role} is a vendor-neutral table role such as {@code DEALS}, {@code CONTACTS}, or {@code
 * UNKNOWN}. Day-3 table inference will refine this for custom modules.
 */
public record CrmModule(String apiName, String label, String role, boolean isCustom) {

  public static final String ROLE_DEALS = "DEALS";
  public static final String ROLE_CONTACTS = "CONTACTS";
  public static final String ROLE_ACCOUNTS = "ACCOUNTS";
  public static final String ROLE_ACTIVITIES = "ACTIVITIES";
  public static final String ROLE_SUPPORT_CASES = "SUPPORT_CASES";
  public static final String ROLE_UNKNOWN = "UNKNOWN";

  public CrmModule {
    apiName = apiName == null ? "" : apiName.trim();
    label = label == null || label.isBlank() ? apiName : label.trim();
    role = role == null || role.isBlank() ? ROLE_UNKNOWN : role.trim().toUpperCase();
  }
}
