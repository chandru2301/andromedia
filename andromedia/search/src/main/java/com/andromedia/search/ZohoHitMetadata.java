package com.andromedia.search;

import java.util.List;
import java.util.Optional;

/** Structured Zoho CRM fields and quality flags carried on a search hit (Bucket 2 + 3). */
public record ZohoHitMetadata(
    Optional<String> stage,
    Optional<String> amount,
    Optional<String> expectedRevenue,
    Optional<String> probability,
    Optional<String> closingDate,
    Optional<String> owner,
    Optional<String> leadSource,
    Optional<String> nextStep,
    Optional<String> accountName,
    Optional<String> accountDescription,
    Optional<String> qualityScore,
    List<String> qualitySignals,
    List<String> contacts,
    List<String> noteSnippets) {

  public static final ZohoHitMetadata EMPTY =
      new ZohoHitMetadata(
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          List.of(),
          List.of(),
          List.of());

  public ZohoHitMetadata {
    stage = stage == null ? Optional.empty() : stage;
    amount = amount == null ? Optional.empty() : amount;
    expectedRevenue = expectedRevenue == null ? Optional.empty() : expectedRevenue;
    probability = probability == null ? Optional.empty() : probability;
    closingDate = closingDate == null ? Optional.empty() : closingDate;
    owner = owner == null ? Optional.empty() : owner;
    leadSource = leadSource == null ? Optional.empty() : leadSource;
    nextStep = nextStep == null ? Optional.empty() : nextStep;
    accountName = accountName == null ? Optional.empty() : accountName;
    accountDescription = accountDescription == null ? Optional.empty() : accountDescription;
    qualityScore = qualityScore == null ? Optional.empty() : qualityScore;
    qualitySignals = qualitySignals == null ? List.of() : List.copyOf(qualitySignals);
    contacts = contacts == null ? List.of() : List.copyOf(contacts);
    noteSnippets = noteSnippets == null ? List.of() : List.copyOf(noteSnippets);
  }

  public boolean isEmpty() {
    return stage.isEmpty()
        && amount.isEmpty()
        && expectedRevenue.isEmpty()
        && probability.isEmpty()
        && closingDate.isEmpty()
        && owner.isEmpty()
        && leadSource.isEmpty()
        && nextStep.isEmpty()
        && accountName.isEmpty()
        && accountDescription.isEmpty()
        && qualityScore.isEmpty()
        && qualitySignals.isEmpty()
        && contacts.isEmpty()
        && noteSnippets.isEmpty();
  }
}
