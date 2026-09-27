package dev.plsql.spring.it.boot.app;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.plsql.spring.annotation.PlsqlApi;
import dev.plsql.spring.annotation.SqlQuery;
import dev.plsql.spring.call.ArgumentDefaults;

/** A minimal Boot application: its package is scanned for @PlsqlApi interfaces. */
@Configuration(proxyBeanMethods = false)
@AutoConfigurationPackage
public class BootApp {

    /** Injected by the test: the pool on the PLSQL_IT schema. */
    public static DataSource dataSource;

    /** The pool belongs to the test, not to this context: do not close it on shutdown. */
    @Bean(destroyMethod = "")
    DataSource dataSource() {
        return dataSource;
    }

    @Bean
    ArgumentDefaults defaults() {
        Supplier<Object> minId = () -> 2;
        return ArgumentDefaults.byName(Map.of("P_MIN_ID", minId));
    }

    public record Emp(long id, String name, LocalDate hired, boolean flag) {
    }

    @PlsqlApi(packageName = "LAB_PKG")
    public interface Employees {

        /** P_MIN_ID comes from ArgumentDefaults. */
        List<Emp> emps();

        @SqlQuery("select id, name, hired, flag from lab_emp where id = :id")
        Optional<Emp> find(long id);
    }
}
