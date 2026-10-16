package com.andromedia.ingestion.zoho;

import com.andromedia.ingestion.crm.CrmConnection;
import com.andromedia.ingestion.crm.CrmFetchOptions;
import com.andromedia.ingestion.crm.CrmFetchQuery;
import com.andromedia.ingestion.crm.CrmField;
import com.andromedia.ingestion.crm.CrmFieldType;
import com.andromedia.ingestion.crm.CrmModule;
import com.andromedia.ingestion.crm.CrmRecord;
import com.andromedia.ingestion.crm.CrmSchemaAdapter;
import com.andromedia.ingestion.crm.FieldClassifier;
import com.andromedia.ingestion.crm.RawCrmField;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Zoho implementation of the universal CRM schema adapter contract. */
public final class ZohoSchemaAdapter implements CrmSchemaAdapter {

  /**
   * Zoho Search Records expects DateTime values as ISO-8601 with an explicit offset ({@code
   * +00:00}), not a bare date and not the {@code Z} suffix from {@link Instant#toString()}.
   */
  private static final DateTimeFormatter ZOHO_SEARCH_DATETIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

  private final ZohoApiClient apiClient;
  private final FieldClassifier fieldClassifier;
  private final Clock clock;
  private final Map<String, List<RawCrmField>> rawSchemaByModule = new ConcurrentHashMap<>();

  public ZohoSchemaAdapter(ZohoApiClient apiClient) {
    this(apiClient, new FieldClassifier(), Clock.systemUTC());
  }

  public ZohoSchemaAdapter(
      ZohoApiClient apiClient, FieldClassifier fieldClassifier, Clock clock) {
    this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
    this.fieldClassifier = Objects.requireNonNull(fieldClassifier, "fieldClassifier");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public List<CrmModule> discoverModules(CrmConnection connection) throws IOException {
    return apiClient.discoverModules().stream()
        .map(
            module ->
                new CrmModule(
                    module.apiName(),
                    module.label(),
                    canonicalRole(module.apiName()),
                    module.custom()))
        .toList();
  }

  @Override
  public List<CrmField> discoverFields(CrmConnection connection, String moduleName)
      throws IOException {
    ZohoApiClient.ZohoModuleSchema schema = apiClient.discoverModuleSchema(moduleName);
    List<RawCrmField> rawFields =
        schema.fields().stream().map(ZohoSchemaAdapter::toRawField).toList();
    rawSchemaByModule.put(normalizeModule(moduleName), rawFields);
    return rawFields.stream()
        .map(this::classifyField)
        .filter(field -> field.type() != CrmFieldType.SKIP)
        .toList();
  }

  @Override
  public CrmField classifyField(RawCrmField rawField) {
    return fieldClassifier.classify(rawField);
  }

  @Override
  public CrmFetchQuery buildFetchQuery(
      String moduleName, List<CrmField> fields, CrmFetchOptions options) {
    String normalizedModule = moduleName == null ? "" : moduleName.trim();
    CrmFetchOptions effectiveOptions = options == null ? CrmFetchOptions.defaults() : options;

    Set<String> fetchableFields = new LinkedHashSet<>();
    addCommaSeparated(fetchableFields, requiredFields(normalizedModule));
    if (fields != null) {
      fields.stream()
          .filter(Objects::nonNull)
          .filter(field -> field.type() != CrmFieldType.SKIP)
          .map(CrmField::apiName)
          .filter(apiName -> apiName != null && !apiName.isBlank())
          .map(String::trim)
          .forEach(fetchableFields::add);
    }

    String criteria;
    if (effectiveOptions.fullSync()) {
      criteria = null;
    } else if (effectiveOptions.modifiedSince() != null) {
      criteria =
          "Modified_Time:greater_than:"
              + formatSearchDateTime(effectiveOptions.modifiedSince());
    } else if (effectiveOptions.lookbackDays() > 0) {
      Instant cutoff =
          Instant.now(clock).minus(effectiveOptions.lookbackDays(), ChronoUnit.DAYS);
      criteria = "Modified_Time:greater_than:" + formatSearchDateTime(cutoff);
    } else {
      criteria = null;
    }

    return new CrmFetchQuery(
        "/crm/v3/" + normalizedModule,
        String.join(",", fetchableFields),
        criteria,
        effectiveOptions.maxRecords());
  }

  /** Formats an instant for Zoho Search Records DateTime criteria. */
  static String formatSearchDateTime(Instant instant) {
    OffsetDateTime value =
        OffsetDateTime.ofInstant(instant, ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    return ZOHO_SEARCH_DATETIME.format(value);
  }

  /**
   * Reclassifies discovered fields from first-page values. Returns empty when discovery came from a
   * shared cache and this adapter instance has no raw schema; the cached verdict remains valid.
   */
  public List<CrmField> refineFieldsFromFirstPage(
      String moduleName, List<CrmRecord> firstPageRecords) {
    List<RawCrmField> rawFields = rawSchemaByModule.get(normalizeModule(moduleName));
    if (rawFields == null) {
      return List.of();
    }
    List<CrmField> refined = new ArrayList<>();
    for (RawCrmField rawField : rawFields) {
      List<String> samples =
          firstPageRecords == null
              ? List.of()
              : firstPageRecords.stream()
                  .limit(FieldClassifier.MAX_SAMPLE_RECORDS)
                  .map(record -> record.getString(rawField.apiName()))
                  .toList();
      CrmField field = fieldClassifier.classify(rawField, samples);
      refined.add(field);
    }
    return List.copyOf(refined);
  }

  private static RawCrmField toRawField(ZohoApiClient.ZohoFieldSchema field) {
    return new RawCrmField(
        field.apiName(),
        field.displayLabel(),
        field.dataType(),
        field.maxLength(),
        field.custom());
  }

  private static String canonicalRole(String moduleName) {
    String normalized =
        moduleName == null ? "" : moduleName.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "deals", "potentials", "opportunities" -> CrmModule.ROLE_DEALS;
      case "contacts", "leads" -> CrmModule.ROLE_CONTACTS;
      case "accounts", "companies", "organizations", "organisations" ->
          CrmModule.ROLE_ACCOUNTS;
      case "tasks", "calls", "events", "meetings", "activities" ->
          CrmModule.ROLE_ACTIVITIES;
      case "cases", "tickets", "support_cases" -> CrmModule.ROLE_SUPPORT_CASES;
      default -> CrmModule.ROLE_UNKNOWN;
    };
  }

  private static String requiredFields(String moduleName) {
    return switch (normalizeModule(moduleName)) {
      case "deals" -> ZohoApiClient.DEAL_FIELDS;
      case "accounts" -> ZohoApiClient.ACCOUNT_FIELDS;
      case "contacts" -> ZohoApiClient.CONTACT_FIELDS;
      default -> "";
    };
  }

  private static void addCommaSeparated(Set<String> fields, String commaSeparated) {
    if (commaSeparated == null || commaSeparated.isBlank()) {
      return;
    }
    for (String field : commaSeparated.split(",")) {
      if (!field.isBlank()) {
        fields.add(field.trim());
      }
    }
  }

  private static String normalizeModule(String moduleName) {
    return moduleName == null ? "" : moduleName.trim().toLowerCase(Locale.ROOT);
  }
}
