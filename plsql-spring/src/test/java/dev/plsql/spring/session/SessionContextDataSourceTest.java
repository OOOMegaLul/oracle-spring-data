package dev.plsql.spring.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import oracle.jdbc.OracleConnection;

/**
 * Порядок и условия подготовки сессии при каждой выдаче соединения, на моках.
 * {@link SessionContextDataSource} сбрасывает состояние пакетов, записывает пользователя в
 * {@code CLIENT_IDENTIFIER} и выполняет блоки инициализации, чтобы следующий пользователь пула
 * не унаследовал чужой контекст. Тексты SQL здесь условные: моки узнают операторы по тексту.
 */
class SessionContextDataSourceTest {

    /**
     * Условный блок сброса с пробой: его единственный плейсхолдер — OUT, в котором блок
     * сообщает ключ, хранящийся сейчас в сессии (например, тенант).
     */
    static final String PROBE = "begin reset; ? := probe; end;";
    /** Условный блок, который пишет ключ в сессию; выполняется только при расхождении с пробой. */
    static final String SCOPED = "begin write(?); end;";
    /** Условный блок инициализации, выполняемый при каждой выдаче; получает пользователя. */
    static final String INIT = "begin set_user(?); end;";

    DataSource target;
    OracleConnection con;
    CallableStatement reset;
    CallableStatement scoped;
    CallableStatement init;
    /** Текущий пользователь приложения; тест может его сменить. */
    final AtomicReference<String> user = new AtomicReference<>("IVANOV");
    /** Ключ, который должен оказаться в сессии (тенант 1001). */
    final AtomicReference<Object> tenant = new AtomicReference<>(1001L);

    /**
     * Готовит моки: {@code DataSource} выдаёт соединение Oracle; стандартный сброс
     * ({@code REINITIALIZE_PACKAGES}) и {@link #PROBE} возвращают один и тот же оператор
     * {@code reset}, а {@link #SCOPED} и {@link #INIT} — свои.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @BeforeEach
    void setUp() throws SQLException {
        target = mock(DataSource.class);
        con = mock(OracleConnection.class);
        reset = mock(CallableStatement.class);
        scoped = mock(CallableStatement.class);
        init = mock(CallableStatement.class);
        when(target.getConnection()).thenReturn(con);
        when(con.unwrap(OracleConnection.class)).thenReturn(con);
        when(con.prepareCall(SessionContextDataSource.REINITIALIZE_PACKAGES)).thenReturn(reset);
        when(con.prepareCall(PROBE)).thenReturn(reset);
        when(con.prepareCall(SCOPED)).thenReturn(scoped);
        when(con.prepareCall(INIT)).thenReturn(init);
    }

    /**
     * Обёртка над моком {@code DataSource} с текущим пользователем из {@code user} и блоком
     * {@link #INIT}, в который тот же пользователь передаётся параметром; сброс — стандартный.
     *
     * @return новая обёртка
     */
    SessionContextDataSource guarded() {
        SessionContextDataSource ds = new SessionContextDataSource(target, user::get);
        ds.setInitSql(INIT, user::get);
        return ds;
    }

    /**
     * Проверяет порядок при выдаче соединения: сначала сброс состояния пакетов, затем
     * пользователь в {@code CLIENT_IDENTIFIER} (свойство {@code OCSID.CLIENTID}), затем блок
     * инициализации с пользователем; вызывающий получает то же соединение. Инициализация идёт
     * отдельным блоком после сброса, потому что сброс применяется по окончании своего блока и
     * стёр бы записанное в том же блоке.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void resetThenIdentityThenInit() throws SQLException {
        Connection c = guarded().getConnection();

        assertThat(c).isSameAs(con);
        InOrder order = inOrder(reset, con, init);
        order.verify(reset).execute();
        order.verify(con).setClientInfo("OCSID.CLIENTID", "IVANOV");
        order.verify(init).setObject(1, "IVANOV");
        order.verify(init).execute();
    }

    /**
     * Проверяет, что при сбое подготовки наружу идёт сама причина, даже если и соединение
     * закрыть не удалось: ошибка закрытия прикладывается к ней как подавленная.
     *
     * @throws SQLException не бросается: драйвер подменён
     */
    @Test
    void failureToCloseDoesNotHideTheCause() throws SQLException {
        SQLException cause = new SQLException("ORA-06550", "65000", 6550);
        SQLException closeFailure = new SQLException("Closed Connection", "08003", 17008);
        when(init.execute()).thenThrow(cause);
        org.mockito.Mockito.doThrow(closeFailure).when(con).close();

        assertThatThrownBy(() -> guarded().getConnection()).isSameAs(cause);
        assertThat(cause.getSuppressed()).containsExactly(closeFailure);
    }

