package dev.plsql.spring.call;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.util.ObjectUtils;

import dev.plsql.spring.meta.ArgKind;
import dev.plsql.spring.meta.ArgumentInfo;
import dev.plsql.spring.support.Values;

/**
 * Index-by таблицы, которые драйвер Oracle не передаёт сам: таблицы записей, {@code TIMESTAMP} и
 * {@code BOOLEAN}. Такая таблица едет несколькими простыми таблицами, по одной на поле элемента.
 *
 * <p>Драйвер умеет привязывать только index-by таблицы чисел, строк и дат (проверено на 11.2:
 * таблица {@code TIMESTAMP} на входе даёт ORA-00600, на выходе рвёт соединение). Поэтому блок
 * объявляет простые таблицы ({@code TABLE OF NUMBER / VARCHAR2 / DATE INDEX BY PLS_INTEGER}) и
 * перекладывает значения циклом: на входе — из простых таблиц в поля элементов настоящей таблицы,
 * на выходе — обратно. {@code TIMESTAMP} едет строкой в строгом формате, {@code BOOLEAN} — числом
 * 1/0. Ключи выходной таблицы не сохраняются: элементы приходят списком в порядке ключей
 * (таблица может быть разреженной: 10, 20, 30); входная таблица заполняется с ключа 1.
 */
final class TableSplit {

    /** Формат {@code TIMESTAMP} в строке на стороне PL/SQL. */
    static final String TS_FORMAT = "YYYY-MM-DD HH24:MI:SS.FF9";
    /** Формат {@code TIMESTAMP WITH (LOCAL) TIME ZONE} в строке на стороне PL/SQL. */
    static final String TSTZ_FORMAT = "YYYY-MM-DD HH24:MI:SS.FF9 TZH:TZM";
    /** Тот же формат {@code TIMESTAMP} на стороне Java. */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS");
    /** Тот же формат {@code TIMESTAMP WITH TIME ZONE} на стороне Java. */
    private static final DateTimeFormatter TSTZ = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS xxx");
    /** Длина строки, в которой едет {@code TIMESTAMP}: дата, время с наносекундами и смещение. */
    static final int TS_LENGTH = 40;
    /** Тип данных, которым помечается поле {@code BOOLEAN} в описании простой таблицы. */
    private static final String BOOLEAN_ORIGIN = "PL/SQL BOOLEAN";

    /** Класс только со статическими методами: экземпляры не создаются. */
    private TableSplit() {
    }

    /**
     * Поле элемента таблицы, которое едет отдельной простой таблицей.
     *
     * @param path      путь к полю внутри элемента ({@code ADDR}, {@code CITY}); пустой, если
     *                  элемент сам простое значение
     * @param field     описание поля (или самого элемента)
     * @param transport в какой простой таблице оно едет: {@code NUMBER}, {@code STRING} или
     *                  {@code DATE}
     */
    record Leaf(List<String> path, ArgumentInfo field, ArgKind transport) {

        /**
         * Возвращает путь как продолжение выражения PL/SQL: {@code .ADDR.CITY} или пустую строку.
         *
         * @return суффикс пути
         */
        String suffix() {
            StringBuilder sb = new StringBuilder();
            path.forEach(p -> sb.append('.').append(p));
            return sb.toString();
        }
    }

    /**
     * Проверяет, передаёт ли драйвер таблицу с такими элементами сам: числа, строки и даты.
     *
     * @param el описание элемента таблицы
     * @return {@code true}, если таблица привязывается напрямую
     */
    static boolean direct(ArgumentInfo el) {
        return el.kind() == ArgKind.NUMBER || el.kind() == ArgKind.STRING || el.kind() == ArgKind.DATE;
    }

