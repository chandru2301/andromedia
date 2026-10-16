package com.andromedia.core.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Map;
import java.util.Optional;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

class UserCredentialStoreTest {

  private static PostgreSQLContainer<?> postgres;
  private static JdbcTemplate jdbc;
  private UserCredentialStore store;

  @BeforeAll
  static void startPostgres() {
    assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(), "Docker required for Postgres tests");
    try {
      postgres =
          new PostgreSQLContainer<>("postgres:16-alpine")
              .withDatabaseName("andromedia")
              .withUsername("andromedia")
              .withPassword("andromedia");
      postgres.start();
    } catch (Throwable ex) {
      assumeTrue(false, "Could not start Postgres Testcontainer: " + ex.getMessage());
      return;
    }
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
  }

  @AfterAll
  static void stopPostgres() {
    if (postgres != null) {
      postgres.stop();
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    assumeTrue(jdbc != null, "Postgres Testcontainer not available");
    jdbc.update("DELETE FROM user_credentials");
    jdbc.update("DELETE FROM users");
    CredentialCipher cipher = new CredentialCipher(randomKey());
    store = new UserCredentialStore(jdbc, cipher);
  }

  @Test
  void upsertEncryptsAndResolvesWithoutReturningPlaintext() {
    store.touchUser("user_abc", "user_abc");
    CredentialPublicView view =
        store.upsert(
            "user_abc",
            CredentialProviders.SLACK,
            Map.of("botToken", "xoxb-secret-token"),
            Map.of("channels", "#general"),
            true);

    assertTrue(view.hasSecret());
    assertEquals("slack", view.provider());
    assertEquals("#general", view.metadata().get("channels"));

    String encrypted =
        jdbc.queryForObject(
            "SELECT encrypted_payload FROM user_credentials WHERE trial_id = ? AND provider = ?",
            String.class,
            "user_abc",
            "slack");
    assertFalse(encrypted.contains("xoxb-secret-token"));

    Optional<String> resolved = store.resolveSlackBotToken("user_abc", "");
    assertEquals(Optional.of("xoxb-secret-token"), resolved);
    assertEquals(
        Optional.of("xoxb-from-request"),
        store.resolveSlackBotToken("user_abc", "xoxb-from-request"));
  }

  @Test
  void blankSecretKeepsExisting() {
    store.ensureUser("local");
    store.upsert(
        "local",
        CredentialProviders.NOTION,
        Map.of("apiKey", "secret_original"),
        Map.of("pageIds", java.util.List.of("page-1")),
        true);
    store.upsert(
        "local",
        CredentialProviders.NOTION,
        Map.of(),
        Map.of("pageIds", java.util.List.of("page-2")),
        false);

    assertEquals(Optional.of("secret_original"), store.resolveNotionApiKey("local", ""));
    assertEquals(
        java.util.List.of("page-2"),
        store.findPublic("local", CredentialProviders.NOTION).orElseThrow().metadata().get("pageIds"));
  }

  private static SecretKey randomKey() throws Exception {
    KeyGenerator generator = KeyGenerator.getInstance("AES");
    generator.init(256);
    return generator.generateKey();
  }
}
