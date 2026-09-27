package dev.plsql.spring.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * What plain JDBC does on Oracle 11.2 with ojdbc 19, and what the library changes:
 * PL/SQL-only types, package state leaking between pooled users, recompiled packages,
 * temporary LOBs.
 */
class RawJdbcIT {

    @Test
    void plainJdbcCannotBindPlsqlOnlyTypes() throws SQLException {
        try (Connection c = ItDatabase.connect()) {
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

    @Test
    void packageStateLeaksThroughAPoolUnlessReset() throws SQLException {
        try (HikariDataSource pool = ItDatabase.pool(1, true)) {
            try (Connection a = pool.getConnection(); Statement s = a.createStatement()) {
                s.execute("begin lab_pkg.set_state('secret of user A'); end;");
            }
            try (Connection b = pool.getConnection()) {
                assertThat(state(b)).isEqualTo("secret of user A");
            }

            SessionContextDataSource guarded = new SessionContextDataSource(pool, () -> "USER_B");
            try (Connection a = guarded.getConnection(); Statement s = a.createStatement()) {
                s.execute("begin lab_pkg.set_state('secret of user A'); end;");
            }
            try (Connection b = guarded.getConnection()) {
                assertThat(state(b)).isNull();
                assertThat(clientIdentifier(b)).isEqualTo("USER_B");
            }
        }
    }

    @Test
    void resetAppliesAfterTheBlockThatAskedForIt() throws SQLException {
        try (Connection c = ItDatabase.connect(); Statement s = c.createStatement()) {
            s.execute("begin dbms_session.modify_package_state(dbms_session.reinitialize); lab_pkg.set_state('new'); end;");
            assertThat(state(c)).as("value set in the same block as the reset").isNull();
        }
    }

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

    // ------------------------------------------------------------------ helpers

    private static void recompile(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("alter package lab_pkg compile");
        }
    }

    private static String state(Connection c) throws SQLException {
        try (CallableStatement cs = c.prepareCall("{? = call lab_pkg.get_state}")) {
            cs.registerOutParameter(1, Types.VARCHAR);
            cs.execute();
            return cs.getString(1);
        }
    }

    private static String clientIdentifier(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select sys_context('USERENV','CLIENT_IDENTIFIER') from dual");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static long roundTrips(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "select m.value from v$mystat m join v$statname n on n.statistic# = m.statistic# "
                        + "where n.name = 'SQL*Net roundtrips to/from client'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

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
