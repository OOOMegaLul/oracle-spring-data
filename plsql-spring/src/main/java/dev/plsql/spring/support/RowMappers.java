package dev.plsql.spring.support;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.BeanUtils;
import org.springframework.core.ResolvableType;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SingleColumnRowMapper;

/**
 * Превращает строки результата запроса в объекты Java: record и бины — по имени колонки
 * ({@code BEGIN_DATE -> beginDate}), единственную колонку — в простой тип, всё остальное —
 * в {@code Map}.
 *
 * <p>Строки приходят из {@link ResultSet} — курсора JDBC по результату запроса; это и
 * результат {@code @SqlQuery}, и выходной {@code REF CURSOR} процедуры. {@link RowMapper} —
 * интерфейс Spring, который превращает текущую строку {@code ResultSet} в один объект.
 */
public final class RowMappers {

    /** Закрытый конструктор: класс — набор статических методов, экземпляры не создаются. */
    private RowMappers() {
    }

    /**
     * Выбирает {@link RowMapper} для типа одной строки.
     *
     * <ul>
     *   <li>{@code Map} или {@code Object} — {@link ColumnMapRowMapper}: карта «имя колонки —
     *       значение»;</li>
     *   <li>простой тип ({@code String}, число, дата, перечисление и т.п., по
     *       {@code BeanUtils.isSimpleValueType}) или примитив — {@link SingleColumnRowMapper}:
     *       значение единственной колонки строки;</li>
     *   <li>всё остальное — {@link DataClassRowMapper}: record или класс с конструктором
     *       заполняется по параметрам конструктора, бин — через setter'ы; колонки
     *       сопоставляются с именами Java, {@code BEGIN_DATE} — с {@code beginDate}.</li>
     * </ul>
     *
     * @param type тип одной строки
     * @return новый преобразователь строк для этого типа
     */
    public static RowMapper<?> forType(Class<?> type) {
        if (Map.class.isAssignableFrom(type) || type == Object.class) {
            return new ColumnMapRowMapper();
        }
        if (BeanUtils.isSimpleValueType(type) || type.isPrimitive()) {
            return new SingleColumnRowMapper<>(type);
        }
        return new DataClassRowMapper<>(type);
    }

    /**
     * Читает все оставшиеся строки {@link ResultSet} и превращает каждую в объект типа
     * {@code element}.
     *
     * <p>Если тип не удаётся определить (например, {@code List} без параметра типа), строки
     * становятся {@code Map}. {@code ResultSet} здесь не закрывается — это делает вызывающий
     * код.
     *
     * @param rs      открытый результат запроса или курсора
     * @param element тип одной строки
     * @return список объектов в порядке строк; пустой, если строк нет
     * @throws SQLException если драйвер не смог прочитать строку
     */
    public static List<Object> mapAll(ResultSet rs, ResolvableType element) throws SQLException {
        RowMapper<?> mapper = forType(element.resolve(Map.class));
        List<Object> rows = new ArrayList<>();
        int n = 0;
        while (rs.next()) {
            rows.add(mapper.mapRow(rs, n++));
        }
        return rows;
    }
}
