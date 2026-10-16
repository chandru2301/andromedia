package com.andromedia.cli.shell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ShellPaths {

  private ShellPaths() {}

  public static Path historyFile() {
    return Path.of(System.getProperty("user.home"), ".andromedia", "history");
  }

  public static void ensureHistoryParentExists() throws IOException {
    Files.createDirectories(historyFile().getParent());
  }
}
