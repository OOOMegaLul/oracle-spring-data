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
 * Rows to Java: records and beans by column name ({@code BEGIN_DATE -> beginDate}),
 * a single column to a simple type, anything else to a map.
 */
public final class RowMappers {

    private RowMappers() {
    }

    public static RowMapper<?> forType(Class<?> type) {
        if (Map.class.isAssignableFrom(type) || type == Object.class) {
            return new ColumnMapRowMapper();
        }
        if (BeanUtils.isSimpleValueType(type) || type.isPrimitive()) {
            return new SingleColumnRowMapper<>(type);
        }
        return new DataClassRowMapper<>(type);
    }

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
