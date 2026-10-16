package com.andromedia.ingestion.zoho;

import java.time.Instant;

public record ZohoContact(
    String id,
    String name,
    String email,
    String phone,
    String accountId,
    String accountName,
    Instant modifiedTime) {

  public ZohoContact {
    id = id == null ? "" : id;
    name = name == null ? "" : name;
    email = email == null ? "" : email;
    phone = phone == null ? "" : phone;
    accountId = accountId == null ? "" : accountId;
    accountName = accountName == null ? "" : accountName;
  }
}
