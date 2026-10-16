package com.andromedia.core.index;

import com.andromedia.common.ServiceIndexPathResolver;
import com.andromedia.search.ServiceIndexOverviewReader;
import com.andromedia.search.ServiceIndexOverviewReader.Overview;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

@Service
public class ServiceIndexOverviewService {

  private final ActiveZohoIndexStore activeZohoIndexStore;

  public ServiceIndexOverviewService(ActiveZohoIndexStore activeZohoIndexStore) {
    this.activeZohoIndexStore = activeZohoIndexStore;
  }

  public Map<String, Object> overview(String service) {
    List<Path> indexPaths = activeZohoIndexStore.resolveIndexPaths(service);
    List<Overview> parts = new ArrayList<>();
    for (Path path : indexPaths) {
      parts.add(ServiceIndexOverviewReader.read(path));
    }
    Overview merged = merge(parts, indexPaths);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("service", service);
    response.put("indexPath", merged.indexPath());
    response.put(
        "indexPaths", indexPaths.stream().map(path -> path.toAbsolutePath().normalize().toString()).toList());
    response.put("exists", merged.exists());
    response.put("totalChunks", merged.totalChunks());
    response.put("sourcesConnected", merged.sourcesConnected());
    response.put("sourceTypes", merged.sourceTypes());
    response.put(
        "lastSyncAt", merged.lastSyncAt() != null ? merged.lastSyncAt().toString() : null);
    response.put("indexSizeBytes", merged.indexSizeBytes());
    activeZohoIndexStore
        .getActiveConnectionKey(service)
        .ifPresent(key -> response.put("zohoConnectionKey", key));
    return response;
  }

  /**
   * Every workspace with an index on disk, newest activity first.
   *
   * <p>Workspaces are discovered by listing {@code ~/.andromedia/index/by-service}
   * rather than from a registry, so an index created by the CLI shows up here
   * without any extra bookkeeping.
   *
   * <p>Zoho sub-buckets ({@code <service>__z_<connectionKey>}) are folded into
   * their parent workspace: they are one logical project's CRM connection, not
   * separate places to choose between, and listing them would offer the user
   * near-duplicate entries.
   */
  public List<Map<String, Object>> list() {
    Path base = ServiceIndexPathResolver.resolveIndexBasePath();
    if (!Files.isDirectory(base)) {
      return List.of();
    }

    Set<String> services = new TreeSet<>();
    try (var entries = Files.list(base)) {
      for (Path entry : entries.toList()) {
        if (!Files.isDirectory(entry)) {
          continue;
        }
        String name = entry.getFileName().toString();
        if (name.isBlank()) {
          continue;
        }
        // Fold "<service>__z_<key>" back into "<service>".
        int zohoMarker = name.indexOf("__z_");
        services.add(zohoMarker > 0 ? name.substring(0, zohoMarker) : name);
      }
    } catch (IOException ex) {
      return List.of();
    }

    List<Map<String, Object>> result = new ArrayList<>();
    for (String service : services) {
      Map<String, Object> overview;
      try {
        overview = overview(service);
      } catch (RuntimeException ex) {
        // A workspace with unreadable segments should not hide the others.
        continue;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("service", service);
      row.put("exists", overview.get("exists"));
      row.put("totalChunks", overview.get("totalChunks"));
      row.put("sourceTypes", overview.get("sourceTypes"));
      row.put("lastSyncAt", overview.get("lastSyncAt"));
      row.put("indexSizeBytes", overview.get("indexSizeBytes"));
      result.add(row);
    }

    result.sort(
        Comparator.comparing(
                (Map<String, Object> row) -> {
                  Object lastSync = row.get("lastSyncAt");
                  return lastSync == null ? "" : lastSync.toString();
                })
            .reversed());
    return result;
  }

  static Overview merge(List<Overview> parts, List<Path> indexPaths) {
    if (parts == null || parts.isEmpty()) {
      Path fallback =
          indexPaths == null || indexPaths.isEmpty()
              ? Path.of(".")
              : indexPaths.getFirst();
      return new Overview(fallback.toString(), false, 0, 0, List.of(), null, 0L);
    }

    int totalChunks = 0;
    long sizeBytes = 0L;
    boolean exists = false;
    Instant lastSync = null;
    Set<String> sourceTypes = new LinkedHashSet<>();
    List<String> pathLabels = new ArrayList<>();

    for (int i = 0; i < parts.size(); i++) {
      Overview part = parts.get(i);
      pathLabels.add(part.indexPath());
      if (!part.exists()) {
        continue;
      }
      exists = true;
      totalChunks += part.totalChunks();
      sizeBytes += part.indexSizeBytes();
      if (part.sourceTypes() != null) {
        sourceTypes.addAll(part.sourceTypes());
      }
      if (part.lastSyncAt() != null
          && (lastSync == null || part.lastSyncAt().isAfter(lastSync))) {
        lastSync = part.lastSyncAt();
      }
    }

    String displayPath = String.join(",", pathLabels);
    return new Overview(
        displayPath,
        exists,
        totalChunks,
        sourceTypes.size(),
        List.copyOf(sourceTypes),
        lastSync,
        sizeBytes);
  }
}
