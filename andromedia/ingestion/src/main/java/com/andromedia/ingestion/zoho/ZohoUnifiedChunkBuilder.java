package com.andromedia.ingestion.zoho;

import com.andromedia.common.ChunkIdGenerator;
import com.andromedia.common.IndexConstants;
import com.andromedia.common.ZohoDealContextChunk;
import com.andromedia.ingestion.crm.CrmField;
import com.andromedia.ingestion.crm.CrmFieldType;
import com.andromedia.ingestion.crm.CrmRecord;
import com.andromedia.ingestion.crm.UniversalCrmChunkBuilder;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ZohoUnifiedChunkBuilder {

  static final int MAX_BODY_CHARS = 5000;
  static final int NOTE_SNIPPET_CHARS = 280;
  static final int ACTIVITY_SNIPPET_CHARS = 200;

  /**
   * Fields already rendered by the static Zoho narrative (or reserved for structured Lucene
   * metadata). Universal enrichment must not duplicate these.
   */
  static final Set<String> DEAL_NARRATIVE_EXCLUSIONS =
      Set.of(
          "id",
          "Deal_Name",
          "Stage",
          "Account_Name",
          "Contact_Name",
          "Modified_Time",
          "Description",
          "Next_Step",
          "Amount",
          "Expected_Revenue",
          "Probability",
          "Closing_Date",
          "Owner",
          "Lead_Source");

  static final Set<String> ACCOUNT_NARRATIVE_EXCLUSIONS =
      Set.of("id", "Account_Name", "Description", "Modified_Time");

  private static final DateTimeFormatter DATE_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

  private ZohoUnifiedChunkBuilder() {}

  public static ZohoDealContextChunk build(
      ZohoDeal deal,
      ZohoAccount account,
      List<ZohoContact> contacts,
      List<ZohoNote> notes,
      List<ZohoActivity> activities,
      ZohoRecordQualityScorer.QualityResult quality) {
    return build(deal, account, contacts, notes, activities, quality, null, null, null, null);
  }

  public static ZohoDealContextChunk build(
      ZohoDeal deal,
      ZohoAccount account,
      List<ZohoContact> contacts,
      List<ZohoNote> notes,
      List<ZohoActivity> activities,
      ZohoRecordQualityScorer.QualityResult quality,
      CrmRecord dealRecord,
      List<CrmField> dealFields,
      CrmRecord accountRecord,
      List<CrmField> accountFields) {
    List<ZohoDealContextChunk.ZohoContactRef> contactRefs =
        contacts == null
            ? List.of()
            : contacts.stream()
                .map(
                    c ->
                        new ZohoDealContextChunk.ZohoContactRef(
                            c.id(), c.name(), c.email(), c.phone()))
                .toList();

    String accountId = account != null ? account.id() : deal.accountId();
    String accountName = account != null ? account.name() : deal.accountName();
    String accountDescription = account != null ? account.description() : "";

    List<String> noteSnippets = new ArrayList<>();
    for (ZohoNote note : notes == null ? List.<ZohoNote>of() : notes) {
      String title = note.title().isBlank() ? "Note" : note.title().trim();
      String content = snippet(note.content(), NOTE_SNIPPET_CHARS);
      if (content.isBlank()) {
        noteSnippets.add(title);
      } else {
        noteSnippets.add(title + ": " + content);
      }
    }

    List<ZohoDealContextChunk.ZohoProvenanceRef> provenance = new ArrayList<>();
    provenance.add(new ZohoDealContextChunk.ZohoProvenanceRef("Deals", deal.id(), "Deal_Name"));
    if (deal.description() != null && !deal.description().isBlank()) {
      provenance.add(new ZohoDealContextChunk.ZohoProvenanceRef("Deals", deal.id(), "Description"));
    }
    if (deal.nextStep() != null && !deal.nextStep().isBlank()) {
      provenance.add(new ZohoDealContextChunk.ZohoProvenanceRef("Deals", deal.id(), "Next_Step"));
    }
    if (account != null && account.id() != null && !account.id().isBlank()) {
      provenance.add(
          new ZohoDealContextChunk.ZohoProvenanceRef("Accounts", account.id(), "Account_Name"));
      if (account.description() != null && !account.description().isBlank()) {
        provenance.add(
            new ZohoDealContextChunk.ZohoProvenanceRef("Accounts", account.id(), "Description"));
      }
    }
    for (ZohoContact contact : contacts == null ? List.<ZohoContact>of() : contacts) {
      provenance.add(
          new ZohoDealContextChunk.ZohoProvenanceRef("Contacts", contact.id(), "Full_Name"));
    }
    for (ZohoNote note : notes == null ? List.<ZohoNote>of() : notes) {
      provenance.add(
          new ZohoDealContextChunk.ZohoProvenanceRef("Notes", note.id(), "Note_Content"));
    }
    for (ZohoActivity activity : activities == null ? List.<ZohoActivity>of() : activities) {
      String module =
          switch (activity.type().toLowerCase(Locale.ROOT)) {
            case "call" -> "Calls";
            case "event" -> "Events";
            default -> "Tasks";
          };
      provenance.add(
          new ZohoDealContextChunk.ZohoProvenanceRef(module, activity.id(), "Subject"));
    }

    String narrative = assembleNarrative(deal, account, contacts, notes, activities);
    narrative =
        enrichNarrative(
            narrative, dealRecord, dealFields, accountRecord, accountFields);
    provenance.addAll(
        enrichmentProvenance(
            deal, dealRecord, dealFields, account, accountRecord, accountFields));

    return new ZohoDealContextChunk(
        ChunkIdGenerator.generateZohoDealContextId(deal.id()),
        deal.id(),
        deal.name(),
        deal.stage(),
        deal.amount(),
        deal.expectedRevenue(),
        deal.probability(),
        deal.closingDate(),
        deal.owner(),
        deal.leadSource(),
        deal.nextStep(),
        deal.modifiedTime(),
        accountId,
        accountName,
        accountDescription,
        contactRefs,
        List.copyOf(noteSnippets),
        narrative,
        quality.score(),
        quality.signals(),
        List.copyOf(provenance),
        "deal_context",
        IndexConstants.SOURCE_TYPE_ZOHO);
  }

  static String assembleNarrative(
      ZohoDeal deal,
      ZohoAccount account,
      List<ZohoContact> contacts,
      List<ZohoNote> notes,
      List<ZohoActivity> activities) {
    String modified =
        deal.modifiedTime() == null ? "-" : DATE_FORMAT.format(deal.modifiedTime());
    StringBuilder builder = new StringBuilder();
    builder
        .append("[ZOHO] Deal · ")
        .append(deal.name())
        .append(" · ")
        .append(blankToDash(deal.stage()))
        .append(" · ")
        .append(blankToDash(account == null ? deal.accountName() : account.name()))
        .append(" · updated ")
        .append(modified)
        .append('\n');

    // Compact structured summary so sparse deals (no notes/description) remain BM25-visible.
    // Free-text notes stay separate; Amount/Close also remain on typed Lucene columns.
    builder.append("\nStructured:\n");
    builder
        .append("Account: ")
        .append(blankToDash(account == null ? deal.accountName() : account.name()))
        .append('\n');
    builder
        .append("Stage: ")
        .append(blankToDash(deal.stage()))
        .append(" · Amount: ")
        .append(blankToDash(deal.amount()))
        .append(" · Probability: ")
        .append(formatProbabilityLabel(deal.probability()))
        .append('\n');
    builder.append("Close date: ").append(blankToDash(deal.closingDate())).append('\n');
    builder
        .append("Next step: ")
        .append(
            deal.nextStep() == null || deal.nextStep().isBlank()
                ? "(not set)"
                : deal.nextStep().trim())
        .append('\n');

    if (deal.description() != null && !deal.description().isBlank()) {
      builder.append("\nDeal description:\n").append(deal.description().trim()).append('\n');
    }
    if (deal.nextStep() != null && !deal.nextStep().isBlank()) {
      builder.append("\nNext step:\n").append(deal.nextStep().trim()).append('\n');
    }
    if (account != null && account.description() != null && !account.description().isBlank()) {
      builder.append("\nAccount context:\n").append(account.description().trim()).append('\n');
    }
    if (contacts != null && !contacts.isEmpty()) {
      builder.append("\nContacts:\n");
      for (ZohoContact contact : contacts) {
        builder.append("- ").append(contact.name());
        if (!contact.email().isBlank()) {
          builder.append(" <").append(contact.email()).append('>');
        }
        builder.append('\n');
      }
    }
    if (notes != null && !notes.isEmpty()) {
      builder.append("\nNotes:\n");
      for (ZohoNote note : notes) {
        String title = note.title().isBlank() ? "Note" : note.title().trim();
        String content = snippet(note.content(), NOTE_SNIPPET_CHARS);
        if (content.isBlank()) {
          builder.append("- ").append(title).append('\n');
        } else {
          builder.append("- ").append(title).append(": ").append(content).append('\n');
        }
      }
    }
    if (activities != null && !activities.isEmpty()) {
      builder.append("\nActivities:\n");
      for (ZohoActivity activity : activities) {
        String typeLabel = activityTypeLabel(activity.type());
        String when =
            activity.occurredAt() == null || activity.occurredAt().equals(java.time.Instant.EPOCH)
                ? "-"
                : DATE_FORMAT.format(activity.occurredAt());
        builder
            .append("- [")
            .append(typeLabel)
            .append("] ")
            .append(blankToDash(activity.subject()));
        if (!activity.status().isBlank()) {
          builder.append(" · ").append(activity.status());
        }
        builder.append(" · ").append(when).append('\n');
        String desc = snippet(activity.description(), ACTIVITY_SNIPPET_CHARS);
        if (!desc.isBlank()) {
          builder.append("  ").append(desc).append('\n');
        }
      }
    }
    return truncate(builder.toString().trim(), MAX_BODY_CHARS);
  }

  static String enrichNarrative(
      String baseNarrative,
      CrmRecord dealRecord,
      List<CrmField> dealFields,
      CrmRecord accountRecord,
      List<CrmField> accountFields) {
    String narrative = baseNarrative == null ? "" : baseNarrative;
    String dealExtras =
        UniversalCrmChunkBuilder.buildNarrativeExtras(
            dealRecord, dealFields, DEAL_NARRATIVE_EXCLUSIONS);
    if (!dealExtras.isBlank()) {
      narrative =
          UniversalCrmChunkBuilder.appendSection(
              narrative, "Additional deal fields", dealExtras, MAX_BODY_CHARS);
    }

    String accountExtras =
        UniversalCrmChunkBuilder.buildNarrativeExtras(
            accountRecord, accountFields, ACCOUNT_NARRATIVE_EXCLUSIONS);
    if (!accountExtras.isBlank()) {
      narrative =
          UniversalCrmChunkBuilder.appendSection(
              narrative, "Additional account fields", accountExtras, MAX_BODY_CHARS);
    }
    return narrative;
  }

  /**
   * Collects provenance refs for enriched fields that actually contributed non-blank values.
   * Package-visible for tests.
   */
  static List<ZohoDealContextChunk.ZohoProvenanceRef> enrichmentProvenance(
      ZohoDeal deal,
      CrmRecord dealRecord,
      List<CrmField> dealFields,
      ZohoAccount account,
      CrmRecord accountRecord,
      List<CrmField> accountFields) {
    List<ZohoDealContextChunk.ZohoProvenanceRef> refs = new ArrayList<>();
    if (deal != null && dealRecord != null && dealFields != null) {
      for (CrmField field : dealFields) {
        if (!contributesToNarrative(field, DEAL_NARRATIVE_EXCLUSIONS)) {
          continue;
        }
        String rendered = UniversalCrmChunkBuilder.renderValue(dealRecord.get(field.apiName()));
        if (!rendered.isBlank()) {
          refs.add(
              new ZohoDealContextChunk.ZohoProvenanceRef("Deals", deal.id(), field.apiName()));
        }
      }
    }
    if (account != null
        && account.id() != null
        && !account.id().isBlank()
        && accountRecord != null
        && accountFields != null) {
      for (CrmField field : accountFields) {
        if (!contributesToNarrative(field, ACCOUNT_NARRATIVE_EXCLUSIONS)) {
          continue;
        }
        String rendered = UniversalCrmChunkBuilder.renderValue(accountRecord.get(field.apiName()));
        if (!rendered.isBlank()) {
          refs.add(
              new ZohoDealContextChunk.ZohoProvenanceRef(
                  "Accounts", account.id(), field.apiName()));
        }
      }
    }
    return refs;
  }

  private static boolean contributesToNarrative(CrmField field, Set<String> exclusions) {
    if (field == null || field.apiName().isBlank()) {
      return false;
    }
    if (field.type() != CrmFieldType.NARRATIVE && field.type() != CrmFieldType.STRUCTURED) {
      return false;
    }
    return !isExcluded(field.apiName(), exclusions);
  }

  private static boolean isExcluded(String apiName, Set<String> exclusions) {
    if (apiName == null || apiName.isBlank()) {
      return true;
    }
    for (String excluded : exclusions) {
      if (excluded.equalsIgnoreCase(apiName.trim())) {
        return true;
      }
    }
    return false;
  }

  private static String formatProbabilityLabel(String probability) {
    if (probability == null || probability.isBlank()) {
      return "-";
    }
    String trimmed = probability.trim();
    return trimmed.endsWith("%") ? trimmed : trimmed + "%";
  }

  private static String activityTypeLabel(String type) {
    if (type == null || type.isBlank()) {
      return "Task";
    }
    return switch (type.toLowerCase(Locale.ROOT)) {
      case "call" -> "Call";
      case "event" -> "Event";
      default -> "Task";
    };
  }

  private static String snippet(String value, int maxChars) {
    if (value == null || value.isBlank()) {
      return "";
    }
    String trimmed = value.trim().replace('\n', ' ');
    return truncate(trimmed, maxChars);
  }

  private static String blankToDash(String value) {
    return value == null || value.isBlank() ? "-" : value;
  }

  private static String truncate(String value, int maxChars) {
    if (value.length() <= maxChars) {
      return value;
    }
    if (maxChars <= 1) {
      return value.substring(0, maxChars);
    }
    return value.substring(0, maxChars - 1) + "…";
  }
}
