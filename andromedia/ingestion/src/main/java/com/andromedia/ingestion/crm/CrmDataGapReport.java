package com.andromedia.ingestion.crm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Builds per-field completeness / data-gap entries for ingestion job metadata.
 *
 * <p>Recommendation strings are emitted for low-completeness {@link CrmFieldType#NARRATIVE} fields
 * and {@link CrmFieldCategory#OUTCOME} fields (and for consistently empty SKIP fields that look
 * unused). andro UI for this report is deferred.
 */
public final class CrmDataGapReport {

  /** Completeness below this threshold (0–100) triggers a recommendation when applicable. */
  public static final int LOW_COMPLETENESS_THRESHOLD = 50;

  private CrmDataGapReport() {}

  public record Entry(
      String module, String apiName, String type, int completenessScore, String recommendation) {

    public Entry {
      module = module == null ? "" : module.trim();
      apiName = apiName == null ? "" : apiName.trim();
      type = type == null ? "" : type.trim();
      completenessScore = Math.max(0, Math.min(100, completenessScore));
      recommendation = recommendation == null ? "" : recommendation.trim();
    }

    public Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("module", module);
      map.put("apiName", apiName);
      map.put("type", type);
      map.put("completenessScore", completenessScore);
      map.put("recommendation", recommendation);
      return map;
    }
  }

  public static List<Entry> build(Map<String, List<CrmField>> schemaByModule) {
    if (schemaByModule == null || schemaByModule.isEmpty()) {
      return List.of();
    }
    List<Entry> entries = new ArrayList<>();
    for (Map.Entry<String, List<CrmField>> moduleEntry : schemaByModule.entrySet()) {
      String module = moduleEntry.getKey();
      List<CrmField> fields = moduleEntry.getValue();
      if (fields == null) {
        continue;
      }
      for (CrmField field : fields) {
        if (field == null || field.apiName().isBlank()) {
          continue;
        }
        entries.add(
            new Entry(
                module,
                field.apiName(),
                field.type().name(),
                field.completenessScore(),
                recommendation(module, field)));
      }
    }
    return List.copyOf(entries);
  }

  public static List<Map<String, Object>> toMaps(List<Entry> entries) {
    if (entries == null || entries.isEmpty()) {
      return List.of();
    }
    return entries.stream().map(Entry::toMap).toList();
  }

  static String recommendation(String module, CrmField field) {
    Objects.requireNonNull(field, "field");
    if (!needsRecommendation(field)) {
      return "";
    }
    String moduleLabel = moduleLabel(module);
    String name =
        field.displayName() == null || field.displayName().isBlank()
            ? field.apiName()
            : field.displayName();
    int score = field.completenessScore();
    if (field.type() == CrmFieldType.SKIP || field.isEmpty()) {
      return "Only "
          + score
          + "% of "
          + moduleLabel
          + " fill "
          + name
          + "; field is consistently empty — consider removing it or making it required.";
    }
    return "Only "
        + score
        + "% of "
        + moduleLabel
        + " fill "
        + name
        + "; make it required.";
  }

  private static boolean needsRecommendation(CrmField field) {
    if (field.completenessScore() >= LOW_COMPLETENESS_THRESHOLD) {
      return false;
    }
    if (field.type() == CrmFieldType.NARRATIVE) {
      return true;
    }
    if (field.category() == CrmFieldCategory.OUTCOME) {
      return true;
    }
    return field.type() == CrmFieldType.SKIP || field.isEmpty();
  }

  private static String moduleLabel(String module) {
    if (module == null || module.isBlank()) {
      return "records";
    }
    String normalized = module.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "deals", "potentials", "opportunities" -> "deals";
      case "accounts", "companies" -> "accounts";
      case "contacts", "leads" -> "contacts";
      default -> normalized;
    };
  }
}
