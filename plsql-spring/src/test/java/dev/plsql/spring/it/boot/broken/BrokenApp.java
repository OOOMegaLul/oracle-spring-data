package dev.plsql.spring.it.boot.broken;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.plsql.spring.annotation.PlsqlApi;

/** An application whose interface does not match the database: the context must not start. */
@Configuration(proxyBeanMethods = false)
@AutoConfigurationPackage
public class BrokenApp {

    public static DataSource dataSource;

    /** The pool belongs to the test, not to this context: do not close it on shutdown. */
    @Bean(destroyMethod = "")
    DataSource dataSource() {
        return dataSource;
    }

    @PlsqlApi(packageName = "LAB_PKG")
    public interface Renamed {
        /** The argument is P_MIN_ID. */
        void emps(long minimum);
    }
}
