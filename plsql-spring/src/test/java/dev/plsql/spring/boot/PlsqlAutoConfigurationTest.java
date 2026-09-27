package dev.plsql.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.boot.fixture.FixtureApp;
import dev.plsql.spring.support.CharsetGuard;

/** Auto-configuration without a database: signatures from a fixture bean. */
class PlsqlAutoConfigurationTest {

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlsqlAutoConfiguration.class))
            .withUserConfiguration(FixtureApp.class)
            .withPropertyValues("plsql.database-charset=CL8MSWIN1251");

    @Test
    void factoryAndInterfaceBeans() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(PlsqlApiFactory.class).hasSingleBean(FixtureApp.Sequences.class);
            assertThat(ctx.getBean(PlsqlApiFactory.class).runtime().charsetGuard().isActive()).isTrue();
            assertThat(ctx.getBean(FixtureApp.Sequences.class).toString()).contains("Sequences");
        });
    }

    @Test
    void propertiesAreBound() {
        runner.withPropertyValues("plsql.charset-policy=IGNORE", "plsql.retry-discarded-state=false").run(ctx -> {
            PlsqlApiFactory f = ctx.getBean(PlsqlApiFactory.class);
            assertThat(f.runtime().charsetGuard()).isSameAs(CharsetGuard.none());
            assertThat(f.runtime().retryDiscardedState()).isFalse();
        });
    }

    @Test
    void ownFactoryWins() {
        runner.withBean("myFactory", PlsqlApiFactory.class, () -> PlsqlApiFactory
                        .builder(org.mockito.Mockito.mock(javax.sql.DataSource.class))
                        .signatureSource(new FixtureApp().signatures()).charsetPolicy(CharsetGuard.Policy.IGNORE).build())
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(PlsqlApiFactory.class);
                    assertThat(ctx.getBeanNamesForType(PlsqlApiFactory.class)).containsExactly("myFactory");
                });
    }
}
