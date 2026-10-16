package com.andromedia.ingestion.zoho;

import java.time.Instant;

public record ZohoNote(
    String id, String title, String content, String parentDealId, Instant modifiedTime) {

  public ZohoNote {
    id = id == null ? "" : id;
    title = title == null ? "" : title;
    content = content == null ? "" : content;
    parentDealId = parentDealId == null ? "" : parentDealId;
  }
}
