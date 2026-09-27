package dev.plsql.spring.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import com.zaxxer.hikari.HikariDataSource;

import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.annotation.PlsqlApi;
import dev.plsql.spring.support.CharsetGuard;
import dev.plsql.spring.support.PlsqlApiInvocationHandler;
import dev.plsql.spring.support.PlsqlBusinessException;
import dev.plsql.spring.support.Values;

/** Every argument shape of LAB_PKG through the library, on a real database. */
class LabApiIT {

    static HikariDataSource ds;
    static PlsqlApiFactory factory;
    static LabApi api;

    @BeforeAll
    static void setUp() {
        ds = ItDatabase.pool(2, true);
        factory = PlsqlApiFactory.builder(ds).build();
        api = factory.create(LabApi.class);
    }

    @AfterAll
    static void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    @Test
    void booleanArgumentsTravelThroughTheBlock() {
        assertThat(api.echoBool(true)).isFalse();
        assertThat(api.echoBool(false)).isTrue();
        assertThat(api.boolInout(true)).isFalse();
        assertThat(PlsqlApiInvocationHandler.sqlOf(api, "echoBool"))
                .contains("CASE ? WHEN 1 THEN TRUE WHEN 0 THEN FALSE END");
    }

    @Test
    void recordsWithBooleanFields() {
        assertThat(api.makeRec(7, "seven")).isEqualTo(new LabApi.Rec(7L, "seven", true, LocalDate.of(2024, 2, 29)));
        assertThat(api.recInout(new LabApi.Rec(3L, "abc", true, LocalDate.of(2020, 1, 1))))
                .isEqualTo(new LabApi.Rec(30L, "ABC", false, LocalDate.of(2020, 1, 1)));
    }

    @Test
    void rowtypeTableIsTakenFromSource() {
        LabApi.Emp e = api.empRow(1);
        assertThat(e).isEqualTo(new LabApi.Emp(1, "Иванов", LocalDate.of(2020, 1, 15), true));
        assertThat(PlsqlApiInvocationHandler.sqlOf(api, "empRow")).contains("LAB_EMP%ROWTYPE");
    }

    @Test
    void indexByTablesOfScalars() {
        assertThat(api.sumIbt(List.of(1L, 2L, 39L))).isEqualByComparingTo("42");
        assertThat(api.ibtOut(3)).containsExactly("item 1", "item 2", "item 3");
        assertThat(api.sumIbt(List.of())).isEqualByComparingTo("0");
    }

    @Test
    void sqlObjectsAndCollections() {
        assertThat(api.objs(2)).containsExactly(
                new LabApi.Obj(1L, "Объект 1", LocalDate.of(2024, 1, 2)),
                new LabApi.Obj(2L, "Объект 2", LocalDate.of(2024, 1, 3)));
        assertThat(api.sumNums(List.of(5L, 6L))).isEqualTo(11);
        assertThat(api.echoObj(new LabApi.Obj(1L, "x", LocalDate.of(2024, 1, 1))))
                .isEqualTo(new LabApi.Obj(2L, "x!", LocalDate.of(2024, 1, 2)));
        assertThat(api.countObjs(List.of(new LabApi.Obj(1L, "Ёж", null), new LabApi.Obj(2L, "Ёлка", null)))).isEqualTo(6);
    }

    @Test
    void refCursorsOutReturnAndInOut() {
        List<LabApi.Emp> out = api.emps(2);
        assertThat(out).extracting(LabApi.Emp::id).containsExactly(2L, 3L);
        assertThat(api.empsF(2)).isEqualTo(out);
        assertThat(api.empsInout(1, 2)).isEqualTo(out);
        assertThat(api.empsInout(0, 2)).as("cursor left unopened").isNull();
    }

