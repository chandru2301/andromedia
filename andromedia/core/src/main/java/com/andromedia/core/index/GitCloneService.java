package com.andromedia.core.index;

import com.andromedia.common.ServiceIndexPathResolver;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GitCloneService {

  private static final Logger log = LoggerFactory.getLogger(GitCloneService.class);

  public Path cloneOrPull(String serviceName, String repoUrl, String branch) throws IOException, InterruptedException {
    String safeBranch = branch == null || branch.isBlank() ? "main" : branch.trim();
    Path repoPath = ServiceIndexPathResolver.resolveRepoPath(serviceName);
    if (Files.exists(repoPath.resolve(".git"))) {
      runGit(repoPath, "fetch", "--depth", "1", "origin", safeBranch);
      runGit(repoPath, "checkout", safeBranch);
      runGit(repoPath, "reset", "--hard", "origin/" + safeBranch);
      return repoPath;
    }
    Files.createDirectories(repoPath.getParent());
    runGit(
        repoPath.getParent(),
        "clone",
        "--branch",
        safeBranch,
        "--depth",
        "1",
        repoUrl,
        repoPath.getFileName().toString());
    return repoPath;
  }

  private void runGit(Path workingDirectory, String... args) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add("git");
    for (String arg : args) {
      command.add(arg);
    }
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(workingDirectory.toFile());
    builder.redirectErrorStream(true);
    Process process = builder.start();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        log.debug("git: {}", line);
      }
    }
    int exitCode = process.waitFor();
    if (exitCode != 0) {
      throw new IOException("git " + String.join(" ", args) + " failed with exit code " + exitCode);
    }
  }
}
