package com.andromedia.ingestion.zoho;

import com.andromedia.ingestion.crm.CrmFetchQuery;
import com.andromedia.ingestion.crm.CrmRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZohoApiClient {

  private static final Logger log = LoggerFactory.getLogger(ZohoApiClient.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final int PAGE_SIZE = 200;
  private static final int MAX_FIELDS_PER_REQUEST = 50;
  /** Safety cap: 500 × 200 = 100k records per module before aborting a broken cursor loop. */
  static final int MAX_CURSOR_PAGES = 500;
  private static final List<String> CORE_MODULES = List.of("Deals", "Accounts", "Contacts");
  private static final List<String> ENRICHMENT_MODULES =
      List.of("Notes", "Tasks", "Calls", "Events", "Meetings");
  static final int MAX_NOTES_PER_DEAL = 5;
  static final int MAX_ACTIVITIES_PER_DEAL = 5;
  static final String DEAL_FIELDS =
      "Deal_Name,Account_Name,Contact_Name,Stage,Amount,Expected_Revenue,Probability,Closing_Date,Description,Next_Step,Modified_Time,Owner,Lead_Source";
  static final String ACCOUNT_FIELDS = "Account_Name,Description,Modified_Time";
  static final String CONTACT_FIELDS =
      "Full_Name,Email,Phone,Mobile,Account_Name,Modified_Time";

  public interface Transport {
    JsonNode get(String url, String accessToken) throws IOException;
  }

  private final ZohoRegion region;
  private final ZohoAuthClient authClient;
  private final String refreshToken;
  private final Transport transport;
  private String accessToken;
  private Instant accessTokenExpiresAt = Instant.EPOCH;

  public ZohoApiClient(
      String clientId, String clientSecret, String refreshToken, ZohoRegion region) {
    this(clientId, clientSecret, refreshToken, region, ZohoHttpRetry.Policy.defaults());
  }

  public ZohoApiClient(
      String clientId,
      String clientSecret,
      String refreshToken,
      ZohoRegion region,
      ZohoHttpRetry.Policy retryPolicy) {
    this(
        new ZohoAuthClient(clientId, clientSecret, region, retryPolicy),
        refreshToken,
        region,
        new HttpTransport(retryPolicy));
  }

  ZohoApiClient(
      ZohoAuthClient authClient,
      String refreshToken,
      ZohoRegion region,
      Transport transport) {
    if (refreshToken == null || refreshToken.isBlank()) {
      throw new IllegalArgumentException("Zoho refresh token is required");
    }
    this.authClient = authClient;
    this.refreshToken = refreshToken.trim();
    this.region = region == null ? ZohoRegion.COM : region;
    this.transport = transport;
  }

  public ZohoConnectionTestResult testConnection() throws IOException {
    List<ZohoModuleDefinition> modules = discoverModules();
    boolean hasDeals =
        modules.stream().map(ZohoModuleDefinition::apiName).anyMatch("Deals"::equalsIgnoreCase);
    return new ZohoConnectionTestResult(true, region.suffix(), modules.size(), hasDeals);
  }

  /** Discovers all modules visible to the connected Zoho CRM organization. */
  public List<ZohoModuleDefinition> discoverModules() throws IOException {
    ensureAccessToken();
    JsonNode response = transport.get(region.crmBaseUrl() + "/settings/modules", accessToken);
    List<ZohoModuleDefinition> modules = new ArrayList<>();
    JsonNode data = response.path("modules");
    if (data.isArray()) {
      for (JsonNode module : data) {
        String apiName = module.path("api_name").asText("");
        if (apiName.isBlank()) {
          continue;
        }
        String label =
            firstNonBlank(
                module.path("plural_label").asText(""),
                module.path("module_name").asText(""),
                apiName);
        boolean custom =
            module.path("custom").asBoolean(false)
                || "custom".equalsIgnoreCase(module.path("generated_type").asText(""));
        modules.add(new ZohoModuleDefinition(apiName, label, custom));
      }
    }
    return List.copyOf(modules);
  }

  public Map<String, ZohoModuleSchema> discoverV1Schema() throws IOException {
    Map<String, ZohoModuleSchema> schemas = new LinkedHashMap<>();
    for (String module : CORE_MODULES) {
      schemas.put(module, discoverModuleSchema(module));
    }
    for (String module : ENRICHMENT_MODULES) {
      try {
        schemas.put(module, discoverModuleSchema(module));
      } catch (IOException ex) {
        log.warn("Zoho schema discovery skipped for {}: {}", module, ex.getMessage());
      }
    }
    return Map.copyOf(schemas);
  }

  public ZohoModuleSchema discoverModuleSchema(String module) throws IOException {
    ensureAccessToken();
    JsonNode response =
        transport.get(
            region.crmBaseUrl() + "/settings/fields?module=" + urlEncode(module), accessToken);
    List<ZohoFieldSchema> fields = new ArrayList<>();
    JsonNode data = response.path("fields");
    if (data.isArray()) {
      for (JsonNode field : data) {
        String apiName = field.path("api_name").asText("");
        String dataType = field.path("data_type").asText("");
        if (!apiName.isBlank()) {
          String displayLabel =
              firstNonBlank(field.path("field_label").asText(""), apiName);
          int maxLength = Math.max(0, field.path("length").asInt(0));
          boolean custom =
              field.path("custom_field").asBoolean(false)
                  || field.path("custom").asBoolean(false);
          fields.add(
              new ZohoFieldSchema(
                  apiName,
                  displayLabel,
                  dataType,
                  maxLength,
                  custom,
                  isTextCapable(dataType, apiName)));
        }
      }
    }
    return new ZohoModuleSchema(module, List.copyOf(fields));
  }

  public List<ZohoDeal> fetchDeals() throws IOException {
    return fetchModuleRecords("Deals", DEAL_FIELDS, ZohoApiClient::parseDeal);
  }

  /** Fetches typed Deals plus raw custom fields using an adapter-built projection. */
  public ZohoModuleRecords<ZohoDeal> fetchDeals(CrmFetchQuery query) throws IOException {
    return fetchProjectedModule("Deals", DEAL_FIELDS, query, ZohoApiClient::parseDeal);
  }

  public List<ZohoAccount> fetchAccounts() throws IOException {
    return fetchModuleRecords("Accounts", ACCOUNT_FIELDS, ZohoApiClient::parseAccount);
  }

  /** Fetches typed Accounts plus raw custom fields using an adapter-built projection. */
  public ZohoModuleRecords<ZohoAccount> fetchAccounts(CrmFetchQuery query) throws IOException {
    return fetchProjectedModule("Accounts", ACCOUNT_FIELDS, query, ZohoApiClient::parseAccount);
  }

  public List<ZohoContact> fetchContacts() throws IOException {
    return fetchModuleRecords("Contacts", CONTACT_FIELDS, ZohoApiClient::parseContact);
  }

  /** Fetches typed Contacts plus raw custom fields using an adapter-built projection. */
  public ZohoModuleRecords<ZohoContact> fetchContacts(CrmFetchQuery query) throws IOException {
    return fetchProjectedModule("Contacts", CONTACT_FIELDS, query, ZohoApiClient::parseContact);
  }

  /** Notes linked to the given deals (Parent_Id.id), newest first, capped per deal. */
  public List<ZohoNote> fetchDealNotes(java.util.Collection<String> dealIds) {
    java.util.Set<String> dealIdSet =
        dealIds == null
            ? java.util.Set.of()
            : dealIds.stream().filter(id -> id != null && !id.isBlank()).collect(java.util.stream.Collectors.toSet());
    List<ZohoNote> notes =
        fetchModuleRecordsBestEffort(
            "Notes",
            "Note_Title,Note_Content,Parent_Id,Modified_Time",
            ZohoApiClient::parseNote);
    List<ZohoNote> dealNotes = new ArrayList<>();
    for (ZohoNote note : notes) {
      if (note == null || note.parentDealId().isBlank()) {
        continue;
      }
      // Zoho often omits Parent_Id.module; keep when id matches a known deal.
      if (dealIdSet.contains(note.parentDealId())) {
        dealNotes.add(note);
      }
    }
    return List.copyOf(dealNotes);
  }

  /** Tasks/Calls/Events (or Meetings) linked to the given deals via What_Id. */
  public List<ZohoActivity> fetchDealActivities(java.util.Collection<String> dealIds) {
    java.util.Set<String> dealIdSet =
        dealIds == null
            ? java.util.Set.of()
            : dealIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toSet());
    List<ZohoActivity> activities = new ArrayList<>();
    activities.addAll(
        fetchModuleRecordsBestEffort(
            "Tasks",
            "Subject,Status,Description,What_Id,Modified_Time",
            row -> parseActivity(row, "task")));
    activities.addAll(
        fetchModuleRecordsBestEffort(
            "Calls",
            "Subject,Call_Type,Description,What_Id,Call_Start_Time,Modified_Time",
            row -> parseActivity(row, "call")));
    List<ZohoActivity> events =
        fetchModuleRecordsBestEffort(
            "Events",
            "Event_Title,Title,Description,What_Id,Start_DateTime,Modified_Time",
            row -> parseActivity(row, "event"));
    if (events.isEmpty()) {
      events =
          fetchModuleRecordsBestEffort(
              "Meetings",
              "Event_Title,Title,Description,What_Id,Start_DateTime,Modified_Time",
              row -> parseActivity(row, "event"));
    }
    activities.addAll(events);
    List<ZohoActivity> dealActivities = new ArrayList<>();
    for (ZohoActivity activity : activities) {
      if (activity != null
          && !activity.dealId().isBlank()
          && dealIdSet.contains(activity.dealId())) {
        dealActivities.add(activity);
      }
    }
    return List.copyOf(dealActivities);
  }

  private <T> List<T> fetchModuleRecordsBestEffort(
      String module, String fields, RecordParser<T> parser) {
    try {
      return fetchModuleRecords(module, fields, parser);
    } catch (IOException ex) {
      log.warn("Zoho {} fetch skipped: {}", module, ex.getMessage());
      return List.of();
    }
  }

  private <T> List<T> fetchModuleRecords(String module, String fields, RecordParser<T> parser)
      throws IOException {
    return fetchModuleRecordsWithCursor(module, fields, parser, true);
  }

  /**
   * Cursor-based list fetch using Zoho {@code next_page_token}. Avoids the REST offset cap of 2000
   * records ({@code DISCRETE_PAGINATION_LIMIT_EXCEEDED}).
   */
  private <T> List<T> fetchModuleRecordsWithCursor(
      String module, String fields, RecordParser<T> parser, boolean allowTokenRestart)
      throws IOException {
    return fetchModuleRecordsDetailed(module, fields, null, parser, allowTokenRestart).records();
  }

  private <T> ZohoModuleRecords<T> fetchProjectedModule(
      String module, String requiredFields, CrmFetchQuery query, RecordParser<T> parser)
      throws IOException {
    List<String> batches = projectionBatches(query, requiredFields);
    ZohoModuleRecords<T> first =
        fetchModuleRecordsDetailed(module, batches.get(0), query, parser, true);
    if (batches.size() == 1) {
      return first;
    }

    Map<String, CrmRecord> mergedById = new LinkedHashMap<>();
    for (CrmRecord record : first.rawRecords()) {
      mergedById.put(record.id(), record);
    }
    List<String> sampleIds = first.firstPageSamples().stream().map(CrmRecord::id).toList();

    for (int i = 1; i < batches.size(); i++) {
      ZohoModuleRecords<T> extra =
          fetchModuleRecordsDetailed(module, batches.get(i), query, parser, true);
      for (CrmRecord record : extra.rawRecords()) {
        mergedById.merge(record.id(), record, ZohoApiClient::mergeRawRecords);
      }
    }

    List<CrmRecord> merged = List.copyOf(mergedById.values());
    List<CrmRecord> samples =
        sampleIds.stream().map(mergedById::get).filter(Objects::nonNull).toList();
    return new ZohoModuleRecords<>(first.records(), merged, samples);
  }

  private <T> ZohoModuleRecords<T> fetchModuleRecordsDetailed(
      String module,
      String fields,
      CrmFetchQuery query,
      RecordParser<T> parser,
      boolean allowTokenRestart)
      throws IOException {
    List<T> records = new ArrayList<>();
    List<CrmRecord> rawRecords = new ArrayList<>();
    List<CrmRecord> firstPageSamples = new ArrayList<>();
    String pageToken = null;
    boolean more = true;
    int pages = 0;
    int maxRecords =
        query == null || query.maxRecords() <= 0 ? Integer.MAX_VALUE : query.maxRecords();

    while (more && records.size() < maxRecords) {
      ensureAccessToken();
      String criteria = query == null ? null : query.criteria();
      String url =
          criteria != null && !criteria.isBlank()
              ? buildModuleSearchUrl(module, fields, criteria, pageToken)
              : buildModuleListUrl(module, fields, pageToken);
      JsonNode response;
      try {
        response = transport.get(url, accessToken);
      } catch (IOException ex) {
        if (allowTokenRestart && pageToken != null && isRecoverablePageTokenError(ex)) {
          log.warn(
              "Zoho {} page_token failed ({}); restarting module fetch once",
              module,
              ex.getMessage());
          return fetchModuleRecordsDetailed(module, fields, query, parser, false);
        }
        throw ex;
      }

      JsonNode data = response.path("data");
      if (data.isArray()) {
        for (JsonNode row : data) {
          if (records.size() >= maxRecords) {
            more = false;
            break;
          }
          records.add(parser.parse(row));
          CrmRecord raw = toCrmRecord(module, row);
          rawRecords.add(raw);
          if (pages == 0 && firstPageSamples.size() < PAGE_SIZE) {
            firstPageSamples.add(raw);
          }
        }
      }

      JsonNode info = response.path("info");
      more = info.path("more_records").asBoolean(false);
      String nextToken = info.path("next_page_token").asText("").trim();
      if (more && nextToken.isBlank()) {
        log.warn(
            "Zoho {} reported more_records=true but no next_page_token; stopping pagination",
            module);
        more = false;
      } else {
        pageToken = nextToken.isBlank() ? null : nextToken;
      }

      pages++;
      if (pages >= MAX_CURSOR_PAGES) {
        log.warn(
            "Zoho {} hit max cursor pages ({}); stopping with {} records",
            module,
            MAX_CURSOR_PAGES,
            records.size());
        break;
      }
    }
    return new ZohoModuleRecords<>(
        List.copyOf(records), List.copyOf(rawRecords), List.copyOf(firstPageSamples));
  }

  String buildModuleListUrl(String module, String fields, String pageToken) {
    StringBuilder url =
        new StringBuilder(region.crmBaseUrl())
            .append('/')
            .append(module)
            .append("?fields=")
            .append(urlEncode(fields))
            .append("&per_page=")
            .append(PAGE_SIZE);
    if (pageToken != null && !pageToken.isBlank()) {
      url.append("&page_token=").append(urlEncode(pageToken));
    }
    return url.toString();
  }

  /**
   * Zoho Search Records URL. Criteria is wrapped in parentheses per Zoho CRM search API
   * conventions.
   */
  String buildModuleSearchUrl(String module, String fields, String criteria, String pageToken) {
    String wrapped = wrapSearchCriteria(criteria);
    StringBuilder url =
        new StringBuilder(region.crmBaseUrl())
            .append('/')
            .append(module)
            .append("/search?criteria=")
            .append(urlEncode(wrapped))
            .append("&fields=")
            .append(urlEncode(fields))
            .append("&per_page=")
            .append(PAGE_SIZE);
    if (pageToken != null && !pageToken.isBlank()) {
      url.append("&page_token=").append(urlEncode(pageToken));
    }
    return url.toString();
  }

  static String wrapSearchCriteria(String criteria) {
    if (criteria == null || criteria.isBlank()) {
      return criteria;
    }
    String trimmed = criteria.trim();
    if (trimmed.startsWith("(") && trimmed.endsWith(")")) {
      return trimmed;
    }
    return "(" + trimmed + ")";
  }

  /**
   * Fetches deleted records for a module since {@code since} (exclusive). Uses {@code type=all}
   * (recycle bin + permanent). Zoho only retains ~30 days of deletions.
   */
  public List<ZohoDeletedRecord> fetchDeletedSince(String module, Instant since)
      throws IOException {
    Instant effectiveSince = since == null ? Instant.EPOCH : since;
    List<ZohoDeletedRecord> deleted = new ArrayList<>();
    String pageToken = null;
    boolean more = true;
    int pages = 0;

    while (more) {
      ensureAccessToken();
      String url = buildDeletedRecordsUrl(module, pageToken);
      JsonNode response = transport.get(url, accessToken);
      JsonNode data = response.path("data");
      if (data.isArray()) {
        for (JsonNode row : data) {
          ZohoDeletedRecord record = parseDeletedRecord(row);
          if (record != null
              && record.deletedTime() != null
              && record.deletedTime().isAfter(effectiveSince)) {
            deleted.add(record);
          }
        }
      }

      JsonNode info = response.path("info");
      more = info.path("more_records").asBoolean(false);
      String nextToken = info.path("next_page_token").asText("").trim();
      if (more && nextToken.isBlank()) {
        more = false;
      } else {
        pageToken = nextToken.isBlank() ? null : nextToken;
      }
      pages++;
      if (pages >= MAX_CURSOR_PAGES) {
        log.warn(
            "Zoho {}/deleted hit max cursor pages ({}); stopping with {} records",
            module,
            MAX_CURSOR_PAGES,
            deleted.size());
        break;
      }
    }
    return List.copyOf(deleted);
  }

  String buildDeletedRecordsUrl(String module, String pageToken) {
    StringBuilder url =
        new StringBuilder(region.crmBaseUrl())
            .append('/')
            .append(module)
            .append("/deleted?type=all&per_page=")
            .append(PAGE_SIZE);
    if (pageToken != null && !pageToken.isBlank()) {
      url.append("&page_token=").append(urlEncode(pageToken));
    }
    return url.toString();
  }

  static ZohoDeletedRecord parseDeletedRecord(JsonNode row) {
    if (row == null || row.isMissingNode() || row.isNull()) {
      return null;
    }
    String id = row.path("id").asText("").trim();
    if (id.isBlank()) {
      return null;
    }
    String deletedTimeRaw = row.path("deleted_time").asText("").trim();
    if (deletedTimeRaw.isBlank()) {
      return null;
    }
    Instant deletedTime = parseInstant(deletedTimeRaw);
    return new ZohoDeletedRecord(
        id,
        firstNonBlank(row.path("display_name").asText(""), id),
        row.path("type").asText("").trim(),
        deletedTime);
  }

  public record ZohoDeletedRecord(
      String zohoId, String displayName, String deletionType, Instant deletedTime) {}

  static boolean isRecoverablePageTokenError(IOException ex) {
    if (ex == null || ex.getMessage() == null) {
      return false;
    }
    String message = ex.getMessage().toLowerCase(Locale.ROOT);
    if (message.contains("discrete_pagination_limit_exceeded")) {
      return true;
    }
    if (message.contains("page_token")
        && (message.contains("invalid")
            || message.contains("expired")
            || message.contains("required"))) {
      return true;
    }
    return message.contains("invalid_token") || message.contains("invalid pagetoken");
  }

  private void ensureAccessToken() throws IOException {
    if (accessToken != null
        && !accessToken.isBlank()
        && Instant.now().isBefore(accessTokenExpiresAt)) {
      return;
    }
    ZohoAuthClient.TokenPair tokens = authClient.refreshAccessToken(refreshToken);
    this.accessToken = tokens.accessToken();
    this.accessTokenExpiresAt = tokens.expiresAt();
  }

  static ZohoDeal parseDeal(JsonNode row) {
    JsonNode account = row.path("Account_Name");
    JsonNode contact = row.path("Contact_Name");
    JsonNode owner = row.path("Owner");
    return new ZohoDeal(
        row.path("id").asText(""),
        row.path("Deal_Name").asText(""),
        row.path("Stage").asText(""),
        numericOrText(row.path("Amount")),
        numericOrText(row.path("Expected_Revenue")),
        numericOrText(row.path("Probability")),
        row.path("Closing_Date").asText(""),
        row.path("Description").asText(""),
        parseInstant(row.path("Modified_Time").asText(null)),
        lookupId(account),
        lookupName(account),
        lookupId(contact),
        lookupName(contact),
        lookupName(owner),
        row.path("Lead_Source").asText(""),
        row.path("Next_Step").asText(""));
  }

  static ZohoAccount parseAccount(JsonNode row) {
    return new ZohoAccount(
        row.path("id").asText(""),
        row.path("Account_Name").asText(""),
        row.path("Description").asText(""),
        parseInstant(row.path("Modified_Time").asText(null)));
  }

  static ZohoContact parseContact(JsonNode row) {
    JsonNode account = row.path("Account_Name");
    String phone = row.path("Phone").asText("");
    if (phone.isBlank()) {
      phone = row.path("Mobile").asText("");
    }
    return new ZohoContact(
        row.path("id").asText(""),
        row.path("Full_Name").asText(""),
        row.path("Email").asText(""),
        phone,
        lookupId(account),
        lookupName(account),
        parseInstant(row.path("Modified_Time").asText(null)));
  }

  private static String numericOrText(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return "";
    }
    if (node.isNumber()) {
      return node.asText("");
    }
    return node.asText("");
  }

  static ZohoNote parseNote(JsonNode row) {
    JsonNode parent = row.path("Parent_Id");
    String parentId = lookupId(parent);
    String module = lookupModule(parent, row.path("$se_module").asText(""));
    String parentDealId = "";
    if (parentId.isBlank()) {
      parentDealId = "";
    } else if (module.isBlank() || "Deals".equalsIgnoreCase(module)) {
      // Module often absent on Notes Parent_Id; resolve against known deal ids at fetch time.
      parentDealId = parentId;
    }
    return new ZohoNote(
        row.path("id").asText(""),
        row.path("Note_Title").asText(""),
        row.path("Note_Content").asText(""),
        parentDealId,
        parseInstant(row.path("Modified_Time").asText(null)));
  }

  static ZohoActivity parseActivity(JsonNode row, String type) {
    JsonNode what = row.path("What_Id");
    String seModule = row.path("$se_module").asText("");
    String module = lookupModule(what, seModule);
    String whatId = lookupId(what);
    // Activities may omit $se_module; unresolved ids are filtered against deal ids later.
    String dealId = "";
    if (!whatId.isBlank() && (module.isBlank() || "Deals".equalsIgnoreCase(module))) {
      dealId = whatId;
    }
    String subject = row.path("Subject").asText("");
    if (subject.isBlank()) {
      subject = row.path("Event_Title").asText("");
    }
    if (subject.isBlank()) {
      subject = row.path("Title").asText("");
    }
    String status = row.path("Status").asText("");
    if (status.isBlank()) {
      status = row.path("Call_Type").asText("");
    }
    Instant occurredAt = parseInstant(row.path("Call_Start_Time").asText(null));
    if (occurredAt.equals(Instant.EPOCH)) {
      occurredAt = parseInstant(row.path("Start_DateTime").asText(null));
    }
    Instant modified = parseInstant(row.path("Modified_Time").asText(null));
    if (occurredAt.equals(Instant.EPOCH)) {
      occurredAt = modified;
    }
    return new ZohoActivity(
        row.path("id").asText(""),
        type,
        subject,
        status,
        row.path("Description").asText(""),
        dealId,
        occurredAt,
        modified);
  }

  static String lookupModule(JsonNode lookup, String seModule) {
    if (lookup != null && lookup.isObject()) {
      JsonNode moduleNode = lookup.path("module");
      if (moduleNode.isObject()) {
        String apiName = moduleNode.path("api_name").asText("");
        if (!apiName.isBlank()) {
          return apiName;
        }
      } else if (moduleNode.isTextual() && !moduleNode.asText("").isBlank()) {
        return moduleNode.asText("");
      }
    }
    return seModule == null ? "" : seModule;
  }

  static boolean isDealLookup(JsonNode lookup, String seModule) {
    if (lookup == null || lookup.isMissingNode() || lookup.isNull()) {
      return false;
    }
    return "Deals".equalsIgnoreCase(lookupModule(lookup, seModule));
  }

  /** Cap notes per deal (newest Modified_Time first). */
  static Map<String, List<ZohoNote>> indexNotesByDeal(List<ZohoNote> notes) {
    Map<String, List<ZohoNote>> map = new LinkedHashMap<>();
    List<ZohoNote> sorted = new ArrayList<>(notes == null ? List.of() : notes);
    sorted.sort(
        Comparator.comparing(
                (ZohoNote n) -> n.modifiedTime() == null ? Instant.EPOCH : n.modifiedTime())
            .reversed());
    for (ZohoNote note : sorted) {
      if (note.parentDealId().isBlank()) {
        continue;
      }
      List<ZohoNote> bucket =
          map.computeIfAbsent(note.parentDealId(), key -> new ArrayList<>());
      if (bucket.size() < MAX_NOTES_PER_DEAL) {
        bucket.add(note);
      }
    }
    Map<String, List<ZohoNote>> copy = new LinkedHashMap<>();
    for (Map.Entry<String, List<ZohoNote>> entry : map.entrySet()) {
      copy.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Map.copyOf(copy);
  }

  /** Cap activities per deal (newest modified/occurred first). */
  static Map<String, List<ZohoActivity>> indexActivitiesByDeal(List<ZohoActivity> activities) {
    Map<String, List<ZohoActivity>> map = new LinkedHashMap<>();
    List<ZohoActivity> sorted = new ArrayList<>(activities == null ? List.of() : activities);
    sorted.sort(
        Comparator.comparing(
                (ZohoActivity a) -> {
                  Instant t = a.modifiedTime() == null ? Instant.EPOCH : a.modifiedTime();
                  Instant o = a.occurredAt() == null ? Instant.EPOCH : a.occurredAt();
                  return t.isAfter(o) ? t : o;
                })
            .reversed());
    for (ZohoActivity activity : sorted) {
      if (activity.dealId().isBlank()) {
        continue;
      }
      List<ZohoActivity> bucket =
          map.computeIfAbsent(activity.dealId(), key -> new ArrayList<>());
      if (bucket.size() < MAX_ACTIVITIES_PER_DEAL) {
        bucket.add(activity);
      }
    }
    Map<String, List<ZohoActivity>> copy = new LinkedHashMap<>();
    for (Map.Entry<String, List<ZohoActivity>> entry : map.entrySet()) {
      copy.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Map.copyOf(copy);
  }

  private static String lookupId(JsonNode lookup) {
    if (lookup == null || lookup.isMissingNode() || lookup.isNull()) {
      return "";
    }
    if (lookup.isObject()) {
      return lookup.path("id").asText("");
    }
    return "";
  }

  private static String lookupName(JsonNode lookup) {
    if (lookup == null || lookup.isMissingNode() || lookup.isNull()) {
      return "";
    }
    if (lookup.isObject()) {
      return lookup.path("name").asText("");
    }
    return lookup.asText("");
  }

  private static boolean isTextCapable(String dataType, String apiName) {
    if (apiName != null
        && (apiName.toLowerCase(Locale.ROOT).contains("description")
            || apiName.toLowerCase(Locale.ROOT).contains("note"))) {
      return true;
    }
    if (dataType == null) {
      return false;
    }
    String type = dataType.toLowerCase(Locale.ROOT);
    return type.contains("text")
        || type.contains("textarea")
        || type.contains("string")
        || type.contains("email");
  }

  private static Instant parseInstant(String value) {
    if (value == null || value.isBlank()) {
      return Instant.EPOCH;
    }
    try {
      return Instant.parse(value);
    } catch (Exception ignored) {
      // fall through
    }
    try {
      return java.time.OffsetDateTime.parse(value).toInstant();
    } catch (Exception ex) {
      return Instant.EPOCH;
    }
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return "";
  }

  private static List<String> projectionBatches(
      CrmFetchQuery query, String requiredFields) {
    java.util.Set<String> fields = new java.util.LinkedHashSet<>();
    addFields(fields, requiredFields);
    if (query != null) {
      addFields(fields, query.fieldList());
    }
    List<String> ordered = List.copyOf(fields);
    List<String> batches = new ArrayList<>();
    for (int start = 0; start < ordered.size(); start += MAX_FIELDS_PER_REQUEST) {
      int end = Math.min(start + MAX_FIELDS_PER_REQUEST, ordered.size());
      batches.add(String.join(",", ordered.subList(start, end)));
    }
    return batches.isEmpty() ? List.of(requiredFields) : List.copyOf(batches);
  }

  private static void addFields(java.util.Set<String> fields, String commaSeparated) {
    if (commaSeparated == null || commaSeparated.isBlank()) {
      return;
    }
    for (String field : commaSeparated.split(",")) {
      if (!field.isBlank()) {
        fields.add(field.trim());
      }
    }
  }

  private static CrmRecord toCrmRecord(String module, JsonNode row) {
    Map<String, Object> values =
        OBJECT_MAPPER.convertValue(
            row, new TypeReference<LinkedHashMap<String, Object>>() {});
    return new CrmRecord(module, row.path("id").asText(""), values);
  }

  private static CrmRecord mergeRawRecords(CrmRecord first, CrmRecord second) {
    Map<String, Object> merged = new LinkedHashMap<>(first.values());
    merged.putAll(second.values());
    return new CrmRecord(first.module(), first.id(), merged);
  }

  private static String urlEncode(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  @FunctionalInterface
  interface RecordParser<T> {
    T parse(JsonNode row);
  }

  public record ZohoConnectionTestResult(
      boolean ok, String region, int moduleCount, boolean hasDealsModule) {}

  public record ZohoModuleDefinition(String apiName, String label, boolean custom) {}

  public record ZohoFieldSchema(
      String apiName,
      String displayLabel,
      String dataType,
      int maxLength,
      boolean custom,
      boolean textCapable) {

    /** Backward-compatible constructor for callers that only have the original v1 metadata. */
    public ZohoFieldSchema(String apiName, String dataType, boolean textCapable) {
      this(apiName, apiName, dataType, 0, false, textCapable);
    }
  }

  public record ZohoModuleSchema(String module, List<ZohoFieldSchema> fields) {}

  /**
   * One dynamically projected module fetch. {@code firstPageSamples} is a subset of {@code
   * rawRecords} capped at Zoho's 200-row page size; no extra sampling request is made.
   */
  public record ZohoModuleRecords<T>(
      List<T> records, List<CrmRecord> rawRecords, List<CrmRecord> firstPageSamples) {
    public ZohoModuleRecords {
      records = records == null ? List.of() : List.copyOf(records);
      rawRecords = rawRecords == null ? List.of() : List.copyOf(rawRecords);
      firstPageSamples =
          firstPageSamples == null ? List.of() : List.copyOf(firstPageSamples);
    }
  }

  static final class HttpTransport implements Transport {
    private final HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ZohoHttpRetry.Policy policy;
    private final ZohoHttpRetry.Sleeper sleeper;
    private final Exchange exchange;

    HttpTransport() {
      this(ZohoHttpRetry.Policy.defaults());
    }

    HttpTransport(ZohoHttpRetry.Policy policy) {
      this(policy, ZohoHttpRetry.Sleeper.THREAD, null);
    }

    HttpTransport(
        ZohoHttpRetry.Policy policy, ZohoHttpRetry.Sleeper sleeper, Exchange exchange) {
      this.policy = policy == null ? ZohoHttpRetry.Policy.defaults() : policy;
      this.sleeper = sleeper == null ? ZohoHttpRetry.Sleeper.THREAD : sleeper;
      this.exchange = exchange;
    }

    @Override
    public JsonNode get(String url, String accessToken) throws IOException {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(60))
              .header("Authorization", "Zoho-oauthtoken " + accessToken)
              .GET()
              .build();
      int maxAttempts = policy.maxRetries() + 1;
      IOException lastFailure = null;
      for (int attempt = 0; attempt < maxAttempts; attempt++) {
        RawResponse response = send(request);
        int status = response.statusCode();
        if (status == 401) {
          throw new IOException("Zoho API unauthorized — check region and refresh token");
        }
        if (status < 400) {
          return OBJECT_MAPPER.readTree(response.body());
        }
        if (!ZohoHttpRetry.isRetryableStatus(status) || attempt >= policy.maxRetries()) {
          String snippet = truncate(response.body());
          throw new IOException(
              "Zoho API HTTP "
                  + status
                  + " after "
                  + (attempt + 1)
                  + " attempt(s): "
                  + snippet);
        }
        OptionalLong retryAfter =
            ZohoHttpRetry.parseRetryAfterSeconds(response.retryAfterHeader());
        long delayMs =
            ZohoHttpRetry.backoffMillis(attempt, policy, retryAfter, status);
        log.warn(
            "Zoho API HTTP {} on attempt {}/{}; sleeping {} ms (Retry-After={})",
            status,
            attempt + 1,
            maxAttempts,
            delayMs,
            retryAfter.isPresent() ? retryAfter.getAsLong() : "none");
        ZohoHttpRetry.sleep(sleeper, delayMs, "Interrupted calling Zoho API");
        lastFailure =
            new IOException(
                "Zoho API HTTP " + status + " after " + (attempt + 1) + " attempt(s)");
      }
      throw lastFailure == null
          ? new IOException("Zoho API request failed")
          : lastFailure;
    }

    private RawResponse send(HttpRequest request) throws IOException {
      try {
        if (exchange != null) {
          return exchange.exchange(request);
        }
        HttpResponse<String> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Optional<String> retryAfter =
            response.headers().firstValue("Retry-After");
        return new RawResponse(
            response.statusCode(),
            response.body() == null ? "" : response.body(),
            retryAfter.orElse(null));
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted calling Zoho API", ex);
      }
    }

    private static String truncate(String body) {
      if (body == null) {
        return "";
      }
      return body.length() <= 500 ? body : body.substring(0, 500);
    }
  }

  @FunctionalInterface
  interface Exchange {
    RawResponse exchange(HttpRequest request) throws IOException, InterruptedException;
  }

  record RawResponse(int statusCode, String body, String retryAfterHeader) {}
}
