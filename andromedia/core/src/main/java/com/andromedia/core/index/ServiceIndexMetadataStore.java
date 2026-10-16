package com.andromedia.core.index;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class ServiceIndexMetadataStore {

  private final ConcurrentHashMap<String, ServiceIndexMetadata> byService = new ConcurrentHashMap<>();

  public void save(String service, Map<String, Object> stats) {
    byService.put(service, new ServiceIndexMetadata(service, Instant.now(), stats));
  }

  public ServiceIndexMetadata get(String service) {
    return byService.get(service);
  }

  public record ServiceIndexMetadata(
      String service, Instant indexedAt, Map<String, Object> stats) {}
}
