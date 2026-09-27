package dev.plsql.spring.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import dev.plsql.spring.call.CallPlanner;
import dev.plsql.spring.meta.ArgKind;
import dev.plsql.spring.meta.ArgumentInfo;
import dev.plsql.spring.meta.DictionarySignatureSource;
import dev.plsql.spring.meta.SubprogramInfo;

/** How the data dictionary describes LAB_PKG, read through the library. */
class DictionaryIT {

    static HikariDataSource ds;
    static DictionarySignatureSource source;

    @BeforeAll
    static void setUp() {
        ds = ItDatabase.pool(1, true);
        source = new DictionarySignatureSource(ds);
    }

    @AfterAll
    static void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    @Test
    void rowtypeArgumentGetsItsTableFromTheSource() {
        SubprogramInfo sp = only("EMP_ROW");
        ArgumentInfo row = sp.arguments().get(1);
        assertThat(row.kind()).isEqualTo(ArgKind.RECORD);
        assertThat(row.declaredType()).isEqualTo(ItDatabase.USER + ".LAB_EMP");
        assertThat(row.children()).extracting(ArgumentInfo::name).containsExactly("ID", "NAME", "HIRED", "FLAG");
    }

    @Test
    void objectAttributesComeFromTypeAttrs() {
        ArgumentInfo obj = only("ECHO_OBJ").arguments().get(0);
        assertThat(obj.kind()).isEqualTo(ArgKind.OBJECT);
        assertThat(obj.children()).extracting(ArgumentInfo::name).containsExactly("ID", "NAME", "DT");
        ArgumentInfo coll = only("COUNT_OBJS").arguments().get(0);
        assertThat(coll.kind()).isEqualTo(ArgKind.SQL_COLLECTION);
        assertThat(coll.children().get(0).children()).extracting(ArgumentInfo::name).containsExactly("ID", "NAME", "DT");
    }

    @Test
    void xmltypeAndCursors() {
        assertThat(only("WRAP_XML").returnValue().kind()).isEqualTo(ArgKind.XMLTYPE);
        assertThat(only("EMPS_INOUT").arguments().get(2).inOut()).isEqualTo("IN/OUT");
    }

    @Test
    void defaultsAndOverloads() {
        assertThat(only("WITH_DEFAULTS").arguments()).extracting(ArgumentInfo::defaulted)
                .containsExactly(false, true, true, false);
        assertThat(source.find(null, "LAB_PKG", "OVER")).hasSize(2);
        assertThat(only("NOOP").arguments()).isEmpty();
    }

    @Test
    void missingNamesAreEmptyNotErrors() {
        assertThat(source.find(null, "LAB_PKG", "NO_SUCH")).isEmpty();
        assertThat(source.find(null, "NO_SUCH_PKG", "X")).isEmpty();
        assertThat(source.find(null, null, "NO_SUCH_PROC")).isEmpty();
    }

    @Test
    void onlyIndexTablesOfRecordsAreUnsupported() {
        assertThat(CallPlanner.supportIssues(only("RECS"))).singleElement().asString().contains("index-by table");
        for (String name : List.of("ECHO_BOOL", "REC_INOUT", "EMP_ROW", "WRAP_XML", "XREC_INOUT", "EMPS_INOUT", "ECHO_OBJ")) {
            assertThat(CallPlanner.supportIssues(only(name))).as(name).isEmpty();
        }
    }

    private static SubprogramInfo only(String name) {
        List<SubprogramInfo> all = source.find(null, "LAB_PKG", name);
        assertThat(all).as(name).hasSize(1);
        return all.get(0);
    }

    @Test
    void codeThatDoesNotCompileIsReportedAsInvalidNotMissing() throws Exception {
        try (java.sql.Connection c = ItDatabase.connect(); java.sql.Statement st = c.createStatement()) {
            st.execute("create or replace procedure lab_broken(p_x number) is begin no_such_thing; end;");
            st.execute("create or replace package lab_broken_pkg as procedure p(p_x no_such_type); end;");
            try {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> source.find(null, null, "LAB_BROKEN"))
                        .isInstanceOf(dev.plsql.spring.meta.DictionaryReader.InvalidObjectException.class)
                        .hasMessageContaining("LAB_BROKEN is INVALID");
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> source.find(null, "LAB_BROKEN_PKG", "P"))
                        .hasMessageContaining("LAB_BROKEN_PKG is INVALID");
            } finally {
                st.execute("drop procedure lab_broken");
                st.execute("drop package lab_broken_pkg");
            }
        }
    }

    @Test
    void batchReadGivesEveryRequestedName() throws Exception {
        java.util.Map<String, List<SubprogramInfo>> pkg = source.findAll(null, "LAB_PKG", List.of("ECHO_BOOL", "OVER", "NO_SUCH"));
        assertThat(pkg.get("ECHO_BOOL")).hasSize(1);
        assertThat(pkg.get("OVER")).hasSize(2);
        assertThat(pkg.get("NO_SUCH")).isEmpty();
        try (java.sql.Connection c = ItDatabase.connect(); java.sql.Statement st = c.createStatement()) {
            st.execute("create or replace function lab_standalone(p_x number) return number is begin return p_x + 1; end;");
            st.execute("create or replace procedure lab_noargs is begin null; end;");
            try {
                java.util.Map<String, List<SubprogramInfo>> sa = source.findAll(null, null,
                        List.of("LAB_STANDALONE", "LAB_NOARGS", "NO_SUCH_PROC"));
                assertThat(sa.get("LAB_STANDALONE")).singleElement().satisfies(sp -> {
                    assertThat(sp.isFunction()).isTrue();
                    assertThat(sp.arguments()).extracting(ArgumentInfo::name).containsExactly("P_X");
                });
                assertThat(sa.get("LAB_NOARGS")).singleElement().satisfies(sp -> assertThat(sp.arguments()).isEmpty());
                assertThat(sa.get("NO_SUCH_PROC")).isEmpty();
            } finally {
                st.execute("drop function lab_standalone");
                st.execute("drop procedure lab_noargs");
            }
        }
    }
}
