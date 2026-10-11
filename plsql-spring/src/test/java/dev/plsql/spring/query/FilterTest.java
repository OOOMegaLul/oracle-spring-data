package dev.plsql.spring.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Проверяет построение фильтров ({@link Filter}): проверки при создании и удобства для полей формы.
 */
class FilterTest {

    /**
     * Проверяет, что {@code null} в значении — ошибка с подсказкой про {@code isNull}, а число
     * значений должно подходить к операции.
     */
    @Test
    void valuesAreChecked() {
        assertThatThrownBy(() -> Filter.eq("status", null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Filter.isNull(\"status\")");
        assertThatThrownBy(() -> Filter.of("status", Filter.Operator.IS_NULL, 1)).hasMessageContaining("1 values");
        assertThatThrownBy(() -> Filter.of("status", Filter.Operator.EQ)).hasMessageContaining("0 values");
        assertThatThrownBy(() -> Filter.eq(" ", 1)).hasMessageContaining("field is empty");
        assertThatThrownBy(() -> Filter.sql(" ")).hasMessageContaining("empty");
    }

    /**
     * Проверяет удобства для необязательных полей формы: пустые границы диапазона не ограничивают,
     * {@code null} в {@code allOf} пропускается.
     */
    @Test
    void optionalFormFields() {
        assertThat(Filter.between("sum", null, null)).isEqualTo(Filter.none());
        assertThat(Filter.between("sum", 1, null)).isEqualTo(Filter.ge("sum", 1));
        assertThat(Filter.between("sum", null, 9)).isEqualTo(Filter.le("sum", 9));
        assertThat(Filter.allOf(null, Filter.eq("a", 1), null))
                .isEqualTo(new Filter.All(List.of(Filter.eq("a", 1))));
        assertThat(Filter.eq("a", 1).and(Filter.eq("b", 2)))
                .isEqualTo(new Filter.All(List.of(Filter.eq("a", 1), Filter.eq("b", 2))));
        assertThat(Filter.eq("a", 1).negate()).isEqualTo(new Filter.Not(Filter.eq("a", 1)));
    }
}
