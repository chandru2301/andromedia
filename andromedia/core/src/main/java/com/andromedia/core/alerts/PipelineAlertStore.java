package com.andromedia.core.alerts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Durable per-service alert config + last deal snapshot under {@code ~/.andromedia/alerts/}.
 */
@Component
public class PipelineAlertStore {

  private static final Logger log = LoggerFactory.getLogger(PipelineAlertStore.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Path storeDir;

  public PipelineAlertStore() {
    this(
        Path.of(System.getProperty("user.home"), ".andromedia", "alerts")
            .toAbsolutePath()
            .normalize());
  }

  PipelineAlertStore(Path storeDir) {
    this.storeDir = storeDir;
  }

  public Optional<AlertConfig> getConfig(String service) {
    return read(service).map(this::parseConfig);
  }

  public void saveConfig(String service, AlertConfig config) {
    if (service == null || service.isBlank()) {
      throw new IllegalArgumentException("service is required");
    }
    if (config == null) {
      throw new IllegalArgumentException("config is required");
    }
    try {
      Files.createDirectories(storeDir);
      ObjectNode root = read(service).orElseGet(MAPPER::createObjectNode);
      root.put("enabled", config.enabled());
      root.put("botToken", config.botToken() == null ? "" : config.botToken());
      root.put("channelId", config.channelId() == null ? "" : config.channelId());
      root.put("channelName", config.channelName() == null ? "" : config.channelName());
      root.put("updatedAt", java.time.Instant.now().toString());
      write(service, root);
    } catch (IOException ex) {
      throw new IllegalStateException("Unable to save pipeline alert config: " + ex.getMessage(), ex);
    }
  }

  public Map<String, DealSnapshot> getSnapshot(String service) {
    Optional<ObjectNode> root = read(service);
    if (root.isEmpty()) {
      return Map.of();
    }
    JsonNode deals = root.get().path("deals");
    if (!deals.isObject()) {
      return Map.of();
    }
    Map<String, DealSnapshot> snapshot = new LinkedHashMap<>();
    deals
        .fields()
        .forEachRemaining(
            entry -> {
              JsonNode node = entry.getValue();
              List<String> flags = new ArrayList<>();
              JsonNode flagsNode = node.path("flags");
              if (flagsNode.isArray()) {
                for (JsonNode flag : flagsNode) {
                  if (flag != null && !flag.asText("").isBlank()) {
                    flags.add(flag.asText().trim());
                  }
                }
              }
              snapshot.put(
                  entry.getKey(),
                  new DealSnapshot(
                      text(node, "stage"),
                      text(node, "amount"),
                      text(node, "closeDate"),
                      node.path("score").asDouble(0),
                      flags));
            });
    return Map.copyOf(snapshot);
  }

  public void saveSnapshot(String service, Map<String, DealSnapshot> snapshot) {
    if (service == null || service.isBlank()) {
      throw new IllegalArgumentException("service is required");
    }
    try {
      Files.createDirectories(storeDir);
      ObjectNode root = read(service).orElseGet(MAPPER::createObjectNode);
      ObjectNode deals = MAPPER.createObjectNode();
      if (snapshot != null) {
        for (Map.Entry<String, DealSnapshot> entry : snapshot.entrySet()) {
          DealSnapshot deal = entry.getValue();
          ObjectNode node = MAPPER.createObjectNode();
          node.put("stage", deal.stage() == null ? "" : deal.stage());
          node.put("amount", deal.amount() == null ? "" : deal.amount());
          node.put("closeDate", deal.closeDate() == null ? "" : deal.closeDate());
          node.put("score", deal.score());
          ArrayNode flags = node.putArray("flags");
          if (deal.flags() != null) {
            for (String flag : deal.flags()) {
              flags.add(flag);
            }
          }
          deals.set(entry.getKey(), node);
        }
      }
      root.set("deals", deals);
      root.put("snapshotAt", java.time.Instant.now().toString());
      write(service, root);
    } catch (IOException ex) {
      throw new IllegalStateException("Unable to save pipeline alert snapshot: " + ex.getMessage(), ex);
    }
  }

  public AlertConfigView toPublicView(AlertConfig config) {
    if (config == null) {
      return new AlertConfigView(false, false, "", "", false);
    }
    return new AlertConfigView(
        config.enabled(),
        config.botToken() != null && !config.botToken().isBlank(),
        config.channelId() == null ? "" : config.channelId(),
        config.channelName() == null ? "" : config.channelName(),
        true);
  }

  private AlertConfig parseConfig(ObjectNode root) {
    return new AlertConfig(
        root.path("enabled").asBoolean(false),
        text(root, "botToken"),
        text(root, "channelId"),
        text(root, "channelName"));
  }

  private Optional<ObjectNode> read(String service) {
    Path path = pathFor(service);
    if (!Files.isRegularFile(path)) {
      return Optional.empty();
    }
    try {
      JsonNode node = MAPPER.readTree(Files.readString(path));
      if (node instanceof ObjectNode objectNode) {
        return Optional.of(objectNode);
      }
      return Optional.empty();
    } catch (IOException ex) {
      log.warn("Unable to read pipeline alert store for {}: {}", service, ex.getMessage());
      return Optional.empty();
    }
  }

  private void write(String service, ObjectNode root) throws IOException {
    Files.writeString(
        pathFor(service), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
  }

  private Path pathFor(String service) {
    String safe =
        service
            .trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_-]+", "_");
    return storeDir.resolve(safe + ".json");
  }

  private static String text(JsonNode node, String field) {
    String value = node.path(field).asText("");
    return value == null ? "" : value.trim();
  }

  public record AlertConfig(
      boolean enabled, String botToken, String channelId, String channelName) {}

  public record AlertConfigView(
      boolean enabled,
      boolean hasBotToken,
      String channelId,
      String channelName,
      boolean configured) {}

  public record DealSnapshot(
      String stage, String amount, String closeDate, double score, List<String> flags) {
    public DealSnapshot {
      flags = flags == null ? List.of() : List.copyOf(flags);
    }
  }
}
