package dev.plsql.spring.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

class PlsqlExceptionTranslatorTest {

    final PlsqlExceptionTranslator translator = new PlsqlExceptionTranslator();

    @Test
    void applicationErrorsKeepOnlyTheText() {
        SQLException e = new SQLException("""
                ORA-20001: Отпуск пересекается с командировкой.
                ORA-06512: at "APP.HR_RULES", line 42
                ORA-06512: at line 2""", "72000", 20001);
        RuntimeException r = translator.translate("F", "sql", e);
        assertThat(r).isInstanceOf(PlsqlBusinessException.class)
                .hasMessage("Отпуск пересекается с командировкой.");
        assertThat(((PlsqlBusinessException) r).getErrorCode()).isEqualTo(20001);
        assertThat(r.getCause()).isSameAs(e);
    }

    @Test
    void otherErrorsUseSpringsOracleCodes() {
        assertThat(translator.translate("t", "s", new SQLException("ORA-00001: unique constraint", "23000", 1)))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(translator.translate("t", "s", new SQLException("ORA-01400: cannot insert NULL", "23000", 1400)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void discardedPackageStateIsRecognisedAlsoWhenChained() {
        assertThat(PlsqlExceptionTranslator.isStateDiscarded(new SQLException("ORA-04068", "72000", 4068))).isTrue();
        SQLException outer = new SQLException("ORA-06508", "72000", 6508);
        assertThat(PlsqlExceptionTranslator.isStateDiscarded(outer)).isTrue();
        SQLException wrapped = new SQLException("x", "72000", 6550, new SQLException("ORA-04061", "72000", 4061));
        assertThat(PlsqlExceptionTranslator.isStateDiscarded(wrapped)).isTrue();
        assertThat(PlsqlExceptionTranslator.isStateDiscarded(new SQLException("ORA-00942", "42000", 942))).isFalse();
    }

    @Test
    void messageWithoutPrefixIsKept() {
        assertThat(PlsqlExceptionTranslator.userMessage("plain", 20001)).isEqualTo("plain");
        assertThat(PlsqlExceptionTranslator.userMessage(null, 20001)).isEqualTo("ORA-20001");
    }
}
