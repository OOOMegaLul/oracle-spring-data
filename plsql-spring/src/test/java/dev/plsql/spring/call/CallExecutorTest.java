package dev.plsql.spring.call;

import static dev.plsql.spring.test.Signatures.func;
import static dev.plsql.spring.test.Signatures.proc;
import static dev.plsql.spring.test.Signatures.xml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import dev.plsql.spring.support.CharsetGuard;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.OracleTypes;

/** Binding and reading with ojdbc, on mocks: what is set, what is registered, what is freed. */
class CallExecutorTest {

    interface Api {
        long insert(long tenant, String name);

        long length(String text);

        String wrap(String x);

        List<Map<String, Object>> cursor(long minId);
    }

    static Method m(String name) {
        return Arrays.stream(Api.class.getMethods()).filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
    }

    final CallPlanner planner = new CallPlanner(ArgumentDefaults.none());
    OracleConnection con;
    CallableStatement cs;

    @BeforeEach
    void setUp() throws SQLException {
        con = mock(OracleConnection.class);
        cs = mock(CallableStatement.class);
        when(con.unwrap(OracleConnection.class)).thenReturn(con);
        when(con.prepareCall(anyString())).thenReturn(cs);
    }

    @Test
    void scalarsInAndOut() throws SQLException {
        CallPlan p = planner.plan(m("insert"), proc(null, "P_INSERT").in("NTENANT", "NUMBER").in("SNAME", "VARCHAR2")
                .out("NRN", "NUMBER").build());
        when(cs.getBigDecimal(3)).thenReturn(new BigDecimal("33993908"));

        Object result = new CallExecutor(100).execute(con, p, new Object[]{1001L, "Петров"});

        assertThat(result).isEqualTo(33993908L);
        InOrder order = inOrder(cs);
        order.verify(cs).setBigDecimal(1, BigDecimal.valueOf(1001));
        order.verify(cs).setString(2, "Петров");
        order.verify(cs).registerOutParameter(3, Types.NUMERIC);
        order.verify(cs).execute();
        order.verify(cs).close();
    }

    @Test
    void nullsAreTypedNulls() throws SQLException {
        CallPlan p = planner.plan(m("insert"), proc(null, "P_INSERT").in("NTENANT", "NUMBER").in("SNAME", "VARCHAR2")
                .out("NRN", "NUMBER").build());
        new CallExecutor(100).execute(con, p, new Object[]{null, null});
        verify(cs).setNull(1, Types.NUMERIC);
        verify(cs).setNull(2, Types.VARCHAR);
    }

    @Test
    void temporaryClobIsFreedEvenWhenTheCallFails() throws SQLException {
        CallPlan p = planner.plan(m("length"), func(null, "F_LEN", "NUMBER").in("P_TEXT", "CLOB").build());
        Clob clob = mock(Clob.class);
        when(con.createClob()).thenReturn(clob);
        when(cs.execute()).thenThrow(new SQLException("ORA-01013", "72000", 1013));

        assertThatThrownBy(() -> new CallExecutor(100).execute(con, p, new Object[]{"text"}))
                .isInstanceOf(SQLException.class);
        verify(clob).setString(1, "text");
        verify(cs).setClob(2, clob);
        verify(clob).free();
    }

    @Test
    void textTheDatabaseCannotStoreIsRejectedBeforeAnythingIsSent() throws SQLException {
        CallPlan p = planner.plan(m("length"), func(null, "F_LEN", "NUMBER").in("P_TEXT", "CLOB").build());
        CallExecutor strict = new CallExecutor(100, CharsetGuard.forDatabase("CL8MSWIN1251", CharsetGuard.Policy.FAIL));

        assertThatThrownBy(() -> strict.execute(con, p, new Object[]{"Әлем"}))
                .isInstanceOf(CharsetGuard.UnrepresentableCharacterException.class)
                .hasMessageContaining("P_TEXT");
        verify(con, never()).createClob();
        verify(cs, never()).execute();
    }

