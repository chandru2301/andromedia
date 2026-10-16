package com.andromedia.common;

import java.nio.file.Path;

public final class ServiceIndexPathResolver {

  private ServiceIndexPathResolver() {}

  public static Path resolveRepoPath(String serviceName) {
    return Path.of(System.getProperty("user.home"), ".andromedia", "repos", sanitize(serviceName))
        .toAbsolutePath()
        .normalize();
  }

  public static Path resolveIndexPath(String serviceName) {
    return Path.of(
            System.getProperty("user.home"), ".andromedia", "index", "by-service", sanitize(serviceName))
        .toAbsolutePath()
        .normalize();
  }

  /**
   * The directory holding every service's index ({@code ~/.andromedia/index/by-service}).
   *
   * <p>Needed to enumerate workspaces: {@link #resolveIndexPath(String)} rejects a
   * blank name, so the container cannot be derived by passing an empty string.
   */
  public static Path resolveIndexBasePath() {
    return Path.of(System.getProperty("user.home"), ".andromedia", "index", "by-service")
        .toAbsolutePath()
        .normalize();
  }

  /**
   * Separate Lucene directory for a Zoho OAuth connection under the same Andro {@code service}
   * (trial id stays unchanged; only the on-disk bucket changes).
   */
  public static Path resolveZohoConnectionIndexPath(String serviceName, String connectionKey) {
    if (connectionKey == null || connectionKey.isBlank()) {
      throw new IllegalArgumentException("Zoho connection key is required");
    }
    String bucket = sanitize(serviceName) + "__z_" + sanitize(connectionKey.trim());
    return Path.of(System.getProperty("user.home"), ".andromedia", "index", "by-service", bucket)
        .toAbsolutePath()
        .normalize();
  }

  public static String sanitizeServiceName(String serviceName) {
    return sanitize(serviceName);
  }

  private static String sanitize(String serviceName) {
    if (serviceName == null || serviceName.isBlank()) {
      throw new IllegalArgumentException("service name is required");
    }
    return serviceName.replaceAll("[^a-zA-Z0-9._-]", "_");
  }
}
