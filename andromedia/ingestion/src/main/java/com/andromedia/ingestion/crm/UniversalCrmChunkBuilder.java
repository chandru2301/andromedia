package com.andromedia.ingestion.crm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CRM-agnostic narrative enrichment from discovered schema fields.
 *
 * <p>{@link CrmFieldType#NARRATIVE} values become searchable prose. Non-canonical {@link
 * CrmFieldType#STRUCTURED} extras are also folded into narrative for v1 (no Lucene schema churn).
 * Canonical structured outcome fields (amount, probability, dates) stay out of prose when listed in
 * {@code excludeApiNames}.
 */
public final class UniversalCrmChunkBuilder {

  private UniversalCrmChunkBuilder() {}

  /**
   * Builds a narrative block of discovered field values not already covered by a source-specific
   * static assembler.
   *
   * @param record raw CRM record (may be null)
   * @param fields discovered/classified fields for the module (may be null)
   * @param excludeApiNames API names already rendered by the static chunk builder
   * @return prose fragment (may be blank), without a trailing blank line
   */
  public static String buildNarrativeExtras(
      CrmRecord record, List<CrmField> fields, Collection<String> excludeApiNames) {
    if (record == null || fields == null || fields.isEmpty()) {
      return "";
    }
    Set<String> excluded = normalizeExclusions(excludeApiNames);
    List<String> lines = new ArrayList<>();
    for (CrmField field : fields) {
      if (field == null || field.type() == CrmFieldType.SKIP) {
        continue;
      }
      String apiName = field.apiName();
      if (apiName.isBlank() || excluded.contains(normalizeKey(apiName))) {
        continue;
      }
      if (field.type() != CrmFieldType.NARRATIVE && field.type() != CrmFieldType.STRUCTURED) {
        continue;
      }
      String rendered = renderValue(record.get(apiName));
      if (rendered.isBlank()) {
        continue;
      }
      String label =
          field.displayName() == null || field.displayName().isBlank()
              ? apiName
              : field.displayName();
      lines.add(label + ": " + rendered);
    }
    if (lines.isEmpty()) {
      return "";
    }
    return String.join("\n", lines);
  }

  /**
   * Appends enrichment prose under a section header, respecting a max character budget on the full
   * combined narrative.
   */
  public static String appendSection(
      String baseNarrative, String sectionTitle, String extras, int maxChars) {
    String base = baseNarrative == null ? "" : baseNarrative.trim();
    String body = extras == null ? "" : extras.trim();
    if (body.isBlank()) {
      return truncate(base, maxChars);
    }
    String title =
        sectionTitle == null || sectionTitle.isBlank() ? "Additional fields" : sectionTitle.trim();
    String combined = base.isBlank() ? title + ":\n" + body : base + "\n\n" + title + ":\n" + body;
    return truncate(combined, maxChars);
  }

  private static Set<String> normalizeExclusions(Collection<String> excludeApiNames) {
    if (excludeApiNames == null || excludeApiNames.isEmpty()) {
      return Set.of();
    }
    Set<String> normalized = new LinkedHashSet<>();
    for (String name : excludeApiNames) {
      if (name != null && !name.isBlank()) {
        normalized.add(normalizeKey(name));
      }
    }
    return normalized;
  }

  private static String normalizeKey(String apiName) {
    return apiName.trim().toLowerCase(Locale.ROOT);
  }

  public static String renderValue(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof Map<?, ?> map) {
      Object name = map.get("name");
      if (name != null && !String.valueOf(name).isBlank()) {
        return String.valueOf(name).trim();
      }
      Object id = map.get("id");
      if (id != null && !String.valueOf(id).isBlank()) {
        return String.valueOf(id).trim();
      }
      return "";
    }
    if (value instanceof Collection<?> collection) {
      List<String> parts = new ArrayList<>();
      for (Object item : collection) {
        String rendered = renderValue(item);
        if (!rendered.isBlank()) {
          parts.add(rendered);
        }
      }
      return String.join(", ", parts);
    }
    String text = Objects.toString(value, "").trim();
    if (text.isBlank()
        || text.equalsIgnoreCase("null")
        || text.equalsIgnoreCase("n/a")
        || text.equalsIgnoreCase("none")
        || text.equalsIgnoreCase("test")
        || text.equalsIgnoreCase("tbd")) {
      return "";
    }
    return text.replace('\n', ' ');
  }

  private static String truncate(String value, int maxChars) {
    if (value == null) {
      return "";
    }
    if (maxChars <= 0 || value.length() <= maxChars) {
      return value;
    }
    if (maxChars == 1) {
      return value.substring(0, 1);
    }
    return value.substring(0, maxChars - 1) + "…";
  }
}
