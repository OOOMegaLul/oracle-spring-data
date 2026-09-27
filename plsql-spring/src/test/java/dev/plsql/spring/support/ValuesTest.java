package dev.plsql.spring.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.w3c.dom.Document;

import dev.plsql.spring.annotation.Arg;

class ValuesTest {

    record Emp(long id, String name, LocalDate hired, boolean active) {
    }

    record Named(@Arg("NFOLDER") long folder) {
    }

    public static class Bean {
        private String code;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }
    }

    static Object convert(Object v, Class<?> type) {
        return Values.convert(v, ResolvableType.forClass(type));
    }

    @Test
    void numbersAndBooleans() {
        assertThat(convert(new BigDecimal("42"), long.class)).isEqualTo(42L);
        assertThat(convert(BigDecimal.ONE, boolean.class)).isEqualTo(true);
        assertThat(convert("Y", Boolean.class)).isEqualTo(true);
        assertThat(convert(null, int.class)).isEqualTo(0);
        assertThat(convert(null, Long.class)).isNull();
        assertThat(Values.toNumber(3.5)).isEqualByComparingTo("3.5");
        assertThat(Values.toNumber(true)).isEqualByComparingTo("1");
        assertThat(Values.toBooleanNumber("true")).isEqualTo(1);
        assertThat(Values.toBooleanNumber(null)).isNull();
    }

    @Test
    void dates() {
        Timestamp ts = Timestamp.valueOf("2024-02-29 13:45:00");
        assertThat(convert(ts, LocalDate.class)).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(convert(ts, LocalDateTime.class)).isEqualTo(LocalDateTime.of(2024, 2, 29, 13, 45));
        assertThat(Values.toTimestamp(LocalDate.of(2024, 1, 2))).isEqualTo(Timestamp.valueOf("2024-01-02 00:00:00"));
        assertThatThrownBy(() -> Values.toTimestamp("2024-01-02")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapsBecomeRecordsAndBeans() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ID", BigDecimal.TEN);
        row.put("NAME", "Иванов");
        row.put("HIRED", Timestamp.valueOf("2020-01-15 00:00:00"));
        row.put("ACTIVE", BigDecimal.ONE);
        assertThat(convert(row, Emp.class)).isEqualTo(new Emp(10, "Иванов", LocalDate.of(2020, 1, 15), true));

        Object list = Values.convert(List.of(row), ResolvableType.forClassWithGenerics(List.class, Emp.class));
        assertThat(list).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);

        Bean b = (Bean) convert(Map.of("SCODE", "X1"), Bean.class);
        assertThat(b.getCode()).isEqualTo("X1");
    }

    @Test
    void optionalWrapsNull() {
        ResolvableType opt = ResolvableType.forClassWithGenerics(Optional.class, String.class);
        assertThat(Values.convert(null, opt)).isEqualTo(Optional.empty());
        assertThat(Values.convert("x", opt)).isEqualTo(Optional.of("x"));
    }

    @Test
    void propertiesByPlsqlName() {
        Emp e = new Emp(1, "A", null, true);
        assertThat(Values.property(e, "NID")).isEqualTo(1L);
        assertThat(Values.property(e, "SNAME")).isEqualTo("A");
        assertThat(Values.property(new Named(7), "NFOLDER")).isEqualTo(7L);
        assertThat(Values.property(Map.of("code", "c"), "SCODE")).isEqualTo("c");
        Bean b = new Bean();
        b.setCode("z");
        assertThat(Values.property(b, "P_CODE")).isEqualTo("z");
        assertThat(Values.property(null, "X")).isNull();
    }

    @Test
    void xmlIsParsedSafely() {
        Document d = Values.parse("<a><b>Ё</b></a>");
        assertThat(d.getDocumentElement().getTagName()).isEqualTo("a");
        assertThat(Values.toText(d)).isEqualTo("<a><b>Ё</b></a>");
        assertThat(convert("<x/>", Document.class)).isInstanceOf(Document.class);
        assertThatThrownBy(() -> Values.parse("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    enum Color { RED }

    @Test
    void enumsTravelByName() {
        assertThat(Values.toText(Color.RED)).isEqualTo("RED");
        assertThat(convert("RED", Color.class)).isEqualTo(Color.RED);
    }
}
