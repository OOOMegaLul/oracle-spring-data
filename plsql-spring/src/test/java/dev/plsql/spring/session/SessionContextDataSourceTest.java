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

/** Order and conditions of the per-borrow session preparation, on mocks. */
class SessionContextDataSourceTest {

    static final String PROBE = "begin reset; ? := probe; end;";
    static final String SCOPED = "begin write(?); end;";
    static final String INIT = "begin set_user(?); end;";

    DataSource target;
    OracleConnection con;
    CallableStatement reset;
    CallableStatement scoped;
    CallableStatement init;
    final AtomicReference<String> user = new AtomicReference<>("IVANOV");
    final AtomicReference<Object> tenant = new AtomicReference<>(1001L);

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

    SessionContextDataSource guarded() {
        SessionContextDataSource ds = new SessionContextDataSource(target, user::get);
        ds.setInitSql(INIT, user::get);
        return ds;
    }

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

    @Test
    void noUserClearsTheIdentifier() throws SQLException {
        user.set(null);
        guarded().getConnection();
        verify(con).setClientInfo("OCSID.CLIENTID", "");
    }

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

    @Test
    void withoutAProbeScopedInitAlwaysRuns() throws SQLException {
        SessionContextDataSource ds = guarded();
        ds.setScopedInitSql(tenant::get, SCOPED, tenant::get);
        ds.getConnection();
        ds.getConnection();
        verify(scoped, org.mockito.Mockito.times(2)).execute();
    }

    @Test
    void failedPreparationClosesTheConnection() throws SQLException {
        when(reset.execute()).thenThrow(new SQLException("ORA-03113", "08006", 3113));
        assertThatThrownBy(() -> guarded().getConnection()).isInstanceOf(SQLException.class);
        verify(con).close();
        verify(con, never()).prepareCall(INIT);
    }

    @Test
    void resetCanBeSwitchedOff() throws SQLException {
        SessionContextDataSource ds = guarded();
        ds.setResetSql(null);
        ds.getConnection();
        verify(reset, never()).execute();
        verify(init).execute();
        verify(con, never()).prepareCall(SessionContextDataSource.REINITIALIZE_PACKAGES);
    }
}