    @Test
    void xmltypeIsReadAsClobText() throws SQLException {
        CallPlan p = planner.plan(m("wrap"), func("PKG", "WRAP", xml(null, "OUT")).add(xml("P_X", "IN")).build());
        Clob in = mock(Clob.class);
        Clob out = mock(Clob.class);
        when(con.createClob()).thenReturn(in);
        when(cs.getClob(2)).thenReturn(out);
        when(out.length()).thenReturn(11L);
        when(out.getSubString(1, 11)).thenReturn("<w><a/></w>");

        Object result = new CallExecutor(100).execute(con, p, new Object[]{"<a/>"});

        assertThat(result).isEqualTo("<w><a/></w>");
        verify(cs).registerOutParameter(2, Types.CLOB);
        verify(in).free();
        verify(out).free();
    }

    @Test
    void cursorLeftUnopenedReadsAsNull() throws SQLException {
        CallPlan p = planner.plan(m("cursor"), proc("PKG", "CUR").in("P_MIN_ID", "NUMBER").inOut("P_CUR", "REF CURSOR").build());
        when(cs.getObject(2)).thenThrow(new SQLException("ORA-24338", "HY000", 24338));

        assertThat(new CallExecutor(100).execute(con, p, new Object[]{1L})).isNull();
        verify(cs).registerOutParameter(2, OracleTypes.CURSOR);
    }

    @Test
    void otherCursorErrorsPropagate() throws SQLException {
        CallPlan p = planner.plan(m("cursor"), proc("PKG", "CUR").in("P_MIN_ID", "NUMBER").inOut("P_CUR", "REF CURSOR").build());
        when(cs.getObject(anyInt())).thenThrow(new SQLException("ORA-01001", "HY000", 1001));
        assertThatThrownBy(() -> new CallExecutor(100).execute(con, p, new Object[]{1L})).isInstanceOf(SQLException.class);
    }

    @Test
    void recordOutputsAreFoldedIntoOneMap() {
        Map<String, Object> outs = new java.util.LinkedHashMap<>();
        outs.put("P_REC.ID", 1);
        outs.put("P_REC.NAME", "a");
        outs.put("P_OTHER", 2);
        CallExecutor.foldRecords(outs, List.of("P_REC"));
        assertThat(outs).containsEntry("P_REC", Map.of("ID", 1, "NAME", "a")).containsEntry("P_OTHER", 2).hasSize(2);
    }

    @Test
    void failureToFreeIsAttachedToTheCallErrorNotSwappedForIt() throws SQLException {
        CallPlan p = planner.plan(m("length"), func(null, "F_LEN", "NUMBER").in("P_TEXT", "CLOB").build());
        Clob clob = mock(Clob.class);
        when(con.createClob()).thenReturn(clob);
        SQLException freeFailure = new SQLException("ORA-03113", "08006", 3113);
        org.mockito.Mockito.doThrow(freeFailure).when(clob).free();
        when(cs.execute()).thenThrow(new SQLException("ORA-04068", "72000", 4068));

        assertThatThrownBy(() -> new CallExecutor(100).execute(con, p, new Object[]{"text"}))
                .isInstanceOfSatisfying(SQLException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(4068);
                    assertThat(e.getSuppressed()).containsExactly(freeFailure);
                });
    }

    @Test
    void failureToFreeAfterASuccessfulCallIsReported() throws SQLException {
        CallPlan p = planner.plan(m("length"), func(null, "F_LEN", "NUMBER").in("P_TEXT", "CLOB").build());
        Clob clob = mock(Clob.class);
        when(con.createClob()).thenReturn(clob);
        org.mockito.Mockito.doThrow(new SQLException("ORA-22922", "99999", 22922)).when(clob).free();
        assertThatThrownBy(() -> new CallExecutor(100).execute(con, p, new Object[]{"text"}))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getErrorCode()).isEqualTo(22922));
    }
}
