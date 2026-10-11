package dev.plsql.spring.call;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
 * Index-by таблицы, которые драйвер Oracle не передаёт сам: таблицы записей, {@code TIMESTAMP},
 * {@code BOOLEAN}, LOB, {@code XMLTYPE}, {@code RAW} и национальных строк. Такая таблица едет
 * несколькими простыми таблицами, по одной на поле элемента.
 *
 * <p>Драйвер умеет привязывать только index-by таблицы чисел, строк и дат (проверено на 11.2:
 * таблица {@code TIMESTAMP} на входе даёт ORA-00600, на выходе рвёт соединение). Поэтому блок
 * объявляет простые таблицы ({@code TABLE OF NUMBER / VARCHAR2 / DATE INDEX BY PLS_INTEGER}) и
 * перекладывает значения циклом: на входе — из простых таблиц в поля элементов настоящей таблицы,
 * на выходе — обратно. {@code TIMESTAMP} едет строкой в строгом формате, {@code BOOLEAN} — числом
 * 1/0. Ключи выходной таблицы не сохраняются: элементы приходят списком в порядке ключей
 * (таблица может быть разреженной: 10, 20, 30); входная таблица заполняется с ключа 1.
 *
 * <p>Поля {@code CLOB}, {@code NCLOB}, {@code BLOB}, {@code XMLTYPE}, {@code RAW} и национальные
 * строки ({@code NVARCHAR2}, {@code NCHAR}) едут иначе: значения поля всех элементов склеиваются в
 * один LOB ({@code CLOB}, {@code NCLOB} или {@code BLOB}), а рядом идёт таблица чисел с длиной
 * каждого значения ({@code NULL} — значение {@code NULL}). Так нет предела в 32 767 байт на
 * значение и драйверу не нужно заранее резервировать память под выходные строки. Национальный
 * текст едет в {@code NCLOB}, и символы вне основной кодировки базы не пропадают.
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
    /** Окончание ключа таблицы длин у поля, которое едет склеенным LOB. */
    static final String LENGTHS = "#len";
    /** Окончание ключа склеенного LOB. */
    static final String CONTENT = "#lob";

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
     *                  {@code DATE}; {@code CLOB} и {@code BLOB} — склеенным LOB с таблицей длин
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
     * Таблицу национальных строк ({@code NVARCHAR2}) — нет: на XE 11.2 любая её привязка даёт
     * PLS-00382.
     *
     * @param el описание элемента таблицы
     * @return {@code true}, если таблица привязывается напрямую
     */
    static boolean direct(ArgumentInfo el) {
        return el.kind() == ArgKind.NUMBER || (el.kind() == ArgKind.STRING && !national(el))
                || el.kind() == ArgKind.DATE;
    }

    /**
     * Возвращает причину, по которой таблицу с такими элементами нельзя разложить по полям, или
     * {@code null}, если можно.
     *
     * <p>Раскладываются записи (в том числе вложенные) с полями-числами, строками (и
     * национальными), датами, {@code TIMESTAMP}, {@code BOOLEAN}, LOB, {@code XMLTYPE} и
     * {@code RAW}, а также таблицы этих типов. Не раскладываются поля-коллекции, объекты,
     * курсоры и таблицы внутри элементов.
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
            case NUMBER, STRING, DATE, TIMESTAMP, BOOLEAN, CLOB, BLOB, RAW, XMLTYPE -> {
                return null;
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
            case CLOB, XMLTYPE -> ArgKind.CLOB;
            case BLOB, RAW -> ArgKind.BLOB;
            case STRING -> national(el) ? ArgKind.CLOB : ArgKind.STRING;
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
     * Проверяет, едет ли поле склеенным LOB с таблицей длин.
     *
     * @param leaf поле
     * @return {@code true} для LOB, {@code XMLTYPE}, {@code RAW} и национальных строк
     */
    static boolean lob(Leaf leaf) {
        return leaf.transport() == ArgKind.CLOB || leaf.transport() == ArgKind.BLOB;
    }

    /**
     * Возвращает тип LOB, в котором склеиваются значения поля.
     *
     * @param field поле
     * @return {@code BLOB} для {@code BLOB} и {@code RAW}, {@code NCLOB} для национального
     *         текста, иначе {@code CLOB}
     */
    static String lobType(ArgumentInfo field) {
        if (field.kind() == ArgKind.BLOB || field.kind() == ArgKind.RAW) {
            return "BLOB";
        }
        return national(field) ? "NCLOB" : "CLOB";
    }

    /**
     * Описывает склеенный LOB поля — для привязки: тип данных {@code CLOB}, {@code NCLOB} или
     * {@code BLOB}.
     *
     * @param table исходная index-by таблица
     * @param leaf  поле элемента
     * @return описание LOB
     */
    static ArgumentInfo contentArg(ArgumentInfo table, Leaf leaf) {
        return new ArgumentInfo(name(table, leaf) + CONTENT, table.position(), table.dataLevel(), lobType(leaf.field()),
                null, table.inOut(), false, null, null, null, List.of(), null);
    }

    /**
     * Описывает таблицу длин поля, которое едет склеенным LOB, — простую таблицу чисел.
     *
     * @param table исходная index-by таблица
     * @param leaf  поле элемента
     * @return описание таблицы длин
     */
    static ArgumentInfo lengthsTable(ArgumentInfo table, Leaf leaf) {
        ArgumentInfo el = new ArgumentInfo(null, 1, table.dataLevel() + 1, "NUMBER", null, table.inOut(), false,
                null, null, null, List.of(), null);
        return new ArgumentInfo(name(table, leaf) + LENGTHS, table.position(), table.dataLevel(), "PL/SQL TABLE", null,
                table.inOut(), false, null, null, null, List.of(el), null);
    }

    /**
     * Возвращает имя поля для сообщений: имя таблицы (или {@code RETURN}) и путь к полю.
     *
     * @param table index-by таблица
     * @param leaf  поле элемента
     * @return имя вида {@code P_ROWS.DOC}
     */
    private static String name(ArgumentInfo table, Leaf leaf) {
        return (table.name() == null ? "RETURN" : table.name()) + leaf.suffix();
    }

    /**
     * Проверяет, нужна ли полю вспомогательная переменная-LOB в блоке: на входе — у
     * {@code CLOB}, {@code NCLOB}, {@code BLOB} и {@code XMLTYPE} (значение собирается во
     * временном LOB), на выходе — у {@code XMLTYPE} (текст из {@code getClobVal()}).
     *
     * @param field поле
     * @return {@code true}, если переменная нужна
     */
    static boolean needsScratch(ArgumentInfo field) {
        return field.kind() == ArgKind.CLOB || field.kind() == ArgKind.BLOB || field.kind() == ArgKind.XMLTYPE;
    }

    /**
     * Возвращает операторы PL/SQL для входной таблицы, которые достают значение поля одного
     * элемента из склеенного LOB: длина берётся из таблицы длин, начало — из счётчика позиции,
     * который затем сдвигается на эту длину. {@code CLOB}, {@code NCLOB} и {@code BLOB}
     * копируются во временный LOB (живёт до конца вызова), {@code XMLTYPE} строится из него же;
     * национальная строка и {@code RAW} читаются через {@code DBMS_LOB.SUBSTR}.
     *
     * @param dest    поле элемента, например {@code v1(j1).DOC}
     * @param length  длина значения, например {@code v2(j1)}; {@code NULL} — значение {@code NULL}
     * @param content переменная со склеенным LOB
     * @param pos     переменная-счётчик позиции в нём
     * @param scratch вспомогательная переменная-LOB или {@code null}, если она не нужна
     * @param field   поле
     * @return операторы PL/SQL
     */
    static String lobToField(String dest, String length, String content, String pos, String scratch,
                             ArgumentInfo field) {
        String copy = scratch + " := NULL; DBMS_LOB.CREATETEMPORARY(" + scratch + ", TRUE, DBMS_LOB.CALL); ";
        return switch (field.kind()) {
            case CLOB, BLOB -> "IF " + length + " IS NULL THEN " + dest + " := NULL; ELSE " + copy
                    + "IF " + length + " > 0 THEN DBMS_LOB.COPY(" + scratch + ", " + content + ", " + length + ", 1, "
                    + pos + "); " + pos + " := " + pos + " + " + length + "; END IF; " + dest + " := " + scratch
                    + "; END IF;";
            case XMLTYPE -> "IF " + length + " IS NULL OR " + length + " = 0 THEN " + dest + " := NULL; ELSE " + copy
                    + "DBMS_LOB.COPY(" + scratch + ", " + content + ", " + length + ", 1, " + pos + "); " + pos
                    + " := " + pos + " + " + length + "; " + dest + " := XMLTYPE(" + scratch + "); END IF;";
            default -> "IF " + length + " IS NULL OR " + length + " = 0 THEN " + dest + " := NULL; ELSE " + dest
                    + " := DBMS_LOB.SUBSTR(" + content + ", " + length + ", " + pos + "); " + pos + " := " + pos
                    + " + " + length + "; END IF;";
        };
    }

    /**
     * Возвращает операторы PL/SQL для выходной таблицы, которые дописывают значение поля одного
     * элемента в склеенный LOB и кладут его длину в таблицу длин ({@code NULL} — значение
     * {@code NULL}). Длина {@code CLOB} и национальной строки — в символах так, как их считает
     * LOB (эмодзи — два, как в Java), {@code BLOB} и {@code RAW} — в байтах.
     *
     * @param value   значение поля, например {@code v1(v9).DOC}
     * @param length  элемент таблицы длин, например {@code v2(v8)}
     * @param content переменная со склеенным LOB
     * @param scratch вспомогательная переменная-{@code CLOB} для {@code XMLTYPE} или {@code null}
     * @param field   поле
     * @return операторы PL/SQL
     */
    static String lobFromField(String value, String length, String content, String scratch, ArgumentInfo field) {
        String none = "IF " + value + " IS NULL THEN " + length + " := NULL; ELSE ";
        return switch (field.kind()) {
            case CLOB, BLOB -> none + length + " := DBMS_LOB.GETLENGTH(" + value + "); IF " + length
                    + " > 0 THEN DBMS_LOB.APPEND(" + content + ", " + value + "); END IF; END IF;";
            case XMLTYPE -> none + scratch + " := " + value + ".getClobVal(); " + length + " := DBMS_LOB.GETLENGTH("
                    + scratch + "); IF " + length + " > 0 THEN DBMS_LOB.APPEND(" + content + ", " + scratch
                    + "); END IF; IF DBMS_LOB.ISTEMPORARY(" + scratch + ") = 1 THEN DBMS_LOB.FREETEMPORARY(" + scratch
                    + "); END IF; END IF;";
            case RAW -> none + length + " := UTL_RAW.LENGTH(" + value + "); DBMS_LOB.WRITEAPPEND(" + content + ", "
                    + length + ", " + value + "); END IF;";
            default -> none + length + " := LENGTH(" + value + "); DBMS_LOB.WRITEAPPEND(" + content + ", " + length
                    + ", " + value + "); END IF;";
        };
    }

    /**
     * Возвращает длины значений поля для таблицы длин (входная таблица): число символов текста
     * или байтов; {@code null} — значение {@code null}.
     *
     * @param list список (или массив) элементов; {@code null} — пустая таблица
     * @param leaf поле, которое едет склеенным LOB
     * @return длины по элементам, по порядку
     * @throws IllegalArgumentException если значение для {@code BLOB} или {@code RAW} не массив байтов
     */
    static List<Object> lengths(Object list, Leaf leaf) {
        List<Object> out = new ArrayList<>();
        for (Object v : fieldValues(list, leaf)) {
            out.add(v == null ? null : leaf.transport() == ArgKind.BLOB ? bytes(v, leaf).length : Values.toText(v).length());
        }
        return out;
    }

    /**
     * Текст поля всех элементов, который едет одним LOB, вместе со значениями по элементам: по
     * ним исполнитель проверяет кодировку базы и называет в ошибке элемент
     * ({@code P_DOCS[2].BODY}) и позицию внутри его значения, а не в склейке.
     *
     * @param table имя таблицы для сообщений
     * @param field путь к полю ({@code .BODY}) или пустая строка
     * @param parts значения по элементам; {@code null} — значение {@code NULL}
     */
    record Glued(String table, String field, List<String> parts) {

        /**
         * Склеивает значения, пропуская {@code null}.
         *
         * @return склеенный текст
         */
        String text() {
            StringBuilder sb = new StringBuilder();
            parts.forEach(p -> {
                if (p != null) {
                    sb.append(p);
                }
            });
            return sb.toString();
        }

        /**
         * Возвращает имя значения одного элемента для сообщений.
         *
         * @param i номер элемента с нуля
         * @return имя вида {@code P_DOCS[2].BODY}
         */
        String name(int i) {
            return table + "[" + (i + 1) + "]" + field;
        }
    }

    /**
     * Склеивает значения поля всех элементов в одно (входная таблица): текст — в {@link Glued}
     * ({@code XML} — своим текстом), байты — в массив; {@code null} пропускается.
     *
     * @param list  список (или массив) элементов; {@code null} — пустая таблица
     * @param leaf  поле, которое едет склеенным LOB
     * @param table имя таблицы для сообщений
     * @return {@link Glued} или массив байтов
     * @throws IllegalArgumentException если значение для {@code BLOB} или {@code RAW} не массив байтов
     */
    static Object content(Object list, Leaf leaf, String table) {
        if (leaf.transport() == ArgKind.BLOB) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Object v : fieldValues(list, leaf)) {
                if (v != null) {
                    out.writeBytes(bytes(v, leaf));
                }
            }
            return out.toByteArray();
        }
        List<String> parts = new ArrayList<>();
        for (Object v : fieldValues(list, leaf)) {
            parts.add(v == null ? null : Values.toText(v));
        }
        return new Glued(table, leaf.suffix(), parts);
    }

    /**
     * Проверяет, что значение для {@code BLOB} или {@code RAW} — массив байтов.
     *
     * @param v    значение
     * @param leaf поле
     * @return массив байтов
     * @throws IllegalArgumentException если это не массив байтов
     */
    private static byte[] bytes(Object v, Leaf leaf) {
        if (v instanceof byte[] b) {
            return b;
        }
        throw new IllegalArgumentException("Cannot bind " + v.getClass().getName() + " as "
                + leaf.field().dataType() + " field " + String.join(".", leaf.path()) + "; use byte[]");
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
            // С поясом — в UTC: SYS_EXTRACT_UTC принимает и WITH TIME ZONE, и WITH LOCAL TIME ZONE, а
            // CAST внутри PL/SQL на 11.2 даёт PLS-00382. Java всё равно получает момент времени.
            case TIMESTAMP -> withZone(field)
                    ? "CASE WHEN " + expr + " IS NULL THEN NULL ELSE TO_CHAR(SYS_EXTRACT_UTC(" + expr + "), '"
                            + TS_FORMAT + "') || ' +00:00' END"
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
        List<Object> out = new ArrayList<>();
        for (Object v : fieldValues(list, leaf)) {
            out.add(toTransport(v, leaf.field()));
        }
        return out;
    }

    /**
     * Достаёт из списка Java значения одного поля как есть.
     *
     * @param list список (или массив) элементов: record, бины, {@code Map} или простые значения;
     *             {@code null} — пустая таблица
     * @param leaf поле
     * @return значения поля по элементам, по порядку
     */
    private static List<Object> fieldValues(Object list, Leaf leaf) {
        Collection<?> c = list == null ? List.of()
                : list instanceof Collection<?> col ? col : Arrays.asList(ObjectUtils.toObjectArray(list));
        List<Object> out = new ArrayList<>(c.size());
        for (Object e : c) {
            Object v = e;
            for (String p : leaf.path()) {
                v = v == null ? null : Values.property(v, p);
            }
            out.add(v);
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
                // Момент времени (Instant, Timestamp) — сразу в UTC: через местное время JVM час
                // перевода часов осенью неоднозначен, и момент сдвинулся бы на час.
                OffsetDateTime odt = v instanceof OffsetDateTime o ? o
                        : v instanceof ZonedDateTime z ? z.toOffsetDateTime()
                        : v instanceof LocalDateTime l ? l.atZone(ZoneId.systemDefault()).toOffsetDateTime()
                        : Values.toTimestamp(v).toInstant().atOffset(ZoneOffset.UTC);
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
            slice(outs, prefix);
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
     * Разрезает склеенные LOB таблицы обратно на значения: пара ключей {@code ПОЛЕ#lob} (строка
     * или массив байтов) и {@code ПОЛЕ#len} (длины) становится списком значений под ключом
     * {@code ПОЛЕ}.
     *
     * @param outs   выходные значения вызова; меняется на месте
     * @param prefix начало ключей таблицы, {@code ТАБЛИЦА[]}
     */
    private static void slice(Map<String, Object> outs, String prefix) {
        List<String> keys = outs.keySet().stream().filter(k -> k.startsWith(prefix) && k.endsWith(LENGTHS)).toList();
        for (String k : keys) {
            String base = k.substring(0, k.length() - LENGTHS.length());
            List<?> lengths = (List<?>) outs.remove(k);
            Object content = outs.remove(base + CONTENT);
            List<Object> values = new ArrayList<>(lengths.size());
            int pos = 0;
            for (Object l : lengths) {
                if (l == null) {
                    values.add(null);
                    continue;
                }
                int n = ((Number) l).intValue();
                values.add(content instanceof byte[] b ? Arrays.copyOfRange(b, pos, pos + n)
                        : ((String) content).substring(pos, pos + n));
                pos += n;
            }
            outs.put(base, values);
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
