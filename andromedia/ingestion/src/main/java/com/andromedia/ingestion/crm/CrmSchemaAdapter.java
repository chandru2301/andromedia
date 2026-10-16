package com.andromedia.ingestion.crm;

import java.io.IOException;
import java.util.List;

/**
 * Vendor-specific CRM schema discovery and fetch-query construction.
 *
 * <p>Implementations (Zoho, Salesforce, …) map native metadata onto the canonical {@link CrmField}
 * model. Day 1 defines the contract only; adapters are wired in later days.
 */
public interface CrmSchemaAdapter {

  /** Discover modules available in this CRM org. */
  List<CrmModule> discoverModules(CrmConnection connection) throws IOException;

  /** Discover and classify fields for a specific module. */
  List<CrmField> discoverFields(CrmConnection connection, String moduleName) throws IOException;

  /** Classify a raw vendor field into the canonical model (may be SKIP). */
  CrmField classifyField(RawCrmField rawField);

  /** Build a fetch query for the given classified fields and options. */
  CrmFetchQuery buildFetchQuery(
      String moduleName, List<CrmField> fields, CrmFetchOptions options);
}
