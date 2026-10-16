package com.andromedia.ingestion.crm;

/** How a CRM field should be treated during chunking and indexing. */
public enum CrmFieldType {
  /** Long-form text that belongs in searchable chunk narrative. */
  NARRATIVE,
  /** Facts worth storing as structured metadata (numbers, dates, picklists). */
  STRUCTURED,
  /** System, formula, or unused fields that should not be indexed. */
  SKIP
}
