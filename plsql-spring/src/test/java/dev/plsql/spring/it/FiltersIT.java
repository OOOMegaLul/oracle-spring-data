package dev.plsql.spring.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.zaxxer.hikari.HikariDataSource;

import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.annotation.PlsqlApi;
import dev.plsql.spring.annotation.Restricted;
import dev.plsql.spring.annotation.SqlQuery;
import dev.plsql.spring.annotation.Unrestricted;
import dev.plsql.spring.query.Filter;
import dev.plsql.spring.query.QueryRestriction;

/**
 * Фильтры и обязательные ограничения запросов {@code @SqlQuery} на настоящей базе. Таблица
 * {@code LAB_EMP} из {@code it/schema-objects.sql}: 1 — Иванов (2020-01-15, флаг 1), 2 — Петров
 * (2021-06-01, флаг 0), 3 — Smith (2022-03-10, флаг 1). Ограничение {@code visible} пропускает
 * только сотрудников с флагом 1 — подзапросом, как «организации, доступные пользователю».
 */
class FiltersIT {

    /**
     * Строка {@code LAB_EMP}.
     *
     * @param id    номер
     * @param name  имя
     * @param hired дата приёма
     * @param flag  флаг
     */
    record Emp(long id, String name, LocalDate hired, boolean flag) {
    }

    /** Реестр сотрудников под ограничением {@code visible}. */
    @Restricted("visible")
    @PlsqlApi
    interface Emps {
        /**
         * Сотрудники по фильтру.
         *
         * @param filter фильтр
         * @return сотрудники по номерам
         */
        @SqlQuery("select id, name, hired, flag from lab_emp order by id")
        List<Emp> list(Filter filter);

        /**
         * Страница сотрудников по фильтру.
         *
         * @param filter   фильтр
         * @param pageable страница и сортировка
         * @return страница
         */
        @SqlQuery("select id, name, hired, flag from lab_emp")
        Page<Emp> page(Filter filter, Pageable pageable);

        /**
         * Сотрудники потоком.
         *
         * @param filter фильтр
         * @return поток
         */
        @SqlQuery("select id, name, hired, flag from lab_emp order by id")
        Stream<Emp> stream(Filter filter);

        /**
         * Один сотрудник по номеру — тоже под ограничением.
         *
         * @param id номер
         * @return сотрудник, если он виден
         */
        @SqlQuery("select id, name, hired, flag from lab_emp where id = :id")
        Optional<Emp> find(long id);

        /**
         * Все сотрудники, без ограничения.
         *
         * @param filter фильтр
         * @return сотрудники по номерам
         */
        @Unrestricted("для теста")
        @SqlQuery("select id, name, hired, flag from lab_emp order by id")
        List<Emp> all(Filter filter);
    }

    /** Запрос без пометки — для режима обязательных ограничений. */
    @PlsqlApi
    interface Unmarked {
        /**
         * Все сотрудники.
         *
         * @return сотрудники
         */
        @SqlQuery("select id, name, hired, flag from lab_emp")
        List<Emp> all();
    }

    /** Пул к тестовой схеме. */
    static HikariDataSource ds;
    /** Фабрика с ограничением {@code visible}. */
    static PlsqlApiFactory factory;
    /** Реализация {@link Emps}. */
    static Emps emps;

    /** Создаёт пул, фабрику с ограничением и реализацию. */
    @BeforeAll
    static void setUp() {
        ds = ItDatabase.pool(1, true);
        factory = PlsqlApiFactory.builder(ds)
                .restriction("visible", QueryRestriction.on("ID",
                        field -> Filter.inSelect(field, "select id from lab_emp where flag = ?", 1)))
                .requireRestrictions(true)
                .build();
        emps = factory.create(Emps.class);
    }

    /** Закрывает пул. */
    @AfterAll
    static void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    /**
     * Возвращает номера сотрудников.
     *
     * @param list сотрудники
     * @return номера по порядку
     */
    static List<Long> ids(List<Emp> list) {
        return list.stream().map(Emp::id).toList();
    }

    /**
     * Проверяет, что ограничение действует на каждый вид результата: список, страница с подсчётом,
     * поток и одна строка; запрос с {@code @Unrestricted} видит всех.
     */
    @Test
    void restrictionAppliesToEveryResult() {
        assertThat(ids(emps.list(Filter.none()))).containsExactly(1L, 3L);
        assertThat(ids(emps.list(null))).containsExactly(1L, 3L);
        Page<Emp> page = emps.page(Filter.none(), PageRequest.of(0, 1, Sort.by("id")));
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(ids(page.getContent())).containsExactly(1L);
        try (Stream<Emp> s = emps.stream(Filter.none())) {
            assertThat(s.map(Emp::id).toList()).containsExactly(1L, 3L);
        }
        assertThat(emps.find(1)).isPresent();
        assertThat(emps.find(2)).isEmpty();
        assertThat(ids(emps.all(Filter.none()))).containsExactly(1L, 2L, 3L);
    }

    /**
     * Проверяет фильтры пользователя поверх ограничения: поиск без регистра по кириллице, дата
     * строкой как целый день, диапазон дат с включённым последним днём, список, пустота, ИЛИ и НЕ.
     */
    @Test
    void userFiltersWorkOnTheDatabase() {
        assertThat(ids(emps.list(Filter.containsIgnoreCase("name", "иван")))).containsExactly(1L);
        assertThat(ids(emps.list(Filter.eq("hired", "2020-01-15")))).containsExactly(1L);
        assertThat(ids(emps.list(Filter.between("hired", LocalDate.of(2020, 1, 1), LocalDate.of(2022, 3, 10)))))
                .containsExactly(1L, 3L);
        assertThat(ids(emps.list(Filter.in("id", List.of(1, 2, 3))))).containsExactly(1L, 3L);
        assertThat(ids(emps.all(Filter.in("id", List.of(1, 2, 3))))).containsExactly(1L, 2L, 3L);
        assertThat(ids(emps.list(Filter.isNull("name")))).isEmpty();
        assertThat(ids(emps.list(Filter.startsWith("name", "Sm").or(Filter.eq("id", "1"))))).containsExactly(1L, 3L);
        assertThat(ids(emps.list(Filter.startsWith("name", "Sm").negate()))).containsExactly(1L);
        assertThat(ids(emps.all(Filter.eq("flag", true)))).containsExactly(1L, 3L);
        Page<Emp> page = emps.page(Filter.eq("name", "Smith"), PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    /**
     * Проверяет ошибки фильтра: поле, которого нет в запросе, и значение не того типа — до
     * выполнения запроса и с понятным текстом.
     */
    @Test
    void badFiltersAreRejected() {
        assertThatThrownBy(() -> emps.list(Filter.eq("salary", 1)))
                .isInstanceOf(InvalidDataAccessApiUsageException.class).hasMessageContaining("cannot filter by 'salary'");
        assertThatThrownBy(() -> emps.list(Filter.eq("id", "one")))
                .isInstanceOf(InvalidDataAccessApiUsageException.class).hasMessageContaining("'one' is not a number");
    }

    /** Проверяет режим обязательных ограничений: запрос без пометки не даёт создать реализацию. */
    @Test
    void requiredRestrictionsStopAnUnmarkedQuery() {
        assertThatThrownBy(() -> factory.create(Unmarked.class)).hasMessageContaining("restrictions are required");
    }
}
