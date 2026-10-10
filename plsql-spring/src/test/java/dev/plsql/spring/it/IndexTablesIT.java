package dev.plsql.spring.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.annotation.PlsqlApi;

/**
 * Index-by таблицы, которые драйвер Oracle сам не передаёт, на настоящей базе: таблица записей
 * (с {@code BOOLEAN}, {@code DATE}, {@code TIMESTAMP}, {@code TIMESTAMP WITH TIME ZONE} и вложенной
 * записью) в обе стороны, таблицы {@code TIMESTAMP}, {@code BOOLEAN} и {@code DATE}, таблица как
 * поле записи. Пакет {@code LAB_TAB} из {@code it/schema-objects.sql}.
 */
class IndexTablesIT {

    /**
     * Вложенная запись {@code ADDR_T}.
     *
     * @param city город
     * @param zip  индекс
     */
    record Addr(String city, Long zip) {
    }

    /**
     * Элемент таблицы {@code ROW_TAB}.
     *
     * @param id    номер
     * @param name  имя
     * @param flag  {@code BOOLEAN}
     * @param hired {@code DATE}
     * @param stamp {@code TIMESTAMP} с наносекундами
     * @param zoned {@code TIMESTAMP WITH TIME ZONE} как момент времени
     * @param addr  вложенная запись
     */
    record Row(Long id, String name, Boolean flag, LocalDate hired, LocalDateTime stamp, Instant zoned, Addr addr) {
    }

    /**
     * Запись {@code ORDER_T} с таблицей в поле.
     *
     * @param id    номер
     * @param lines строки заказа — index-by таблица записей
     */
    record Order(Long id, List<Row> lines) {
    }

    /** Функции и процедуры {@code LAB_TAB}. */
    @PlsqlApi(packageName = "LAB_TAB")
    interface Tables {
        /**
         * Таблица записей из функции; ключи разреженные: 10, 20, 30.
         *
         * @param n сколько записей
         * @return записи по порядку ключей
         */
        List<Row> rowsOut(long n);

        /**
         * Таблица записей туда и обратно: функция прибавляет к {@code id} единицу.
         *
         * @param rows записи
         * @return те же записи
         */
        List<Row> rowsEcho(List<Row> rows);

        /**
         * Таблица записей как {@code IN OUT}: процедура переводит имена в верхний регистр.
         *
         * @param rows записи
         * @return записи после процедуры
         */
        List<Row> rowsInout(List<Row> rows);

        /**
         * Таблица {@code TIMESTAMP}: функция прибавляет сутки.
         *
         * @param vals моменты
         * @return моменты на сутки позже
         */
        List<LocalDateTime> stamps(List<LocalDateTime> vals);

        /**
         * Таблица {@code BOOLEAN}: функция отрицает каждое значение.
         *
         * @param vals значения
         * @return отрицания
         */
        List<Boolean> flags(List<Boolean> vals);

        /**
         * Таблица {@code DATE}: драйвер передаёт её сам.
         *
         * @param vals даты
         * @return даты на день позже
         */
        List<LocalDate> dates(List<LocalDate> vals);

        /**
         * Запись с таблицей записей в поле: функция прибавляет к {@code id} число строк.
         *
         * @param o заказ
         * @return заказ после функции
         */
        Order orderEcho(Order o);
    }

    /** Пул к тестовой схеме. */
    static HikariDataSource ds;
    /** Реализация {@link Tables}. */
    static Tables tables;

    /** Создаёт пул и реализацию; при этом каждый метод сверяется со словарём. */
    @BeforeAll
    static void setUp() {
        ds = ItDatabase.pool(1, true);
        tables = PlsqlApiFactory.builder(ds).build().create(Tables.class);
    }

    /** Закрывает пул. */
    @AfterAll
    static void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    /**
     * Проверяет таблицу записей на выходе: три записи с разреженными ключами приходят по порядку,
     * со всеми полями, включая доли секунды {@code TIMESTAMP}, момент с поясом и вложенную запись.
     */
    @Test
    void recordTableOut() {
        List<Row> rows = tables.rowsOut(3);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(1)).isEqualTo(new Row(2L, "row 2", true, LocalDate.of(2024, 1, 3),
                // TIMESTAMP без точности в PL/SQL — 6 знаков: .123456789 хранится как .123457.
                LocalDateTime.of(2024, 1, 1, 12, 0, 0, 123_457_000), Instant.parse("2024-01-01T07:00:00.500Z"),
                new Addr("city 2", 102L)));
        assertThat(rows).extracting(Row::flag).containsExactly(false, true, false);
        assertThat(tables.rowsOut(0)).isEmpty();
    }

    /**
     * Проверяет таблицу записей в обе стороны и как {@code IN OUT}: значения, {@code NULL} и
     * кириллица доходят до PL/SQL и обратно; пустой список — пустая таблица.
     */
    @Test
    void recordTableInAndOut() {
        Row full = new Row(1L, "Пётр", true, LocalDate.of(2020, 2, 29), LocalDateTime.of(2021, 3, 4, 5, 6, 7, 890_000_000),
                Instant.parse("2022-01-01T00:00:00Z"), new Addr("Омск", 644000L));
        Row empty = new Row(5L, null, null, null, null, null, new Addr(null, null));
        assertThat(tables.rowsEcho(List.of(full, empty))).containsExactly(
                new Row(2L, "Пётр", true, LocalDate.of(2020, 2, 29), LocalDateTime.of(2021, 3, 4, 5, 6, 7, 890_000_000),
                        Instant.parse("2022-01-01T00:00:00Z"), new Addr("Омск", 644000L)),
                new Row(6L, null, null, null, null, null, new Addr(null, null)));
        assertThat(tables.rowsEcho(List.of())).isEmpty();
        assertThat(tables.rowsInout(List.of(full))).extracting(Row::name).containsExactly("ПЁТР");
    }

    /** Проверяет таблицы {@code TIMESTAMP}, {@code BOOLEAN} и {@code DATE} с {@code NULL} внутри. */
    @Test
    void scalarTables() {
        assertThat(tables.stamps(Arrays.asList(LocalDateTime.of(2024, 2, 28, 23, 59, 59, 1_000), null)))
                .containsExactly(LocalDateTime.of(2024, 2, 29, 23, 59, 59, 1_000), null);
        assertThat(tables.flags(Arrays.asList(true, false, null))).containsExactly(false, true, null);
        assertThat(tables.dates(Arrays.asList(LocalDate.of(2024, 12, 31), null)))
                .containsExactly(LocalDate.of(2025, 1, 1), null);
    }

    /** Проверяет таблицу записей как поле записи, в обе стороны. */
    @Test
    void tableAsRecordField() {
        Row line = new Row(1L, "a", false, null, null, null, new Addr("x", 1L));
        Order back = tables.orderEcho(new Order(10L, List.of(line, line)));
        assertThat(back.id()).isEqualTo(12L);
        assertThat(back.lines()).containsExactly(line, line);
    }
}
