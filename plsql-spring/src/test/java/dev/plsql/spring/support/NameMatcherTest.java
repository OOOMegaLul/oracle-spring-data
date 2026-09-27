package dev.plsql.spring.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;


/**
 * Тесты {@link NameMatcher}: как имя параметра Java находит имя аргумента PL/SQL — без учёта
 * регистра и подчёркиваний, с отбрасыванием типовых префиксов, и без угадывания, когда
 * кандидатов несколько.
 */
class NameMatcherTest {

    /**
     * Имена аргументов с однобуквенными типовыми префиксами, как принято в старом коде:
     * {@code N} — число, {@code S} — строка, {@code D} — дата.
     */
    static final List<String> PREFIXED = List.of("NTENANT", "SCODE", "DBEGIN_DATE", "NRN", "SLOGIN", "SLOGIN_DB");

    /**
     * Проверяет, что однобуквенный типовой префикс отбрасывается, а регистр и подчёркивания не
     * важны: {@code tenant} находит {@code NTENANT}, {@code beginDate} — {@code DBEGIN_DATE}.
     * При этом {@code login} и {@code loginDb} не путают {@code SLOGIN} с {@code SLOGIN_DB}.
     */
    @Test
    void typePrefixes() {
        assertThat(NameMatcher.find("tenant", PREFIXED, s -> s)).isEqualTo("NTENANT");
        assertThat(NameMatcher.find("code", PREFIXED, s -> s)).isEqualTo("SCODE");
        assertThat(NameMatcher.find("beginDate", PREFIXED, s -> s)).isEqualTo("DBEGIN_DATE");
        assertThat(NameMatcher.find("rn", PREFIXED, s -> s)).isEqualTo("NRN");
        assertThat(NameMatcher.find("login", PREFIXED, s -> s)).isEqualTo("SLOGIN");
        assertThat(NameMatcher.find("loginDb", PREFIXED, s -> s)).isEqualTo("SLOGIN_DB");
    }

    /**
     * Проверяет, что точное совпадение сильнее совпадения без префикса: {@code name} находит
     * {@code NAME}, а не {@code SNAME}, и неоднозначностью это не считается.
     */
    @Test
    void exactNameWinsOverPrefix() {
        assertThat(NameMatcher.find("name", List.of("NAME", "SNAME"), s -> s)).isEqualTo("NAME");
    }

    /**
     * Проверяет, что словесный префикс {@code P_} тоже отбрасывается: {@code flag} находит
     * {@code P_FLAG}.
     */
    @Test
    void wordPrefixes() {
        assertThat(NameMatcher.find("flag", List.of("P_FLAG", "P_OUT"), s -> s)).isEqualTo("P_FLAG");
    }

    /**
     * Проверяет, что два равноценных кандидата ({@code DDATE} и {@code P_DATE} для {@code date})
     * дают ошибку, а не выбор наугад.
     */
    @Test
    void ambiguityIsAnError() {
        assertThatThrownBy(() -> NameMatcher.find("date", List.of("DDATE", "P_DATE"), s -> s))
                .hasMessageContaining("several");
    }

    /** Проверяет, что без совпадения возвращается {@code null}: {@code id} не находит {@code NRN}. */
    @Test
    void noMatch() {
        assertThat(NameMatcher.find("id", List.of("NRN"), s -> s)).isNull();
    }
}