    /**
     * Возвращает причину, по которой таблицу с такими элементами нельзя разложить по полям, или
     * {@code null}, если можно.
     *
     * <p>Раскладываются записи (в том числе вложенные) с полями-числами, строками, датами,
     * {@code TIMESTAMP} и {@code BOOLEAN}, а также таблицы {@code TIMESTAMP} и {@code BOOLEAN}.
     * Национальные строки ({@code NVARCHAR2}) не раскладываются: простая таблица строк хранит их в
     * основной кодировке базы, и символы вне неё пропали бы.
     *
     * @param el описание элемента таблицы
     * @return причина или {@code null}
     */
    static String splitIssue(ArgumentInfo el) {
        switch (el.kind()) {
            case RECORD -> {
                if (el.children().isEmpty()) {
                    return "a record with no known fields";
                }
                for (ArgumentInfo f : el.children()) {
                    String issue = splitIssue(f);
                    if (issue != null) {
                        return "field " + f.name() + ": " + issue;
                    }
                }
                return null;
            }
            case NUMBER, DATE, TIMESTAMP, BOOLEAN -> {
                return null;
            }
            case STRING -> {
                return national(el) ? el.dataType() + " (national characters)" : null;
            }
            default -> {
                return el.dataType();
            }
        }
    }

    /**
     * Перечисляет поля элемента таблицы, каждое из которых едет своей простой таблицей.
     *
     * @param table index-by таблица
     * @return поля в порядке объявления, вложенные записи — развёрнутыми
     */
    static List<Leaf> leaves(ArgumentInfo table) {
        List<Leaf> out = new ArrayList<>();
        collect(table.children().get(0), new ArrayList<>(), out);
        return out;
    }

    /**
     * Собирает поля элемента рекурсивно.
     *
     * @param el   элемент или поле
     * @param path путь до него
     * @param out  куда складывать поля
     */
    private static void collect(ArgumentInfo el, List<String> path, List<Leaf> out) {
        if (el.kind() == ArgKind.RECORD) {
            for (ArgumentInfo f : el.children()) {
                List<String> p = new ArrayList<>(path);
                p.add(f.name());
                collect(f, p, out);
            }
            return;
        }
        ArgKind transport = switch (el.kind()) {
            case NUMBER, BOOLEAN -> ArgKind.NUMBER;
            case DATE -> ArgKind.DATE;
            default -> ArgKind.STRING;
        };
        out.add(new Leaf(List.copyOf(path), el, transport));
    }

    /**
     * Описывает простую таблицу, в которой едет поле, — для привязки и проверки памяти.
     *
     * <p>Тип элемента — тот, которым поле едет ({@code NUMBER}, строка, {@code DATE}); исходный тип
     * поля для {@code TIMESTAMP} и {@code BOOLEAN} записан в {@code plsType} элемента: по нему
     * исполнитель превращает прочитанные значения обратно.
     *
     * @param table исходная index-by таблица
     * @param leaf  поле элемента
     * @return описание простой таблицы
     */
    static ArgumentInfo leafTable(ArgumentInfo table, Leaf leaf) {
        ArgumentInfo f = leaf.field();
        String type;
        String origin = null;
        Integer length = null;
        switch (f.kind()) {
            case TIMESTAMP -> {
                type = "VARCHAR2";
                origin = f.dataType();
                length = TS_LENGTH;
            }
            case BOOLEAN -> {
                type = "NUMBER";
                origin = BOOLEAN_ORIGIN;
            }
            case STRING -> {
                type = f.dataType();
                length = f.charLength();
            }
            default -> type = f.dataType();
        }
        ArgumentInfo el = new ArgumentInfo(null, 1, table.dataLevel() + 1, type, origin, table.inOut(), false,
                null, null, null, List.of(), length);
        String name = (table.name() == null ? "RETURN" : table.name()) + leaf.suffix();
        return new ArgumentInfo(name, table.position(), table.dataLevel(), "PL/SQL TABLE", null, table.inOut(),
                false, null, null, null, List.of(el), null);
    }

