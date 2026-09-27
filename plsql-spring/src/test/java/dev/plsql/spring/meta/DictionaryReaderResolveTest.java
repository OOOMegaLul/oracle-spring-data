package dev.plsql.spring.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

/**
 * Разрешение имён в {@link DictionaryReader} через {@code DBMS_UTILITY.NAME_RESOLVE} на моках.
 */
class DictionaryReaderResolveTest {

    /**
     * Готовит соединение, у которого {@code NAME_RESOLVE} отвечает заданными значениями.
     *
     * @param schema схема
     * @param part1  первая часть имени
     * @param part2  вторая часть имени
     * @param dblink ссылка на другую базу или {@code null}
     * @param type   тип первой части: 9 — пакет, 7 — процедура
     * @return соединение-мок
     * @throws SQLException не бросается: драйвер подменён
     */
    static Connection resolving(String schema, String part1, String part2, String dblink, int type) throws SQLException {
        Connection con = mock(Connection.class);
        CallableStatement cs = mock(CallableStatement.class);
        when(con.prepareCall(anyString())).thenReturn(cs);
        when(cs.getString(2)).thenReturn(schema);
        when(cs.getString(3)).thenReturn(part1);
        when(cs.getString(4)).thenReturn(part2);
        when(cs.getString(5)).thenReturn(dblink);
        when(cs.getInt(6)).thenReturn(type);
        return con;
    }

    /**
     * Проверяет, что имя пакетной подпрограммы раскладывается на схему, пакет и имя.
     *
     * @throws SQLException не бросается: драйвер подменён
     */
    @Test
    void packagedNameIsSplit() throws SQLException {
        DictionaryReader.Resolved r = new DictionaryReader().resolve(resolving("APP", "PKG", "P", null, 9), null, "PKG", "P");
        assertThat(r).isEqualTo(new DictionaryReader.Resolved("APP", "PKG", "P"));
    }

    /**
     * Проверяет, что синоним объекта в другой базе (dblink) — понятная ошибка, а не поиск в своём
     * словаре, где такого объекта нет.
     *
     * @throws SQLException не бросается: драйвер подменён
     */
    @Test
    void remoteSynonymIsReportedAsSuch() throws SQLException {
        Connection con = resolving("APP", "REMOTE_P", null, "OTHER_DB", 7);
        assertThatThrownBy(() -> new DictionaryReader().resolve(con, null, null, "REMOTE_P"))
                .isInstanceOf(DictionaryReader.RemoteObjectException.class)
                .hasMessageContaining("REMOTE_P").hasMessageContaining("@OTHER_DB");
    }
}
