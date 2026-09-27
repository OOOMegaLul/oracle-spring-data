package dev.plsql.spring.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.boot.PlsqlProperties;
import dev.plsql.spring.call.ArgumentDefaults;
import dev.plsql.spring.meta.SignatureSource;
import dev.plsql.spring.session.SessionContextDataSource;
import dev.plsql.spring.support.enablefixture.EnableApp;
import dev.plsql.spring.test.Signatures;

/**
 * Выбор {@link PlsqlApiFactory} в {@link PlsqlApiFactoryBean}, когда в приложении две базы.
 *
 * <p>Раньше общая фабрика первой базы регистрировалась обычным бином {@code PlsqlApiFactory},
 * становилась «единственной» в контексте, и интерфейсы с {@code dataSourceRef} второй базы
 * молча уходили в первую. Здесь контекст — голая фабрика бинов с двумя {@code DataSource}.
 */
class PlsqlApiFactoryBeanTest {

    /** Фабрика бинов вместо контекста: регистрация и поиск бинов без сканирования пакетов. */
    DefaultListableBeanFactory beans;

    /** Основная база, бин {@code dataSource}. */
    DataSource main;

    /** Вторая база, бин {@code reportsDataSource}. */
    DataSource reports;

    /**
     * Регистрирует две базы и готовые сигнатуры: строить фабрику можно без обращения к словарю.
     */
    @BeforeEach
    void setUp() {
        beans = new DefaultListableBeanFactory();
        main = mock(DataSource.class, RETURNS_DEEP_STUBS);
        reports = mock(DataSource.class, RETURNS_DEEP_STUBS);
        beans.registerSingleton("dataSource", main);
        beans.registerSingleton("reportsDataSource", reports);
        beans.registerSingleton("signatures", signatures());
    }

    /**
     * Возвращает сигнатуры для интерфейса {@link EnableApp.First}.
     *
     * @return источник сигнатур без базы
     */
    static SignatureSource signatures() {
        return Signatures.source(Signatures.func("PKG", "NEXT", "NUMBER").in("NTENANT", "NUMBER").build());
    }

    /**
     * Создаёт фабричный бин интерфейса {@link EnableApp.First} для указанной базы.
     *
     * @param dataSourceRef имя бина {@code DataSource}
     * @return фабричный бин, ещё не создавший реализацию
     */
    PlsqlApiFactoryBean<EnableApp.First> bean(String dataSourceRef) {
        PlsqlApiFactoryBean<EnableApp.First> b = new PlsqlApiFactoryBean<>(EnableApp.First.class);
        b.setDataSourceRef(dataSourceRef);
        b.setBeanFactory(beans);
        return b;
    }

    /**
     * Проверяет, что без объявленной фабрики у каждой базы своя общая фабрика, одна на все
     * интерфейсы этой базы, и что общие фабрики не видны как бины {@code PlsqlApiFactory}.
     */
    @Test
    void eachDataSourceGetsItsOwnSharedFactory() {
        PlsqlApiFactory first = bean("dataSource").factory();
        PlsqlApiFactory second = bean("reportsDataSource").factory();

        assertThat(first.runtime().dataSource()).isSameAs(main);
        assertThat(second.runtime().dataSource()).isSameAs(reports);
        assertThat(bean("dataSource").factory()).isSameAs(first);
        assertThat(beans.getBeansOfType(PlsqlApiFactory.class)).isEmpty();
    }

