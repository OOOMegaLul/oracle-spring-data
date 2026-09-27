package dev.plsql.spring.boot.fixture;

import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.plsql.spring.annotation.PlsqlApi;
import dev.plsql.spring.meta.SignatureSource;
import dev.plsql.spring.test.Signatures;

/**
 * Приложение Boot без базы данных: сигнатуры берутся из фикстуры. {@code @AutoConfigurationPackage}
 * делает пакет этого класса пакетом приложения, и автоконфигурация ищет в нём интерфейсы
 * {@code @PlsqlApi}, как Spring Boot ищет репозитории Spring Data.
 */
@Configuration(proxyBeanMethods = false)
@AutoConfigurationPackage
public class FixtureApp {

    /**
     * Мок {@code DataSource}: автоконфигурации нужен ровно один источник соединений. Настоящих
     * обращений к нему в тестах нет.
     *
     * @return мок источника соединений
     */
    @Bean
    DataSource dataSource() {
        return mock(DataSource.class);
    }

    /**
     * Сигнатура единственной функции {@code PKG.NEXT(NTENANT IN NUMBER) RETURN NUMBER};
     * автоконфигурация берёт этот бин вместо чтения словаря базы. Метод публичный: тест
     * собирает на нём и собственную фабрику.
     *
     * @return источник сигнатур из фикстуры
     */
    @Bean
    public SignatureSource signatures() {
        return Signatures.source(Signatures.func("PKG", "NEXT", "NUMBER").in("NTENANT", "NUMBER").build());
    }

    /** Интерфейс пакета {@code PKG}, который автоконфигурация должна найти и зарегистрировать. */
    @PlsqlApi(packageName = "PKG")
    public interface Sequences {
        /**
         * Функция {@code PKG.NEXT(NTENANT IN NUMBER) RETURN NUMBER}.
         *
         * @param tenant идёт в {@code NTENANT}
         * @return результат функции
         */
        long next(long tenant);
    }
}
