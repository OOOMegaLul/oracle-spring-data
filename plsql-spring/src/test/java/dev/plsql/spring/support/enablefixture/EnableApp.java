package dev.plsql.spring.support.enablefixture;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.plsql.spring.annotation.EnablePlsqlApis;
import dev.plsql.spring.annotation.PlsqlApi;
import dev.plsql.spring.meta.SignatureSource;
import dev.plsql.spring.test.Signatures;

/** Plain Spring, no Boot: @EnablePlsqlApis with two interfaces and no PlsqlApiFactory bean. */
@Configuration(proxyBeanMethods = false)
@EnablePlsqlApis
public class EnableApp {

    @Bean
    DataSource dataSource() {
        return mock(DataSource.class, RETURNS_DEEP_STUBS);
    }

    @Bean
    SignatureSource signatures() {
        return Signatures.source(
                Signatures.func("PKG", "NEXT", "NUMBER").in("NTENANT", "NUMBER").build(),
                Signatures.proc("PKG", "TOUCH").in("NTENANT", "NUMBER").build());
    }

    @PlsqlApi(packageName = "PKG")
    public interface First {
        long next(long tenant);
    }

    @PlsqlApi(packageName = "PKG")
    public interface Second {
        void touch(long tenant);
    }
}
