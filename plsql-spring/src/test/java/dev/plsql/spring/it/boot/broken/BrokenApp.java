package dev.plsql.spring.it.boot.broken;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.plsql.spring.annotation.PlsqlApi;

/**
 * Приложение, чей интерфейс не совпадает с базой: его контекст не должен запуститься
 * (см. {@code SpringBootIT.mismatchStopsTheContext}).
 *
 * <p>Лежит в отдельном пакете: {@link AutoConfigurationPackage} делает сканируемым только его,
 * так что интерфейс {@link Renamed} не попадает в рабочее приложение {@code BootApp}.
 */
@Configuration(proxyBeanMethods = false)
@AutoConfigurationPackage
public class BrokenApp {

    /** Задаётся тестом: пул соединений к схеме {@code PLSQL_IT}. */
    public static DataSource dataSource;

    /**
     * Отдаёт пул теста как бин {@code DataSource}. Пул принадлежит тесту, а не этому контексту:
     * при остановке контекста его нельзя закрывать ({@code destroyMethod = ""} отключает
     * автоматический вызов {@code close()}).
     *
     * @return пул из статического поля {@code dataSource}
     */
    @Bean(destroyMethod = "")
    DataSource dataSource() {
        return dataSource;
    }

    /**
     * Интерфейс к {@code LAB_PKG} с параметром, имя которого не подходит ни к одному аргументу
     * процедуры.
     */
    @PlsqlApi(packageName = "LAB_PKG")
    public interface Renamed {
        /**
         * Должен вызывать {@code LAB_PKG.EMPS}, но аргумент там называется {@code P_MIN_ID}, а имя
         * параметра {@code minimum} с ним не сопоставляется (и {@code @Arg} нет), поэтому создание
         * бина падает с сообщением «'minimum' has no matching argument».
         *
         * @param minimum нижняя граница {@code id}; по имени не совпадает с {@code P_MIN_ID}
         */
        void emps(long minimum);
    }
}
