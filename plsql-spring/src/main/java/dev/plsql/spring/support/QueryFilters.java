package dev.plsql.spring.support;

import java.math.BigDecimal;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.ResolvableType;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.support.JdbcUtils;

import dev.plsql.spring.query.Filter;

/**
 * Превращает условия запроса {@code @SqlQuery} — фильтр из параметра {@link Filter} и обязательные
 * ограничения ({@link dev.plsql.spring.query.QueryRestriction}) — в обёртку
 * {@code SELECT * FROM (<запрос>) q_ WHERE ...} со значениями-параметрами.
 *
 * <p>Имена полей сверяются с настоящими колонками запроса, которые база описывает без его
 * выполнения ({@link Columns}), по тем же правилам, по которым строки ложатся в record. В текст SQL
 * попадает имя колонки из описания, взятое в кавычки, а не строка из фильтра; значения — только
 * через {@code ?} и приведённые к типу колонки.
 */
final class QueryFilters {

    /** Больше значений в одном {@code IN (...)} Oracle не принимает (ORA-01795). */
    private static final int MAX_IN_LIST = 1000;

    /** Поле в условии на SQL: {@code {status}}. */
    private static final Pattern FIELD = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_$#]*)\\}");

    /** Дата без времени: {@code 2024-01-31}. */
    private static final Pattern DATE_ONLY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** Класс только со статическими методами: экземпляры не создаются. */
    private QueryFilters() {
    }

    /**
     * Колонка запроса по описанию базы.
     *
     * @param label    имя колонки, как его вернула база
     * @param jdbcType код типа {@link Types}
     * @param typeName имя типа в базе ({@code VARCHAR2}, {@code DATE}, {@code NUMBER})
     */
    record Column(String label, int jdbcType, String typeName) {

        /**
         * Возвращает ссылку на колонку во внешнем запросе: {@code q_."ИМЯ"}.
         *
         * @return выражение SQL
         */
        String sql() {
            return "q_.\"" + label.replace("\"", "\"\"") + "\"";
        }

        /**
         * Проверяет, текстовая ли колонка.
         *
         * @return {@code true} для строк и {@code CLOB}
         */
        boolean text() {
            return switch (jdbcType) {
                case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                     Types.CLOB, Types.NCLOB -> true;
                default -> false;
            };
        }

        /**
         * Проверяет, национальная ли это строка ({@code NVARCHAR2}, {@code NCHAR}, {@code NCLOB}): её
         * текст не проверяется на основную кодировку базы.
         *
         * @return {@code true} для национальных строк
         */
        boolean national() {
            return jdbcType == Types.NCHAR || jdbcType == Types.NVARCHAR || jdbcType == Types.LONGNVARCHAR
                    || jdbcType == Types.NCLOB || (typeName != null && typeName.startsWith("N"));
        }

        /**
         * Проверяет, числовая ли колонка.
         *
         * @return {@code true} для {@code NUMBER} и прочих чисел
         */
        boolean number() {
            return switch (jdbcType) {
                case Types.NUMERIC, Types.DECIMAL, Types.INTEGER, Types.SMALLINT, Types.TINYINT, Types.BIGINT,
                     Types.FLOAT, Types.REAL, Types.DOUBLE -> true;
                default -> "BINARY_FLOAT".equals(typeName) || "BINARY_DOUBLE".equals(typeName);
            };
        }

        /**
         * Проверяет, колонка ли это даты или момента времени.
         *
         * @return {@code true} для {@code DATE} и {@code TIMESTAMP}
         */
        boolean date() {
            return jdbcType == Types.DATE || jdbcType == Types.TIMESTAMP || jdbcType == Types.TIMESTAMP_WITH_TIMEZONE
                    || (typeName != null && (typeName.equals("DATE") || typeName.startsWith("TIMESTAMP")));
        }
    }

    /**
     * Колонки запроса: все, разрешённые для фильтра пользователя и свойства record строки, которые
     * в них лежат.
     */
    static final class Columns {

        /** Все колонки запроса по порядку. */
        private final List<Column> all;
        /** Колонки, по которым может отбирать фильтр пользователя. */
        private final List<Column> allowed;
        /** Свойства record строки (нормализованные имена) и их колонки, только разрешённые. */
        private final Map<String, Column> properties;
        /** Имена свойств, как они записаны в record, — для сообщений. */
        private final List<String> propertyNames;

