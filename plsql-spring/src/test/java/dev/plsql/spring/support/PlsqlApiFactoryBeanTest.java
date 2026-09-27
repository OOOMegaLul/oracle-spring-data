package dev.plsql.spring.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import dev.plsql.spring.PlsqlApiFactory;
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
}
