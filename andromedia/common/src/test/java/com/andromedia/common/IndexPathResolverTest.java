package com.andromedia.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexPathResolverTest {

  @TempDir
  Path tempDirectory;

  @Test
  void resolvesWorkspaceRootFromNestedDirectory() throws IOException {
    Path workspaceRoot = Files.createDirectories(tempDirectory.resolve("a").resolve("b").resolve("c"));
    Files.createDirectory(workspaceRoot.resolve(".git"));
    Path nestedDirectory = Files.createDirectories(workspaceRoot.resolve("d").resolve("e"));

    Path detectedRoot = IndexPathResolver.resolveWorkspaceRoot(nestedDirectory);

    assertEquals(workspaceRoot.toRealPath(), detectedRoot);
  }

  @Test
  void fallsBackToStartDirectoryWhenNoMarkerExists() throws IOException {
    Path startDirectory = Files.createDirectories(tempDirectory.resolve("standalone").resolve("folder"));

    Path detectedRoot = IndexPathResolver.resolveWorkspaceRoot(startDirectory);

    assertEquals(startDirectory.toRealPath(), detectedRoot);
  }

  @Test
  void resolvesSameProjectIdForRootAndNestedDirectory() throws IOException {
    Path workspaceRoot = Files.createDirectories(tempDirectory.resolve("workspace"));
    Files.createFile(workspaceRoot.resolve("pom.xml"));
    Path nestedDirectory = Files.createDirectories(workspaceRoot.resolve("module").resolve("src"));

    String rootProjectId = IndexPathResolver.resolveProjectId(workspaceRoot);
    String nestedProjectId = IndexPathResolver.resolveProjectId(nestedDirectory);

    assertEquals(rootProjectId, nestedProjectId);
  }

  @Test
  void resolvesDifferentProjectIdsForDifferentWorkspaceRoots() throws IOException {
    Path workspaceA = Files.createDirectories(tempDirectory.resolve("workspace-a"));
    Path workspaceB = Files.createDirectories(tempDirectory.resolve("workspace-b"));
    Files.createFile(workspaceA.resolve("pom.xml"));
    Files.createFile(workspaceB.resolve("pom.xml"));

    String projectIdA = IndexPathResolver.resolveProjectId(workspaceA);
    String projectIdB = IndexPathResolver.resolveProjectId(workspaceB);

    assertNotEquals(projectIdA, projectIdB);
  }

  @Test
  void prefersAndromediaMarkerOverNestedBuildMarker() throws IOException {
    Path workspaceRoot = Files.createDirectories(tempDirectory.resolve("workspace"));
    Files.createDirectory(workspaceRoot.resolve(".andromedia"));

    Path nestedModule = Files.createDirectories(workspaceRoot.resolve("services").resolve("processor"));
    Files.createFile(nestedModule.resolve("pom.xml"));

    Path detectedRoot = IndexPathResolver.resolveWorkspaceRoot(nestedModule);

    assertEquals(workspaceRoot.toRealPath(), detectedRoot);
  }

  @Test
  void ignoresGlobalAndromediaHomeWhenResolvingRepoWorkspace() throws IOException {
    Path fakeHome = Files.createDirectories(tempDirectory.resolve("home"));
    Files.createDirectories(fakeHome.resolve(".andromedia"));
    Path repoRoot = Files.createDirectories(tempDirectory.resolve("repo"));
    Files.createDirectory(repoRoot.resolve(".git"));
    Path nestedSource = Files.createDirectories(repoRoot.resolve("src"));

    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", fakeHome.toString());
    try {
      Path detectedRoot = IndexPathResolver.resolveWorkspaceRoot(nestedSource);
      assertEquals(repoRoot.toRealPath(), detectedRoot);
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }
}
