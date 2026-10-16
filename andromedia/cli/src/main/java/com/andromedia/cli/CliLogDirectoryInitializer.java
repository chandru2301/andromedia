package com.andromedia.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class CliLogDirectoryInitializer {

  public CliLogDirectoryInitializer() {
    try {
      Files.createDirectories(Path.of(System.getProperty("user.home"), ".andromedia", "logs"));
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to create CLI log directory", ex);
    }
  }
}
