package com.andromedia.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

public final class IndexPathResolver {

  private static final String WORKSPACE_MARKER = ".andromedia";
  private static final List<String> FALLBACK_ROOT_MARKERS =
      List.of(".git", "pom.xml", "package.json", "settings.gradle", "settings.gradle.kts");

  private IndexPathResolver() {}

  public static Path resolve(String configuredIndexPath) {
    if (configuredIndexPath != null && !configuredIndexPath.isBlank()) {
      return Path.of(configuredIndexPath).toAbsolutePath().normalize();
    }
    return Path.of(System.getProperty("user.home"), ".andromedia", "index")
        .toAbsolutePath()
        .normalize();
  }

  public static Path resolveWorkspaceRoot(Path startDirectory) {
    Path normalizedStart = canonicalize(startDirectory);
    Path globalAndromediaHome = globalAndromediaHome();
    Path current = normalizedStart;
    while (current != null) {
      Path marker = current.resolve(WORKSPACE_MARKER);
      if (Files.exists(marker) && !marker.equals(globalAndromediaHome)) {
        return current;
      }
      current = current.getParent();
    }

    current = normalizedStart;
    while (current != null) {
      if (containsFallbackRootMarker(current)) {
        return current;
      }
      current = current.getParent();
    }
    return normalizedStart;
  }

  public static Path resolveProjectIndexPath(String configuredIndexPath, Path startDirectory) {
    Optional<Path> direct = IndexPathHolder.directIndexPath();
    if (direct.isPresent()) {
      return direct.get();
    }
    Path baseIndexPath = resolve(configuredIndexPath);
    String projectId = resolveProjectId(startDirectory);
    return baseIndexPath.resolve(projectId).toAbsolutePath().normalize();
  }

  public static String resolveProjectId(Path startDirectory) {
    Path workspaceRoot = resolveWorkspaceRoot(startDirectory);
    String canonicalWorkspaceRoot = canonicalize(workspaceRoot).toString();
    return hashProjectPath(canonicalWorkspaceRoot);
  }

  private static String hashProjectPath(String canonicalWorkspaceRoot) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashBytes = digest.digest(canonicalWorkspaceRoot.getBytes(StandardCharsets.UTF_8));
      String hex = HexFormat.of().formatHex(hashBytes);
      return hex.substring(0, IndexConstants.PROJECT_ID_HEX_LENGTH);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 digest is not available", ex);
    }
  }

  public static Path workspaceMarkerPath(Path workspaceRoot) {
    return canonicalize(workspaceRoot).resolve(WORKSPACE_MARKER);
  }

  private static Path globalAndromediaHome() {
    return Path.of(System.getProperty("user.home"), ".andromedia").toAbsolutePath().normalize();
  }

  private static boolean containsFallbackRootMarker(Path directory) {
    return FALLBACK_ROOT_MARKERS.stream().anyMatch(marker -> Files.exists(directory.resolve(marker)));
  }

  private static Path canonicalize(Path path) {
    Path normalizedPath = path.toAbsolutePath().normalize();
    try {
      return normalizedPath.toRealPath();
    } catch (IOException ex) {
      return normalizedPath;
    }
  }
}
