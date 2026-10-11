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

    /**
     * Элемент {@code WHEN_T}: дата со временем и число.
     *
     * @param d {@code DATE} со временем суток
     * @param n число
     */
    record When(LocalDateTime d, Long n) {
    }

    /**
     * Запись {@code HOLDER_T}, чьи поля — таблицы, которые драйвер передаёт сам.
     *
     * @param id    номер
     * @param nums  таблица чисел
     * @param names таблица строк
     * @param days  таблица дат со временем
     */
    record Holder(Long id, List<Long> nums, List<String> names, List<LocalDateTime> days) {
    }

    /**
     * Строка {@code LAB_EMP} — элемент таблицы {@code LAB_EMP%ROWTYPE}.
     *
     * @param id    номер
     * @param name  имя
     * @param hired дата приёма
     * @param flag  признак
     */
    record Emp(Long id, String name, LocalDate hired, Long flag) {
    }

    /** Функции {@code LAB_TAB2}: пограничные случаи index-by таблиц. */
    @PlsqlApi(packageName = "LAB_TAB2")
    interface Edges {
        /**
         * Таблица дат: функция прибавляет час.
         *
         * @param vals даты со временем
         * @return те же даты на час позже
         */
        List<LocalDateTime> later(List<LocalDateTime> vals);

        /**
         * Таблица записей с полем {@code DATE}: функция прибавляет к нему час.
         *
         * @param vals записи
         * @return записи после функции
         */
        List<When> whens(List<When> vals);

        /**
         * Запись с таблицами чисел, строк и дат в полях: функция пишет в {@code id} число элементов
         * {@code nums}, прибавляет к числам единицу, строки переводит в верхний регистр, к датам
         * прибавляет час.
         *
         * @param h запись
         * @return запись после функции
         */
        Holder holderEcho(Holder h);

        /**
         * Таблица записей с ключом {@code VARCHAR2}: ключи {@code k1..kN} функция заполняет в
         * обратном порядке.
         *
         * @param n сколько элементов
         * @return элементы в порядке ключей
         */
        List<When> named(long n);

        /**
         * Таблица записей с ключом {@code VARCHAR2} на входе.
         *
         * @param vals элементы
         * @return первый ключ и число из его элемента через двоеточие
         */
        String namedFirst(List<When> vals);

        /**
         * Таблица {@code TIMESTAMP WITH LOCAL TIME ZONE}: функция прибавляет час.
         *
         * @param vals моменты
         * @return моменты на час позже
         */
        List<Instant> ltz(List<Instant> vals);

        /**
         * Таблица {@code LAB_EMP%ROWTYPE}: все строки таблицы по порядку номеров.
         *
         * @return строки
         */
        List<Emp> emps();
    }

    /** Пул к тестовой схеме. */
    static HikariDataSource ds;
    /** Реализация {@link Tables}. */
    static Tables tables;
    /** Реализация {@link Edges}. */
    static Edges edges;

    /** Создаёт пул и реализацию; при этом каждый метод сверяется со словарём. */
    @BeforeAll
    static void setUp() {
        ds = ItDatabase.pool(1, true);
        PlsqlApiFactory factory = PlsqlApiFactory.builder(ds).build();
        tables = factory.create(Tables.class);
        edges = factory.create(Edges.class);
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

    /**
     * Проверяет, что дата в index-by таблице не теряет время суток: и в таблице, которую драйвер
     * передаёт сам, и в поле таблицы записей.
     */
    @Test
    void datesKeepTheTimeOfDay() {
        assertThat(edges.later(Arrays.asList(LocalDateTime.of(2024, 1, 1, 10, 15, 30), null)))
                .containsExactly(LocalDateTime.of(2024, 1, 1, 11, 15, 30), null);
        assertThat(edges.whens(List.of(new When(LocalDateTime.of(2024, 3, 5, 23, 30), 1L))))
                .containsExactly(new When(LocalDateTime.of(2024, 3, 6, 0, 30), 1L));
    }

    /** Проверяет таблицы чисел, строк и дат как поля записи, в обе стороны. */
    @Test
    void directTablesAsRecordFields() {
        Holder back = edges.holderEcho(new Holder(0L, List.of(1L, 2L, 3L), List.of("a", "Пётр"),
                List.of(LocalDateTime.of(2024, 1, 1, 8, 0))));
        assertThat(back).isEqualTo(new Holder(3L, List.of(2L, 3L, 4L), List.of("A", "ПЁТР"),
                List.of(LocalDateTime.of(2024, 1, 1, 9, 0))));
        assertThat(edges.holderEcho(new Holder(5L, List.of(), List.of(), List.of())))
                .isEqualTo(new Holder(0L, List.of(), List.of(), List.of()));
    }

    /**
     * Проверяет таблицу записей с ключом {@code VARCHAR2}: на выходе элементы идут в порядке
     * ключей, на входе ключи — номера элементов с единицы.
     */
    @Test
    void varchar2KeyedTable() {
        assertThat(edges.named(3)).extracting(When::n).containsExactly(1L, 2L, 3L);
        assertThat(edges.namedFirst(List.of(new When(null, 7L), new When(null, 8L)))).isEqualTo("1:7");
    }

    /** Проверяет таблицу {@code TIMESTAMP WITH LOCAL TIME ZONE} в обе стороны. */
    @Test
    void localTimeZoneTable() {
        assertThat(edges.ltz(Arrays.asList(Instant.parse("2024-06-01T12:00:00.123456Z"), null)))
                .containsExactly(Instant.parse("2024-06-01T13:00:00.123456Z"), null);
    }

    /** Проверяет таблицу {@code %ROWTYPE}: её поля берутся из столбцов таблицы. */
    @Test
    void rowtypeTable() {
        List<Emp> emps = edges.emps();
        assertThat(emps).extracting(Emp::id).containsExactly(1L, 2L, 3L);
        assertThat(emps.get(2)).isEqualTo(new Emp(3L, "Smith", LocalDate.of(2022, 3, 10), 1L));
    }
}