    /**
     * Возвращает выражение PL/SQL, которое превращает значение из простой таблицы в значение поля
     * (для входной таблицы).
     *
     * @param expr  значение из простой таблицы, например {@code v2(j1)}
     * @param field поле
     * @return выражение типа поля
     */
    static String toField(String expr, ArgumentInfo field) {
        return switch (field.kind()) {
            case TIMESTAMP -> withZone(field)
                    ? "TO_TIMESTAMP_TZ(" + expr + ", '" + TSTZ_FORMAT + "')"
                    : "TO_TIMESTAMP(" + expr + ", '" + TS_FORMAT + "')";
            case BOOLEAN -> "CASE " + expr + " WHEN 1 THEN TRUE WHEN 0 THEN FALSE END";
            default -> expr;
        };
    }

    /**
     * Возвращает выражение PL/SQL, которое превращает значение поля в значение простой таблицы
     * (для выходной таблицы).
     *
     * @param expr  значение поля, например {@code v1(v4).HIRED}
     * @param field поле
     * @return выражение типа простой таблицы
     */
    static String toTransport(String expr, ArgumentInfo field) {
        return switch (field.kind()) {
            case TIMESTAMP -> withZone(field)
                    ? "TO_CHAR(CAST(" + expr + " AS TIMESTAMP WITH TIME ZONE), '" + TSTZ_FORMAT + "')"
                    : "TO_CHAR(" + expr + ", '" + TS_FORMAT + "')";
            case BOOLEAN -> "CASE WHEN " + expr + " THEN 1 WHEN NOT " + expr + " THEN 0 END";
            default -> expr;
        };
    }

    /**
     * Достаёт из списка Java значения одного поля, уже в том виде, в каком поле едет.
     *
     * @param list список (или массив) элементов: record, бины, {@code Map} или простые значения;
     *             {@code null} — пустая таблица
     * @param leaf поле
     * @return значения поля по элементам, по порядку
     */
    static List<Object> values(Object list, Leaf leaf) {
        Collection<?> c = list == null ? List.of()
                : list instanceof Collection<?> col ? col : Arrays.asList(ObjectUtils.toObjectArray(list));
        List<Object> out = new ArrayList<>(c.size());
        for (Object e : c) {
            Object v = e;
            for (String p : leaf.path()) {
                v = v == null ? null : Values.property(v, p);
            }
            out.add(toTransport(v, leaf.field()));
        }
        return out;
    }

    /**
     * Превращает значение Java в значение простой таблицы: {@code TIMESTAMP} — в строку, логическое
     * значение — в 1/0; остальное едет как есть.
     *
     * @param v     значение Java или {@code null}
     * @param field поле
     * @return значение для простой таблицы
     */
    static Object toTransport(Object v, ArgumentInfo field) {
        if (v == null) {
            return null;
        }
        return switch (field.kind()) {
            case TIMESTAMP -> {
                if (!withZone(field)) {
                    yield Values.toTimestamp(v).toLocalDateTime().format(TS);
                }
                OffsetDateTime odt = v instanceof OffsetDateTime o ? o
                        : v instanceof ZonedDateTime z ? z.toOffsetDateTime()
                        : Values.toTimestamp(v).toLocalDateTime().atZone(ZoneId.systemDefault()).toOffsetDateTime();
                yield odt.format(TSTZ);
            }
            case BOOLEAN -> {
                boolean b = v instanceof Boolean x ? x
                        : v instanceof Number n ? n.intValue() != 0 : Values.parseBoolean(v.toString());
                yield b ? 1 : 0;
            }
            default -> v;
        };
    }

