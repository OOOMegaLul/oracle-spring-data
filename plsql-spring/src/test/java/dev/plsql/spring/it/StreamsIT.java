package dev.plsql.spring.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaxxer.hikari.HikariDataSource;

import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.annotation.PlsqlApi;
import dev.plsql.spring.annotation.Procedure;
import dev.plsql.spring.annotation.SqlQuery;

/**
 * Результаты-{@code Stream} на настоящем Oracle 11.2: курсор процедуры, курсор функции и
 * {@code @SqlQuery}. Пул из одного соединения: если поток не вернёт соединение при закрытии,
 * следующий вызов не дождётся его.
 */
class StreamsIT {

    /** Курсоры {@code LAB_PKG} и запрос потоком. */
    @PlsqlApi(packageName = "LAB_PKG")
    interface Rows {
        /**
         * Сотрудники с {@code id >= minId} из OUT-курсора процедуры {@code EMPS}.
         *
         * @param minId наименьший номер
         * @return поток строк
         */
        @Procedure("EMPS")
        Stream<LabApi.Emp> emps(long minId);

        /**
         * То же из курсора, который возвращает функция {@code EMPS_F}.
         *
         * @param minId наименьший номер
         * @return поток строк
         */
        @Procedure("EMPS_F")
        Stream<LabApi.Emp> empsF(long minId);

        /**
         * Курсор {@code IN OUT}, который процедура открывает, только если {@code open = 1}.
         *
         * @param open  1 — открыть курсор
         * @param minId наименьший номер
         * @return поток строк; пустой, если курсор не открыт
         */
        @Procedure("EMPS_INOUT")
        Stream<LabApi.Emp> maybe(long open, long minId);

        /**
         * Много строк потоком.
         *
         * @param count сколько чисел
         * @return числа от 1 до {@code count}
         */
        @SqlQuery("select level from dual connect by level <= :count")
        Stream<Long> numbers(long count);
    }

    /** Журнал теста: сюда пишется замер. */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StreamsIT.class);

    /** Пул из одного соединения. */
    static HikariDataSource ds;
    /** Реализация {@link Rows}. */
    static Rows rows;

    /** Создаёт пул и реализацию. */
    @BeforeAll
    static void setUp() {
        ds = ItDatabase.pool(1, false);
        rows = PlsqlApiFactory.builder(ds).build().create(Rows.class);
    }

    /** Закрывает пул. */
    @AfterAll
    static void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    /**
     * Проверяет курсор процедуры и функции потоком; раз вызовы идут друг за другом через пул из
     * одного соединения, закрытый поток его вернул.
     */
    @Test
    void cursorsStream() {
        try (Stream<LabApi.Emp> s = rows.emps(2)) {
            assertThat(s.map(LabApi.Emp::id).toList()).containsExactly(2L, 3L);
        }
        try (Stream<LabApi.Emp> s = rows.empsF(1)) {
            assertThat(s.findFirst()).hasValueSatisfying(e -> assertThat(e.name()).isEqualTo("Иванов"));
        }
        try (Stream<LabApi.Emp> s = rows.maybe(0, 1)) {
            assertThat(s.toList()).isEmpty();
        }
        try (Stream<LabApi.Emp> s = rows.maybe(1, 3)) {
            assertThat(s.toList()).extracting(LabApi.Emp::id).containsExactly(3L);
        }
    }

    /**
     * Проверяет {@code @SqlQuery} потоком: 200 000 строк складываются, не собираясь в список, а
     * брошенный на середине поток после закрытия возвращает соединение.
     */
    @Test
    void queryStreams() {
        long t0 = System.nanoTime();
        try (Stream<Long> s = rows.numbers(200_000)) {
            assertThat(s.mapToLong(Long::longValue).sum()).isEqualTo(200_000L * 200_001 / 2);
        }
        log.info("200 000 rows streamed in {} ms", (System.nanoTime() - t0) / 1_000_000);
        try (Stream<Long> s = rows.numbers(1_000)) {
            assertThat(s.limit(3).toList()).isEqualTo(List.of(1L, 2L, 3L));
        }
        try (Stream<Long> s = rows.numbers(2)) {
            assertThat(s.count()).isEqualTo(2);
        }
    }

    /**
     * Проверяет, что дочитанный поток сам возвращает соединение, даже если его не закрыли: пул из
     * одного соединения, три незакрытых {@code toList()} подряд и ещё один вызов.
     */
    @Test
    void exhaustedStreamNeedsNoClose() {
        for (int i = 0; i < 3; i++) {
            assertThat(rows.emps(2).toList()).hasSize(2);
            assertThat(rows.numbers(3).toList()).hasSize(3);
        }
        try (Stream<Long> s = rows.numbers(1)) {
            assertThat(s.toList()).containsExactly(1L);
        }
    }

    /** Проверяет поток внутри транзакции Spring: соединение транзакции, чтение до её конца. */
    @Test
    void streamInsideATransaction() {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        List<Long> ids = tx.execute(status -> {
            try (Stream<LabApi.Emp> s = rows.emps(1)) {
                return s.map(LabApi.Emp::id).toList();
            }
        });
        assertThat(ids).containsExactly(1L, 2L, 3L);
    }
}
