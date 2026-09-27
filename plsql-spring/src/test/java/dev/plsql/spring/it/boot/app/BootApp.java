package dev.plsql.spring.it.boot.app;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.plsql.spring.annotation.PlsqlApi;
import dev.plsql.spring.annotation.SqlQuery;
import dev.plsql.spring.call.ArgumentDefaults;

/**
 * Минимальное приложение Spring Boot для {@code SpringBootIT}: его пакет сканируется в поисках
 * интерфейсов {@link PlsqlApi}.
 *
 * <p>{@link AutoConfigurationPackage} регистрирует пакет этого класса как пакет приложения (то же
 * делает {@code @SpringBootApplication}), и автоконфигурация библиотеки ищет интерфейсы именно
 * там. Сломанное приложение {@code BrokenApp} лежит в соседнем пакете, поэтому его интерфейс сюда
 * не попадает.
 */
@Configuration(proxyBeanMethods = false)
@AutoConfigurationPackage
public class BootApp {

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
     * Бин {@link ArgumentDefaults}: значение аргумента {@code P_MIN_ID}, которого нет среди
     * параметров Java-метода, всегда равно 2. Автоконфигурация передаёт этот бин фабрике.
     *
     * @return значения аргументов по их именам
     */
    @Bean
    ArgumentDefaults defaults() {
        Supplier<Object> minId = () -> 2;
        return ArgumentDefaults.byName(Map.of("P_MIN_ID", minId));
    }

    /**
     * Строка {@code LAB_EMP} для этого приложения (своя копия, независимая от {@code LabApi.Emp}).
     *
     * @param id    колонка {@code ID}
     * @param name  колонка {@code NAME}
     * @param hired колонка {@code HIRED} (дата приёма)
     * @param flag  колонка {@code FLAG NUMBER(1)}: ненулевое значение даёт {@code true}
     */
    public record Emp(long id, String name, LocalDate hired, boolean flag) {
    }

    /**
     * PL/SQL API приложения к пакету {@code LAB_PKG}; автоконфигурация находит его в пакете
     * приложения и регистрирует как бин.
     */
    @PlsqlApi(packageName = "LAB_PKG")
    public interface Employees {

        /**
         * Вызывает {@code LAB_PKG.EMPS(P_MIN_ID, P_CUR OUT SYS_REFCURSOR)} без параметров Java:
         * {@code P_MIN_ID} приходит из {@link ArgumentDefaults} (бин {@link BootApp#defaults()}),
         * а курсор {@code P_CUR} становится результатом.
         *
         * @return сотрудники с {@code id >= 2}
         */
        List<Emp> emps();

        /**
         * SQL-запрос: сотрудник по {@code id}.
         *
         * @param id значение параметра {@code :id}
         * @return строка или пустой {@code Optional}, если её нет
         */
        @SqlQuery("select id, name, hired, flag from lab_emp where id = :id")
        Optional<Emp> find(long id);
    }
}
