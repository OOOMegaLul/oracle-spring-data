package dev.plsql.spring.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaxxer.hikari.HikariDataSource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.annotation.PlsqlApi;
import dev.plsql.spring.annotation.Procedure;
import dev.plsql.spring.annotation.SqlQuery;
import dev.plsql.spring.support.PlsqlBusinessException;

/**
 * Долгие вызовы и вывод {@code DBMS_OUTPUT} на настоящей базе: пакет {@code LAB_RUN} из
 * {@code it/schema-objects.sql}.
 *
 * <p>Пул открывается со свойством драйвера {@code oracle.net.disableOob=true}. Без него драйвер
 * просит сервер прервать вызов «срочными» данными TCP (out-of-band), а проброс портов Docker
 * Desktop их теряет: замерено, вызов с Windows доходит до конца, хотя срок давно вышел. С этим
 * свойством просьба идёт в обычном потоке, и сервер прерывает цикл PL/SQL через 2,0 с при сроке
 * 2 с — и с Windows, и из соседнего контейнера.
 */
class LongCallsIT {

    /** Долгие процедуры и вывод; сроки заданы на методах. */
    @PlsqlApi(packageName = "LAB_RUN")
    interface Run {
        /**
         * Крутится {@code seconds} секунд, но не дольше одной.
         *
         * @param seconds сколько секунд крутиться
         * @param mark    строка, которую процедура до цикла вставляет в {@code LAB_MARK}
         */
        @Procedure(value = "BUSY", timeout = 1)
        void busyAtMostASecond(long seconds, String mark);

        /**
         * Крутится {@code seconds} секунд со сроком фабрики.
         *
         * @param seconds сколько секунд крутиться
         */
        void busy(long seconds);

        /**
         * Запрос, который крутится в функции {@code SLOW_VALUE}, со сроком одна секунда.
         *
         * @param seconds сколько секунд крутиться
         * @return {@code seconds}, если успеет
         */
        @SqlQuery(value = "select lab_run.slow_value(:seconds) from dual", timeout = 1)
        long slowQuery(long seconds);

        /**
         * Пишет {@code text}, пустую строку и {@code end} в {@code DBMS_OUTPUT}.
         *
         * @param text первая строка
         * @param fail {@code 1} — после вывода упасть с ORA-20043
         */
        void say(String text, long fail);
    }

    /** Журнал теста: сюда пишутся замеры. */
    private static final Logger log = LoggerFactory.getLogger(LongCallsIT.class);

    /** Пул к тестовой схеме с внутриполосной просьбой о прерывании. */
    static HikariDataSource ds;
    /** Реализация без срока по умолчанию. */
    static Run run;

    /** Создаёт пул и реализацию {@link Run}. */
    @BeforeAll
    static void setUp() {
        ds = ItDatabase.pool(2, true, Map.of("oracle.net.disableOob", "true"));
        run = PlsqlApiFactory.builder(ds).build().create(Run.class);
    }

    /** Закрывает пул. */
    @AfterAll
    static void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    /** Возвращает журналу вывода уровень INFO из {@code logback-test.xml} и убирает перехватчик. */
    @AfterEach
    void quiet() {
        outputLogger().setLevel(Level.INFO);
        outputLogger().detachAppender("capture");
    }

