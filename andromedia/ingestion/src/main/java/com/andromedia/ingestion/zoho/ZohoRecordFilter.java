package com.andromedia.ingestion.zoho;

public final class ZohoRecordFilter {

  private ZohoRecordFilter() {}

  public static boolean shouldIndex(
      ZohoDeal deal, ZohoRecordQualityScorer.QualityResult quality, double minQualityScore) {
    if (deal == null || deal.id() == null || deal.id().isBlank()) {
      return false;
    }
    if (deal.name() == null || deal.name().isBlank()) {
      return false;
    }
    if (quality == null) {
      return false;
    }
    // Demo/sample records are indexed with flags so answers can report demo-only CRM data.
    if (quality.signals().contains("test_demo_pattern")) {
      return true;
    }
    boolean hasText =
        (deal.description() != null && !deal.description().isBlank())
            || quality.signals().contains("has_account_context");
    if (!hasText && quality.score() < minQualityScore) {
      return false;
    }
    return quality.score() >= minQualityScore;
  }
}
