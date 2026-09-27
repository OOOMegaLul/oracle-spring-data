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

/**
 * Автоконфигурация Spring Boot на настоящей базе: бины по интерфейсам и остановка контекста при
 * расхождении с базой.
 *
 * <p>Контекст поднимается через {@link ApplicationContextRunner} — лёгкий запуск контекста Spring
 * без веб-сервера и без {@code @SpringBootTest}. В контекст попадают только
 * {@link PlsqlAutoConfiguration} и конфигурация тестового приложения ({@link BootApp} или
 * {@link BrokenApp}). Пул соединений создаёт сам тест и передаёт приложениям через их статические
 * поля.
 */
class SpringBootIT {

    /** Общий пул для обоих приложений; принадлежит тесту и закрывается в {@link #tearDown()}. */
    static HikariDataSource ds;

    /** Создаёт пул из двух соединений к тестовой схеме и передаёт его обоим тестовым приложениям. */
    @BeforeAll
    static void setUp() {
        ds = ItDatabase.pool(2, true);
        BootApp.dataSource = ds;
        BrokenApp.dataSource = ds;
    }

    /** Закрывает пул, если он был создан. */
    @AfterAll
    static void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    /**
     * Проверяет, что автоконфигурация сама находит интерфейс {@code BootApp.Employees} с
     * {@code @PlsqlApi} в пакете приложения и делает из него бин, а вызовы доходят до базы:
     * {@code emps()} получает {@code P_MIN_ID = 2} из бина {@code ArgumentDefaults} и возвращает
     * строки с {@code id} 2 и 3, запрос {@code find(1)} находит сотрудника «Иванов». Кроме того, в
     * контексте ровно одна {@link PlsqlApiFactory}.
     *
     * <p>Так приложение на Spring Boot получает PL/SQL API без явного {@code @Enable...}, как
     * получает репозитории Spring Data.
     */
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

    /**
     * Проверяет, что расхождение интерфейса с базой останавливает запуск контекста: у метода
     * {@code BrokenApp.Renamed.emps(long minimum)} параметр не подходит ни к одному аргументу
     * {@code LAB_PKG.EMPS}, и корневая причина ошибки называет и метод, и параметр.
     *
     * <p>Приложение не должно стартовать с интерфейсом, который упадёт при первом вызове, а
     * сообщение должно сразу показывать, что чинить.
     */
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
