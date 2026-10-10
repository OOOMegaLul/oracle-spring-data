package dev.plsql.spring.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zaxxer.hikari.HikariDataSource;

import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.annotation.Arg;
import dev.plsql.spring.annotation.PlsqlApi;
import dev.plsql.spring.annotation.Procedure;
import dev.plsql.spring.support.PlsqlBusinessException;

/**
 * Пакетные вызовы ({@code @Procedure(batch = true)}) на настоящем Oracle: процедура
 * {@code LAB_RUN.MARK} вставляет строку в {@code LAB_MARK} и возвращает длину текста в
 * {@code OUT}-аргументе, который пакет отбрасывает. Пул без {@code autoCommit}: пакет вне
 * транзакции — одна единица работы.
 */
class BatchIT {

    /**
     * Строка пакета.
     *
     * @param txt текст метки, идёт в {@code P_TXT}
     */
    record Mark(String txt) {
    }

    /** Вызовы {@code LAB_RUN.MARK} по одному и пакетом. */
    @PlsqlApi(packageName = "LAB_RUN")
    interface Marks {
        /**
         * Пакет по record, пачками по 1000.
         *
         * @param marks строки
         * @return число вызовов
         */
        @Procedure(value = "MARK", batch = true)
        int markAll(List<Mark> marks);

        /**
         * Пакет по простым значениям: имя аргумента задано на параметре-списке.
         *
         * @param texts тексты меток
         */
        @Procedure(value = "MARK", batch = true, batchSize = 7)
        void markTexts(@Arg("P_TXT") List<String> texts);

        /**
         * Один вызов: длина текста из {@code OUT}-аргумента.
         *
         * @param txt текст метки
         * @return длина текста
         */
        @Procedure("MARK")
        long markOne(String txt);
    }

    /** Журнал теста: сюда пишется замер. */
    private static final Logger log = LoggerFactory.getLogger(BatchIT.class);

    /** Пул без autoCommit. */
    static HikariDataSource ds;
    /** Реализация {@link Marks}. */
    static Marks marks;

    /** Создаёт пул и реализацию. */
    @BeforeAll
    static void setUp() {
        ds = ItDatabase.pool(2, false);
        marks = PlsqlApiFactory.builder(ds).build().create(Marks.class);
    }

    /** Закрывает пул. */
    @AfterAll
    static void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    /**
     * Очищает {@code LAB_MARK} перед каждым тестом.
     *
     * @throws SQLException если очистка не удалась
     */
    @BeforeEach
    void clean() throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("delete from lab_mark")) {
            ps.executeUpdate();
            c.commit();
        }
    }

    /**
     * Проверяет пакет из 2500 строк пачками по 1000: все строки на месте, метод вернул число
     * вызовов; пакет из простых значений пачками по 7; пустой список — ноль вызовов.
     *
     * @throws SQLException если не удалось посчитать строки
     */
    @Test
    void batchCallsEveryElement() throws SQLException {
        List<Mark> rows = IntStream.range(0, 2500).mapToObj(i -> new Mark("b" + i)).toList();
        assertThat(marks.markAll(rows)).isEqualTo(2500);
        assertThat(count("b%")).isEqualTo(2500);

        marks.markTexts(List.of("t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9", "t10"));
        assertThat(count("t%")).isEqualTo(10);
        assertThat(marks.markAll(List.of())).isZero();
    }

    /**
     * Проверяет ошибку в середине пакета: бизнес-ошибка процедуры ({@code RAISE_APPLICATION_ERROR})
     * доходит как {@link PlsqlBusinessException}, и весь пакет откатывается, включая уже
     * отправленные пачки.
     *
     * @throws SQLException если не удалось посчитать строки
     */
    @Test
    void failureRollsBackTheWholeBatch() throws SQLException {
        List<Mark> rows = new ArrayList<>(IntStream.range(0, 1500).mapToObj(i -> new Mark("f" + i)).toList());
        rows.set(1200, new Mark("bad"));
        assertThatThrownBy(() -> marks.markAll(rows))
                .isInstanceOf(PlsqlBusinessException.class).hasMessage("bad mark");
        assertThat(count("f%")).isZero();
    }

    /**
     * Сравнивает 2000 вызовов по одному и пакетом; замер пишется в журнал, а пакет должен быть
     * быстрее.
     */
    @Test
    void batchIsFasterThanOneByOne() {
        List<Mark> rows = IntStream.range(0, 2000).mapToObj(i -> new Mark("s" + i)).toList();
        marks.markOne("warm-up");
        marks.markAll(List.of(new Mark("warm-up")));
        long t0 = System.nanoTime();
        for (Mark m : rows) {
            marks.markOne(m.txt());
        }
        long single = (System.nanoTime() - t0) / 1_000_000;
        t0 = System.nanoTime();
        marks.markAll(rows);
        long batch = (System.nanoTime() - t0) / 1_000_000;
        log.info("2000 calls: {} ms one by one, {} ms as a batch", single, batch);
        assertThat(batch).isLessThan(single);
    }

    /**
     * Считает строки {@code LAB_MARK} по шаблону.
     *
     * @param like шаблон {@code LIKE}
     * @return число строк
     * @throws SQLException если запрос не удался
     */
    private static int count(String like) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("select count(*) from lab_mark where txt like ?")) {
            ps.setString(1, like);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
