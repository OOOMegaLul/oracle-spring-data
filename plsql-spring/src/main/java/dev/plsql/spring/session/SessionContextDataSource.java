package dev.plsql.spring.session;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import oracle.jdbc.OracleConnection;

/**
 * Makes a pooled connection belong to the current user before anyone uses it.
 *
 * <p>Two things leak between users of a pool: who the database thinks is working, and
 * whatever the previous user left in package variables and session-level temporary
 * tables. APEX hides both because it resets the session on every page request. A pool
 * does not, so this wrapper does it on every borrow:
 * <ol>
 *   <li>{@code resetSql} (default: reinitialise all package state) - one round trip;</li>
 *   <li>the user goes into CLIENT_IDENTIFIER through the JDBC end-to-end metrics, which
 *       ride on the next call and cost no round trip of their own;</li>
 *   <li>{@code initSql}, if set, for everything else the code expects a session to
 *       carry: the user where legacy code reads it ({@code apex_application.g_user}),
 *       the tenant ({@code APP_CONTEXT.SET_TENANT}) - one more round trip.
 *       It cannot share a block with the reset: package state is reinitialised when the
 *       block that asked for it ends, so values set in the same block are wiped.</li>
 * </ol>
 *
 * <p>Outside a Spring transaction every call borrows a connection, so every call starts
 * from a clean session. Code that sets session state in one call and reads it in the
 * next must run inside {@code @Transactional}, which holds one connection for the request.
 */
public class SessionContextDataSource extends DelegatingDataSource {

    public static final String REINITIALIZE_PACKAGES =
            "begin dbms_session.modify_package_state(dbms_session.reinitialize); end;";

    private final Supplier<String> currentUser;
    private String resetSql = REINITIALIZE_PACKAGES;
    private String initSql;
    private List<Supplier<?>> initBinds = List.of();
    private boolean resetReportsKey;
    private Supplier<?> scopeKey;
    private String scopedSql;
    private List<Supplier<?>> scopedBinds = List.of();

    public SessionContextDataSource(DataSource target, Supplier<String> currentUser) {
        super(target);
        this.currentUser = currentUser;
    }

    /** Statement run first on every borrow; null to skip. */
    public void setResetSql(String resetSql) {
        this.resetSql = resetSql;
        this.resetReportsKey = false;
    }

    /**
     * Reset block whose only placeholder is an OUT that reports the scope key the session
     * currently holds (e.g. a tenant stored in a session table). Read in the same
     * round trip as the reset, so the scoped init below costs nothing when it is not needed.
     */
    public void setResetProbeSql(String resetSql) {
        this.resetSql = resetSql;
        this.resetReportsKey = true;
    }

    /** Block run after the reset; each {@code ?} is bound from the matching supplier. */
    public void setInitSql(String initSql, Supplier<?>... binds) {
        this.initSql = initSql;
        this.initBinds = List.of(binds);
    }

    /**
     * Block run only when {@code key} differs from what the reset probe says the session
     * holds, and committed on its own. For session state that has to be written (a row in
     * a temporary table): writing it on every request makes every request a writing
     * transaction that waits for the redo log at the end. The probe reads the session
     * itself rather than remembering what this wrapper wrote, because PL/SQL code can
     * change that state too.
     */
    public void setScopedInitSql(Supplier<?> key, String sql, Supplier<?>... binds) {
        this.scopeKey = key;
        this.scopedSql = sql;
        this.scopedBinds = List.of(binds);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return prepare(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return prepare(super.getConnection(username, password));
    }

    private static void bind(CallableStatement cs, List<Supplier<?>> binds) throws SQLException {
        for (int i = 0; i < binds.size(); i++) {
            cs.setObject(i + 1, binds.get(i).get());
        }
    }

    private Connection prepare(Connection con) throws SQLException {
        try {
            String user = currentUser.get();
            String current = null;
            if (resetSql != null) {
                try (CallableStatement cs = con.prepareCall(resetSql)) {
                    if (resetReportsKey) {
                        cs.registerOutParameter(1, java.sql.Types.VARCHAR);
                    }
                    cs.execute();
                    if (resetReportsKey) {
                        current = cs.getString(1);
                    }
                }
            }
            OracleConnection oc = con.unwrap(OracleConnection.class);
            oc.setClientInfo("OCSID.CLIENTID", user == null ? "" : user);
            if (scopedSql != null) {
                Object key = scopeKey.get();
                String wanted = key == null ? null : key.toString();
                if (!resetReportsKey || !Objects.equals(current, wanted)) {
                    try (CallableStatement cs = con.prepareCall(scopedSql)) {
                        bind(cs, scopedBinds);
                        cs.execute();
                    }
                    if (!con.getAutoCommit()) {
                        con.commit(); // nothing else is pending on a freshly borrowed connection
                    }
                }
            }
            if (initSql != null) {
                try (CallableStatement cs = con.prepareCall(initSql)) {
                    bind(cs, initBinds);
                    cs.execute();
                }
            }
            return con;
        } catch (SQLException | RuntimeException e) {
            con.close();
            throw e;
        }
    }
}
