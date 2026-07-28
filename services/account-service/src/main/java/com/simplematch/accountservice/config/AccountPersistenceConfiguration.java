package com.simplematch.accountservice.config;

import com.simplematch.config.PlatformProperties;
import com.simplematch.config.PostgresJdbcUrl;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;

@Configuration
public class AccountPersistenceConfiguration {
    private static final String ACCOUNT_SERVICE_SCHEMA = "account_service";

    @Bean
    Clock accountServiceClock() {
        return Clock.systemUTC();
    }
    //Todo: Refactor it to use auto-configuration
    @Bean
    DataSource accountServiceDataSource(PlatformProperties properties) {
        final PostgresJdbcUrl parsedJdbcDsn = PostgresJdbcUrl.parse(properties.postgres().dsn());
        final HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(parsedJdbcDsn.jdbcUrl());
        if (parsedJdbcDsn.username() != null) {
            dataSource.setUsername(parsedJdbcDsn.username());
        }
        if (parsedJdbcDsn.password() != null) {
            dataSource.setPassword(parsedJdbcDsn.password());
        }
        dataSource.setSchema(ACCOUNT_SERVICE_SCHEMA);
        dataSource.setMaximumPoolSize(4);
        dataSource.setPoolName("account-service-hikari");
        return dataSource;
    }
}
