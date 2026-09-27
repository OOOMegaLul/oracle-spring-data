package dev.plsql.spring.support;

import static dev.plsql.spring.test.Signatures.func;
import static dev.plsql.spring.test.Signatures.proc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.annotation.PlsqlApi;
import dev.plsql.spring.test.Signatures;
import oracle.jdbc.OracleConnection;

/** Proxy behaviour on mocks: startup validation, units of work, retries, error mapping. */
class PlsqlApiInvocationHandlerTest {

    @PlsqlApi(packageName = "PKG")
    interface Api {
        long next(long tenant);

        void touch(long tenant);

        default long twice(long tenant) {
            return next(tenant) * 2;
        }
    }

    @PlsqlApi(packageName = "PKG")
    interface Queries {
        @dev.plsql.spring.annotation.SqlQuery("/* audit */ update t set x = 1 where id = :id")
        int touch(long id);

        @dev.plsql.spring.annotation.SqlQuery("select name from t where id = :id")
        java.util.Optional<String> name(long id);
    }

    @PlsqlApi(packageName = "PKG")
    interface BadQuery {
        @dev.plsql.spring.annotation.SqlQuery("select 1 from dual where x = :missing")
        int q(long other);
    }

    @PlsqlApi(packageName = "PKG")
    interface Broken {
        long missing();

        long next(String wrongName);
    }

    DataSource ds;
    OracleConnection con;
    CallableStatement cs;
    Api api;

    @BeforeEach
    void setUp() throws SQLException {
        ds = mock(DataSource.class);
        con = mock(OracleConnection.class);
        cs = mock(CallableStatement.class);
        when(ds.getConnection()).thenReturn(con);
        when(con.unwrap(OracleConnection.class)).thenReturn(con);
        when(con.prepareCall(anyString())).thenReturn(cs);
        when(con.getAutoCommit()).thenReturn(true);
        api = factory().create(Api.class);
    }

    PlsqlApiFactory factory() {
        return PlsqlApiFactory.builder(ds)
                .signatureSource(Signatures.source(
                        func("PKG", "NEXT", "NUMBER").in("NTENANT", "NUMBER").build(),
                        proc("PKG", "TOUCH").in("NTENANT", "NUMBER").build()))
                .databaseCharset("AL32UTF8")
                .build();
    }

    @Test
    void callsGoThroughThePlannedBlock() throws SQLException {
        when(cs.getBigDecimal(1)).thenReturn(BigDecimal.TEN);
        assertThat(api.next(1001)).isEqualTo(10);
        assertThat(api.twice(1)).isEqualTo(20);
        verify(con, atLeastOnce()).prepareCall("BEGIN\n  ? := APP.PKG.NEXT(NTENANT => ?);\nEND;");
        assertThat(api.toString()).contains(Api.class.getName());
        assertThat(api).isEqualTo(api).isNotEqualTo(new Object());
    }

