package dev.plsql.spring.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Тесты {@link CharsetGuard}. Однобайтовая база (например, CL8MSWIN1251) молча заменяет символ
 * вне своей кодовой страницы на {@code ?}, поэтому такой текст нужно останавливать до отправки
 * и называть аргумент и позицию символа.
 */
class CharsetGuardTest {

    /** Проверка для базы в CL8MSWIN1251 с политикой {@code FAIL}. */
    static final CharsetGuard CP1251 = CharsetGuard.forDatabase("CL8MSWIN1251", CharsetGuard.Policy.FAIL);

    /**
     * Проверяет, что кириллица (включая «Ё») и знаки, которые есть в windows-1251 (€, №, длинное
     * тире, кавычки-ёлочки), проходят, и что {@code null} ошибкой не считается.
     */
    @Test
    void cyrillicAndCommonPunctuationPass() {
        assertThat(CP1251.isActive()).isTrue();
        assertThatCode(() -> CP1251.check("SNAME", "Ёжик в тумане € № — «»")).doesNotThrowAnyException();
        assertThatCode(() -> CP1251.check("SNAME", null)).doesNotThrowAnyException();
    }

    /**
     * Проверяет текст ошибки: имя аргумента, сам символ, его код Unicode и позиция с единицы.
     * Эмодзи вне базовой плоскости Unicode (в Java это два {@code char}) тоже ловится, и
     * позиция указывает на его начало.
     */
    @Test
    void characterOutsideTheCodePageIsNamedWithItsPosition() {
        assertThatThrownBy(() -> CP1251.check("SNAME", "Мырзакул Әлем"))
                .isInstanceOf(CharsetGuard.UnrepresentableCharacterException.class)
                .hasMessage("SNAME: character 'Ә' (U+04D8) at position 10 cannot be stored in a CL8MSWIN1251 database");
        assertThatThrownBy(() -> CP1251.check("SNOTE", "ok 😀"))
                .hasMessageContaining("U+1F600").hasMessageContaining("position 4");
    }

    /**
     * Проверяет, когда проверка выключена: база в Unicode (AL32UTF8), политика {@code IGNORE},
     * неизвестная кодировка базы ({@code null}) и {@link CharsetGuard#none()}.
     */
    @Test
    void unicodeDatabasesAndIgnorePolicyCheckNothing() {
        assertThat(CharsetGuard.forDatabase("AL32UTF8", CharsetGuard.Policy.FAIL).isActive()).isFalse();
        assertThat(CharsetGuard.forDatabase("CL8MSWIN1251", CharsetGuard.Policy.IGNORE).isActive()).isFalse();
        assertThat(CharsetGuard.forDatabase(null, CharsetGuard.Policy.FAIL).isActive()).isFalse();
        assertThatCode(() -> CharsetGuard.none().check("X", "Ә")).doesNotThrowAnyException();
    }
}
