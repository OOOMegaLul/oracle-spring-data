package dev.plsql.spring.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.annotation.PlsqlApi;

/**
 * Коллекции SQL и объектные типы SQL как поля записи PL/SQL, на настоящей базе: запись
 * {@code LAB_BAG.BAG_T} с полями {@code LAB_NUM_TAB}, {@code LAB_OBJ_TAB} и {@code LAB_OBJ} в обе
 * стороны и как {@code OUT}-аргумент. Пакет {@code LAB_BAG} из {@code it/schema-objects.sql}.
 */
class SqlTypeFieldsIT {

    /**
     * Java-двойник объектного типа {@code LAB_OBJ}.
     *
     * @param id   номер
     * @param name имя
     * @param dt   дата
     */
    record Obj(Long id, String name, LocalDate dt) {
    }

    /**
     * Запись {@code BAG_T}.
     *
     * @param id   номер
     * @param nums коллекция чисел {@code LAB_NUM_TAB}
     * @param objs коллекция объектов {@code LAB_OBJ_TAB}
     * @param one  объект {@code LAB_OBJ}
     */
    record Bag(Long id, List<Long> nums, List<Obj> objs, Obj one) {
    }

    /** Функции и процедуры {@code LAB_BAG}. */
    @PlsqlApi(packageName = "LAB_BAG")
    interface Bags {
        /**
         * Запись туда и обратно: функция пишет в {@code id} число элементов {@code nums} ({@code -1},
         * если коллекции нет), прибавляет к числам единицу, имена объектов переводит в верхний
         * регистр, к номеру отдельного объекта прибавляет единицу.
         *
         * @param b запись
         * @return запись после функции
         */
        Bag bagEcho(Bag b);

        /**
         * Запись как {@code OUT}-аргумент процедуры.
         *
         * @param n сколько элементов в коллекциях
         * @return заполненная запись
         */
        Bag bagFill(long n);
    }

    /** Пул к тестовой схеме. */
    static HikariDataSource ds;
    /** Реализация {@link Bags}. */
    static Bags bags;

    /** Создаёт пул и реализацию; при этом каждый метод сверяется со словарём. */
    @BeforeAll
    static void setUp() {
        ds = ItDatabase.pool(1, true);
        bags = PlsqlApiFactory.builder(ds).build().create(Bags.class);
    }

    /** Закрывает пул. */
    @AfterAll
    static void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    /**
     * Проверяет поля-коллекции и поле-объект в обе стороны, с кириллицей; {@code NULL} вместо
     * коллекции и объекта остаётся {@code NULL}, пустая коллекция — пустой.
     */
    @Test
    void sqlTypesTravelAsRecordFields() {
        Bag back = bags.bagEcho(new Bag(0L, List.of(1L, 2L), List.of(new Obj(1L, "Пётр", LocalDate.of(2024, 2, 29))),
                new Obj(5L, "x", null)));
        assertThat(back).isEqualTo(new Bag(2L, List.of(2L, 3L), List.of(new Obj(1L, "ПЁТР", LocalDate.of(2024, 2, 29))),
                new Obj(6L, "x", null)));
        assertThat(bags.bagEcho(new Bag(9L, null, null, null))).isEqualTo(new Bag(-1L, null, null, null));
        assertThat(bags.bagEcho(new Bag(9L, List.of(), List.of(), null))).isEqualTo(new Bag(0L, List.of(), List.of(), null));
    }

    /** Проверяет запись с полями SQL-типов как {@code OUT}-аргумент процедуры. */
    @Test
    void sqlTypeFieldsComeBackFromOut() {
        Bag b = bags.bagFill(2);
        assertThat(b.nums()).containsExactly(10L, 20L);
        assertThat(b.objs()).containsExactly(new Obj(1L, "obj 1", LocalDate.of(2024, 1, 2)),
                new Obj(2L, "obj 2", LocalDate.of(2024, 1, 3)));
        assertThat(b.one()).isEqualTo(new Obj(0L, "one", null));
    }
}