    @Test
    void allMismatchesAreReportedAtCreation() {
        assertThatThrownBy(() -> factory().create(Broken.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Broken.missing -> PKG.MISSING: not found in the database")
                .hasMessageContaining("Broken.next -> PKG.NEXT: parameter 'wrongName' has no matching argument");
        assertThatThrownBy(() -> factory().create(String.class)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void applicationErrorBecomesBusinessException() throws SQLException {
        when(cs.execute()).thenThrow(new SQLException("ORA-20001: Нельзя\nORA-06512: at line 1", "72000", 20001));
        assertThatThrownBy(() -> api.touch(1)).isInstanceOf(PlsqlBusinessException.class).hasMessage("Нельзя");
        verify(con).close();
    }

    @Test
    void discardedPackageStateIsRetriedOnce() throws SQLException {
        when(cs.execute())
                .thenThrow(new SQLException("ORA-04068", "72000", 4068))
                .thenReturn(false);
        api.touch(1);
        verify(cs, times(2)).execute();
    }

    @Test
    void secondDiscardIsNotRetried() throws SQLException {
        when(cs.execute()).thenThrow(new SQLException("ORA-04068", "72000", 4068));
        assertThatThrownBy(() -> api.touch(1)).hasMessageContaining("ORA-04068");
        verify(cs, times(2)).execute();
    }

    @Test
    void withoutATransactionAndAutoCommitOffTheCallIsItsOwnUnitOfWork() throws SQLException {
        when(con.getAutoCommit()).thenReturn(false);
        api.touch(1);
        verify(con).commit();

        when(cs.execute()).thenThrow(new SQLException("ORA-00001", "23000", 1));
        assertThatThrownBy(() -> api.touch(1)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        verify(con).rollback();
    }

    @Test
    void autoCommitConnectionsAreLeftAlone() throws SQLException {
        api.touch(1);
        verify(con, never()).commit();
        verify(con, never()).rollback();
    }

    @Test
    void insideASpringTransactionNothingIsCommittedPerCall() throws SQLException {
        when(con.getAutoCommit()).thenReturn(false);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.executeWithoutResult(s -> {
            api.touch(1);
            api.touch(2);
            try {
                verify(con, never()).commit();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        verify(con, times(1)).commit(); // by the transaction manager, once
        verify(ds, times(1)).getConnection();
    }

    @Test
    void sqlOfShowsTheBlock() {
        assertThat(PlsqlApiInvocationHandler.sqlOf(api, "touch")).isEqualTo("BEGIN\n  APP.PKG.TOUCH(NTENANT => ?);\nEND;");
        assertThat(PlsqlApiInvocationHandler.sqlOf(api, "twice")).isNull();
        assertThat(PlsqlApiInvocationHandler.subprogramName(
                Api.class.getMethods()[0])).matches("[A-Z_]+");
    }

    @Test
    void signaturesOfAnInterfaceAreReadInOneBatch() {
        java.util.concurrent.atomic.AtomicInteger single = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger batch = new java.util.concurrent.atomic.AtomicInteger();
        dev.plsql.spring.meta.SignatureSource fixture = Signatures.source(
                func("PKG", "NEXT", "NUMBER").in("NTENANT", "NUMBER").build(),
                proc("PKG", "TOUCH").in("NTENANT", "NUMBER").build());
        dev.plsql.spring.meta.SignatureSource counting = new dev.plsql.spring.meta.SignatureSource() {
            @Override
            public java.util.List<dev.plsql.spring.meta.SubprogramInfo> find(String schema, String pkg, String name) {
                single.incrementAndGet();
                return fixture.find(schema, pkg, name);
            }

            @Override
            public java.util.Map<String, java.util.List<dev.plsql.spring.meta.SubprogramInfo>> findAll(
                    String schema, String pkg, java.util.Collection<String> names) {
                batch.incrementAndGet();
                assertThat(names).containsExactlyInAnyOrder("NEXT", "TOUCH");
                java.util.Map<String, java.util.List<dev.plsql.spring.meta.SubprogramInfo>> m = new java.util.HashMap<>();
                names.forEach(n -> m.put(n, fixture.find(schema, pkg, n)));
                return m;
            }
        };
        PlsqlApiFactory.builder(ds).signatureSource(counting).databaseCharset("AL32UTF8").build().create(Api.class);
        assertThat(batch).hasValue(1);
        assertThat(single).hasValue(0);
    }

    @Test
    void queryParametersAreCheckedAtCreation() {
        assertThatThrownBy(() -> factory().create(BadQuery.class))
                .hasMessageContaining("BadQuery.q").hasMessageContaining("missing");
    }

    @Test
    void queryResultKindComesFromJdbcNotFromTheFirstWord() throws SQLException {
        java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
        when(con.prepareStatement(anyString())).thenReturn(ps);
        Queries q = factory().create(Queries.class);

        when(ps.execute()).thenReturn(false);
        when(ps.getUpdateCount()).thenReturn(1);
        assertThat(q.touch(5)).isEqualTo(1);
        verify(con).prepareStatement("/* audit */ update t set x = 1 where id = ?");

        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        java.sql.ResultSetMetaData md = mock(java.sql.ResultSetMetaData.class);
        when(ps.execute()).thenReturn(true);
        when(ps.getResultSet()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getMetaData()).thenReturn(md);
        when(md.getColumnCount()).thenReturn(1);
        when(rs.getString(1)).thenReturn("Иванов");
        assertThat(q.name(1)).contains("Иванов");
    }
}
