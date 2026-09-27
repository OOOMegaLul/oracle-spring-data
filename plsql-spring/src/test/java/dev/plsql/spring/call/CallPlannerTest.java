package dev.plsql.spring.call;

import static dev.plsql.spring.test.Signatures.arg;
import static dev.plsql.spring.test.Signatures.field;
import static dev.plsql.spring.test.Signatures.func;
import static dev.plsql.spring.test.Signatures.indexTable;
import static dev.plsql.spring.test.Signatures.proc;
import static dev.plsql.spring.test.Signatures.record;
import static dev.plsql.spring.test.Signatures.rowtype;
import static dev.plsql.spring.test.Signatures.xml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import dev.plsql.spring.annotation.Arg;
import dev.plsql.spring.annotation.Procedure;
import dev.plsql.spring.meta.ArgKind;
import dev.plsql.spring.meta.SubprogramInfo;

class CallPlannerTest {

    static final CallPlanner PLANNER = new CallPlanner(ArgumentDefaults.none());

    interface Api {
        void deleteFolder(long tenant, long rn);

        long versionOf(String unit);

        void withDefault(long a);

        @Procedure(nullForMissing = true)
        long create(String name);

        record NewUser(String login, String name, @Arg("NFOLDER") long folder) {
        }

        long insertUser(NewUser user);

        boolean flag(boolean flag);

        boolean toggle(boolean flag);

        record Rec(Long id, Boolean flag, String body) {
        }

        Rec rec(Rec rec);

        Map<String, Object> row(long id);

        String wrap(String x);

        List<Map<String, Object>> cursor(long minId);

        String over(@Arg("P_X") long x);

        String overAmbiguous(@Arg("P_X") Object x);

        record Outs(BigDecimal doubled, @Arg("P_TEXT") String label) {
        }

        Outs twoOuts(long in);

        long noOuts(long a);

        void unknown(long nope);

        BigDecimal sum(List<Long> vals);

        void refresh(long rn);

        record Wrong(BigDecimal doubled, String text) {
        }

        Wrong twoOutsWrong(long in);
    }

