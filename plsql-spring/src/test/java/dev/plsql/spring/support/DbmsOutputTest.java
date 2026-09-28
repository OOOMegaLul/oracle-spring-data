package dev.plsql.spring.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Array;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

/**
 * Разбор результата {@code DBMS_OUTPUT.GET_LINES} в {@link DbmsOutput}.
 */
class DbmsOutputTest {

    /**
     * Проверяет, что берутся только прочитанные строки (на 11.2 массив длиннее на лишний
     * {@code NULL}), {@code NULL} внутри вывода становится пустой строкой, а массив освобождается.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void onlyTheReadLinesAreTaken() throws SQLException {
        Array a = mock(Array.class);
        when(a.getArray()).thenReturn(new Object[]{"привет", null, "end", null});
        assertThat(DbmsOutput.lines(a, 3)).containsExactly("привет", "", "end");
        verify(a).free();
    }

    /**
     * Проверяет пустой вывод: {@code NULL} вместо массива и массив без строк дают пустой список.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void noOutputGivesNoLines() throws SQLException {
        assertThat(DbmsOutput.lines(null, 0)).isEmpty();
        Array a = mock(Array.class);
        when(a.getArray()).thenReturn(new Object[]{null});
        assertThat(DbmsOutput.lines(a, 0)).isEmpty();
    }
}
