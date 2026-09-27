package com.example.hr;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import com.zaxxer.hikari.HikariDataSource;

import dev.plsql.spring.session.SessionContextDataSource;

/**
 * Подготовка сеанса Oracle под пользователя при каждой выдаче соединения из пула (профиль
 * {@code session}): для PL/SQL, который хранит «кто работает» в переменных пакетов.
 */
@Configuration(proxyBeanMethods = false)
@Profile("session")
public class SessionConfig {

    /**
     * Сам пул соединений; настройки {@code spring.datasource.hikari.*} применяются к нему.
     *
     * @param props настройки {@code spring.datasource.*}
     * @return пул HikariCP
     */
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource pool(DataSourceProperties props) {
        return props.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    /**
     * Главный {@code DataSource} приложения — обёртка над пулом. Её получают и библиотека, и
     * менеджер транзакций, и {@code JdbcTemplate}.
     *
     * @param pool пул соединений
     * @return обёртка, которая готовит сеанс при каждой выдаче соединения
     */
    @Bean
    @Primary
    SessionContextDataSource dataSource(HikariDataSource pool) {
        SessionContextDataSource ds = new SessionContextDataSource(pool, SessionConfig::currentUser);
        // Сброс состояния пакетов включён по умолчанию; initSql выполняется после него.
        ds.setInitSql("begin app_context.set_user(?); end;", SessionConfig::currentUser);
        return ds;
    }

    /**
     * Текущий пользователь. В настоящем приложении — из Spring Security или сессии HTTP.
     *
     * @return логин пользователя
     */
    static String currentUser() {
        return "DEMO_USER";
    }
}
