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

/**
 * Обычный Spring без Boot: {@code @EnablePlsqlApis} с двумя интерфейсами и без бина
 * {@code PlsqlApiFactory}. Пакеты для сканирования не заданы, поэтому сканируется пакет этого
 * класса.
 */
@Configuration(proxyBeanMethods = false)
@EnablePlsqlApis
public class EnableApp {

    /**
     * Мок {@code DataSource} с глубокими заглушками ({@code RETURNS_DEEP_STUBS}). Кодировка базы
     * не задана, поэтому общая фабрика при создании читает {@code NLS_CHARACTERSET} запросом, и
     * вся цепочка «соединение → запрос → строки» должна отвечать моками. Строк в ответе нет,
     * кодировка остаётся неизвестной, и проверка символов выключается.
     *
     * @return мок источника соединений
     */
    @Bean
    DataSource dataSource() {
        return mock(DataSource.class, RETURNS_DEEP_STUBS);
    }

    /**
     * Сигнатуры для обоих интерфейсов: функция {@code PKG.NEXT(NTENANT)} и процедура
     * {@code PKG.TOUCH(NTENANT)}. Фабрика берёт этот бин вместо чтения словаря базы.
     *
     * @return источник сигнатур из фикстуры
     */
    @Bean
    SignatureSource signatures() {
        return Signatures.source(
                Signatures.func("PKG", "NEXT", "NUMBER").in("NTENANT", "NUMBER").build(),
                Signatures.proc("PKG", "TOUCH").in("NTENANT", "NUMBER").build());
    }

    /** Первый интерфейс пакета {@code PKG}. */
    @PlsqlApi(packageName = "PKG")
    public interface First {
        /**
         * Функция {@code PKG.NEXT(NTENANT IN NUMBER) RETURN NUMBER}.
         *
         * @param tenant идёт в {@code NTENANT}
         * @return результат функции
         */
        long next(long tenant);
    }

    /** Второй интерфейс того же пакета; должен получить ту же фабрику, что и {@link First}. */
    @PlsqlApi(packageName = "PKG")
    public interface Second {
        /**
         * Процедура {@code PKG.TOUCH(NTENANT IN NUMBER)}.
         *
         * @param tenant идёт в {@code NTENANT}
         */
        void touch(long tenant);
    }
}
