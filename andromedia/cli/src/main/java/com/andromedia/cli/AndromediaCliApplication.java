package com.andromedia.cli;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * CLI entry point.
 *
 * <p>JDBC/Flyway are excluded for the same reason as {@code core}: the CLI only
 * reads and writes a local Lucene index and must run without a database. Without
 * these exclusions Spring attempts to auto-configure a DataSource and the process
 * fails at startup with "Failed to configure a DataSource" — before any command
 * can run, including {@code index}.
 */
@SpringBootApplication(
    scanBasePackages = {
      "com.andromedia.cli",
      "com.andromedia.indexing",
      "com.andromedia.search",
      "com.andromedia.llm"
    },
    exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
public class AndromediaCliApplication {

  public static void main(String[] args) {
    SpringApplication.run(AndromediaCliApplication.class, args);
  }
}
