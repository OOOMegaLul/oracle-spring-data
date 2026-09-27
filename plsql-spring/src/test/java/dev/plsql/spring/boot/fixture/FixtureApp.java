package dev.plsql.spring.boot.fixture;

import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.plsql.spring.annotation.PlsqlApi;
import dev.plsql.spring.meta.SignatureSource;
import dev.plsql.spring.test.Signatures;

/** A Boot application without a database: signatures come from a fixture. */
@Configuration(proxyBeanMethods = false)
@AutoConfigurationPackage
public class FixtureApp {

    @Bean
    DataSource dataSource() {
        return mock(DataSource.class);
    }

    @Bean
    public SignatureSource signatures() {
        return Signatures.source(Signatures.func("PKG", "NEXT", "NUMBER").in("NTENANT", "NUMBER").build());
    }

    @PlsqlApi(packageName = "PKG")
    public interface Sequences {
        long next(long tenant);
    }
}
