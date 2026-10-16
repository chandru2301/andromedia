package com.andromedia.ingestion.zoho;

import java.time.Instant;

public record ZohoAccount(String id, String name, String description, Instant modifiedTime) {
  public ZohoAccount {
    id = id == null ? "" : id;
    name = name == null ? "" : name;
    description = description == null ? "" : description;
  }
}
