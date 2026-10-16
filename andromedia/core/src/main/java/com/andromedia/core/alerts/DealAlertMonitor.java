package com.andromedia.core.alerts;

import com.andromedia.core.alerts.PipelineAlertStore.AlertConfig;
import com.andromedia.core.alerts.PipelineAlertStore.DealSnapshot;
import com.andromedia.core.alerts.SlackMessageService.AlertDigestMessage;
import com.andromedia.core.alerts.SlackMessageService.AlertEvent;
import com.andromedia.core.index.ActiveZohoIndexStore;
import com.andromedia.search.ZohoQualificationService;
import com.andromedia.search.ZohoQualificationService.DealLedgerEntry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DealAlertMonitor {

  private static final Logger log = LoggerFactory.getLogger(DealAlertMonitor.class);

  static final Set<String> HOT_STAGES =
      Set.of(
          "negotiation/review",
          "proposal/price quote",
          "id. decision makers",
          "identify decision makers");

  private final ZohoQualificationService zohoQualificationService;
  private final ActiveZohoIndexStore activeZohoIndexStore;
  private final PipelineAlertStore alertStore;
  private final PipelineAlertProperties alertProperties;
  private final SlackMessageService slackMessageService;

  public DealAlertMonitor(
      ZohoQualificationService zohoQualificationService,
      ActiveZohoIndexStore activeZohoIndexStore,
      PipelineAlertStore alertStore,
      PipelineAlertProperties alertProperties,
      SlackMessageService slackMessageService) {
    this.zohoQualificationService = zohoQualificationService;
    this.activeZohoIndexStore = activeZohoIndexStore;
    this.alertStore = alertStore;
    this.alertProperties = alertProperties;
    this.slackMessageService = slackMessageService;
  }

  /**
   * Diffs the current active-deal ledger against the last snapshot. First run only baselines.
   * Posts a Slack digest when new signals appear and alerts are enabled with credentials.
   */
  public MonitorResult evaluateAfterZohoReindex(String service) {
    if (service == null || service.isBlank()) {
      return MonitorResult.skipped("service required");
    }
    Path indexPath = activeZohoIndexStore.resolveIndexPath(service.trim());
    List<DealLedgerEntry> ledger = zohoQualificationService.listActiveDealLedger(indexPath);
    Map<String, DealSnapshot> previous = alertStore.getSnapshot(service.trim());
    Map<String, DealSnapshot> current = toSnapshot(ledger);

    List<AlertEvent> events = new ArrayList<>();
    boolean firstRun = previous.isEmpty();
    if (!firstRun) {
      events.addAll(diff(previous, current, ledger));
    }

    alertStore.saveSnapshot(service.trim(), current);

    if (firstRun) {
      return MonitorResult.baselined(current.size());
    }
    if (events.isEmpty()) {
      return MonitorResult.noChanges(current.size());
    }

    ResolvedDelivery delivery = resolveDelivery(service.trim());
    if (!delivery.enabled()) {
      return MonitorResult.eventsWithoutDelivery(events, "alerts disabled");
    }
    if (delivery.botToken().isBlank() || delivery.channelId().isBlank()) {
      return MonitorResult.eventsWithoutDelivery(
          events, "alert channel or bot token not configured");
    }

    try {
      slackMessageService.sendAlertDigest(
          new AlertDigestMessage(
              delivery.botToken(), delivery.channelId(), service.trim(), events));
      return MonitorResult.posted(events);
    } catch (Exception ex) {
      log.warn(
          "Pipeline alert Slack post failed for {}: {}",
          service,
          ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
      return MonitorResult.eventsWithoutDelivery(
          events, ex.getMessage() == null ? "slack post failed" : ex.getMessage());
    }
  }

  static List<AlertEvent> diff(
      Map<String, DealSnapshot> previous,
      Map<String, DealSnapshot> current,
      List<DealLedgerEntry> ledger) {
    Map<String, DealLedgerEntry> byKey = new LinkedHashMap<>();
    for (DealLedgerEntry entry : ledger) {
      byKey.put(dealKey(entry), entry);
    }
    List<AlertEvent> events = new ArrayList<>();
    for (Map.Entry<String, DealSnapshot> entry : current.entrySet()) {
      String key = entry.getKey();
      DealSnapshot now = entry.getValue();
      DealSnapshot was = previous.get(key);
      DealLedgerEntry deal = byKey.get(key);
      String name = deal == null ? key : deal.name();

      if (was == null) {
        // Brand-new deal: only flag if already hot/stale so we don't spam every new row.
        if (hasFlag(now, "stale_record")) {
          events.add(new AlertEvent(name, "stale_record · newly indexed", "stale_record"));
        }
        if (hasFlag(now, "close_date_passed")) {
          events.add(
              new AlertEvent(name, "close_date_passed · newly indexed", "close_date_passed"));
        }
        if (isHotStage(now.stage()) && !hasFlag(now, "test_demo_pattern")) {
          events.add(
              new AlertEvent(name, "entered " + now.stage(), "hot_stage"));
        }
        continue;
      }

      if (!hasFlag(was, "stale_record") && hasFlag(now, "stale_record")) {
        events.add(new AlertEvent(name, "stale_record", "stale_record"));
      }
      if (!hasFlag(was, "close_date_passed") && hasFlag(now, "close_date_passed")) {
        events.add(new AlertEvent(name, "close_date_passed", "close_date_passed"));
      }
      if (!isHotStage(was.stage()) && isHotStage(now.stage())) {
        events.add(new AlertEvent(name, "entered " + now.stage(), "hot_stage"));
      }
    }
    return events;
  }

  static Map<String, DealSnapshot> toSnapshot(List<DealLedgerEntry> ledger) {
    Map<String, DealSnapshot> snapshot = new LinkedHashMap<>();
    if (ledger == null) {
      return snapshot;
    }
    for (DealLedgerEntry entry : ledger) {
      if (entry.demo()) {
        continue;
      }
      Map<String, String> fields = entry.primaryFields();
      snapshot.put(
          dealKey(entry),
          new DealSnapshot(
              fields.getOrDefault("Stage", ""),
              fields.getOrDefault("Amount", ""),
              fields.getOrDefault("Close Date", ""),
              entry.qualificationScore(),
              entry.flags()));
    }
    return snapshot;
  }

  static String dealKey(DealLedgerEntry entry) {
    if (entry.chunkId() != null && !entry.chunkId().isBlank()) {
      return entry.chunkId().trim();
    }
    String name = entry.name() == null ? "" : entry.name().trim().toLowerCase(Locale.ROOT);
    String account =
        entry.accountName() == null ? "" : entry.accountName().trim().toLowerCase(Locale.ROOT);
    return name + "|" + account;
  }

  static boolean hasFlag(DealSnapshot snapshot, String flag) {
    if (snapshot == null || snapshot.flags() == null) {
      return false;
    }
    for (String value : snapshot.flags()) {
      if (flag.equalsIgnoreCase(value)) {
        return true;
      }
    }
    return false;
  }

  static boolean isHotStage(String stage) {
    if (stage == null || stage.isBlank()) {
      return false;
    }
    return HOT_STAGES.contains(stage.trim().toLowerCase(Locale.ROOT));
  }

  private ResolvedDelivery resolveDelivery(String service) {
    Optional<AlertConfig> stored = alertStore.getConfig(service);
    boolean enabled =
        stored.map(AlertConfig::enabled).orElse(alertProperties.enabledByDefault());
    String botToken =
        stored
            .map(AlertConfig::botToken)
            .filter(token -> token != null && !token.isBlank())
            .orElse(nullToEmpty(alertProperties.botToken()));
    String channelId =
        stored
            .map(AlertConfig::channelId)
            .filter(id -> id != null && !id.isBlank())
            .orElse(nullToEmpty(alertProperties.channel()));
    return new ResolvedDelivery(enabled, botToken.trim(), channelId.trim());
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private record ResolvedDelivery(boolean enabled, String botToken, String channelId) {}

  public record MonitorResult(
      String status, int dealCount, List<AlertEvent> events, String message) {

    static MonitorResult skipped(String message) {
      return new MonitorResult("skipped", 0, List.of(), message);
    }

    static MonitorResult baselined(int dealCount) {
      return new MonitorResult("baselined", dealCount, List.of(), "first run — snapshot saved");
    }

    static MonitorResult noChanges(int dealCount) {
      return new MonitorResult("no_changes", dealCount, List.of(), null);
    }

    static MonitorResult posted(List<AlertEvent> events) {
      return new MonitorResult("posted", 0, List.copyOf(events), null);
    }

    static MonitorResult eventsWithoutDelivery(List<AlertEvent> events, String message) {
      return new MonitorResult("events_not_delivered", 0, List.copyOf(events), message);
    }
  }
}