    /**
     * Проверяет, что объявленная фабрика берётся для своей базы, в том числе когда она работает
     * через обёртку {@link SessionContextDataSource}, а для другой базы — нет.
     */
    @Test
    void declaredFactoryServesOnlyItsOwnDataSource() {
        PlsqlApiFactory declared = PlsqlApiFactory.builder(new SessionContextDataSource(main, () -> "USER_A"))
                .signatureSource(signatures()).databaseCharset("AL32UTF8").build();
        beans.registerSingleton("plsqlApiFactory", declared);
        beans.registerSingleton("pool", main);

        assertThat(bean("dataSource").factory()).isSameAs(declared);
        assertThat(bean("pool").factory()).isSameAs(declared);
        PlsqlApiFactory other = bean("reportsDataSource").factory();
        assertThat(other).isNotSameAs(declared);
        assertThat(other.runtime().dataSource()).isSameAs(reports);
    }
    /**
     * Проверяет, что две равноправные фабрики на одной базе — ошибка с советом указать
     * {@code factoryRef}, а не молчаливая сборка третьей. Для базы, с которой ни одна не работает,
     * по-прежнему строится общая.
     */
    @Test
    void twoEqualFactoriesAreAnError() {
        beans.registerSingleton("f1", PlsqlApiFactory.builder(main).signatureSource(signatures()).databaseCharset("AL32UTF8").build());
        beans.registerSingleton("f2", PlsqlApiFactory.builder(main).signatureSource(signatures()).databaseCharset("AL32UTF8").build());

        assertThatThrownBy(() -> bean("dataSource").factory())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("2 PlsqlApiFactory beans")
                .hasMessageContaining("factoryRef");
        assertThat(bean("reportsDataSource").factory().runtime().dataSource()).isSameAs(reports);
    }

    /**
     * Проверяет, что два равноправных бина {@link ArgumentDefaults} — ошибка, а не молчаливый
     * отказ от обоих.
     */
    @Test
    void twoArgumentDefaultsAreAnError() {
        beans.registerSingleton("d1", ArgumentDefaults.none());
        beans.registerSingleton("d2", ArgumentDefaults.none());

        assertThatThrownBy(() -> bean("dataSource").factory()).isInstanceOf(NoUniqueBeanDefinitionException.class);
    }

    /**
     * Проверяет, что настройки {@code plsql.*} Spring Boot доходят и до общей фабрики, которую
     * строит сам фабричный бин (раньше она их не видела).
     */
    @Test
    void bootSettingsReachTheSharedFactory() {
        PlsqlProperties props = new PlsqlProperties();
        props.setRetryDiscardedState(false);
        props.setDatabaseCharset("CL8MSWIN1251");
        beans.registerSingleton("plsqlProperties", props);

        PlsqlApiFactory f = bean("reportsDataSource").factory();

        assertThat(f.runtime().retryDiscardedState()).isFalse();
        assertThat(f.runtime().charsetGuard().isActive()).isTrue();
    }
    /**
     * Проверяет, что без явного {@code dataSourceRef} берётся фабрика бина {@code dataSource}, даже
     * если рядом есть фабрика второй базы (раньше это была ошибка «две фабрики»).
     */
    @Test
    void unspecifiedRefPicksTheFactoryOfTheDefaultDataSource() {
        PlsqlApiFactory onMain = PlsqlApiFactory.builder(main).signatureSource(signatures()).databaseCharset("AL32UTF8").build();
        beans.registerSingleton("mainFactory", onMain);
        beans.registerSingleton("reportsFactory", PlsqlApiFactory.builder(reports).signatureSource(signatures())
                .databaseCharset("AL32UTF8").build());

        assertThat(bean("").factory()).isSameAs(onMain);
    }

    /**
     * Проверяет, что явно указанный {@code dataSourceRef = "dataSource"} не уходит в фабрику другой
     * базы, даже если она в контексте единственная: строится общая фабрика на нужной базе.
     */
    @Test
    void explicitRefNeverFallsBackToAnotherDatabase() {
        beans.registerSingleton("reportsFactory", PlsqlApiFactory.builder(reports).signatureSource(signatures())
                .databaseCharset("AL32UTF8").build());

        assertThat(bean("dataSource").factory().runtime().dataSource()).isSameAs(main);
    }

    /**
     * Проверяет, что без явного {@code dataSourceRef} единственная фабрика контекста берётся, даже
     * если собрана на {@code DataSource}, который не бин (как и в 0.1.0).
     */
    @Test
    void unspecifiedRefFallsBackToTheOnlyFactory() {
        PlsqlApiFactory custom = PlsqlApiFactory.builder(mock(DataSource.class, RETURNS_DEEP_STUBS))
                .signatureSource(signatures()).databaseCharset("AL32UTF8").build();
        beans.registerSingleton("customFactory", custom);

        assertThat(bean("").factory()).isSameAs(custom);
    }
}
