package com.andromedia.ingestion.zoho;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class ZohoRecordQualityScorer {

  private static final Pattern PLACEHOLDER_EMAIL =
      Pattern.compile("(?i).*@noemail\\.invalid$|.*@example\\.com$");
  private static final Pattern PLACEHOLDER_PHONE =
      Pattern.compile("^\\+?1?[-.\\s]?\\(?555\\)?[-.\\s]?555[-.\\s]?5555$|^555[-.\\s]?555[-.\\s]?5555$");

  private ZohoRecordQualityScorer() {}

  public static QualityResult scoreDealContext(
      ZohoDeal deal,
      ZohoAccount account,
      List<ZohoContact> contacts,
      List<ZohoNote> notes,
      List<ZohoActivity> activities,
      int lookbackDays) {
    return scoreDealContext(
        deal,
        account,
        contacts,
        notes,
        activities,
        lookbackDays,
        ZohoDemoAccountDetector.DemoAccountIdentities.empty());
  }

  public static QualityResult scoreDealContext(
      ZohoDeal deal,
      ZohoAccount account,
      List<ZohoContact> contacts,
      List<ZohoNote> notes,
      List<ZohoActivity> activities,
      int lookbackDays,
      ZohoDemoAccountDetector.DemoAccountIdentities demoAccounts) {
    List<String> signals = new ArrayList<>();
    double score = 0.0;

    if (deal.name() != null && !deal.name().isBlank()) {
      score += 0.15;
      signals.add("has_deal_name");
    }
    if (deal.stage() != null && !deal.stage().isBlank()) {
      score += 0.1;
      signals.add("has_stage");
    }
    if (deal.amount() != null && !deal.amount().isBlank()) {
      score += 0.05;
      signals.add("has_amount");
    }

    boolean hasDealText = deal.description() != null && !deal.description().isBlank();
    boolean hasAccountText =
        account != null && account.description() != null && !account.description().isBlank();
    if (hasDealText) {
      score += 0.25;
      signals.add("has_description");
    }
    if (hasAccountText) {
      score += 0.1;
      signals.add("has_account_context");
    } else {
      signals.add("missing_account_description");
    }
    if (contacts != null && !contacts.isEmpty()) {
      score += 0.1;
      signals.add("has_contacts");
    } else {
      signals.add("no_contacts");
    }
    if (hasNoteText(notes)) {
      score += 0.1;
      signals.add("has_notes");
    } else {
      signals.add("no_notes");
    }
    if (activities != null && !activities.isEmpty()) {
      score += 0.1;
      signals.add("has_activities");
    } else {
      signals.add("no_activities");
    }

    Instant modified = deal.modifiedTime() == null ? Instant.EPOCH : deal.modifiedTime();
    Instant now = Instant.now();
    Instant recentCutoff = now.minus(Math.max(1, lookbackDays), ChronoUnit.DAYS);
    Instant staleCutoff = now.minus(180, ChronoUnit.DAYS);
    if (!modified.isBefore(recentCutoff)) {
      score += 0.2;
      signals.add("recently_modified");
    }
    if (modified.isBefore(staleCutoff)) {
      score -= 0.15;
      signals.add("stale_record");
    }

    if (isCloseDatePassed(deal.closingDate(), now)) {
      signals.add("close_date_passed");
    }

    if (isDemoDeal(deal, account, contacts, demoAccounts)) {
      score -= 0.4;
      signals.add("test_demo_pattern");
    }

    score = Math.max(0.0, Math.min(1.0, score));
    return new QualityResult(score, List.copyOf(signals));
  }

  private static boolean hasNoteText(List<ZohoNote> notes) {
    if (notes == null || notes.isEmpty()) {
      return false;
    }
    for (ZohoNote note : notes) {
      if ((note.title() != null && !note.title().isBlank())
          || (note.content() != null && !note.content().isBlank())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isCloseDatePassed(String closingDate, Instant now) {
    if (closingDate == null || closingDate.isBlank()) {
      return false;
    }
    try {
      LocalDate close =
          LocalDate.parse(closingDate.trim().substring(0, Math.min(10, closingDate.trim().length())));
      LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
      return close.isBefore(today);
    } catch (DateTimeParseException | StringIndexOutOfBoundsException ex) {
      return false;
    }
  }

  static boolean isDemoDeal(
      ZohoDeal deal,
      ZohoAccount account,
      List<ZohoContact> contacts,
      ZohoDemoAccountDetector.DemoAccountIdentities demoAccounts) {
    if (demoAccounts != null && demoAccounts.matches(account, deal)) {
      return true;
    }
    return looksLikeDemoDeal(deal, account, contacts);
  }

  static boolean looksLikeDemoDeal(
      ZohoDeal deal, ZohoAccount account, List<ZohoContact> contacts) {
    if (looksLikeTestRecord(
        deal.name(),
        deal.description(),
        account == null ? "" : account.name(),
        account == null ? "" : account.description(),
        deal.accountName(),
        deal.contactName())) {
      return true;
    }
    if (contacts == null) {
      return false;
    }
    for (ZohoContact contact : contacts) {
      if (looksLikeTestRecord(contact.name())) {
        return true;
      }
      if (isPlaceholderEmail(contact.email()) || isPlaceholderPhone(contact.phone())) {
        return true;
      }
    }
    return false;
  }

  static boolean looksLikeTestRecord(String... values) {
    for (String value : values) {
      if (value == null || value.isBlank()) {
        continue;
      }
      String lower = value.toLowerCase(Locale.ROOT);
      if (lower.contains("test")
          || lower.contains("demo")
          || lower.contains("sample")
          || lower.contains("(sample)")) {
        return true;
      }
    }
    return false;
  }

  static boolean isPlaceholderEmail(String email) {
    if (email == null || email.isBlank()) {
      return false;
    }
    return PLACEHOLDER_EMAIL.matcher(email.trim()).matches();
  }

  static boolean isPlaceholderPhone(String phone) {
    if (phone == null || phone.isBlank()) {
      return false;
    }
    String digits = phone.replaceAll("[^0-9]", "");
    if ("5555555555".equals(digits) || "15555555555".equals(digits)) {
      return true;
    }
    return PLACEHOLDER_PHONE.matcher(phone.trim()).matches();
  }

  public record QualityResult(double score, List<String> signals) {}
}