    @Test
    void xmltype() {
        assertThat(api.wrapXml("<a>Ёж</a>")).isEqualTo("<wrapped><a>Ёж</a></wrapped>");
        assertThat(api.wrapXml(null)).isNull();
        assertThat(api.xmlIsNull(null)).isEqualTo(1);
        assertThat(api.xmlIsNull("<x/>")).isEqualTo(0);
        assertThat(api.xmlOut(5)).isEqualTo("<n>5</n>");
        assertThat(api.wrapXmlOptional(null)).isEmpty();
        Document dom = api.wrapXmlDom(Values.parse("<b>1</b>"));
        assertThat(dom.getDocumentElement().getTagName()).isEqualTo("wrapped");
        assertThat(api.xrecInout(new LabApi.XRec(1L, "<c/>"))).isEqualTo(new LabApi.XRec(2L, "<wrapped><c/></wrapped>"));
        assertThat(api.xrecInout(new LabApi.XRec(1L, null))).isEqualTo(new LabApi.XRec(2L, null));
        assertThatThrownBy(() -> api.wrapXml("<not closed>")).hasMessageContaining("ORA-");
    }

    @Test
    void businessErrorsKeepOnlyTheMessage() {
        assertThatThrownBy(() -> api.fail("Отпуск пересекается с командировкой"))
                .isInstanceOfSatisfying(PlsqlBusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(20042);
                    assertThat(e.getMessage()).isEqualTo("Отпуск пересекается с командировкой");
                });
    }

    @Test
    void clobsBothWays() {
        String big = "я".repeat(100_000);
        assertThat(api.clobLen(big)).isEqualTo(100_000);
        assertThat(api.bigClob(10_000)).hasSize(100_000);
    }

    @Test
    void defaultsOverloadsAndSeveralOuts() {
        assertThat(api.withDefaults(1)).isEqualTo("1/B/2000-01-01");
        assertThat(api.withDefaults(1, "Z")).isEqualTo("1/Z/2000-01-01");
        assertThat(api.overNumber(5)).isEqualTo("number 5");
        assertThat(api.overString("5")).isEqualTo("varchar2 5");
        LabApi.TwoOuts t = api.twoOuts(21);
        assertThat(t.double_()).isEqualByComparingTo(BigDecimal.valueOf(42));
        assertThat(t.text()).isEqualTo("got 21");
        api.noop();
    }

    @Test
    void queriesAndDefaultMethods() {
        assertThat(api.findEmp(3)).get().extracting(LabApi.Emp::name).isEqualTo("Smith");
        assertThat(api.findEmp(99)).isEmpty();
        assertThat(api.countEmps()).isEqualTo(3);
        assertThat(api.findByName("Петров")).extracting(LabApi.Emp::id).containsExactly(2L);
        assertThat(api.describe(1)).isEqualTo("Иванов");
        assertThat(api.touchEmp(1)).isEqualTo(1);
        assertThat(api.touchEmp(99)).isZero();
    }

    @Test
    void textOutsideTheDatabaseCharsetIsRejected() {
        String kazakh = "Әлем";
        if (ItDatabase.singleByteCyrillic()) {
            assertThatThrownBy(() -> api.echoStr(kazakh))
                    .isInstanceOf(CharsetGuard.UnrepresentableCharacterException.class)
                    .hasMessageContaining("P_S").hasMessageContaining("U+04D8").hasMessageContaining("position 1");
            assertThatThrownBy(() -> api.findByName(kazakh))
                    .isInstanceOf(CharsetGuard.UnrepresentableCharacterException.class);
            LabApi lenient = PlsqlApiFactory.builder(ds).charsetPolicy(CharsetGuard.Policy.IGNORE).build().create(LabApi.class);
            assertThat(lenient.echoStr(kazakh)).isEqualTo("?лем");
        } else {
            assertThat(api.echoStr(kazakh)).isEqualTo(kazakh);
        }
        assertThat(api.echoStr("Ёжик € № — «»")).isEqualTo("Ёжик € № — «»");
    }

    @PlsqlApi(packageName = "LAB_PKG")
    interface Unsupported {
        List<Object> recs(int n);

        void noSuchProcedure();

        String echoStr(String s, int extra);
    }

    @Test
    void mismatchesAreReportedTogetherAtCreation() {
        assertThatThrownBy(() -> factory.create(Unsupported.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported.recs")
                .hasMessageContaining("index-by table")
                .hasMessageContaining("Unsupported.noSuchProcedure")
                .hasMessageContaining("not found")
                .hasMessageContaining("'extra' has no matching argument");
    }
}
