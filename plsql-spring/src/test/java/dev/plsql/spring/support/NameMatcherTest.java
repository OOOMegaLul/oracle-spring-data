package dev.plsql.spring.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;


class NameMatcherTest {

    static final List<String> PREFIXED = List.of("NTENANT", "SCODE", "DBEGIN_DATE", "NRN", "SLOGIN", "SLOGIN_DB");

    @Test
    void typePrefixes() {
        assertThat(NameMatcher.find("tenant", PREFIXED, s -> s)).isEqualTo("NTENANT");
        assertThat(NameMatcher.find("code", PREFIXED, s -> s)).isEqualTo("SCODE");
        assertThat(NameMatcher.find("beginDate", PREFIXED, s -> s)).isEqualTo("DBEGIN_DATE");
        assertThat(NameMatcher.find("rn", PREFIXED, s -> s)).isEqualTo("NRN");
        assertThat(NameMatcher.find("login", PREFIXED, s -> s)).isEqualTo("SLOGIN");
        assertThat(NameMatcher.find("loginDb", PREFIXED, s -> s)).isEqualTo("SLOGIN_DB");
    }

    @Test
    void exactNameWinsOverPrefix() {
        assertThat(NameMatcher.find("name", List.of("NAME", "SNAME"), s -> s)).isEqualTo("NAME");
    }

    @Test
    void wordPrefixes() {
        assertThat(NameMatcher.find("flag", List.of("P_FLAG", "P_OUT"), s -> s)).isEqualTo("P_FLAG");
    }

    @Test
    void ambiguityIsAnError() {
        assertThatThrownBy(() -> NameMatcher.find("date", List.of("DDATE", "P_DATE"), s -> s))
                .hasMessageContaining("several");
    }

    @Test
    void noMatch() {
        assertThat(NameMatcher.find("id", List.of("NRN"), s -> s)).isNull();
    }
}
