package dev.plsql.spring.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.dao.InvalidDataAccessApiUsageException;

import dev.plsql.spring.query.Filter;

/**
 * Проверяет, как фильтр и ограничения превращаются в обёртку запроса ({@link QueryFilters}) без
 * базы: колонки описывает мок {@link ResultSetMetaData}.
 */
class QueryFiltersTest {

    /**
     * Строка реестра: колонки {@code ID}, {@code NUM}, {@code STATUS}, {@code CREATED},
     * {@code TITLE}; колонки {@code ORG_ID} в record нет.
     *
     * @param id      номер
     * @param num     номер документа
     * @param status  состояние
     * @param created дата создания
     * @param title   название ({@code NVARCHAR2})
     */
    record Order(Long id, String num, String status, LocalDate created, String title) {
    }

    /** Базовый запрос с одним параметром. */
    static final String BASE = "select * from orders_v where tenant = ?";

    /** Колонки запроса для строки {@link Order}. */
    QueryFilters.Columns cols;
    /** Проверка кодировки выключена. */
    final CharsetGuard none = CharsetGuard.forDatabase("AL32UTF8", CharsetGuard.Policy.IGNORE);

    /**
     * Описывает колонки: {@code ID NUMBER}, {@code NUM VARCHAR2}, {@code STATUS VARCHAR2},
     * {@code CREATED DATE}, {@code ORG_ID NUMBER}, {@code TITLE NVARCHAR2}.
     *
     * @return мок описания колонок
     * @throws SQLException формально: так объявлены методы JDBC
     */
    static ResultSetMetaData metadata() throws SQLException {
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        String[] names = {"ID", "NUM", "STATUS", "CREATED", "ORG_ID", "TITLE"};
        int[] types = {Types.NUMERIC, Types.VARCHAR, Types.VARCHAR, Types.TIMESTAMP, Types.NUMERIC, Types.NVARCHAR};
        String[] typeNames = {"NUMBER", "VARCHAR2", "VARCHAR2", "DATE", "NUMBER", "NVARCHAR2"};
        when(md.getColumnCount()).thenReturn(names.length);
        for (int i = 0; i < names.length; i++) {
            when(md.getColumnLabel(i + 1)).thenReturn(names[i]);
            when(md.getColumnName(i + 1)).thenReturn(names[i]);
            when(md.getColumnType(i + 1)).thenReturn(types[i]);
            when(md.getColumnTypeName(i + 1)).thenReturn(typeNames[i]);
        }
        return md;
    }

    /**
     * Готовит колонки для строки {@link Order}.
     *
     * @throws SQLException формально: так объявлены методы JDBC
     */
    @BeforeEach
    void setUp() throws SQLException {
        cols = QueryFilters.Columns.of(metadata(), ResolvableType.forClass(Order.class), new String[0]);
    }

    /**
     * Применяет фильтр пользователя к базовому запросу.
     *
     * @param f фильтр
     * @return текст и значения
     */
    QueryFilters.Rendered user(Filter f) {
        return QueryFilters.apply(BASE, new Object[]{7}, cols, f, List.of(), none);
    }

    /**
     * Проверяет обёртку: условия по колонкам из описания (в кавычках), значения только через
     * {@code ?} после значения самого запроса, {@code LocalDate} для колонки-даты — весь день,
     * строка {@code "2024-01-31"} — тоже дата, {@code %} и {@code _} в поиске экранированы, поиск
     * без регистра — через {@code UPPER}.
     */
    @Test
    void conditionsBecomeBindsOnColumnsOfTheQuery() {
        QueryFilters.Rendered r = user(Filter.allOf(
                Filter.eq("status", "NEW"),
                Filter.between("created", LocalDate.of(2024, 1, 1), "2024-01-31"),
                Filter.containsIgnoreCase("num", "5%_"),
                Filter.isNull("title")));

        assertThat(r.sql()).isEqualTo("SELECT * FROM (" + BASE + "\n) q_ WHERE (q_.\"STATUS\" = ?"
                + " AND (q_.\"CREATED\" >= ? AND q_.\"CREATED\" < ?)"
                + " AND UPPER(q_.\"NUM\") LIKE UPPER(?) ESCAPE '\\'"
                + " AND q_.\"TITLE\" IS NULL)");
        assertThat(r.values()).containsExactly(7, "NEW", Timestamp.valueOf("2024-01-01 00:00:00"),
                Timestamp.valueOf("2024-02-01 00:00:00"), "%5\\%\\_%");
    }

    /**
     * Проверяет белый список: фильтр пользователя отбирает только по колонкам, которые попадают в
     * свойства record (по имени свойства или колонки), а ограничение из кода приложения — по любой
     * колонке запроса.
     */
    @Test
    void usersFilterOnlyRecordColumnsRestrictionsAnyColumn() {
        assertThatThrownBy(() -> user(Filter.eq("orgId", 1)))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("cannot filter by 'orgId'").hasMessageContaining("status");
        assertThat(user(Filter.eq("STATUS", "A")).sql()).endsWith("WHERE q_.\"STATUS\" = ?");

        QueryFilters.Rendered r = QueryFilters.apply(BASE, new Object[]{7}, cols, Filter.eq("status", "A"),
                List.of(Filter.inSelect("ORG_ID", "select org_id from user_orgs where user_name = ?", "bob")), none);
        assertThat(r.sql()).endsWith("WHERE q_.\"ORG_ID\" IN (select org_id from user_orgs where user_name = ?\n)"
                + " AND q_.\"STATUS\" = ?");
        assertThat(r.values()).containsExactly(7, "bob", "A");
    }

