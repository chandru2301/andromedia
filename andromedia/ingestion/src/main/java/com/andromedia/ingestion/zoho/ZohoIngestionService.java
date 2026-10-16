package com.andromedia.ingestion.zoho;

import com.andromedia.common.ZohoDealContextChunk;
import com.andromedia.ingestion.crm.CrmConnection;
import com.andromedia.ingestion.crm.CrmDataGapReport;
import com.andromedia.ingestion.crm.CrmFetchOptions;
import com.andromedia.ingestion.crm.CrmFetchQuery;
import com.andromedia.ingestion.crm.CrmField;
import com.andromedia.ingestion.crm.CrmFieldCategory;
import com.andromedia.ingestion.crm.CrmFieldType;
import com.andromedia.ingestion.crm.CrmRecord;
import com.andromedia.ingestion.crm.CrmSchemaCache;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZohoIngestionService {

  private static final Logger log = LoggerFactory.getLogger(ZohoIngestionService.class);

  private final ZohoApiClient apiClient;
  private final ZohoSchemaAdapter schemaAdapter;
  private final CrmSchemaCache schemaCache;
  private final CrmConnection connection;

  public ZohoIngestionService(ZohoApiClient apiClient) {
    this(
        apiClient,
        new ZohoSchemaAdapter(apiClient),
        new CrmSchemaCache(),
        new CrmConnection("default", CrmConnection.PROVIDER_ZOHO, Map.of()));
  }

  public ZohoIngestionService(
      ZohoApiClient apiClient,
      ZohoSchemaAdapter schemaAdapter,
      CrmSchemaCache schemaCache,
      CrmConnection connection) {
    this.apiClient = apiClient;
    this.schemaAdapter = schemaAdapter;
    this.schemaCache = schemaCache;
    this.connection = connection;
  }

  public ZohoApiClient.ZohoConnectionTestResult testConnection() throws IOException {
    return apiClient.testConnection();
  }

  public Map<String, ZohoApiClient.ZohoModuleSchema> discoverSchema() throws IOException {
    return apiClient.discoverV1Schema();
  }

  public IngestResult ingest(ZohoIngestionOptions options) throws IOException {
    Instant syncStart = Instant.now();
    boolean fullSync = options.fullSync() || options.modifiedSince() == null;
    Instant modifiedSince = fullSync ? null : options.modifiedSince();
    String syncType =
        fullSync ? ZohoIngestionOptions.SYNC_TYPE_FULL : ZohoIngestionOptions.SYNC_TYPE_INCREMENTAL;

    if (fullSync) {
      log.info("Zoho full sync — no watermark or fullRefresh requested");
    } else {
      log.info("Zoho incremental sync since {}", modifiedSince);
    }

    CrmFetchOptions fetchOptions =
        new CrmFetchOptions(options.lookbackDays(), 10_000, modifiedSince, fullSync);
    Map<String, List<CrmField>> schema = new LinkedHashMap<>();

    List<CrmField> dealFields = resolveFields("Deals", ZohoApiClient.DEAL_FIELDS);
    List<CrmField> accountFields = resolveFields("Accounts", ZohoApiClient.ACCOUNT_FIELDS);
    List<CrmField> contactFields = resolveFields("Contacts", ZohoApiClient.CONTACT_FIELDS);
    schema.put("Deals", dealFields);
    schema.put("Accounts", accountFields);
    schema.put("Contacts", contactFields);

    List<String> deletedDealIds = List.of();
    if (!fullSync && modifiedSince != null) {
      deletedDealIds = fetchDeletedDealIds(modifiedSince);
    }

    CrmFetchQuery dealQuery = schemaAdapter.buildFetchQuery("Deals", dealFields, fetchOptions);
    CrmFetchQuery accountQuery =
        schemaAdapter.buildFetchQuery("Accounts", accountFields, fetchOptions);
    CrmFetchQuery contactQuery =
        schemaAdapter.buildFetchQuery("Contacts", contactFields, fetchOptions);

    ZohoApiClient.ZohoModuleRecords<ZohoDeal> dealResult = apiClient.fetchDeals(dealQuery);
    ZohoApiClient.ZohoModuleRecords<ZohoAccount> accountResult =
        apiClient.fetchAccounts(accountQuery);
    ZohoApiClient.ZohoModuleRecords<ZohoContact> contactResult =
        apiClient.fetchContacts(contactQuery);

    dealFields = refineAndCache("Deals", dealFields, dealResult.firstPageSamples());
    accountFields = refineAndCache("Accounts", accountFields, accountResult.firstPageSamples());
    contactFields = refineAndCache("Contacts", contactFields, contactResult.firstPageSamples());
    schema.put("Deals", dealFields);
    schema.put("Accounts", accountFields);
    schema.put("Contacts", contactFields);

    List<ZohoDeal> deals = dealResult.records();
    List<ZohoAccount> accounts = accountResult.records();
    List<ZohoContact> contacts = contactResult.records();
    List<String> dealIds = deals.stream().map(ZohoDeal::id).toList();
    List<ZohoNote> notes =
        dealIds.isEmpty() ? List.of() : apiClient.fetchDealNotes(dealIds);
    List<ZohoActivity> activities =
        dealIds.isEmpty() ? List.of() : apiClient.fetchDealActivities(dealIds);

    Map<String, ZohoAccount> accountsById = indexAccounts(accounts);
    Map<String, List<ZohoContact>> contactsByAccountId = indexContactsByAccount(contacts);
    Map<String, List<ZohoContact>> contactsByAccountName = indexContactsByAccountName(contacts);
    Map<String, ZohoContact> contactsById = indexContactsById(contacts);
    Map<String, List<ZohoNote>> notesByDealId = ZohoApiClient.indexNotesByDeal(notes);
    Map<String, List<ZohoActivity>> activitiesByDealId =
        ZohoApiClient.indexActivitiesByDeal(activities);

    ZohoDemoAccountDetector.DemoAccountIdentities demoAccounts =
        ZohoDemoAccountDetector.compute(
            accounts,
            contacts,
            options.enableSandboxDemoSignatures(),
            options.sandboxDemoAccountNames());

    Map<String, CrmRecord> dealRecordsById = indexRecordsById(dealResult.rawRecords());
    Map<String, CrmRecord> accountRecordsById = indexRecordsById(accountResult.rawRecords());

    List<ZohoDealContextChunk> chunks = new ArrayList<>();
    int skipped = 0;
    int dealsIndexed = 0;

    for (ZohoDeal deal : deals) {
      ZohoAccount account = resolveAccount(deal, accountsById);
      List<ZohoContact> linkedContacts =
          resolveContacts(
              deal, account, contactsByAccountId, contactsByAccountName, contactsById);
      List<ZohoNote> dealNotes = notesByDealId.getOrDefault(deal.id(), List.of());
      List<ZohoActivity> dealActivities = activitiesByDealId.getOrDefault(deal.id(), List.of());
      ZohoRecordQualityScorer.QualityResult quality =
          ZohoRecordQualityScorer.scoreDealContext(
              deal,
              account,
              linkedContacts,
              dealNotes,
              dealActivities,
              options.lookbackDays(),
              demoAccounts);
      if (!ZohoRecordFilter.shouldIndex(deal, quality, options.minQualityScore())) {
        skipped++;
        log.debug("Skipping Zoho deal {} score={}", deal.id(), quality.score());
        continue;
      }
      CrmRecord dealRecord = dealRecordsById.get(deal.id());
      CrmRecord accountRecord =
          account != null && account.id() != null && !account.id().isBlank()
              ? accountRecordsById.get(account.id())
              : null;
      chunks.add(
          ZohoUnifiedChunkBuilder.build(
              deal,
              account,
              linkedContacts,
              dealNotes,
              dealActivities,
              quality,
              dealRecord,
              dealFields,
              accountRecord,
              accountFields));
      dealsIndexed++;
    }

    log.info(
        "Zoho ingest ({}): {} deals fetched, {} indexed, {} skipped, {} deleted, {} chunks ({} deal-notes, {} deal-activities, {} demo-account-ids)",
        syncType,
        deals.size(),
        dealsIndexed,
        skipped,
        deletedDealIds.size(),
        chunks.size(),
        notes.size(),
        activities.size(),
        demoAccounts.accountIds().size());

    Map<String, List<CrmRecord>> rawRecordsByModule = new LinkedHashMap<>();
    rawRecordsByModule.put("Deals", dealResult.rawRecords());
    rawRecordsByModule.put("Accounts", accountResult.rawRecords());
    rawRecordsByModule.put("Contacts", contactResult.rawRecords());
    List<CrmDataGapReport.Entry> dataGapReport = CrmDataGapReport.build(schema);
    return new IngestResult(
        chunks,
        schema,
        Map.copyOf(rawRecordsByModule),
        dataGapReport,
        deals.size(),
        dealsIndexed,
        skipped,
        syncType,
        deletedDealIds,
        modifiedSince,
        syncStart,
        accounts.size(),
        contacts.size());
  }

  private List<String> fetchDeletedDealIds(Instant since) {
    try {
      List<ZohoApiClient.ZohoDeletedRecord> deleted =
          apiClient.fetchDeletedSince("Deals", since);
      List<String> ids = new ArrayList<>();
      for (ZohoApiClient.ZohoDeletedRecord record : deleted) {
        if (record != null && record.zohoId() != null && !record.zohoId().isBlank()) {
          ids.add(record.zohoId());
          log.info(
              "Zoho deleted deal pending Lucene cleanup: {} ({})",
              record.displayName(),
              record.zohoId());
        }
      }
      return List.copyOf(ids);
    } catch (IOException | RuntimeException ex) {
      log.warn("Zoho deleted records fetch failed; continuing without delete cleanup: {}",
          ex.getMessage());
      return List.of();
    }
  }

  private List<CrmField> resolveFields(String moduleName, String fallbackFieldList) {
    try {
      return schemaCache.getOrDiscover(
          connection.orgId(), moduleName, schemaAdapter, connection);
    } catch (IOException | RuntimeException ex) {
      log.warn(
          "Zoho dynamic schema discovery failed for {}; using canonical fields: {}",
          moduleName,
          ex.getMessage());
      return fallbackFields(fallbackFieldList);
    }
  }

  private List<CrmField> refineAndCache(
      String moduleName, List<CrmField> current, List<CrmRecord> firstPageSamples) {
    List<CrmField> refined =
        schemaAdapter.refineFieldsFromFirstPage(moduleName, firstPageSamples);
    if (refined.isEmpty()) {
      return current;
    }
    schemaCache.put(connection.orgId(), moduleName, refined);
    return refined;
  }

  private static List<CrmField> fallbackFields(String commaSeparated) {
    if (commaSeparated == null || commaSeparated.isBlank()) {
      return List.of();
    }
    List<CrmField> fields = new ArrayList<>();
    for (String apiName : commaSeparated.split(",")) {
      if (!apiName.isBlank()) {
        fields.add(
            new CrmField(
                apiName.trim(),
                apiName.trim(),
                CrmFieldType.STRUCTURED,
                CrmFieldCategory.SYSTEM,
                false,
                false,
                0));
      }
    }
    return List.copyOf(fields);
  }

  private static Map<String, CrmRecord> indexRecordsById(List<CrmRecord> records) {
    Map<String, CrmRecord> map = new HashMap<>();
    if (records == null) {
      return map;
    }
    for (CrmRecord record : records) {
      if (record != null && record.id() != null && !record.id().isBlank()) {
        map.put(record.id(), record);
      }
    }
    return map;
  }

  private static Map<String, ZohoAccount> indexAccounts(List<ZohoAccount> accounts) {
    Map<String, ZohoAccount> map = new HashMap<>();
    for (ZohoAccount account : accounts) {
      if (account.id() != null && !account.id().isBlank()) {
        map.put(account.id(), account);
      }
    }
    return map;
  }

  private static Map<String, List<ZohoContact>> indexContactsByAccount(List<ZohoContact> contacts) {
    Map<String, List<ZohoContact>> map = new HashMap<>();
    for (ZohoContact contact : contacts) {
      if (contact.accountId() == null || contact.accountId().isBlank()) {
        continue;
      }
      map.computeIfAbsent(contact.accountId(), key -> new ArrayList<>()).add(contact);
    }
    return map;
  }

  private static Map<String, List<ZohoContact>> indexContactsByAccountName(
      List<ZohoContact> contacts) {
    Map<String, List<ZohoContact>> map = new HashMap<>();
    for (ZohoContact contact : contacts) {
      String normalized = ZohoDemoAccountDetector.normalizeAccountName(contact.accountName());
      if (normalized.isBlank()) {
        continue;
      }
      map.computeIfAbsent(normalized, key -> new ArrayList<>()).add(contact);
    }
    return map;
  }

  private static Map<String, ZohoContact> indexContactsById(List<ZohoContact> contacts) {
    Map<String, ZohoContact> map = new HashMap<>();
    for (ZohoContact contact : contacts) {
      if (contact.id() != null && !contact.id().isBlank()) {
        map.put(contact.id(), contact);
      }
    }
    return map;
  }

  private static ZohoAccount resolveAccount(ZohoDeal deal, Map<String, ZohoAccount> accountsById) {
    if (deal.accountId() != null && !deal.accountId().isBlank()) {
      ZohoAccount account = accountsById.get(deal.accountId());
      if (account != null) {
        return account;
      }
    }
    if (deal.accountName() != null && !deal.accountName().isBlank()) {
      String normalizedDealAccount =
          ZohoDemoAccountDetector.normalizeAccountName(deal.accountName());
      return accountsById.values().stream()
          .filter(
              a ->
                  deal.accountName().equalsIgnoreCase(a.name())
                      || normalizedDealAccount.equals(
                          ZohoDemoAccountDetector.normalizeAccountName(a.name())))
          .findFirst()
          .orElse(null);
    }
    return null;
  }

  static List<ZohoContact> resolveContacts(
      ZohoDeal deal,
      ZohoAccount account,
      Map<String, List<ZohoContact>> contactsByAccountId,
      Map<String, List<ZohoContact>> contactsByAccountName,
      Map<String, ZohoContact> contactsById) {
    List<ZohoContact> linked = new ArrayList<>();
    Set<String> seenIds = new LinkedHashSet<>();

    if (deal.contactId() != null && !deal.contactId().isBlank()) {
      ZohoContact dealContact = contactsById.get(deal.contactId());
      if (dealContact == null) {
        dealContact =
            new ZohoContact(
                deal.contactId(),
                deal.contactName(),
                "",
                "",
                deal.accountId(),
                deal.accountName(),
                null);
      }
      linked.add(dealContact);
      seenIds.add(dealContact.id());
    }

    String accountId =
        account != null && account.id() != null && !account.id().isBlank()
            ? account.id()
            : deal.accountId();
    if (accountId != null && !accountId.isBlank()) {
      addContacts(linked, seenIds, contactsByAccountId.getOrDefault(accountId, List.of()));
    }

    String accountName =
        account != null && account.name() != null && !account.name().isBlank()
            ? account.name()
            : deal.accountName();
    String normalizedName = ZohoDemoAccountDetector.normalizeAccountName(accountName);
    if (!normalizedName.isBlank() && contactsByAccountName != null) {
      addContacts(
          linked, seenIds, contactsByAccountName.getOrDefault(normalizedName, List.of()));
    }
    return List.copyOf(linked);
  }

  /** Backward-compatible overload used by older tests. */
  static List<ZohoContact> resolveContacts(
      ZohoDeal deal,
      ZohoAccount account,
      Map<String, List<ZohoContact>> contactsByAccountId,
      Map<String, ZohoContact> contactsById) {
    return resolveContacts(deal, account, contactsByAccountId, Map.of(), contactsById);
  }

  private static void addContacts(
      List<ZohoContact> linked, Set<String> seenIds, List<ZohoContact> candidates) {
    for (ZohoContact contact : candidates) {
      if (contact.id() != null && !contact.id().isBlank()) {
        if (seenIds.add(contact.id())) {
          linked.add(contact);
        }
      } else {
        linked.add(contact);
      }
    }
  }

  public record IngestResult(
      List<ZohoDealContextChunk> chunks,
      Map<String, List<CrmField>> schema,
      Map<String, List<CrmRecord>> rawRecordsByModule,
      List<CrmDataGapReport.Entry> dataGapReport,
      int dealsFetched,
      int dealsIndexed,
      int dealsSkipped,
      String syncType,
      List<String> deletedDealIds,
      Instant watermarkUsed,
      Instant syncStart,
      int accountsFetched,
      int contactsFetched) {

    public IngestResult {
      chunks = chunks == null ? List.of() : List.copyOf(chunks);
      schema = schema == null ? Map.of() : Map.copyOf(schema);
      rawRecordsByModule =
          rawRecordsByModule == null ? Map.of() : Map.copyOf(rawRecordsByModule);
      dataGapReport = dataGapReport == null ? List.of() : List.copyOf(dataGapReport);
      syncType =
          syncType == null || syncType.isBlank()
              ? ZohoIngestionOptions.SYNC_TYPE_FULL
              : syncType.trim();
      deletedDealIds = deletedDealIds == null ? List.of() : List.copyOf(deletedDealIds);
    }

    public int dealsDeleted() {
      return deletedDealIds.size();
    }
  }
}
