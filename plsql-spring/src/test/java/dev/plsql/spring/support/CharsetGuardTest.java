package dev.plsql.spring.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CharsetGuardTest {

    static final CharsetGuard CP1251 = CharsetGuard.forDatabase("CL8MSWIN1251", CharsetGuard.Policy.FAIL);

    @Test
    void cyrillicAndCommonPunctuationPass() {
        assertThat(CP1251.isActive()).isTrue();
        assertThatCode(() -> CP1251.check("SNAME", "Ёжик в тумане € № — «»")).doesNotThrowAnyException();
        assertThatCode(() -> CP1251.check("SNAME", null)).doesNotThrowAnyException();
    }

    @Test
    void characterOutsideTheCodePageIsNamedWithItsPosition() {
        assertThatThrownBy(() -> CP1251.check("SNAME", "Мырзакул Әлем"))
                .isInstanceOf(CharsetGuard.UnrepresentableCharacterException.class)
                .hasMessage("SNAME: character 'Ә' (U+04D8) at position 10 cannot be stored in a CL8MSWIN1251 database");
        assertThatThrownBy(() -> CP1251.check("SNOTE", "ok 😀"))
                .hasMessageContaining("U+1F600").hasMessageContaining("position 4");
    }

    @Test
    void unicodeDatabasesAndIgnorePolicyCheckNothing() {
        assertThat(CharsetGuard.forDatabase("AL32UTF8", CharsetGuard.Policy.FAIL).isActive()).isFalse();
        assertThat(CharsetGuard.forDatabase("CL8MSWIN1251", CharsetGuard.Policy.IGNORE).isActive()).isFalse();
        assertThat(CharsetGuard.forDatabase(null, CharsetGuard.Policy.FAIL).isActive()).isFalse();
        assertThatCode(() -> CharsetGuard.none().check("X", "Ә")).doesNotThrowAnyException();
    }
}
