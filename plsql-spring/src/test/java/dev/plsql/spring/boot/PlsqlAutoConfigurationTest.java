package dev.plsql.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.boot.fixture.FixtureApp;
import dev.plsql.spring.support.CharsetGuard;

/**
 * Автоконфигурация без базы данных: сигнатуры берутся из бина-фикстуры. Кодировка базы задана
 * свойством {@code plsql.database-charset}, поэтому фабрика не запрашивает её у базы.
 */
class PlsqlAutoConfigurationTest {

    /** Контекст с автоконфигурацией, приложением {@link FixtureApp} и кодировкой CL8MSWIN1251. */
    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlsqlAutoConfiguration.class))
            .withUserConfiguration(FixtureApp.class)
            .withPropertyValues("plsql.database-charset=CL8MSWIN1251");

    /**
     * Проверяет, что автоконфигурация создаёт одну фабрику {@link PlsqlApiFactory} и регистрирует
     * бин интерфейса {@code Sequences} из пакета приложения. При кодировке CL8MSWIN1251 и
     * политике по умолчанию ({@code FAIL}) проверка символов включена, а {@code toString}
     * прокси называет интерфейс.
     */
    @Test
    void factoryAndInterfaceBeans() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(PlsqlApiFactory.class).hasSingleBean(FixtureApp.Sequences.class);
            assertThat(ctx.getBean(PlsqlApiFactory.class).runtime().charsetGuard().isActive()).isTrue();
            assertThat(ctx.getBean(FixtureApp.Sequences.class).toString()).contains("Sequences");
        });
    }

    /**
     * Проверяет, что свойства {@code plsql.*} доходят до фабрики: {@code charset-policy=IGNORE}
     * выключает проверку символов ({@code CharsetGuard.none()}), а
     * {@code retry-discarded-state=false} выключает повтор вызова после ORA-04068,
     * {@code query-timeout=1500ms} даёт срок две секунды (JDBC считает целыми секундами). Без
     * свойства срока нет.
     */
    @Test
    void propertiesAreBound() {
        runner.withPropertyValues("plsql.charset-policy=IGNORE", "plsql.retry-discarded-state=false",
                "plsql.query-timeout=1500ms").run(ctx -> {
            PlsqlApiFactory f = ctx.getBean(PlsqlApiFactory.class);
            assertThat(f.runtime().charsetGuard()).isSameAs(CharsetGuard.none());
            assertThat(f.runtime().retryDiscardedState()).isFalse();
            assertThat(f.runtime().queryTimeout()).isEqualTo(2);
        });
        runner.withPropertyValues("plsql.charset-policy=IGNORE").run(ctx ->
                assertThat(ctx.getBean(PlsqlApiFactory.class).runtime().queryTimeout()).isZero());
    }

    /**
     * Проверяет, что собственный бин {@link PlsqlApiFactory} приложения отменяет фабрику
     * автоконфигурации: в контексте ровно одна фабрика, и это {@code myFactory}. Собственная
     * фабрика собрана с политикой {@code IGNORE}, поэтому не запрашивает кодировку у мока.
     */
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