    /** Проверяет, что поставщик пользователя обязателен: ошибка при создании, а не при выдаче. */
    @Test
    void currentUserIsRequired() {
        assertThatThrownBy(() -> new SessionContextDataSource(target, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("currentUser");
    }

    /**
     * Проверяет, что без пользователя {@code CLIENT_IDENTIFIER} очищается пустой строкой, а не
     * остаётся от предыдущего владельца соединения.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void noUserClearsTheIdentifier() throws SQLException {
        user.set(null);
        guarded().getConnection();
        verify(con).setClientInfo("OCSID.CLIENTID", "");
    }

    /**
     * Проверяет запись по ключу. Если проба сообщает, что в сессии уже нужный тенант
     * ({@code "1001"}), блок {@link #SCOPED} не выполняется и ничего не фиксируется; если
     * другой ({@code "2002"}), блок выполняется с нужным значением и фиксируется сам
     * ({@code autoCommit=false}). OUT пробы регистрируется при каждой выдаче. Так запрос не
     * становится пишущим без нужды: пишущая транзакция в конце ждёт запись журнала.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void scopedInitRunsOnlyWhenTheSessionHoldsSomethingElse() throws SQLException {
        SessionContextDataSource ds = guarded();
        ds.setResetProbeSql(PROBE);
        ds.setScopedInitSql(tenant::get, SCOPED, tenant::get);
        when(con.getAutoCommit()).thenReturn(false);

        when(reset.getString(1)).thenReturn("1001");
        ds.getConnection();
        verify(scoped, never()).execute();
        verify(con, never()).commit();

        when(reset.getString(1)).thenReturn("2002");
        ds.getConnection();
        verify(reset, org.mockito.Mockito.times(2)).registerOutParameter(1, Types.VARCHAR);
        verify(scoped).setObject(1, 1001L);
        verify(scoped).execute();
        verify(con).commit();
    }

    /**
     * Проверяет, что на соединении с {@code autoCommit=true} блок {@link #SCOPED} выполняется
     * (проба вернула {@code null}: в сессии ничего нет), но {@code commit} вручную не вызывается.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void scopedInitIsNotCommittedByHandOnAutoCommitConnections() throws SQLException {
        SessionContextDataSource ds = guarded();
        ds.setResetProbeSql(PROBE);
        ds.setScopedInitSql(tenant::get, SCOPED, tenant::get);
        when(con.getAutoCommit()).thenReturn(true);
        when(reset.getString(1)).thenReturn(null);
        ds.getConnection();
        verify(scoped).execute();
        verify(con, never()).commit();
    }

    /**
     * Проверяет, что без пробы узнать содержимое сессии нельзя, поэтому блок {@link #SCOPED}
     * выполняется при каждой выдаче соединения.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void withoutAProbeScopedInitAlwaysRuns() throws SQLException {
        SessionContextDataSource ds = guarded();
        ds.setScopedInitSql(tenant::get, SCOPED, tenant::get);
        ds.getConnection();
        ds.getConnection();
        verify(scoped, org.mockito.Mockito.times(2)).execute();
    }

    /**
     * Проверяет, что если подготовка упала (здесь сброс с ORA-03113, обрыв связи с базой),
     * ошибка выходит наружу, соединение закрывается, а не отдаётся вызывающему, и блок
     * инициализации даже не готовится.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void failedPreparationClosesTheConnection() throws SQLException {
        when(reset.execute()).thenThrow(new SQLException("ORA-03113", "08006", 3113));
        assertThatThrownBy(() -> guarded().getConnection()).isInstanceOf(SQLException.class);
        verify(con).close();
        verify(con, never()).prepareCall(INIT);
    }

    /**
     * Проверяет, что {@code setResetSql(null)} выключает сброс: стандартный блок сброса не
     * готовится и не выполняется, а инициализация всё равно идёт.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void resetCanBeSwitchedOff() throws SQLException {
        SessionContextDataSource ds = guarded();
        ds.setResetSql(null);
        ds.getConnection();
        verify(reset, never()).execute();
        verify(init).execute();
        verify(con, never()).prepareCall(SessionContextDataSource.REINITIALIZE_PACKAGES);
    }
    /**
     * Проверяет сравнение ключа сессии: ключ-число сравнивается как число ({@code 1001.0} из базы
     * и {@code 1001L} из Java — один ключ, и запись с {@code commit} не повторяется на каждой
     * выдаче), а строковый — строго как строка: коды {@code "007"} и {@code "7"} разные, иначе
     * пользователь получил бы сессию, подготовленную под чужой код.
     */
    @Test
    void keysCompareByTheirJavaType() {
        assertThat(SessionContextDataSource.sameKey("1001.0", 1001L)).isTrue();
        assertThat(SessionContextDataSource.sameKey(" 1001", 1001)).isTrue();
        assertThat(SessionContextDataSource.sameKey("007", "7")).isFalse();
        assertThat(SessionContextDataSource.sameKey("1.0", "1")).isFalse();
        assertThat(SessionContextDataSource.sameKey("A", "a")).isFalse();
        assertThat(SessionContextDataSource.sameKey(null, "1")).isFalse();
        assertThat(SessionContextDataSource.sameKey("1", null)).isFalse();
        assertThat(SessionContextDataSource.sameKey(null, null)).isTrue();
    }
}
