package dev.plsql.spring;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import dev.plsql.spring.support.CharsetGuard;

/**
 * Сборка {@link PlsqlApiFactory} через построитель.
 */
class PlsqlApiFactoryTest {

    /**
     * Проверяет, что пустая кодировка (например, {@code plsql.database-charset=} без значения)
     * значит «прочитать из базы», а не «кодировка неизвестна, проверку выключить».
     *
     * @throws SQLException не бросается: драйвер подменён
     */
    @Test
    void blankDatabaseCharsetIsReadFromTheDatabase() throws SQLException {
        DataSource ds = mock(DataSource.class, RETURNS_DEEP_STUBS);
        when(ds.getConnection().prepareStatement(anyString()).executeQuery().next()).thenReturn(true);
        when(ds.getConnection().prepareStatement(anyString()).executeQuery().getString(1)).thenReturn("CL8MSWIN1251");

        PlsqlApiFactory factory = PlsqlApiFactory.builder(ds).databaseCharset("  ").build();

        assertThatThrownBy(() -> factory.runtime().charsetGuard().check("SNAME", "Әлем"))
                .isInstanceOf(CharsetGuard.UnrepresentableCharacterException.class);
    }
}
