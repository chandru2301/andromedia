package com.andromedia.ingestion.crm;

/** Semantic role of a CRM field within a deal/account/contact context. */
public enum CrmFieldCategory {
  /** Name, ID, owner, primary identifiers. */
  IDENTITY,
  /** Descriptions, notes, next steps, free-text reasons. */
  CONTEXT,
  /** Stage, amount, probability, close outcomes. */
  OUTCOME,
  /** Calls, emails, meetings, tasks. */
  ACTIVITY,
  /** Created/modified/audit fields typically skipped for retrieval. */
  SYSTEM
}
