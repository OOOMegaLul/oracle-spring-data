package dev.plsql.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import dev.plsql.spring.session.SessionContextDataSource;
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
    /**
     * Проверяет, что фабрика на {@link SessionContextDataSource} читает и кодировку, и словарь через
     * пул под ним: при старте нет пользователя, и поставщик пользователя звать нельзя. Вызовы при
     * этом идут через обёртку.
     */
    @Test
    void metadataIsReadPastTheSessionWrapper() {
        DataSource pool = mock(DataSource.class, RETURNS_DEEP_STUBS);
        SessionContextDataSource session = new SessionContextDataSource(pool, () -> {
            throw new AssertionError("the user is asked for at startup");
        });

        PlsqlApiFactory factory = PlsqlApiFactory.builder(session).build();

        assertThat(factory.runtime().dataSource()).isSameAs(session);
        // Словарь тоже читается мимо обёртки: создание доходит до «не найдено» в пустом словаре
        // моков, а не до вызова поставщика пользователя.
        assertThatThrownBy(() -> factory.create(Api.class))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not found in the database");
    }

    /** Интерфейс для проверки чтения словаря; в словаре моков его процедуры нет. */
    @dev.plsql.spring.annotation.PlsqlApi(packageName = "PKG")
    interface Api {
        /**
         * Процедура {@code PKG.TOUCH}.
         *
         * @param tenant идёт в {@code NTENANT}
         */
        void touch(long tenant);
    }
}