    static Method m(String name) {
        return Arrays.stream(Api.class.getMethods()).filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void scalarsBindStraightIntoTheCallWithNamedNotation() {
        CallPlan p = PLANNER.plan(m("deleteFolder"),
                proc(null, "P_FOLDER_DELETE").in("NTENANT", "NUMBER").in("NRN", "NUMBER").build());
        assertThat(p.sql()).isEqualTo("BEGIN\n  APP.P_FOLDER_DELETE(NTENANT => ?, NRN => ?);\nEND;");
        assertThat(p.binds()).extracting(CallPlan.Bind::kind).containsExactly(ArgKind.NUMBER, ArgKind.NUMBER);
        assertThat(p.binds().get(0).in().apply(new Object[]{5L, 7L})).isEqualTo(5L);
        assertThat(p.binds().get(1).in().apply(new Object[]{5L, 7L})).isEqualTo(7L);
    }

    @Test
    void functionReturnComesFirstAndDefaultsFillContextArguments() {
        Supplier<Object> tenant = () -> 1001L;
        CallPlanner planner = new CallPlanner(ArgumentDefaults.byName(Map.of("NTENANT", tenant, "NMODE", () -> 0)));
        CallPlan p = planner.plan(m("versionOf"), func(null, "F_UNIT_VERSION", "NUMBER")
                .in("NMODE", "NUMBER").in("NTENANT", "NUMBER").in("SUNIT", "VARCHAR2").build());
        assertThat(p.sql()).isEqualTo("BEGIN\n  ? := APP.F_UNIT_VERSION(NMODE => ?, NTENANT => ?, SUNIT => ?);\nEND;");
        assertThat(p.binds().get(0).outKey()).isEqualTo(CallPlanner.RETURN_KEY);
        assertThat(p.binds().get(2).in().apply(new Object[]{"X"})).isEqualTo(1001L);
        assertThat(p.binds().get(3).in().apply(new Object[]{"X"})).isEqualTo("X");
    }

    @Test
    void defaultedArgumentsAreLeftOut() {
        CallPlan p = PLANNER.plan(m("withDefault"), proc("PKG", "WITH_DEFAULT").in("P_A", "NUMBER").inDefault("P_B", "VARCHAR2").build());
        assertThat(p.sql()).contains("APP.PKG.WITH_DEFAULT(P_A => ?)");
    }

    @Test
    void requiredArgumentMustBeSupplied() {
        SubprogramInfo sp = proc(null, "P_X").in("NTENANT", "NUMBER").in("NRN", "NUMBER").build();
        assertThatThrownBy(() -> PLANNER.plan(m("withDefault"), sp))
                .hasMessageContaining("'a' has no matching argument").hasMessageContaining("NTENANT IN");
        Method rnOnly = m("unknown");
        assertThatThrownBy(() -> PLANNER.plan(rnOnly, proc(null, "P_X").in("NOPE", "NUMBER").in("NRN", "NUMBER").build()))
                .hasMessageContaining("required argument NRN (NUMBER) is not supplied");
    }

    @Test
    void nullForMissingPassesNull() {
        CallPlan p = PLANNER.plan(m("create"), proc(null, "P_INSERT").in("SNAME", "VARCHAR2").in("SNOTE", "VARCHAR2")
                .out("NRN", "NUMBER").build());
        assertThat(p.sql()).contains("SNAME => ?, SNOTE => ?, NRN => ?");
        assertThat(p.binds().get(1).in().apply(new Object[]{"x"})).isNull();
    }

    @Test
    void recordParameterIsSpreadOverArguments() {
        CallPlan p = PLANNER.plan(m("insertUser"), proc(null, "P_USER_INSERT").in("SLOGIN", "VARCHAR2")
                .in("SNAME", "VARCHAR2").in("NFOLDER", "NUMBER").out("NRN", "NUMBER").build());
        Object[] args = {new Api.NewUser("IVANOV", "Иванов", 42)};
        assertThat(p.binds().get(0).in().apply(args)).isEqualTo("IVANOV");
        assertThat(p.binds().get(1).in().apply(args)).isEqualTo("Иванов");
        assertThat(p.binds().get(2).in().apply(args)).isEqualTo(42L);
        assertThat(p.result().returnKey()).isEqualTo("NRN");
    }

    @Test
    void booleanInIsInlinedAndBooleanOutUsesAVariable() {
        CallPlan in = PLANNER.plan(m("flag"), func("PKG", "FLAG", "PL/SQL BOOLEAN").in("P_FLAG", "PL/SQL BOOLEAN").build());
        assertThat(in.sql()).isEqualTo("""
                DECLARE
                  v1 BOOLEAN;
                BEGIN
                  v1 := APP.PKG.FLAG(P_FLAG => (CASE ? WHEN 1 THEN TRUE WHEN 0 THEN FALSE END));
                  ? := CASE WHEN v1 THEN 1 WHEN NOT v1 THEN 0 END;
                END;""");
        CallPlan inOut = PLANNER.plan(m("toggle"), proc("PKG", "TOGGLE").inOut("P_FLAG", "PL/SQL BOOLEAN").build());
        assertThat(inOut.sql()).contains("v1 := CASE ? WHEN 1 THEN TRUE WHEN 0 THEN FALSE END;")
                .contains("APP.PKG.TOGGLE(P_FLAG => v1);").contains("? := CASE WHEN v1 THEN 1 WHEN NOT v1 THEN 0 END;");
    }

    @Test
    void recordFieldsOfEveryKindTravelFieldByField() {
        SubprogramInfo sp = proc("PKG", "REC").add(record("P_REC", "IN/OUT", "PKG", "REC_T",
                field("ID", "NUMBER"), field("FLAG", "PL/SQL BOOLEAN"), field("BODY", "OPAQUE/XMLTYPE"))).build();
        CallPlan p = PLANNER.plan(m("rec"), sp);
        assertThat(p.sql()).isEqualTo("""
                DECLARE
                  v1 APP.PKG.REC_T;
                  v2 CLOB;
                BEGIN
                  v1.ID := ?;
                  v1.FLAG := CASE ? WHEN 1 THEN TRUE WHEN 0 THEN FALSE END;
                  v2 := ?; IF v2 IS NOT NULL THEN v1.BODY := XMLTYPE(v2); END IF;
                  APP.PKG.REC(P_REC => v1);
                  ? := v1.ID;
                  ? := CASE WHEN v1.FLAG THEN 1 WHEN NOT v1.FLAG THEN 0 END;
                  ? := CASE WHEN v1.BODY IS NULL THEN NULL ELSE v1.BODY.getClobVal() END;
                END;""");
        assertThat(p.recordOuts()).containsExactly("P_REC");
        Object[] args = {new Api.Rec(1L, true, "<a/>")};
        assertThat(p.binds().get(1).in().apply(args)).isEqualTo(true);
        assertThat(p.binds().get(2).in().apply(args)).isEqualTo("<a/>");
    }

    @Test
    void rowtypeIsDeclaredFromTheTable() {
        CallPlan p = PLANNER.plan(m("row"), proc("PKG", "ROW").in("P_ID", "NUMBER")
                .add(rowtype("P_ROW", "OUT", "EMP", field("ID", "NUMBER"), field("NAME", "VARCHAR2"))).build());
        assertThat(p.sql()).contains("v1 APP.EMP%ROWTYPE;").contains("? := v1.ID;").contains("? := v1.NAME;");
    }

    @Test
    void xmltypeIsNullSafe() {
        CallPlan p = PLANNER.plan(m("wrap"), func("PKG", "WRAP", xml(null, "OUT")).add(xml("P_X", "IN")).build());
        assertThat(p.sql()).isEqualTo("""
                DECLARE
                  v1 XMLTYPE;
                  v2 CLOB;
                  v3 XMLTYPE;
                BEGIN
                  v2 := ?; IF v2 IS NOT NULL THEN v1 := XMLTYPE(v2); END IF;
                  v3 := APP.PKG.WRAP(P_X => v1);
                  ? := CASE WHEN v3 IS NULL THEN NULL ELSE v3.getClobVal() END;
                END;""");
        assertThat(p.binds()).extracting(CallPlan.Bind::kind).containsExactly(ArgKind.CLOB, ArgKind.XMLTYPE);
    }

    @Test
    void inOutCursorGoesThroughAVariableAndInCursorIsRejected() {
        CallPlan p = PLANNER.plan(m("cursor"), proc("PKG", "CUR").in("P_MIN_ID", "NUMBER").inOut("P_CUR", "REF CURSOR").build());
        assertThat(p.sql()).contains("v1 SYS_REFCURSOR;").contains("P_CUR => v1").contains("? := v1;");
        assertThatThrownBy(() -> PLANNER.plan(m("cursor"), proc("PKG", "CUR").in("P_MIN_ID", "NUMBER").in("P_CUR", "REF CURSOR").build()))
                .hasMessageContaining("IN REF CURSOR");
    }

    @Test
    void overloadIsChosenByJavaType() {
        SubprogramInfo number = proc("PKG", "OVER").overload("1").in("P_X", "NUMBER").out("P_OUT", "VARCHAR2").build();
        SubprogramInfo text = proc("PKG", "OVER").overload("2").in("P_X", "VARCHAR2").out("P_OUT", "VARCHAR2").build();
        assertThat(PLANNER.plan(m("over"), List.of(number, text)).target()).isSameAs(number);
        assertThatThrownBy(() -> PLANNER.plan(m("overAmbiguous"), List.of(number, text)))
                .hasMessageContaining("matches 2 overloads");
    }

    @Test
    void severalOutsFillAJavaRecord() {
        CallPlan p = PLANNER.plan(m("twoOuts"), proc("PKG", "TWO").in("P_IN", "NUMBER")
                .out("P_DOUBLED", "NUMBER").out("P_TEXT", "VARCHAR2").build());
        assertThat(p.result().outsToType()).isTrue();
        Object r = p.result().assemble(Map.of("P_DOUBLED", BigDecimal.TEN, "P_TEXT", "t"));
        assertThat(r).isEqualTo(new Api.Outs(BigDecimal.TEN, "t"));
    }

    @Test
    void returnValueNeedsAnOutArgument() {
        assertThatThrownBy(() -> PLANNER.plan(m("noOuts"), proc(null, "P").in("A", "NUMBER").build()))
                .hasMessageContaining("has no OUT arguments");
    }

    @Test
    void notFoundAndUnsupportedShapes() {
        assertThatThrownBy(() -> PLANNER.plan(m("noOuts"), List.of())).hasMessage("not found in the database");
        SubprogramInfo recs = func("PKG", "RECS", indexTable(null, "OUT", "PL/SQL RECORD")).in("P_N", "NUMBER").build();
        assertThat(CallPlanner.supportIssues(recs)).singleElement().asString().contains("index-by table of PL/SQL RECORD");
        assertThatThrownBy(() -> PLANNER.plan(m("sum"), func("PKG", "SUM", indexTable(null, "OUT", "DATE"))
                .add(indexTable("P_VALS", "IN", "NUMBER")).build()))
                .hasMessageContaining("RETURN: index-by table of DATE");
        SubprogramInfo ok = func("PKG", "SUM", "NUMBER").add(indexTable("P_VALS", "IN", "NUMBER")).build();
        assertThat(CallPlanner.supportIssues(ok)).isEmpty();
        assertThat(PLANNER.plan(m("sum"), ok).binds().get(1).kind()).isEqualTo(ArgKind.INDEX_TABLE);
        SubprogramInfo anydata = proc(null, "P").add(arg("P_X", "OPAQUE/ANYDATA", "IN")).build();
        assertThat(CallPlanner.supportIssues(anydata)).containsExactly("P_X: type OPAQUE/ANYDATA");
    }

    @Test
    void javaTypesAcceptedPerKind() {
        assertThat(CallPlanner.accepts(ArgKind.NUMBER, long.class)).isTrue();
        assertThat(CallPlanner.accepts(ArgKind.NUMBER, String.class)).isFalse();
        assertThat(CallPlanner.accepts(ArgKind.XMLTYPE, org.w3c.dom.Document.class)).isTrue();
        assertThat(CallPlanner.accepts(ArgKind.BOOLEAN, boolean.class)).isTrue();
        assertThat(CallPlanner.accepts(ArgKind.SQL_COLLECTION, List.class)).isTrue();
        assertThat(CallPlanner.accepts(ArgKind.RECORD, Api.Rec.class)).isTrue();
        assertThat(CallPlanner.accepts(ArgKind.RECORD, String.class)).isFalse();
    }

    @Test
    void parameterOnAnOutOnlyArgumentIsRejected() {
        assertThatThrownBy(() -> PLANNER.plan(m("refresh"), proc(null, "P_REFRESH").out("NRN", "NUMBER").build()))
                .hasMessageContaining("parameter 'rn' maps to OUT argument NRN");
        assertThatThrownBy(() -> PLANNER.plan(m("insertUser"), proc(null, "P_USER_INSERT").in("SLOGIN", "VARCHAR2")
                .in("SNAME", "VARCHAR2").out("NFOLDER", "NUMBER").build()))
                .hasMessageContaining("property 'folder' of NewUser maps to OUT argument NFOLDER");
    }

    @Test
    void resultComponentsThatNoOutFillsAreRejected() {
        assertThatThrownBy(() -> PLANNER.plan(m("twoOutsWrong"), proc("PKG", "TWO").in("P_IN", "NUMBER")
                .out("P_DOUBLE", "NUMBER").out("P_TEXT", "VARCHAR2").build()))
                .hasMessageContaining("Wrong components [doubled] match no OUT argument")
                .hasMessageContaining("[P_DOUBLE, P_TEXT]");
    }

    @Test
    void defaultsByNameIgnoreCase() {
        Supplier<Object> tenant = () -> 7L;
        ArgumentDefaults d = ArgumentDefaults.byName(Map.of("nTenant", tenant));
        SubprogramInfo sp = proc(null, "P").in("NTENANT", "NUMBER").build();
        assertThat(d.lookup(sp, sp.arguments().get(0))).isSameAs(tenant);
        assertThatThrownBy(() -> ArgumentDefaults.byName(Map.of("NTENANT", tenant, "ntenant", tenant)))
                .hasMessageContaining("given twice");
    }
}
