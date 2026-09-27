package dev.plsql.spring.it.boot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.zaxxer.hikari.HikariDataSource;

import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.boot.PlsqlAutoConfiguration;
import dev.plsql.spring.it.ItDatabase;
import dev.plsql.spring.it.boot.app.BootApp;
import dev.plsql.spring.it.boot.broken.BrokenApp;

/** The auto-configuration on a real database: beans by interface, fail-fast on mismatch. */
class SpringBootIT {

    static HikariDataSource ds;

    @BeforeAll
    static void setUp() {
        ds = ItDatabase.pool(2, true);
        BootApp.dataSource = ds;
        BrokenApp.dataSource = ds;
    }

    @AfterAll
    static void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    @Test
    void interfacesBecomeBeans() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlsqlAutoConfiguration.class))
                .withUserConfiguration(BootApp.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(PlsqlApiFactory.class).hasSingleBean(BootApp.Employees.class);
                    BootApp.Employees employees = ctx.getBean(BootApp.Employees.class);
                    assertThat(employees.emps()).extracting(BootApp.Emp::id).containsExactly(2L, 3L);
                    assertThat(employees.find(1)).get().extracting(BootApp.Emp::name).isEqualTo("Иванов");
                });
    }

    @Test
    void mismatchStopsTheContext() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlsqlAutoConfiguration.class))
                .withUserConfiguration(BrokenApp.class)
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("Renamed.emps")
                        .hasMessageContaining("'minimum' has no matching argument"));
    }
}