    /**
     * Проверяет срок на методе: процедура, которая крутилась бы 5 секунд, прерывается примерно через
     * секунду с {@link QueryTimeoutException}, строка, которую она успела вставить, откатывается, а
     * соединение пула остаётся рабочим. Замер времени пишется в журнал.
     *
     * @throws SQLException если не удалось проверить таблицу {@code LAB_MARK}
     */
    @Test
    void methodTimeoutInterruptsTheCall() throws SQLException {
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> run.busyAtMostASecond(5, "timeout"))
                .isInstanceOf(QueryTimeoutException.class)
                .hasMessageContaining("ORA-01013");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info("a 5 s call with timeout = 1 s stopped after {} ms", ms);
        assertThat(ms).isLessThan(4_000);
        assertThat(marks("timeout")).isZero();
        run.busyAtMostASecond(0, null); // то же соединение пула снова работает
    }

    /**
     * Проверяет срок у {@code @SqlQuery}: запрос с функцией, которая крутится 5 секунд, прерывается
     * со сроком метода.
     */
    @Test
    void queryTimeoutInterruptsTheQuery() {
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> run.slowQuery(5)).isInstanceOf(QueryTimeoutException.class);
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(4_000);
        assertThat(run.slowQuery(0)).isZero();
    }

    /**
     * Проверяет срок фабрики ({@code plsql.query-timeout}) и срок транзакции: метод без своего
     * срока прерывается сроком фабрики, а внутри {@code TransactionTemplate} с таймаутом 1 секунда —
     * остатком срока транзакции, хотя у фабрики срока нет.
     */
    @Test
    void factoryAndTransactionTimeouts() {
        Run limited = PlsqlApiFactory.builder(ds).queryTimeout(Duration.ofSeconds(1)).build().create(Run.class);
        assertThatThrownBy(() -> limited.busy(5)).isInstanceOf(QueryTimeoutException.class);

        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setTimeout(1);
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> run.busy(5))).isInstanceOf(QueryTimeoutException.class);
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(4_000);
    }

    /**
     * Проверяет перенос {@code DBMS_OUTPUT} в журнал: при DEBUG у журнала
     * {@code dev.plsql.spring.support.DbmsOutput} строки вызова (с кириллицей и пустой строкой)
     * приходят одним сообщением, в том числе когда процедура после вывода падает; без DEBUG
     * ничего не пишется. Замер цены чтения на 200 вызовах пишется в журнал.
     */
    @Test
    void dbmsOutputGoesToTheLog() {
        ListAppender<ILoggingEvent> captured = capture();
        run.say("привет", 0);
        assertThatThrownBy(() -> run.say("перед ошибкой", 1)).isInstanceOf(PlsqlBusinessException.class);
        List<String> messages = captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isEqualTo("PLSQL_IT.LAB_RUN.SAY DBMS_OUTPUT:\nпривет\n\nend");
        assertThat(messages.get(1)).contains("перед ошибкой");

        long withOutput = timeSays(200);
        outputLogger().setLevel(Level.INFO);
        captured.list.clear();
        long without = timeSays(200);
        assertThat(captured.list).isEmpty();
        log.info("200 calls: {} ms with DBMS_OUTPUT read, {} ms without", withOutput, without);
    }

    /**
     * Возвращает журнал, в который библиотека пишет {@code DBMS_OUTPUT}.
     *
     * @return журнал logback с именем {@code dev.plsql.spring.support.DbmsOutput}
     */
    private static ch.qos.logback.classic.Logger outputLogger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("dev.plsql.spring.support.DbmsOutput");
    }

    /**
     * Включает DEBUG у журнала вывода и перехватывает его сообщения.
     *
     * @return перехватчик, в котором копятся сообщения
     */
    private static ListAppender<ILoggingEvent> capture() {
        ListAppender<ILoggingEvent> a = new ListAppender<>();
        a.setName("capture");
        a.start();
        outputLogger().addAppender(a);
        outputLogger().setLevel(Level.DEBUG);
        return a;
    }

    /**
     * Вызывает {@code SAY} {@code n} раз и возвращает затраченное время.
     *
     * @param n число вызовов
     * @return время в миллисекундах
     */
    private static long timeSays(int n) {
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            run.say("x", 0);
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }

    /**
     * Считает строки {@code LAB_MARK} с заданным текстом.
     *
     * @param mark текст метки
     * @return число строк
     * @throws SQLException если запрос не удался
     */
    private static int marks(String mark) throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from lab_mark where txt = '" + mark + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
