package com.andromedia.common;

import java.time.Instant;
import java.util.List;

/** Unified deal-centric CRM chunk emitted by the Zoho FDE pipeline. */
public record ZohoDealContextChunk(
    String chunkId,
    String dealId,
    String dealName,
    String stage,
    String amount,
    String expectedRevenue,
    String probability,
    String closingDate,
    String owner,
    String leadSource,
    String nextStep,
    Instant modifiedTime,
    String accountId,
    String accountName,
    String accountDescription,
    List<ZohoContactRef> contacts,
    List<String> noteSnippets,
    String narrativeText,
    double qualityScore,
    List<String> qualitySignals,
    List<ZohoProvenanceRef> provenance,
    String entityType,
    String sourceType) {

  public record ZohoContactRef(String id, String name, String email, String phone) {
    public ZohoContactRef {
      id = id == null ? "" : id;
      name = name == null ? "" : name;
      email = email == null ? "" : email;
      phone = phone == null ? "" : phone;
    }

    public String displayLabel() {
      StringBuilder label = new StringBuilder(name.isBlank() ? id : name);
      if (!email.isBlank()) {
        label.append(" <").append(email).append('>');
      }
      if (!phone.isBlank()) {
        label.append(" (").append(phone).append(')');
      }
      return label.toString();
    }
  }

  public record ZohoProvenanceRef(String module, String id, String field) {
    public ZohoProvenanceRef {
      module = module == null ? "" : module;
      id = id == null ? "" : id;
      field = field == null ? "" : field;
    }
  }

  public ZohoDealContextChunk {
    dealId = dealId == null ? "" : dealId;
    dealName = dealName == null ? "" : dealName;
    stage = stage == null ? "" : stage;
    amount = amount == null ? "" : amount;
    expectedRevenue = expectedRevenue == null ? "" : expectedRevenue;
    probability = probability == null ? "" : probability;
    closingDate = closingDate == null ? "" : closingDate;
    owner = owner == null ? "" : owner;
    leadSource = leadSource == null ? "" : leadSource;
    nextStep = nextStep == null ? "" : nextStep;
    accountId = accountId == null ? "" : accountId;
    accountName = accountName == null ? "" : accountName;
    accountDescription = accountDescription == null ? "" : accountDescription;
    narrativeText = narrativeText == null ? "" : narrativeText;
    entityType = entityType == null || entityType.isBlank() ? "deal_context" : entityType;
    sourceType =
        sourceType == null || sourceType.isBlank()
            ? IndexConstants.SOURCE_TYPE_ZOHO
            : sourceType;
    contacts = contacts == null ? List.of() : List.copyOf(contacts);
    noteSnippets = noteSnippets == null ? List.of() : List.copyOf(noteSnippets);
    qualitySignals = qualitySignals == null ? List.of() : List.copyOf(qualitySignals);
    provenance = provenance == null ? List.of() : List.copyOf(provenance);
  }

  public String path() {
    return "zoho://deal/" + (dealId.isBlank() ? "unknown" : dealId);
  }

  public String fileName() {
    return dealName.isBlank() ? "zoho-deal" : dealName;
  }

  /** Indexed searchable body (prefix + narrative). */
  public String content() {
    return narrativeText;
  }
}