        /**
         * Запоминает колонки.
         *
         * @param all           все колонки
         * @param allowed       разрешённые для фильтра
         * @param properties    свойства и их колонки
         * @param propertyNames имена свойств для сообщений
         */
        private Columns(List<Column> all, List<Column> allowed, Map<String, Column> properties,
                        List<String> propertyNames) {
            this.all = all;
            this.allowed = allowed;
            this.properties = properties;
            this.propertyNames = propertyNames;
        }

        /**
         * Собирает колонки по описанию запроса.
         *
         * <p>Если строка — record или бин, фильтр пользователя может отбирать только по колонкам,
         * которые попадают в его свойства (так же, как строки собираются в record); иначе — по
         * любой колонке. {@code filterable} сужает список: имена свойств или колонок.
         *
         * @param md         описание колонок, полученное без выполнения запроса
         * @param element    тип одной строки результата
         * @param filterable имена из {@code @SqlQuery(filterable = ...)} или пустой массив
         * @return колонки запроса
         * @throws SQLException если драйвер не отдал описание
         * @throws InvalidDataAccessApiUsageException если имени из {@code filterable} нет среди колонок
         */
        static Columns of(ResultSetMetaData md, ResolvableType element, String[] filterable) throws SQLException {
            List<Column> all = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                String label = JdbcUtils.lookupColumnName(md, i);
                labels.add(label);
                all.add(new Column(label, md.getColumnType(i), md.getColumnTypeName(i)));
            }
            Class<?> type = element == null ? Map.class : element.resolve(Map.class);
            Map<String, Column> properties = new LinkedHashMap<>();
            List<String> names = new ArrayList<>();
            List<Column> allowed;
            if (RowMappers.byName(type)) {
                Values.Binder binder = Values.Binder.of(type, labels);
                allowed = new ArrayList<>();
                for (int i = 0; i < all.size(); i++) {
                    String property = binder.nameOf(i);
                    if (property != null) {
                        properties.put(NameMatcher.normalize(property), all.get(i));
                        names.add(property);
                        allowed.add(all.get(i));
                    }
                }
            } else {
                allowed = all;
            }
            Columns full = new Columns(all, allowed, properties, names);
            if (filterable.length == 0) {
                return full;
            }
            List<Column> narrowed = new ArrayList<>();
            Map<String, Column> narrowedProperties = new LinkedHashMap<>();
            List<String> narrowedNames = new ArrayList<>();
            for (String f : filterable) {
                Column c = full.resolve(f, true);
                narrowed.add(c);
                properties.forEach((p, col) -> {
                    if (col == c) {
                        narrowedProperties.put(p, col);
                    }
                });
                narrowedNames.add(f);
            }
            return new Columns(all, narrowed, narrowedProperties, narrowedNames);
        }

