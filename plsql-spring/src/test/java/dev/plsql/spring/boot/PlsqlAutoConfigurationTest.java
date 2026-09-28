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
     * свойства срока нет. {@code fetch-size=500} меняет размер пачки строк, по умолчанию он 100.
     */
    @Test
    void propertiesAreBound() {
        runner.withPropertyValues("plsql.charset-policy=IGNORE", "plsql.retry-discarded-state=false",
                "plsql.query-timeout=1500ms", "plsql.fetch-size=500").run(ctx -> {
            PlsqlApiFactory f = ctx.getBean(PlsqlApiFactory.class);
            assertThat(f.runtime().charsetGuard()).isSameAs(CharsetGuard.none());
            assertThat(f.runtime().retryDiscardedState()).isFalse();
            assertThat(f.runtime().queryTimeout()).isEqualTo(2);
            assertThat(f.runtime().fetchSize()).isEqualTo(500);
        });
        runner.withPropertyValues("plsql.charset-policy=IGNORE").run(ctx -> {
            PlsqlApiFactory f = ctx.getBean(PlsqlApiFactory.class);
            assertThat(f.runtime().queryTimeout()).isZero();
            assertThat(f.runtime().fetchSize()).isEqualTo(100);
        });
    }

    /**
     * Проверяет, что срок без единицы измерения — секунды, как у {@code @Procedure(timeout)} и
     * {@code @Transactional(timeout)}: {@code plsql.query-timeout=30} — 30 секунд, а не 30 мс.
     */
    @Test
    void bareQueryTimeoutIsSeconds() {
        runner.withPropertyValues("plsql.charset-policy=IGNORE", "plsql.query-timeout=30").run(ctx ->
                assertThat(ctx.getBean(PlsqlApiFactory.class).runtime().queryTimeout()).isEqualTo(30));
    }

    /**
     * Проверяет, что размер пачки строк по умолчанию в настройках Boot тот же, что у исполнителя:
     * в {@code PlsqlProperties} он записан числом ради описания для IDE.
     */
    @Test
    void defaultFetchSizeMatchesTheExecutor() {
        assertThat(new PlsqlProperties().getFetchSize()).isEqualTo(dev.plsql.spring.call.CallExecutor.DEFAULT_FETCH_SIZE);
    }

    /**
     * Проверяет, что реестр наблюдений Micrometer из контекста (его создаёт Spring Boot Actuator)
     * доходит до фабрики, а без него (или когда их два) фабрика никуда не сообщает.
     */
    @Test
    void observationRegistryIsPickedUp() {
        io.micrometer.observation.ObservationRegistry registry = io.micrometer.observation.ObservationRegistry.create();
        runner.withPropertyValues("plsql.charset-policy=IGNORE")
                .withBean(io.micrometer.observation.ObservationRegistry.class, () -> registry)
                .run(ctx -> assertThat(ctx.getBean(PlsqlApiFactory.class).runtime().observations()).isSameAs(registry));
        runner.withPropertyValues("plsql.charset-policy=IGNORE").run(ctx ->
                assertThat(ctx.getBean(PlsqlApiFactory.class).runtime().observations())
                        .isSameAs(io.micrometer.observation.ObservationRegistry.NOOP));
        // Два реестра без @Primary: метрики необязательны, поэтому старт не падает, а фабрика
        // просто никуда не сообщает.
        runner.withPropertyValues("plsql.charset-policy=IGNORE")
                .withBean("first", io.micrometer.observation.ObservationRegistry.class, io.micrometer.observation.ObservationRegistry::create)
                .withBean("second", io.micrometer.observation.ObservationRegistry.class, io.micrometer.observation.ObservationRegistry::create)
                .run(ctx -> assertThat(ctx.getBean(PlsqlApiFactory.class).runtime().observations())
                        .isSameAs(io.micrometer.observation.ObservationRegistry.NOOP));
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