    /**
     * Проверяет приведение значений к типу колонки: строка для числа разбирается, не-число — ошибка
     * с именем поля; поиск текста по числу — ошибка; дата со временем сравнивается как момент.
     */
    @Test
    void valuesFollowTheColumnTypes() {
        assertThat(user(Filter.eq("id", "12")).values()).containsExactly(7, new BigDecimal("12"));
        assertThatThrownBy(() -> user(Filter.eq("id", "abc"))).hasMessageContaining("'abc' is not a number");
        assertThatThrownBy(() -> user(Filter.contains("id", "1"))).hasMessageContaining("not text");
        assertThatThrownBy(() -> user(Filter.eq("created", "31.01.2024"))).hasMessageContaining("is not a date");
        QueryFilters.Rendered at = user(Filter.gt("created", "2024-01-31T10:15"));
        assertThat(at.sql()).endsWith("WHERE q_.\"CREATED\" > ?");
        assertThat(at.values()).containsExactly(7, Timestamp.valueOf("2024-01-31 10:15:00"));
        assertThat(user(Filter.le("created", LocalDate.of(2024, 1, 31))).values())
                .containsExactly(7, Timestamp.valueOf("2024-02-01 00:00:00"));
    }

    /**
     * Проверяет {@code IN} и пустые группы: список длиннее 1000 делится через {@code OR}, пустой
     * не подходит ни к одной строке, даты без времени сравниваются с днём; пустой фильтр не
     * оборачивает запрос, пустое {@code ИЛИ} и отрицание пустого фильтра не пропускают ничего.
     */
    @Test
    void inListsAndEmptyGroups() {
        List<Integer> many = IntStream.rangeClosed(1, 1001).boxed().toList();
        QueryFilters.Rendered big = user(Filter.in("id", many));
        assertThat(big.sql()).contains("WHERE (q_.\"ID\" IN (?").contains(") OR q_.\"ID\" IN (?))");
        assertThat(big.values()).hasSize(1002);
        assertThat(user(Filter.in("id", List.of())).sql()).endsWith("WHERE 1 = 0");
        assertThat(user(Filter.in("created", List.of(LocalDate.of(2024, 1, 1)))).sql())
                .endsWith("WHERE TRUNC(q_.\"CREATED\") IN (?)");

        QueryFilters.Rendered unchanged = user(Filter.none());
        assertThat(unchanged.sql()).isEqualTo(BASE);
        assertThat(unchanged.values()).containsExactly(7);
        assertThat(user(Filter.anyOf()).sql()).endsWith("WHERE 1 = 0");
        assertThat(user(Filter.none().negate()).sql()).endsWith("WHERE 1 = 0");
        assertThat(user(Filter.eq("status", "A").or(Filter.isNotNull("num")).negate()).sql())
                .endsWith("WHERE NOT ((q_.\"STATUS\" = ? OR q_.\"NUM\" IS NOT NULL))");
    }

    /**
     * Проверяет условие на SQL: поля в фигурных скобках становятся колонками (и проверяются),
     * значения идут через {@code ?}.
     */
    @Test
    void sqlExpressionsNameFieldsInBraces() {
        QueryFilters.Rendered r = user(Filter.sql("{status} <> {num} or {id} > ?", 5));
        assertThat(r.sql()).endsWith("WHERE (q_.\"STATUS\" <> q_.\"NUM\" or q_.\"ID\" > ?)");
        assertThat(r.values()).containsExactly(7, 5);
        assertThatThrownBy(() -> user(Filter.sql("{orgId} = 1"))).hasMessageContaining("cannot filter by 'orgId'");
    }

    /**
     * Проверяет кодировку базы: текст для обычной колонки проверяется (иероглифа нет в
     * CL8MSWIN1251), для национальной ({@code NVARCHAR2}) — нет.
     */
    @Test
    void textIsCheckedAgainstTheDatabaseCharset() {
        CharsetGuard strict = CharsetGuard.forDatabase("CL8MSWIN1251", CharsetGuard.Policy.FAIL);
        assertThatThrownBy(() -> QueryFilters.apply(BASE, new Object[]{7}, cols, Filter.eq("status", "中"),
                List.of(), strict)).isInstanceOf(CharsetGuard.UnrepresentableCharacterException.class);
        assertThat(QueryFilters.apply(BASE, new Object[]{7}, cols, Filter.eq("title", "中"), List.of(), strict)
                .values()).containsExactly(7, "中");
    }

    /**
     * Проверяет {@code @SqlQuery(filterable)}: список сужает поля фильтра; у строки-{@code Map}
     * без списка можно отбирать по любой колонке.
     *
     * @throws SQLException формально: так объявлены методы JDBC
     */
    @Test
    void filterableNarrowsAndMapRowsAllowEveryColumn() throws SQLException {
        QueryFilters.Columns narrowed = QueryFilters.Columns.of(metadata(), ResolvableType.forClass(Order.class),
                new String[]{"num"});
        assertThat(QueryFilters.apply(BASE, new Object[0], narrowed, Filter.eq("num", "1"), List.of(), none).sql())
                .endsWith("WHERE q_.\"NUM\" = ?");
        assertThatThrownBy(() -> QueryFilters.apply(BASE, new Object[0], narrowed, Filter.eq("status", "A"), List.of(), none))
                .hasMessageContaining("cannot filter by 'status'");

        QueryFilters.Columns maps = QueryFilters.Columns.of(metadata(), ResolvableType.forClass(Map.class), new String[0]);
        assertThat(QueryFilters.apply(BASE, new Object[0], maps, Filter.eq("orgId", 1), List.of(), none).sql())
                .endsWith("WHERE q_.\"ORG_ID\" = ?");
    }
}