    /**
     * Превращает значения, прочитанные из простой таблицы, обратно: строки {@code TIMESTAMP} — в
     * {@link Timestamp}, 1/0 — в логическое значение. Исходный тип поля записан в {@code plsType}
     * элемента (см. {@link #leafTable}); у обычной таблицы его нет, и значения не меняются.
     *
     * @param raw значения таблицы
     * @param el  описание элемента таблицы
     * @return значения в Java
     */
    static List<Object> fromTransport(List<Object> raw, ArgumentInfo el) {
        String origin = el.plsType() == null ? "" : el.plsType().toUpperCase(Locale.ROOT);
        boolean timestamp = origin.startsWith("TIMESTAMP");
        boolean bool = origin.equals(BOOLEAN_ORIGIN);
        if (!timestamp && !bool) {
            return raw;
        }
        boolean zone = origin.contains("TIME ZONE");
        List<Object> out = new ArrayList<>(raw.size());
        for (Object v : raw) {
            if (v == null) {
                out.add(null);
            } else if (bool) {
                out.add(((BigDecimal) v).intValue() != 0);
            } else if (zone) {
                out.add(Timestamp.from(OffsetDateTime.parse(v.toString(), TSTZ).toInstant()));
            } else {
                out.add(Timestamp.valueOf(LocalDateTime.parse(v.toString(), TS)));
            }
        }
        return out;
    }

    /**
     * Собирает выходные таблицы из простых: значения с ключами {@code ТАБЛИЦА[].ПОЛЕ} становятся
     * списком карт «поле — значение» под ключом {@code ТАБЛИЦА} (вложенная запись — вложенной
     * картой), а значения с ключом {@code ТАБЛИЦА[]} — списком значений.
     *
     * <p>Выполняется до сборки записей: таблица может быть полем записи, тогда её ключ —
     * {@code ЗАПИСЬ.ПОЛЕ}, и запись соберёт её дальше как обычное поле.
     *
     * @param outs   выходные значения вызова; меняется на месте
     * @param tables ключи таблиц, разложенных по полям
     */
    static void fold(Map<String, Object> outs, List<String> tables) {
        for (String table : tables) {
            String prefix = table + "[]";
            Map<String, List<?>> columns = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, Object>> it = outs.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Object> e = it.next();
                if (e.getKey().equals(prefix) || e.getKey().startsWith(prefix + ".")) {
                    columns.put(e.getKey().substring(prefix.length()), (List<?>) e.getValue());
                    it.remove();
                }
            }
            int n = columns.isEmpty() ? 0 : columns.values().iterator().next().size();
            List<Object> rows = new ArrayList<>(n);
            for (int r = 0; r < n; r++) {
                if (columns.containsKey("")) {
                    rows.add(columns.get("").get(r));
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (Map.Entry<String, List<?>> col : columns.entrySet()) {
                    put(row, col.getKey().substring(1).split("\\."), col.getValue().get(r));
                }
                rows.add(row);
            }
            outs.put(table, rows);
        }
    }

    /**
     * Кладёт значение в карту по пути, создавая вложенные карты.
     *
     * @param row   карта элемента
     * @param path  путь к полю
     * @param value значение
     */
    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> row, String[] path, Object value) {
        Map<String, Object> m = row;
        for (int i = 0; i < path.length - 1; i++) {
            m = (Map<String, Object>) m.computeIfAbsent(path[i], k -> new LinkedHashMap<String, Object>());
        }
        m.put(path[path.length - 1], value);
    }

    /**
     * Проверяет, хранит ли {@code TIMESTAMP} часовой пояс ({@code WITH TIME ZONE} или
     * {@code WITH LOCAL TIME ZONE}).
     *
     * @param field поле {@code TIMESTAMP}
     * @return {@code true} для типов с поясом
     */
    private static boolean withZone(ArgumentInfo field) {
        return field.dataType() != null && field.dataType().toUpperCase(Locale.ROOT).contains("TIME ZONE");
    }

    /**
     * Проверяет, национальная ли это строка ({@code NVARCHAR2}, {@code NCHAR}).
     *
     * @param f поле
     * @return {@code true} для национальных строк
     */
    private static boolean national(ArgumentInfo f) {
        return f.dataType() != null && f.dataType().toUpperCase(Locale.ROOT).startsWith("N");
    }
}
