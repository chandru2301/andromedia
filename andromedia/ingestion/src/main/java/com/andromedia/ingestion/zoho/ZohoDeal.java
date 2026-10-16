package com.andromedia.ingestion.zoho;

import java.time.Instant;

public record ZohoDeal(
    String id,
    String name,
    String stage,
    String amount,
    String expectedRevenue,
    String probability,
    String closingDate,
    String description,
    Instant modifiedTime,
    String accountId,
    String accountName,
    String contactId,
    String contactName,
    String owner,
    String leadSource,
    String nextStep) {

  public ZohoDeal {
    id = id == null ? "" : id;
    name = name == null ? "" : name;
    stage = stage == null ? "" : stage;
    amount = amount == null ? "" : amount;
    expectedRevenue = expectedRevenue == null ? "" : expectedRevenue;
    probability = probability == null ? "" : probability;
    closingDate = closingDate == null ? "" : closingDate;
    description = description == null ? "" : description;
    accountId = accountId == null ? "" : accountId;
    accountName = accountName == null ? "" : accountName;
    contactId = contactId == null ? "" : contactId;
    contactName = contactName == null ? "" : contactName;
    owner = owner == null ? "" : owner;
    leadSource = leadSource == null ? "" : leadSource;
    nextStep = nextStep == null ? "" : nextStep;
  }
}
