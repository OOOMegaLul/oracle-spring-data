package dev.plsql.spring.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.SingleColumnRowMapper;

import dev.plsql.spring.annotation.Arg;

/**
 * Строки курсора и {@code @SqlQuery} в объекты Java: колонки сопоставляются по тем же правилам,
 * что и аргументы процедур.
 */
class RowMappersTest {

    /**
     * Строка результата: {@code rn} ← {@code NRN} (типовой префикс), {@code name} ←
     * {@code SNAME}, {@code start} ← {@code D_START} через {@code @Arg}.
     *
     * @param rn    номер
     * @param name  имя
     * @param start дата начала
     */
    record Row(long rn, String name, @Arg("D_START") LocalDate start) {
    }

    /**
     * Класс только с конструктором с аргументами: его заполняет {@code DataClassRowMapper}.
     */
    static final class Immutable {
        /** Номер. */
        private final long id;

        /**
         * Создаёт объект.
         *
         * @param id номер
         */
        Immutable(long id) {
            this.id = id;
        }

        /**
         * Возвращает номер.
         *
         * @return номер
         */
        long id() {
            return id;
        }
    }

    /**
     * Проверяет, что колонки курсора сопоставляются с компонентами record по правилам аргументов:
     * типовой префикс, {@code @Arg}, приведение {@code NUMBER} и {@code DATE}. Раньше
     * {@code DataClassRowMapper} знал только {@code BEGIN_DATE} → {@code beginDate}. Колонка, которая
     * никому не досталась, не читается вовсе.
     *
     * @throws SQLException не бросается: драйвер подменён
     */
    @Test
    void columnsFollowTheArgumentNamingRules() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(md);
        when(md.getColumnCount()).thenReturn(4);
        when(md.getColumnLabel(1)).thenReturn("NRN");
        when(md.getColumnLabel(2)).thenReturn("SNAME");
        when(md.getColumnLabel(3)).thenReturn("D_START");
        when(md.getColumnLabel(4)).thenReturn("UNUSED");
        when(rs.next()).thenReturn(true, false);
        when(rs.getLong(1)).thenReturn(7L);
        when(rs.getString(2)).thenReturn("Иванов");
        when(rs.getObject(3, LocalDate.class)).thenReturn(LocalDate.of(2024, 1, 2));

        List<Object> rows = RowMappers.mapAll(rs, ResolvableType.forClass(Row.class));

        assertThat(rows).containsExactly(new Row(7, "Иванов", LocalDate.of(2024, 1, 2)));
        verify(rs, never()).getObject(4);
        verify(rs, never()).getObject(org.mockito.ArgumentMatchers.eq(4), org.mockito.ArgumentMatchers.<Class<?>>any());
    }

    /**
     * Строка со временем и смещением.
     *
     * @param id номер
     * @param at момент со смещением, из {@code TIMESTAMP WITH TIME ZONE}
     */
    record Stamp(long id, OffsetDateTime at) {
    }

    /**
     * Проверяет, что колонка читается в типе компонента: {@code TIMESTAMP WITH TIME ZONE} приходит в
     * {@code OffsetDateTime} со своим смещением, а не через часовой пояс JVM.
     *
     * @throws SQLException не бросается: драйвер подменён
     */
    @Test
    void columnsAreReadInTheComponentType() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(md);
        when(md.getColumnCount()).thenReturn(2);
        when(md.getColumnLabel(1)).thenReturn("ID");
        when(md.getColumnLabel(2)).thenReturn("AT");
        when(rs.next()).thenReturn(true, false);
        when(rs.getLong(1)).thenReturn(1L);
        OffsetDateTime at = OffsetDateTime.of(2024, 1, 2, 10, 0, 0, 0, ZoneOffset.ofHours(5));
        when(rs.getObject(2, OffsetDateTime.class)).thenReturn(at);

        assertThat(RowMappers.mapAll(rs, ResolvableType.forClass(Stamp.class))).containsExactly(new Stamp(1, at));
    }

    /**
     * Строка из соединения двух таблиц.
     *
     * @param id   номер
     * @param name имя
     */
    record Joined(long id, String name) {
    }

    /**
     * Проверяет, что повторяющееся имя колонки ({@code e.id} и {@code d.id} в соединении таблиц) —
     * не ошибка: достаётся первая колонка, как у JDBC.
     *
     * @throws SQLException не бросается: драйвер подменён
     */
    @Test
    void duplicateColumnLabelsTakeTheFirst() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(md);
        when(md.getColumnCount()).thenReturn(3);
        when(md.getColumnLabel(1)).thenReturn("ID");
        when(md.getColumnLabel(2)).thenReturn("NAME");
        when(md.getColumnLabel(3)).thenReturn("ID");
        when(rs.next()).thenReturn(true, false);
        when(rs.getLong(1)).thenReturn(1L);
        when(rs.getString(2)).thenReturn("a");
        when(rs.getLong(3)).thenReturn(99L);

        assertThat(RowMappers.mapAll(rs, ResolvableType.forClass(Joined.class))).containsExactly(new Joined(1, "a"));
    }

    /**
     * Проверяет выбор преобразователя: {@code Map} — карта колонок, простой тип — единственная
     * колонка, класс без конструктора без аргументов — {@code DataClassRowMapper} Spring, record —
     * по именам колонок.
     */
    @Test
    void mapperFollowsTheRowType() {
        assertThat(RowMappers.forType(Map.class)).isInstanceOf(ColumnMapRowMapper.class);
        assertThat(RowMappers.forType(String.class)).isInstanceOf(SingleColumnRowMapper.class);
        assertThat(RowMappers.forType(Immutable.class)).isInstanceOf(DataClassRowMapper.class)
                .isSameAs(RowMappers.forType(Immutable.class));
        assertThat(RowMappers.forType(Row.class)).isNotInstanceOf(DataClassRowMapper.class);
    }
}
