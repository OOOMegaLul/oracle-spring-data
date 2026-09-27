package dev.plsql.spring.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * Тесты {@link PlsqlExceptionTranslator}: ошибки ORA-20000..20999 из
 * {@code RAISE_APPLICATION_ERROR} становятся {@link PlsqlBusinessException} с чистым текстом,
 * остальные переводятся стандартной таблицей кодов Oracle из Spring.
 */
class PlsqlExceptionTranslatorTest {

    final PlsqlExceptionTranslator translator = new PlsqlExceptionTranslator();

    /**
     * Проверяет, что от ошибки ORA-20001 остаётся только текст сообщения — без префикса
     * {@code ORA-20001:} и без строк стека ORA-06512, — код ошибки сохраняется, а исходное
     * исключение становится причиной.
     */
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

    /**
     * Проверяет, что остальные ошибки переводятся по кодам Oracle, известным Spring: ORA-00001
     * (нарушение уникальности) → {@code DuplicateKeyException}, ORA-01400 (NULL в обязательной
     * колонке) → {@code DataIntegrityViolationException}.
     */
    @Test
    void otherErrorsUseSpringsOracleCodes() {
        assertThat(translator.translate("t", "s", new SQLException("ORA-00001: unique constraint", "23000", 1)))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(translator.translate("t", "s", new SQLException("ORA-01400: cannot insert NULL", "23000", 1400)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Проверяет, какие ошибки считаются сбросом состояния пакета, после которого вызов можно
     * повторить: ORA-04068 и ORA-06508 сами по себе и ORA-04061, вложенная причиной в другую
     * ошибку (ORA-06550). Обычная ошибка ORA-00942 (таблица или представление не существует)
     * сбросом не считается.
     */
    @Test
    void discardedPackageStateIsRecognisedAlsoWhenChained() {
        assertThat(PlsqlExceptionTranslator.isStateDiscarded(new SQLException("ORA-04068", "72000", 4068))).isTrue();
        SQLException outer = new SQLException("ORA-06508", "72000", 6508);
        assertThat(PlsqlExceptionTranslator.isStateDiscarded(outer)).isTrue();
        SQLException wrapped = new SQLException("x", "72000", 6550, new SQLException("ORA-04061", "72000", 4061));
        assertThat(PlsqlExceptionTranslator.isStateDiscarded(wrapped)).isTrue();
        assertThat(PlsqlExceptionTranslator.isStateDiscarded(new SQLException("ORA-00942", "42000", 942))).isFalse();
    }

    /**
     * Проверяет крайние случаи извлечения текста: сообщение без префикса {@code ORA-20001:}
     * остаётся как есть, а вместо отсутствующего сообщения возвращается сам код
     * ({@code ORA-20001}).
     */
    @Test
    void messageWithoutPrefixIsKept() {
        assertThat(PlsqlExceptionTranslator.userMessage("plain", 20001)).isEqualTo("plain");
        assertThat(PlsqlExceptionTranslator.userMessage(null, 20001)).isEqualTo("ORA-20001");
    }
}
