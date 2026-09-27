package dev.plsql.spring.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.zaxxer.hikari.HikariDataSource;

import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.session.SessionContextDataSource;
import oracle.jdbc.OracleConnection;

/**
 * Что делает обычный JDBC на Oracle 11.2 с драйвером ojdbc 19 и что меняет библиотека: типы,
 * существующие только в PL/SQL, утечка состояния пакетов между пользователями пула,
 * перекомпилированные пакеты, временные LOB.
 *
 * <p>Тесты сначала показывают проблему на голом JDBC, затем то же действие через библиотеку.
 * Соединения открываются отдельно ({@link ItDatabase#connect()}) или берутся из пула на одно
 * соединение, чтобы разные «пользователи» гарантированно попадали в одну и ту же сессию Oracle.
 * Там, где сравнивается вызов через библиотеку, она работает поверх уже открытого соединения
 * ({@code SingleConnectionDataSource}), чтобы оба варианта шли в одной и той же сессии.
 */
class RawJdbcIT {

    /**
     * Показывает, что голый JDBC не может вызвать процедуру с PL/SQL-типами: ни с {@code BOOLEAN}
     * ({@code BOOL_INOUT} через {@code setBoolean} и {@code Types.BOOLEAN}), ни с записью пакета
     * {@code LAB_PKG.REC_T} ({@code REC_INOUT} через {@code createStruct}); оба вызова дают
     * {@code SQLException}.
     *
     * <p>Это причина, по которой библиотека вызывает процедуры через анонимный блок с локальными
     * переменными: JDBC передаёт только SQL-типы, а {@code BOOLEAN} и {@code RECORD} на 11.2
     * существуют лишь внутри PL/SQL. С 12c JDBC умеет передавать часть типов пакетов напрямую,
     * поэтому на базе новее 11 тест пропускается.
     *
     * @throws SQLException если не удалось соединиться с базой
     */
    @Test
    void plainJdbcCannotBindPlsqlOnlyTypes() throws SQLException {
        try (Connection c = ItDatabase.connect()) {
            assumeTrue(c.getMetaData().getDatabaseMajorVersion() == 11, "only Oracle 11 lacks these binds");
            assertThatThrownBy(() -> {
                try (CallableStatement cs = c.prepareCall("{call lab_pkg.bool_inout(?)}")) {
                    cs.setBoolean(1, true);
                    cs.registerOutParameter(1, Types.BOOLEAN);
                    cs.execute();
                }
            }).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> {
                try (CallableStatement cs = c.prepareCall("{call lab_pkg.rec_inout(?)}")) {
                    cs.setObject(1, c.createStruct(ItDatabase.USER + ".LAB_PKG.REC_T", new Object[]{1, "a", 1, null}));
                    cs.execute();
                }
            }).isInstanceOf(SQLException.class);
        }
    }

    /**
     * Показывает утечку состояния пакета через пул и её устранение.
     *
     * <p>В пуле из одного соединения «пользователь A» записывает секрет в переменную пакета
     * ({@code LAB_PKG.SET_STATE}) и возвращает соединение; «пользователь B» получает ту же сессию
     * Oracle и читает секрет A. Через {@code SessionContextDataSource} то же самое уже не проходит:
     * при каждой выдаче соединения состояние пакетов сбрасывается, и B видит {@code NULL}, а
     * {@code CLIENT_IDENTIFIER} той же сессии меняется с {@code USER_A} на {@code USER_B} вместе с
     * тем, кто взял соединение.
     *
     * <p>Код, написанный под APEX или Oracle Forms, часто хранит «кто работает» и контекст в
     * переменных пакетов; в пуле это прямая утечка данных между пользователями.
     *
     * @throws SQLException при ошибке JDBC
     */
    @Test
    void packageStateLeaksThroughAPoolUnlessReset() throws SQLException {
        try (HikariDataSource pool = ItDatabase.pool(1, true)) {
            try (Connection a = pool.getConnection(); Statement s = a.createStatement()) {
                s.execute("begin lab_pkg.set_state('secret of user A'); end;");
            }
            try (Connection b = pool.getConnection()) {
                assertThat(state(b)).isEqualTo("secret of user A");
            }

            java.util.concurrent.atomic.AtomicReference<String> user = new java.util.concurrent.atomic.AtomicReference<>("USER_A");
            SessionContextDataSource guarded = new SessionContextDataSource(pool, user::get);
            try (Connection a = guarded.getConnection(); Statement s = a.createStatement()) {
                assertThat(clientIdentifier(a)).isEqualTo("USER_A");
                s.execute("begin lab_pkg.set_state('secret of user A'); end;");
            }
            user.set("USER_B");
            try (Connection b = guarded.getConnection()) {
                assertThat(state(b)).isNull();
                assertThat(clientIdentifier(b)).isEqualTo("USER_B");
            }
        }
    }

    /**
     * Показывает, что {@code DBMS_SESSION.MODIFY_PACKAGE_STATE(DBMS_SESSION.REINITIALIZE)}
     * сбрасывает пакеты не сразу, а по окончании блока, который его вызвал: значение, записанное в
     * том же блоке уже после сброса, тоже стирается.
     *
     * <p>Поэтому {@code SessionContextDataSource} выполняет сброс и последующую инициализацию
     * сессии ({@code initSql}) отдельными блоками, хоть это и лишний обмен с базой.
     *
     * @throws SQLException при ошибке JDBC
     */
    @Test
    void resetAppliesAfterTheBlockThatAskedForIt() throws SQLException {
        try (Connection c = ItDatabase.connect(); Statement s = c.createStatement()) {
            s.execute("begin dbms_session.modify_package_state(dbms_session.reinitialize); lab_pkg.set_state('new'); end;");
            assertThat(state(c)).as("value set in the same block as the reset").isNull();
        }
    }

    /**
     * Показывает, что установка {@code CLIENT_IDENTIFIER} через
     * {@code setClientInfo("OCSID.CLIENTID", ...)} не стоит отдельного обмена с базой: значение
     * уходит вместе со следующим вызовом.
     *
     * <p>Число обменов берётся из статистики сессии {@code SQL*Net roundtrips to/from client}.
     * Сначала измеряется, сколько обменов стоит сам замер ({@code baseline}); затем между двумя
     * замерами выполняется {@code setClientInfo}, и прирост должен остаться равным
     * {@code baseline}. После этого запрос видит {@code IVANOV} в
     * {@code SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER')}.
     *
     * <p>Благодаря этому {@code SessionContextDataSource} помечает соединение текущим пользователем
     * при каждой выдаче из пула без лишних обращений к базе.
     *
     * @throws SQLException при ошибке JDBC
     */
    @Test
    void clientIdentifierRidesOnTheNextCall() throws SQLException {
        try (Connection c = ItDatabase.connect()) {
            long r0 = roundTrips(c);
            long baseline = roundTrips(c) - r0;
            long r1 = roundTrips(c);
            c.unwrap(OracleConnection.class).setClientInfo("OCSID.CLIENTID", "IVANOV");
            long r2 = roundTrips(c);
            assertThat(r2 - r1).isEqualTo(baseline);
            assertThat(clientIdentifier(c)).isEqualTo("IVANOV");
        }
    }

    /**
     * Показывает ORA-04068 после перекомпиляции пакета и повтор вызова в библиотеке.
     *
     * <p>У {@code LAB_PKG} есть состояние (переменная {@code G_STATE}). Когда вторая сессия
     * перекомпилирует пакет ({@code ALTER PACKAGE ... COMPILE}), первая сессия при следующем
     * вызове получает ORA-04068 («existing state of packages has been discarded»): состояние
     * выброшено, а сам вызов не выполнялся. Голый JDBC отдаёт эту ошибку наружу. Библиотека по
     * умолчанию повторяет такой вызов один раз, и он проходит ({@code getState} видит новое
     * значение); с {@code retryDiscardedState(false)} ошибка ORA-04068 доходит до вызывающего кода.
     *
     * <p>Перекомпиляция пакетов под работающим приложением — обычное дело при выкладке; без повтора
     * каждая сессия пула, успевшая обратиться к пакету, один раз вернула бы ошибку пользователю.
     *
     * @throws SQLException при ошибке JDBC
     */
    @Test
    void recompiledPackageIsCalledAgain() throws SQLException {
        try (Connection a = ItDatabase.connect(); Connection b = ItDatabase.connect()) {
            try (Statement s = a.createStatement()) {
                s.execute("begin lab_pkg.set_state('x'); end;");
            }
            recompile(b);
            assertThatThrownBy(() -> {
                try (Statement s = a.createStatement()) {
                    s.execute("begin lab_pkg.set_state('y'); end;");
                }
            }).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getErrorCode()).isEqualTo(4068));

            LabApi api = PlsqlApiFactory.builder(new SingleConnectionDataSource(a, true)).build().create(LabApi.class);
            api.setState("before");
            recompile(b);
            api.setState("z");
            assertThat(api.getState()).isEqualTo("z");

            LabApi noRetry = PlsqlApiFactory.builder(new SingleConnectionDataSource(a, true))
                    .retryDiscardedState(false).build().create(LabApi.class);
            recompile(b);
            assertThatThrownBy(() -> noRetry.setState("w")).hasMessageContaining("ORA-04068");
        }
    }

    /**
     * Показывает утечку временных LOB на голом JDBC и её отсутствие в библиотеке.
     *
     * <p>20 вызовов {@code CLOB_LEN} с {@code Connection.createClob()} без {@code free()} оставляют
     * в сессии 20 временных LOB (по {@code V$TEMPORARY_LOBS}); 20 таких же вызовов через библиотеку
     * на том же соединении не оставляют ни одного.
     *
     * <p>Временный LOB живёт до закрытия соединения и занимает временное табличное пространство, а
     * соединение из пула практически никогда не закрывается, так что без освобождения LOB копятся.
     *
     * @throws SQLException при ошибке JDBC
     */
    @Test
    void temporaryLobsAreFreed() throws SQLException {
        try (Connection c = ItDatabase.connect()) {
            long before = tempLobs(c);
            for (int i = 0; i < 20; i++) {
                try (CallableStatement cs = c.prepareCall("{? = call lab_pkg.clob_len(?)}")) {
                    Clob clob = c.createClob();
                    clob.setString(1, "text " + i);
                    cs.registerOutParameter(1, Types.NUMERIC);
                    cs.setClob(2, clob);
                    cs.execute();
                }
            }
            assertThat(tempLobs(c) - before).as("plain JDBC without free()").isEqualTo(20);

            LabApi api = PlsqlApiFactory.builder(new SingleConnectionDataSource(c, true)).build().create(LabApi.class);
            long mid = tempLobs(c);
            for (int i = 0; i < 20; i++) {
                api.clobLen("text " + i);
            }
            assertThat(tempLobs(c) - mid).as("through the library").isZero();
        }
    }

    // ------------------------------------------------------------------ вспомогательные методы

    /**
     * Перекомпилирует пакет {@code LAB_PKG} ({@code ALTER PACKAGE LAB_PKG COMPILE}) в сессии
     * {@code c}. В других сессиях, где пакет уже использовался, его состояние после этого
     * сбрасывается, и следующий вызов там получает ORA-04068.
     *
     * @param c соединение, в котором выполняется перекомпиляция
     * @throws SQLException при ошибке JDBC
     */
    private static void recompile(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("alter package lab_pkg compile");
        }
    }

    /**
     * Читает переменную пакета {@code G_STATE} через {@code LAB_PKG.GET_STATE} голым JDBC.
     *
     * @param c соединение (сессия), чьё состояние читается
     * @return значение переменной или {@code null}
     * @throws SQLException при ошибке JDBC
     */
    private static String state(Connection c) throws SQLException {
        try (CallableStatement cs = c.prepareCall("{? = call lab_pkg.get_state}")) {
            cs.registerOutParameter(1, Types.VARCHAR);
            cs.execute();
            return cs.getString(1);
        }
    }

    /**
     * Читает {@code CLIENT_IDENTIFIER} сессии — метку пользователя, которую PL/SQL-код видит через
     * {@code SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER')}.
     *
     * @param c соединение
     * @return идентификатор клиента или {@code null}, если он не задан
     * @throws SQLException при ошибке JDBC
     */
    private static String clientIdentifier(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select sys_context('USERENV','CLIENT_IDENTIFIER') from dual");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    /**
     * Читает из {@code V$MYSTAT} статистику текущей сессии {@code SQL*Net roundtrips to/from client} —
     * сколько раз клиент и сервер обменялись сообщениями. Сам этот запрос тоже добавляет обмены.
     * Нужна привилегия {@code SELECT ANY DICTIONARY} (выдаётся в {@code it/schema-dba.sql}).
     *
     * @param c соединение, чью сессию измеряем
     * @return накопленное число обменов
     * @throws SQLException при ошибке JDBC
     */
    private static long roundTrips(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "select m.value from v$mystat m join v$statname n on n.statistic# = m.statistic# "
                        + "where n.name = 'SQL*Net roundtrips to/from client'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Считает временные LOB текущей сессии по {@code V$TEMPORARY_LOBS}
     * ({@code CACHE_LOBS + NOCACHE_LOBS + ABSTRACT_LOBS}). Как и {@link #roundTrips}, требует
     * {@code SELECT ANY DICTIONARY}.
     *
     * @param c соединение, чью сессию проверяем
     * @return число временных LOB в сессии
     * @throws SQLException при ошибке JDBC
     */
    private static long tempLobs(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "select nvl(sum(cache_lobs + nocache_lobs + abstract_lobs), 0) from v$temporary_lobs "
                        + "where sid = sys_context('USERENV','SID')");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
