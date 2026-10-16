package com.andromedia.core.credentials;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnProperty(
    prefix = "andromedia.credentials",
    name = "postgres-enabled",
    havingValue = "true")
public class PostgresCredentialsConfiguration {

  @Bean
  @ConfigurationProperties("spring.datasource")
  DataSourceProperties credentialDataSourceProperties() {
    return new DataSourceProperties();
  }

  @Bean
  DataSource dataSource(DataSourceProperties credentialDataSourceProperties) {
    return credentialDataSourceProperties.initializeDataSourceBuilder().build();
  }

  @Bean
  JdbcTemplate jdbcTemplate(DataSource dataSource) {
    return new JdbcTemplate(dataSource);
  }

  @Bean(initMethod = "migrate")
  Flyway flyway(DataSource dataSource) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .load();
  }
}