        /**
         * Находит колонку по имени поля: имени свойства record, имени колонки или имени колонки
         * без типового префикса ({@link NameMatcher}).
         *
         * @param field   имя поля
         * @param trusted условие из кода приложения (ограничение): ищется среди всех колонок, а не
         *                только разрешённых
         * @return колонка
         * @throws InvalidDataAccessApiUsageException если такой колонки нет или по этому полю
         *                                            фильтровать нельзя
         */
        Column resolve(String field, boolean trusted) {
            List<Column> scope = trusted ? all : allowed;
            String n = NameMatcher.normalize(field);
            Column c = properties.get(n);
            if (c == null) {
                for (Column col : scope) {
                    if (NameMatcher.normalize(col.label()).equals(n)) {
                        c = col;
                        break;
                    }
                }
            }
            if (c == null) {
                try {
                    c = NameMatcher.find(field, scope, Column::label);
                } catch (IllegalStateException e) {
                    throw new InvalidDataAccessApiUsageException("cannot filter by '" + field + "': " + e.getMessage());
                }
            }
            if (c == null) {
                List<String> known = !trusted && !propertyNames.isEmpty() ? propertyNames
                        : scope.stream().map(Column::label).toList();
                throw new InvalidDataAccessApiUsageException("cannot filter by '" + field + "'; the query can be filtered by "
                        + known);
            }
            return c;
        }
    }

    /**
     * Текст запроса с условиями и значения по порядку знаков {@code ?}.
     *
     * @param sql    текст
     * @param values значения
     */
    record Rendered(String sql, Object[] values) {
    }

    /**
     * Добавляет к запросу ограничения и фильтр.
     *
     * <p>Если условий нет (фильтр {@code null} или пустой, ограничения не ограничивают), запрос
     * возвращается как есть. Иначе он оборачивается:
     * {@code SELECT * FROM (<запрос>) q_ WHERE <ограничения> AND <фильтр>}; значения условий идут
     * после значений самого запроса.
     *
     * @param sql          текст запроса с {@code ?}
     * @param values       значения запроса по порядку
     * @param columns      колонки запроса
     * @param user         фильтр из параметра метода или {@code null}
     * @param restrictions условия обязательных ограничений
     * @param guard        проверка текста на кодировку базы
     * @return текст и значения
     * @throws InvalidDataAccessApiUsageException если поле или значение фильтра не подходят
     */
    static Rendered apply(String sql, Object[] values, Columns columns, Filter user, List<Filter> restrictions,
                          CharsetGuard guard) {
        Renderer r = new Renderer(columns, guard, values);
        List<String> conditions = new ArrayList<>();
        for (Filter f : restrictions) {
            String s = r.render(f, true);
            if (s != null) {
                conditions.add(s);
            }
        }
        if (user != null) {
            String s = r.render(user, false);
            if (s != null) {
                conditions.add(s);
            }
        }
        if (conditions.isEmpty()) {
            return new Rendered(sql, values);
        }
        // Перевод строки перед скобкой: однострочный комментарий в конце запроса не съест её.
        return new Rendered("SELECT * FROM (" + sql + "\n) q_ WHERE " + String.join(" AND ", conditions),
                r.values.toArray());
    }

    /**
     * Обходит дерево условий и пишет SQL, складывая значения в том же порядке, в каком в тексте
     * идут их {@code ?}.
     */
    private static final class Renderer {

        /** Колонки запроса. */
        private final Columns columns;
        /** Проверка текста на кодировку базы. */
        private final CharsetGuard guard;
        /** Значения: сначала значения запроса, затем условий. */
        private final List<Object> values;

        /**
         * Готовит обход.
         *
         * @param columns колонки запроса
         * @param guard   проверка текста
         * @param base    значения самого запроса
         */
        Renderer(Columns columns, CharsetGuard guard, Object[] base) {
            this.columns = columns;
            this.guard = guard;
            this.values = new ArrayList<>(Arrays.asList(base));
        }

        /**
         * Пишет условие.
         *
         * @param f       условие
         * @param trusted условие из кода приложения: поля ищутся среди всех колонок
         * @return SQL условия или {@code null}, если условие пропускает любую строку
         */
        String render(Filter f, boolean trusted) {
            if (f instanceof Filter.All all) {
                return join(all.parts(), " AND ", trusted, false);
            }
            if (f instanceof Filter.Any any) {
                return any.parts().isEmpty() ? "1 = 0" : join(any.parts(), " OR ", trusted, true);
            }
            if (f instanceof Filter.Not not) {
                String inner = render(not.filter(), trusted);
                return inner == null ? "1 = 0" : "NOT (" + inner + ")";
            }
            if (f instanceof Filter.Condition c) {
                return condition(c, trusted);
            }
            if (f instanceof Filter.InSelect in) {
                Column col = columns.resolve(in.field(), trusted);
                values.addAll(in.values());
                return col.sql() + " IN (" + in.subquery() + "\n)";
            }
            return sql((Filter.Sql) f, trusted);
        }

        /**
         * Соединяет условия через {@code AND} или {@code OR}.
         *
         * @param parts   условия
         * @param op      {@code " AND "} или {@code " OR "}
         * @param trusted условие из кода приложения
         * @param anyTrue что значит часть, которая пропускает любую строку: для {@code AND}
         *                ({@code false}) она просто выпадает, для {@code OR} ({@code true}) — всё
         *                условие пропускает любую строку
         * @return SQL или {@code null}, если условие пропускает любую строку
         */
        private String join(List<Filter> parts, String op, boolean trusted, boolean anyTrue) {
            List<String> out = new ArrayList<>();
            for (Filter p : parts) {
                String s = render(p, trusted);
                if (s == null) {
                    if (anyTrue) {
                        return null;
                    }
                    continue;
                }
                out.add(s);
            }
            if (out.isEmpty()) {
                return null;
            }
            return out.size() == 1 ? out.get(0) : "(" + String.join(op, out) + ")";
        }

        /**
         * Пишет простое условие.
         *
         * @param c       условие
         * @param trusted условие из кода приложения
         * @return SQL условия
         * @throws InvalidDataAccessApiUsageException если операция не подходит к колонке или
         *                                            значение не приводится к её типу
         */
        private String condition(Filter.Condition c, boolean trusted) {
            Column col = columns.resolve(c.field(), trusted);
            String ref = col.sql();
            boolean upper = c.ignoreCase() && col.text();
            String left = upper ? "UPPER(" + ref + ")" : ref;
            String param = upper ? "UPPER(?)" : "?";
            switch (c.operator()) {
                case IS_NULL:
                    return ref + " IS NULL";
                case IS_NOT_NULL:
                    return ref + " IS NOT NULL";
                case CONTAINS, STARTS_WITH: {
                    if (!col.text()) {
                        throw new InvalidDataAccessApiUsageException("cannot filter " + c.field() + " by "
                                + c.operator() + ": the column " + col.label() + " is " + col.typeName() + ", not text");
                    }
                    String text = (String) bind(col, c.values().get(0), c.field());
                    String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                    values.add(c.operator() == Filter.Operator.CONTAINS ? "%" + escaped + "%" : escaped + "%");
                    return left + " LIKE " + param + " ESCAPE '\\'";
                }
                case IN:
                    return in(c, col, left, param);
                default:
                    break;
            }
            Object v = value(col, c.values().get(0), c.field());
            if (v instanceof LocalDate day) {
                // Дата без времени для колонки-даты — весь день: [d, d + 1).
                Timestamp from = Timestamp.valueOf(day.atStartOfDay());
                Timestamp next = Timestamp.valueOf(day.plusDays(1).atStartOfDay());
                return switch (c.operator()) {
                    case EQ -> {
                        values.add(from);
                        values.add(next);
                        yield "(" + ref + " >= ? AND " + ref + " < ?)";
                    }
                    case NE -> {
                        values.add(from);
                        values.add(next);
                        yield "(" + ref + " < ? OR " + ref + " >= ?)";
                    }
                    case GT -> {
                        values.add(next);
                        yield ref + " >= ?";
                    }
                    case GE -> {
                        values.add(from);
                        yield ref + " >= ?";
                    }
                    case LT -> {
                        values.add(from);
                        yield ref + " < ?";
                    }
                    default -> {
                        values.add(next);
                        yield ref + " < ?";
                    }
                };
            }
            values.add(v);
            String op = switch (c.operator()) {
                case EQ -> " = ";
                case NE -> " <> ";
                case GT -> " > ";
                case GE -> " >= ";
                case LT -> " < ";
                default -> " <= ";
            };
            return left + op + param;
        }

        /**
         * Пишет условие {@code IN}: пустой список не подходит ни к одной строке, длинный делится на
         * части по 1000 через {@code OR}; даты без времени сравниваются с днём колонки
         * ({@code TRUNC}).
         *
         * @param c     условие
         * @param col   колонка
         * @param left  левая часть ({@code q_."X"} или {@code UPPER(q_."X")})
         * @param param знак параметра ({@code ?} или {@code UPPER(?)})
         * @return SQL условия
         */
        private String in(Filter.Condition c, Column col, String left, String param) {
            if (c.values().isEmpty()) {
                return "1 = 0";
            }
            List<String> chunks = new ArrayList<>();
            for (int from = 0; from < c.values().size(); from += MAX_IN_LIST) {
                List<Object> part = c.values().subList(from, Math.min(c.values().size(), from + MAX_IN_LIST));
                boolean days = false;
                List<String> marks = new ArrayList<>();
                for (Object raw : part) {
                    Object v = value(col, raw, c.field());
                    if (v instanceof LocalDate day) {
                        days = true;
                        v = Timestamp.valueOf(day.atStartOfDay());
                    }
                    values.add(v);
                    marks.add(param);
                }
                String target = days ? "TRUNC(" + col.sql() + ")" : left;
                chunks.add(target + " IN (" + String.join(", ", marks) + ")");
            }
            return chunks.size() == 1 ? chunks.get(0) : "(" + String.join(" OR ", chunks) + ")";
        }

        /**
         * Пишет условие на SQL: поля в фигурных скобках заменяются колонками.
         *
         * @param sql     условие
         * @param trusted условие из кода приложения
         * @return SQL условия
         */
        private String sql(Filter.Sql sql, boolean trusted) {
            Matcher m = FIELD.matcher(sql.expression());
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(columns.resolve(m.group(1), trusted).sql()));
            }
            m.appendTail(sb);
            values.addAll(sql.values());
            return "(" + sb + ")";
        }

        /**
         * Приводит значение к типу колонки, оставляя дату без времени для колонки-даты как
         * {@link LocalDate}: её сравнение — с целым днём.
         *
         * @param col   колонка
         * @param v     значение из фильтра
         * @param field имя поля для сообщений
         * @return значение для параметра или {@link LocalDate}
         */
        private Object value(Column col, Object v, String field) {
            if (col.date()) {
                Object t = v instanceof CharSequence s ? parseTemporal(s.toString().trim(), field) : v;
                if (t instanceof LocalDate) {
                    return t;
                }
                try {
                    return Values.toTimestamp(t);
                } catch (IllegalArgumentException e) {
                    throw new InvalidDataAccessApiUsageException("cannot filter " + field + ": " + v + " is not a date");
                }
            }
            return bind(col, v, field);
        }

        /**
         * Приводит значение к типу колонки: число — к числу (строка разбирается), текст — к строке
         * с проверкой кодировки базы, перечисление — к имени.
         *
         * @param col   колонка
         * @param v     значение из фильтра
         * @param field имя поля для сообщений
         * @return значение для параметра
         * @throws InvalidDataAccessApiUsageException если значение не приводится к типу колонки
         */
        private Object bind(Column col, Object v, String field) {
            if (col.number()) {
                if (v instanceof Number) {
                    return v;
                }
                if (v instanceof Boolean b) {
                    return b ? 1 : 0;
                }
                if (v instanceof CharSequence s) {
                    try {
                        return new BigDecimal(s.toString().trim());
                    } catch (NumberFormatException e) {
                        throw new InvalidDataAccessApiUsageException("cannot filter " + field + ": '" + s + "' is not a number");
                    }
                }
                throw new InvalidDataAccessApiUsageException("cannot filter " + field + ": " + v.getClass().getSimpleName()
                        + " is not a number");
            }
            if (col.text()) {
                String s = v instanceof Enum<?> e ? e.name() : Values.toText(v);
                if (!col.national()) {
                    guard.check(field, s);
                }
                return s;
            }
            return v instanceof Enum<?> e ? e.name() : v;
        }

        /**
         * Разбирает дату из строки: {@code 2024-01-31} — день, {@code 2024-01-31T10:15[:30]} или
         * {@code 2024-01-31 10:15} — время, со смещением ({@code +03:00}, {@code Z}) — момент.
         *
         * @param s     строка
         * @param field имя поля для сообщений
         * @return {@link LocalDate}, {@link LocalDateTime}, {@link OffsetDateTime} или {@link Instant}
         * @throws InvalidDataAccessApiUsageException если строка не похожа на дату
         */
        private static Object parseTemporal(String s, String field) {
            if (DATE_ONLY.matcher(s).matches()) {
                return LocalDate.parse(s);
            }
            String iso = s.replace(' ', 'T');
            try {
                return LocalDateTime.parse(iso);
            } catch (DateTimeParseException e) {
                // не местное время — пробуем со смещением
            }
            try {
                return OffsetDateTime.parse(iso);
            } catch (DateTimeParseException e) {
                // и не со смещением — пробуем момент
            }
            try {
                return Instant.parse(iso);
            } catch (DateTimeParseException e) {
                throw new InvalidDataAccessApiUsageException("cannot filter " + field + ": '" + s
                        + "' is not a date; use 2024-01-31 or 2024-01-31T10:15");
            }
        }
    }
}
