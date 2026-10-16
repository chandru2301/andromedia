package com.andromedia.ingestion.zoho;

import java.time.Instant;

public record ZohoActivity(
    String id,
    String type,
    String subject,
    String status,
    String description,
    String dealId,
    Instant occurredAt,
    Instant modifiedTime) {

  public ZohoActivity {
    id = id == null ? "" : id;
    type = type == null ? "" : type;
    subject = subject == null ? "" : subject;
    status = status == null ? "" : status;
    description = description == null ? "" : description;
    dealId = dealId == null ? "" : dealId;
  }
}
