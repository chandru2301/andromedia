package com.andromedia.core.trial;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * Durable trial sessions in DynamoDB when {@code andromedia.trial.dynamodb-table} is set; otherwise
 * in-memory (local/dev).
 */
@Component
public class TrialSessionStore {

  private static final Logger log = LoggerFactory.getLogger(TrialSessionStore.class);

  private final TrialProperties properties;
  private final Map<String, TrialSession> memory = new java.util.concurrent.ConcurrentHashMap<>();
  private final DynamoDbClient dynamo;

  public TrialSessionStore(TrialProperties properties) {
    this.properties = properties;
    if (properties.dynamodbEnabled()) {
      this.dynamo =
          DynamoDbClient.builder().region(Region.of(properties.dynamodbRegion())).build();
      log.info("Trial sessions persist to DynamoDB table {}", properties.dynamodbTable());
    } else {
      this.dynamo = null;
      log.info("Trial sessions use in-memory store (set andromedia.trial.dynamodb-table for durability)");
    }
  }

  public TrialSession save(TrialSession session) {
    if (dynamo != null) {
      putDynamo(session);
    } else {
      memory.put(session.trialId(), session);
      evictExpiredMemory();
    }
    return session;
  }

  public Optional<TrialSession> find(String trialId) {
    if (trialId == null || trialId.isBlank()) {
      return Optional.empty();
    }
    String key = trialId.trim();
    Optional<TrialSession> found = dynamo != null ? getDynamo(key) : Optional.ofNullable(memory.get(key));
    if (found.isEmpty()) {
      return Optional.empty();
    }
    TrialSession session = found.get();
    if (session.expiresAt().isBefore(Instant.now())) {
      if (dynamo == null) {
        memory.remove(key);
      }
      return Optional.empty();
    }
    return Optional.of(session);
  }

  public TrialSession recordQuery(String trialId) {
    TrialSession updated =
        find(trialId)
            .map(TrialSession::withQueryRecorded)
            .orElseThrow(() -> new IllegalArgumentException("Unknown or expired trial session"));
    return save(updated);
  }

  private void putDynamo(TrialSession session) {
    Map<String, AttributeValue> item = new HashMap<>();
    item.put("trialId", AttributeValue.builder().s(session.trialId()).build());
    item.put("createdAt", AttributeValue.builder().s(session.createdAt().toString()).build());
    item.put("expiresAt", AttributeValue.builder().s(session.expiresAt().toString()).build());
    item.put(
        "queriesUsed", AttributeValue.builder().n(Integer.toString(session.queriesUsed())).build());
    item.put(
        "maxQueries", AttributeValue.builder().n(Integer.toString(session.maxQueries())).build());
    if (session.zohoOrgHint() != null && !session.zohoOrgHint().isBlank()) {
      item.put("zohoOrgHint", AttributeValue.builder().s(session.zohoOrgHint()).build());
    }
    // TTL attribute for DynamoDB TTL (epoch seconds)
    item.put(
        "ttl",
        AttributeValue.builder().n(Long.toString(session.expiresAt().getEpochSecond())).build());
    dynamo.putItem(
        PutItemRequest.builder().tableName(properties.dynamodbTable()).item(item).build());
  }

  private Optional<TrialSession> getDynamo(String trialId) {
    var response =
        dynamo.getItem(
            GetItemRequest.builder()
                .tableName(properties.dynamodbTable())
                .key(Map.of("trialId", AttributeValue.builder().s(trialId).build()))
                .consistentRead(true)
                .build());
    if (response.item() == null || response.item().isEmpty()) {
      return Optional.empty();
    }
    Map<String, AttributeValue> item = response.item();
    return Optional.of(
        new TrialSession(
            item.get("trialId").s(),
            Instant.parse(item.get("createdAt").s()),
            Instant.parse(item.get("expiresAt").s()),
            Integer.parseInt(item.get("queriesUsed").n()),
            Integer.parseInt(item.get("maxQueries").n()),
            item.containsKey("zohoOrgHint") ? item.get("zohoOrgHint").s() : null));
  }

  private void evictExpiredMemory() {
    Instant now = Instant.now();
    memory.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
  }
}
