package com.andromedia.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;

/**
 * JDBC/Flyway are opted in via {@code andromedia.credentials.postgres-enabled} so local runs
 * without Postgres keep working.
 */
@SpringBootApplication(
    scanBasePackages = "com.andromedia",
    exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
public class AndromediaApplication {

  public static void main(String[] args) {
    SpringApplication.run(AndromediaApplication.class, args);
  }
}
