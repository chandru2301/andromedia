package com.andromedia.ingestion.zoho;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Marks Zoho accounts as demo/sample so every deal under those accounts inherits {@code
 * test_demo_pattern}, even when individual deal names look real.
 */
public final class ZohoDemoAccountDetector {

  /** Classic Zoho CRM sandbox account names observed in sample orgs. Opt-in via options. */
  public static final List<String> DEFAULT_SANDBOX_SIGNATURES =
      List.of(
          "King",
          "Chapman",
          "Truhlar And Truhlar",
          "Truhlar And Truhlar Attys",
          "Benton",
          "Printing Dimensions",
          "Feltz Printing Service",
          "Feltz",
          "Chemel",
          "Chanay",
          "Morlong Associates");

  private ZohoDemoAccountDetector() {}

  public static DemoAccountIdentities compute(
      List<ZohoAccount> accounts,
      List<ZohoContact> contacts,
      boolean enableSandboxSignatures,
      Collection<String> sandboxSignatures) {
    Set<String> accountIds = new HashSet<>();
    Set<String> normalizedNames = new HashSet<>();

    if (accounts != null) {
      for (ZohoAccount account : accounts) {
        if (account == null) {
          continue;
        }
        boolean demoText =
            ZohoRecordQualityScorer.looksLikeTestRecord(account.name(), account.description());
        boolean sandboxMatch =
            enableSandboxSignatures
                && matchesSandboxSignature(account.name(), sandboxSignatures);
        if (demoText || sandboxMatch) {
          mark(accountIds, normalizedNames, account.id(), account.name());
        }
      }
    }

    if (contacts != null) {
      for (ZohoContact contact : contacts) {
        if (contact == null) {
          continue;
        }
        boolean placeholder =
            ZohoRecordQualityScorer.isPlaceholderEmail(contact.email())
                || ZohoRecordQualityScorer.isPlaceholderPhone(contact.phone());
        boolean demoName = ZohoRecordQualityScorer.looksLikeTestRecord(contact.name());
        if (placeholder || demoName) {
          mark(accountIds, normalizedNames, contact.accountId(), contact.accountName());
        }
      }
    }

    return new DemoAccountIdentities(Set.copyOf(accountIds), Set.copyOf(normalizedNames));
  }

  static boolean matchesSandboxSignature(String accountName, Collection<String> signatures) {
    if (accountName == null || accountName.isBlank() || signatures == null || signatures.isEmpty()) {
      return false;
    }
    String normalized = normalizeAccountName(accountName);
    for (String signature : signatures) {
      if (signature == null || signature.isBlank()) {
        continue;
      }
      if (normalized.equals(normalizeAccountName(signature))) {
        return true;
      }
    }
    return false;
  }

  static String normalizeAccountName(String name) {
    if (name == null) {
      return "";
    }
    String lower = name.trim().toLowerCase(Locale.ROOT);
    if (lower.endsWith("(sample)")) {
      lower = lower.substring(0, lower.length() - "(sample)".length()).trim();
    }
    return lower.replaceAll("\\s+", " ");
  }

  private static void mark(
      Set<String> accountIds, Set<String> normalizedNames, String accountId, String accountName) {
    if (accountId != null && !accountId.isBlank()) {
      accountIds.add(accountId.trim());
    }
    String normalized = normalizeAccountName(accountName);
    if (!normalized.isBlank()) {
      normalizedNames.add(normalized);
    }
  }

  public record DemoAccountIdentities(Set<String> accountIds, Set<String> normalizedNames) {

    public DemoAccountIdentities {
      accountIds = accountIds == null ? Set.of() : Set.copyOf(accountIds);
      normalizedNames = normalizedNames == null ? Set.of() : Set.copyOf(normalizedNames);
    }

    public static DemoAccountIdentities empty() {
      return new DemoAccountIdentities(Set.of(), Set.of());
    }

    public boolean matches(ZohoAccount account, ZohoDeal deal) {
      if (account != null) {
        if (account.id() != null
            && !account.id().isBlank()
            && accountIds.contains(account.id().trim())) {
          return true;
        }
        if (normalizedNames.contains(normalizeAccountName(account.name()))) {
          return true;
        }
      }
      if (deal != null) {
        if (deal.accountId() != null
            && !deal.accountId().isBlank()
            && accountIds.contains(deal.accountId().trim())) {
          return true;
        }
        if (normalizedNames.contains(normalizeAccountName(deal.accountName()))) {
          return true;
        }
      }
      return false;
    }

    public boolean isEmpty() {
      return accountIds.isEmpty() && normalizedNames.isEmpty();
    }
  }
}
