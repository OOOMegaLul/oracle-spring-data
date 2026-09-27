package dev.plsql.spring.meta;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Читает сигнатуры процедур и функций из словаря данных Oracle.
 *
 * <p>Словарь данных — набор системных представлений, в которых база описывает свои объекты.
 * Здесь используются {@code ALL_ARGUMENTS} (аргументы подпрограмм), {@code ALL_OBJECTS}
 * (объекты и их статус), {@code ALL_PROCEDURES} (список подпрограмм), {@code ALL_SOURCE}
 * (исходный текст), {@code ALL_SYNONYMS} (синонимы в {@code %ROWTYPE}), {@code ALL_TYPE_ATTRS},
 * {@code ALL_TYPES} и {@code ALL_COLL_TYPES} (устройство объектных типов и коллекций SQL).
 * Префикс {@code ALL_} означает «всё, что доступно текущему пользователю».
 *
 * <p>Имена разрешаются через {@code DBMS_UTILITY.NAME_RESOLVE} — ту же процедуру, которой
 * пользуется компилятор PL/SQL, поэтому частные и публичные синонимы работают ровно так же,
 * как в вызове, написанном вручную. Синоним — дополнительное имя объекта; частный принадлежит
 * одной схеме, публичный ({@code PUBLIC}) виден всем.
 */
public class DictionaryReader {

    /**
     * Общая часть запросов к {@code ALL_ARGUMENTS}: столбцы, из которых собирается {@code Row}.
     *
     * <p>{@code OVERLOAD} — номер перегрузки, {@code POSITION} — место аргумента в списке,
     * {@code SEQUENCE} — порядок строк, {@code DATA_LEVEL} — уровень вложенности (поля записи
     * и элементы коллекций лежат отдельными строками уровнем ниже), {@code IN_OUT} —
     * направление, {@code DEFAULTED} — есть ли значение по умолчанию,
     * {@code TYPE_OWNER}/{@code TYPE_NAME}/{@code TYPE_SUBNAME} — имя пользовательского типа,
     * {@code CHAR_LENGTH} — объявленная длина строки в символах (нужна элементам index-by
     * таблиц: под выходную таблицу драйвер заранее выделяет место).
     */
    private static final String COLUMNS = """
            select object_name, overload, position, sequence, data_level, argument_name, data_type,
                   pls_type, in_out, defaulted, type_owner, type_name, type_subname,
                   nullif(nvl(char_length, 0), 0) as char_len
              from all_arguments
            """;

    /**
     * Аргументы одной подпрограммы: пакетной или автономной.
     *
     * <p>{@code NVL(PACKAGE_NAME, '~')} подставляет {@code '~'} вместо пустого имени пакета,
     * чтобы одно условие подходило и автономным подпрограммам: в SQL {@code NULL = NULL} не
     * истинно. Перегрузки идут по номеру (без номера — первой), строки внутри перегрузки — по
     * {@code SEQUENCE}.
     */
    private static final String ARGS_SQL = COLUMNS + """
             where owner = ?
               and object_name = ?
               and nvl(package_name, '~') = nvl(?, '~')
             order by overload nulls first, sequence""";

    /** Аргументы всех подпрограмм пакета сразу, по имени подпрограммы, перегрузке и {@code SEQUENCE}. */
    private static final String PACKAGE_SQL = COLUMNS + """
             where owner = ? and package_name = ?
             order by object_name, overload nulls first, sequence""";

    /**
     * Сколько имён подставляется в один список {@code IN (...)}: у Oracle на такой список есть
     * предел (не больше 1000 выражений, ORA-01795), 900 взято с запасом.
     */
    private static final int IN_LIST = 900;

    /**
     * Результат {@code NAME_RESOLVE}: где на самом деле лежит код.
     *
     * <p>Синонимы здесь уже раскрыты: {@code owner} и {@code packageName} указывают на настоящий
     * объект, а не на синоним.
     *
     * @param owner       схема, которой принадлежит код
     * @param packageName пакет или {@code null} у автономной подпрограммы
     * @param name        имя подпрограммы; {@code null} в результате
     *                    {@link DictionaryReader#resolvePackage}, где разрешается только пакет
     */
    public record Resolved(String owner, String packageName, String name) {
    }

    /**
     * Сообщает, что имя — синоним объекта в другой базе (через database link, dblink).
     *
     * <p>Словарь описывает только объекты своей базы, а вызов через dblink не может передать
     * {@code BOOLEAN}, записи и прочее, ради чего пишется блок, поэтому такие имена не
     * поддерживаются, и это говорится прямо, а не «объект не найден».
     */
    public static class RemoteObjectException extends IllegalStateException {
        /**
         * Создаёт исключение с текстом, который называет имя и ссылку на другую базу.
         *
         * @param name   имя, как его разрешали
         * @param dblink ссылка на другую базу, которую вернул {@code NAME_RESOLVE}
         */
        public RemoteObjectException(String name, String dblink) {
            super(name + " is a synonym for an object in another database (@" + dblink
                    + "); calls over a database link are not supported, call it on that database directly");
        }
    }

    /**
     * Сообщает, что объект есть в базе, но не компилируется (статус {@code INVALID}).
     *
     * <p>Для некомпилирующегося кода в {@code ALL_ARGUMENTS} нет строк, поэтому «объекта нет» и
     * «объект сломан» приходится различать отдельно, по статусу в {@code ALL_OBJECTS}.
     */
    public static class InvalidObjectException extends IllegalStateException {
        /**
         * Создаёт исключение с текстом, который называет объект и советует его перекомпилировать.
         *
         * @param owner схема объекта
         * @param name  имя пакета, процедуры или функции
         */
        public InvalidObjectException(String owner, String name) {
            super(owner + "." + name + " is INVALID in the database (it does not compile); recompile it");
        }
    }

    /**
     * Разрешает одно имя пакета, в том числе через синоним.
     *
     * <p>Вызывает {@link #resolve} и оставляет в результате только владельца и пакет. Если
     * {@code NAME_RESOLVE} сообщил, что по имени лежит не пакет, пакетом считается имя, которое
     * он вернул.
     *
     * @param con         соединение, на котором вызывается {@code NAME_RESOLVE}
     * @param schema      схема или {@code null}, чтобы имя разрешалось от имени подключённого
     *                    пользователя
     * @param packageName имя пакета или синонима пакета
     * @return настоящие владелец и имя пакета; {@code name} в результате равен {@code null}
     * @throws SQLException при ошибке вызова; если имени нет, код ошибки ORA-06564
     */
    public Resolved resolvePackage(Connection con, String schema, String packageName) throws SQLException {
        Resolved r = resolve(con, schema, null, packageName);
        return new Resolved(r.owner(), r.packageName() != null ? r.packageName() : r.name(), null);
    }

    /**
     * Разрешает имя подпрограммы так, как это сделал бы компилятор PL/SQL: раскрывает синонимы
     * и находит настоящие схему, пакет и имя.
     *
     * <p>Склеивает непустые части в имя вида {@code SCHEMA.PKG.NAME} и вызывает
     * {@code DBMS_UTILITY.NAME_RESOLVE} с контекстом 1 (код PL/SQL). Процедура возвращает схему,
     * две части имени ({@code part1}, {@code part2}), ссылку на другую базу (dblink), тип первой
     * части и номер объекта. Если имя ведёт в другую базу, бросается
     * {@link RemoteObjectException}; номер объекта не используется. Тип 9 означает пакет:
     * тогда {@code part1} — пакет, а {@code part2} — подпрограмма в нём. Иначе (7 — автономная
     * процедура, 8 — автономная функция) {@code part1} — сама подпрограмма. Если {@code part2}
     * пуст, именем считается {@code part1}; поэтому при разрешении одного имени пакета в
     * {@code name} попадает имя самого пакета.
     *
     * @param con         соединение, на котором вызывается {@code NAME_RESOLVE}
     * @param schema      схема или {@code null}, чтобы имя разрешалось от имени подключённого
     *                    пользователя
     * @param packageName пакет или {@code null}
     * @param name        имя подпрограммы (или пакета, если {@code packageName} равен {@code null})
     * @return где на самом деле лежит код
     * @throws SQLException при ошибке вызова; если имени нет, код ошибки ORA-06564
     * @throws RemoteObjectException если имя — синоним объекта в другой базе
     */
    public Resolved resolve(Connection con, String schema, String packageName, String name) throws SQLException {
        String full = join(schema, packageName, name);
        try (CallableStatement cs = con.prepareCall(
                "begin dbms_utility.name_resolve(?, 1, ?, ?, ?, ?, ?, ?); end;")) {
            cs.setString(1, full);
            cs.registerOutParameter(2, Types.VARCHAR);
            cs.registerOutParameter(3, Types.VARCHAR);
            cs.registerOutParameter(4, Types.VARCHAR);
            cs.registerOutParameter(5, Types.VARCHAR);
            cs.registerOutParameter(6, Types.NUMERIC);
            cs.registerOutParameter(7, Types.NUMERIC);
            cs.execute();
            String owner = cs.getString(2);
            String part1 = cs.getString(3);
            String part2 = cs.getString(4);
            String dblink = cs.getString(5);
            if (dblink != null) {
                throw new RemoteObjectException(full, dblink);
            }
            int part1Type = cs.getInt(6);
            boolean isPackage = part1Type == 9;
            return new Resolved(owner, isPackage ? part1 : null, part2 != null ? part2 : part1);
        }
    }

    /**
     * Читает все перегрузки процедуры или функции из словаря Oracle.
     *
     * <p>Если строк в {@code ALL_ARGUMENTS} нет, различает три случая: объект не компилируется
     * ({@code INVALID} в {@code ALL_OBJECTS}; для пакетной подпрограммы проверяется пакет),
     * это процедура без аргументов (она есть в {@code ALL_PROCEDURES}; возвращается одна
     * перегрузка без аргументов и без номера) или такой подпрограммы нет.
     *
     * @param con соединение, на котором читается словарь
     * @param r   где на самом деле лежит код (результат {@code NAME_RESOLVE})
     * @return перегрузки; пустой список, если такой подпрограммы нет
     * @throws SQLException при ошибке обращения к словарю
     * @throws InvalidObjectException если объект существует, но не компилируется
     */
    public List<SubprogramInfo> read(Connection con, Resolved r) throws SQLException {
        Map<String, Map<String, List<Row>>> rows;
        try (PreparedStatement ps = con.prepareStatement(ARGS_SQL)) {
            ps.setString(1, r.owner());
            ps.setString(2, r.name());
            ps.setString(3, r.packageName());
            rows = rows(ps);
        }
        Map<String, List<Row>> byOverload = rows.getOrDefault(r.name(), Map.of());
        if (byOverload.isEmpty()) {
            String owner = r.owner();
            String object = r.packageName() != null ? r.packageName() : r.name();
            String status = objectStatus(con, owner, object);
            if ("INVALID".equals(status)) {
                throw new InvalidObjectException(owner, object);
            }
            if (existsWithoutArguments(con, r)) {
                return List.of(new SubprogramInfo(r.owner(), r.packageName(), r.name(), null, null, List.of()));
            }
            return List.of();
        }
        return complete(con, r, byOverload);
    }

    /**
     * Читает все подпрограммы пакета одним запросом к {@code ALL_ARGUMENTS}.
     *
     * <p>Если строк нет и спецификация пакета (его объявляющая часть) в статусе
     * {@code INVALID}, бросает исключение; если строк нет по другой причине (пакета нет или в
     * нём нет подпрограмм), возвращает пустую {@code Map}. Затем для каждой подпрограммы
     * дочитываются таблицы {@code %ROWTYPE} и устройство объектных типов SQL, что может стоить
     * дополнительных запросов.
     *
     * @param con         соединение, на котором читается словарь
     * @param owner       схема пакета, точно как в словаре (обычно в верхнем регистре)
     * @param packageName имя пакета, точно как в словаре
     * @return {@code Map} «имя подпрограммы → перегрузки»
     * @throws SQLException при ошибке обращения к словарю
     * @throws InvalidObjectException если спецификация пакета не компилируется
     */
    public Map<String, List<SubprogramInfo>> readPackage(Connection con, String owner, String packageName) throws SQLException {
        Map<String, Map<String, List<Row>>> rows;
        try (PreparedStatement ps = con.prepareStatement(PACKAGE_SQL)) {
            ps.setString(1, owner);
            ps.setString(2, packageName);
            rows = rows(ps);
        }
        if (rows.isEmpty() && "INVALID".equals(objectStatus(con, owner, packageName))) {
            throw new InvalidObjectException(owner, packageName);
        }
        Map<String, List<SubprogramInfo>> out = new LinkedHashMap<>();
        for (var e : rows.entrySet()) {
            out.put(e.getKey(), complete(con, new Resolved(owner, packageName, e.getKey()), e.getValue()));
        }
        return out;
    }

    /**
     * Читает автономные подпрограммы схемы {@code owner} из списка {@code names} за несколько
     * запросов на всех.
     *
     * <p>Имена подставляются в {@code IN (...)} пачками по 900, по запросу на пачку. Имён,
     * которых нет в результате, нет в {@code owner} как подпрограмм с аргументами: это может
     * быть синоним, процедура без аргументов (у неё в {@code ALL_ARGUMENTS} может не быть ни
     * одной строки), невалидный код или отсутствующий объект. Такие имена вызывающий код
     * проверяет по одному через {@link #resolve} и {@link #read}.
     *
     * @param con   соединение, на котором читается словарь
     * @param owner схема, точно как в словаре (обычно в верхнем регистре)
     * @param names имена, точно как в словаре (обычно в верхнем регистре)
     * @return {@code Map} «имя → перегрузки», только для найденных имён
     * @throws SQLException при ошибке обращения к словарю
     */
    public Map<String, List<SubprogramInfo>> readStandalone(Connection con, String owner, List<String> names) throws SQLException {
        Map<String, List<SubprogramInfo>> out = new LinkedHashMap<>();
        for (int from = 0; from < names.size(); from += IN_LIST) {
            List<String> chunk = names.subList(from, Math.min(names.size(), from + IN_LIST));
            String sql = COLUMNS + " where owner = ? and package_name is null and object_name in ("
                    + String.join(", ", java.util.Collections.nCopies(chunk.size(), "?"))
                    + ") order by object_name, overload nulls first, sequence";
            Map<String, Map<String, List<Row>>> rows;
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, owner);
                for (int i = 0; i < chunk.size(); i++) {
                    ps.setString(i + 2, chunk.get(i));
                }
                rows = rows(ps);
            }
            for (var e : rows.entrySet()) {
                out.put(e.getKey(), complete(con, new Resolved(owner, null, e.getKey()), e.getValue()));
            }
        }
        return out;
    }

    /**
     * Возвращает статус пакета, процедуры или функции из {@code ALL_OBJECTS.STATUS}.
     *
     * <p>{@code VALID} — объект скомпилирован; {@code INVALID} — не скомпилирован: в нём ошибка
     * или он ещё не перекомпилирован после изменения того, от чего зависит. Для пакета это
     * статус спецификации, а не тела. Агрегат {@code MIN} даёт ровно одну строку ответа, даже
     * если объекта нет (тогда {@code NULL}).
     *
     * @param con   соединение, на котором читается словарь
     * @param owner схема объекта
     * @param name  имя объекта
     * @return {@code VALID}, {@code INVALID} или {@code null}, если такого объекта нет
     * @throws SQLException при ошибке обращения к словарю
     */
    public String objectStatus(Connection con, String owner, String name) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                select min(status) from all_objects
                 where owner = ? and object_name = ? and object_type in ('PACKAGE', 'PROCEDURE', 'FUNCTION')""")) {
            ps.setString(1, owner);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * Возвращает статус тела пакета ({@code PACKAGE BODY}) из {@code ALL_OBJECTS}.
     *
     * <p>Тело — реализация пакета; оно компилируется отдельно от спецификации, поэтому
     * спецификация может быть в порядке, а тело — нет. Тогда сигнатуры читаются, но вызовы
     * падают.
     *
     * @param con         соединение, на котором читается словарь
     * @param owner       схема пакета
     * @param packageName имя пакета
     * @return {@code VALID}, {@code INVALID} или {@code null}, если тела нет
     * @throws SQLException при ошибке обращения к словарю
     */
    public String packageBodyStatus(Connection con, String owner, String packageName) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "select status from all_objects where owner = ? and object_name = ? and object_type = 'PACKAGE BODY'")) {
            ps.setString(1, owner);
            ps.setString(2, packageName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * Выполняет запрос к {@code ALL_ARGUMENTS} и группирует строки: имя подпрограммы → номер
     * перегрузки → строки, в порядке выдачи запроса.
     *
     * <p>У неперегруженной подпрограммы номера перегрузки нет, её ключ — пустая строка.
     * {@code DEFAULTED = 'Y'} превращается в {@code true}. Длина строки берётся из
     * {@code CHAR_LENGTH}; 0 или пусто — длина не известна. {@code DATA_LENGTH} не берётся: это
     * байты, и у не-символьных типов ({@code ROWID}) он меньше длины их строкового вида.
     *
     * @param ps подготовленный запрос с подставленными параметрами, выбирающий столбцы
     *           {@code COLUMNS}
     * @return {@code Map} «имя подпрограммы → перегрузка → строки»
     * @throws SQLException при ошибке запроса
     */
    private static Map<String, Map<String, List<Row>>> rows(PreparedStatement ps) throws SQLException {
        Map<String, Map<String, List<Row>>> out = new LinkedHashMap<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Row row = new Row(
                        rs.getString("overload"),
                        rs.getInt("position"),
                        rs.getInt("data_level"),
                        rs.getString("argument_name"),
                        rs.getString("data_type"),
                        rs.getString("pls_type"),
                        rs.getString("in_out"),
                        "Y".equals(rs.getString("defaulted")),
                        rs.getString("type_owner"),
                        rs.getString("type_name"),
                        rs.getString("type_subname"),
                        length(rs));
                out.computeIfAbsent(rs.getString("object_name"), k -> new LinkedHashMap<>())
                        .computeIfAbsent(row.overload == null ? "" : row.overload, k -> new ArrayList<>()).add(row);
            }
        }
        return out;
    }

    /**
     * Читает объявленную длину строки в символах ({@code CHAR_LENGTH}) из текущей строки запроса.
     *
     * @param rs результат запроса {@code COLUMNS}, стоящий на строке
     * @return длина в символах или {@code null}, если словарь её не сообщает
     * @throws SQLException при ошибке чтения
     */
    private static Integer length(ResultSet rs) throws SQLException {
        int n = rs.getInt("char_len");
        return rs.wasNull() || n <= 0 ? null : n;
    }

    /**
     * Превращает строки каждой перегрузки в {@link SubprogramInfo} и дочитывает то, чего нет в
     * {@code ALL_ARGUMENTS}: таблицы {@code %ROWTYPE} и устройство объектных типов и коллекций
     * SQL.
     *
     * @param con        соединение, на котором читается словарь
     * @param r          где лежит подпрограмма
     * @param byOverload строки, сгруппированные по номеру перегрузки
     * @return перегрузки в порядке {@code byOverload}
     * @throws SQLException при ошибке обращения к словарю
     */
    private List<SubprogramInfo> complete(Connection con, Resolved r, Map<String, List<Row>> byOverload) throws SQLException {
        List<SubprogramInfo> result = new ArrayList<>();
        for (List<Row> rows : byOverload.values()) {
            SubprogramInfo sp = fillRowtypes(con, build(r, rows));
            result.add(fillSqlTypes(con, sp));
        }
        return result;
    }

    // ------------------------------------------------------------ объектные типы SQL

    /**
     * Дочитывает устройство объектных типов и коллекций SQL для всех аргументов и возвращаемого
     * значения подпрограммы.
     *
     * <p>Объектный тип SQL создаётся командой {@code CREATE TYPE ... AS OBJECT}; в отличие от
     * записи PL/SQL он существует на уровне SQL и передаётся через JDBC как
     * {@code java.sql.Struct}. {@code ALL_ARGUMENTS} описывает записи PL/SQL поле за полем, но на
     * объектных типах SQL останавливается: у аргумента типа {@code OBJECT} строк с атрибутами
     * нет вовсе. Атрибуты берутся из {@code ALL_TYPE_ATTRS}, элементы коллекций — из
     * {@code ALL_COLL_TYPES}.
     *
     * @param con соединение, на котором читается словарь
     * @param sp  подпрограмма, собранная из строк {@code ALL_ARGUMENTS}
     * @return копия {@code sp} с дочитанными атрибутами и элементами
     * @throws SQLException при ошибке обращения к словарю
     */
    private SubprogramInfo fillSqlTypes(Connection con, SubprogramInfo sp) throws SQLException {
        List<ArgumentInfo> args = new ArrayList<>();
        for (ArgumentInfo a : sp.arguments()) {
            args.add(completeSqlType(con, a, 0));
        }
        ArgumentInfo ret = sp.returnValue() == null ? null : completeSqlType(con, sp.returnValue(), 0);
        return new SubprogramInfo(sp.owner(), sp.packageName(), sp.name(), sp.overload(), ret, args);
    }

    /**
     * Рекурсивно дочитывает вложенные элементы одного аргумента.
     *
     * <p>У {@code OBJECT} с известным именем типа вложенные элементы заменяются атрибутами из
     * {@code ALL_TYPE_ATTRS}. У коллекции SQL, для которой словарь не дал строки элемента,
     * элемент читается из {@code ALL_COLL_TYPES}. Остальные вложенные строки остаются как есть.
     * Затем то же делается для каждого вложенного элемента. Глубже {@link #MAX_TYPE_DEPTH}
     * уровней обход не идёт (так он не может уйти в бесконечную рекурсию): такой элемент
     * помечается типом {@code NESTED TOO DEEP}, и планировщик называет его при старте, а не
     * отправляет объект без атрибутов.
     *
     * @param con   соединение, на котором читается словарь
     * @param a     аргумент или вложенный элемент
     * @param depth текущая глубина, 0 у аргумента верхнего уровня
     * @return новый {@link ArgumentInfo} с дочитанными вложенными элементами
     * @throws SQLException при ошибке обращения к словарю
     */
    private ArgumentInfo completeSqlType(Connection con, ArgumentInfo a, int depth) throws SQLException {
        if (depth > MAX_TYPE_DEPTH) {
            return new ArgumentInfo(a.name(), a.position(), a.dataLevel(), "NESTED TOO DEEP", a.plsType(), a.inOut(),
                    a.defaulted(), a.typeOwner(), a.typeName(), a.typeSubname(), null, a.charLength());
        }
        ArgKind k = a.kind();
        List<ArgumentInfo> children = new ArrayList<>();
        if (k == ArgKind.OBJECT && a.typeName() != null) {
            children.addAll(typeAttributes(con, a.typeOwner(), a.typeName(), a.inOut()));
        } else if (k == ArgKind.SQL_COLLECTION && a.typeName() != null && a.children().isEmpty()) {
            ArgumentInfo el = collectionElement(con, a.typeOwner(), a.typeName(), a.inOut());
            if (el != null) {
                children.add(el);
            }
        } else {
            children.addAll(a.children());
        }
        List<ArgumentInfo> completed = new ArrayList<>();
        for (ArgumentInfo c : children) {
            completed.add(completeSqlType(con, c, depth + 1));
        }
        return new ArgumentInfo(a.name(), a.position(), a.dataLevel(), a.dataType(), a.plsType(), a.inOut(),
                a.defaulted(), a.typeOwner(), a.typeName(), a.typeSubname(), completed, a.charLength());
    }

    /**
     * Сколько уровней вложенности объектных типов и коллекций SQL дочитывается.
     */
    private static final int MAX_TYPE_DEPTH = 8;

    /**
     * Читает атрибуты (поля) объектного типа SQL из {@code ALL_TYPE_ATTRS} в порядке объявления.
     *
     * <p>Для каждого атрибута из {@code ALL_TYPES} берётся код типа ({@code TYPECODE}), чтобы
     * отличить объект и коллекцию от встроенного типа, а из {@code ALL_COLL_TYPES} — вид
     * коллекции, если атрибут сам коллекция. У встроенных типов ({@code NUMBER},
     * {@code VARCHAR2}) владельца нет, и соединение с этими представлениями для них явно
     * пропускается. Тип, объявленный через публичный синоним, словарь записывает с владельцем
     * {@code PUBLIC} (так выглядит, например, {@code XMLTYPE}: {@code PUBLIC.XMLTYPE}); синоним
     * раскрывается до настоящего типа ({@code SYS.XMLTYPE}).
     *
     * @param con   соединение, на котором читается словарь
     * @param owner схема типа
     * @param type  имя типа
     * @param inOut направление родительского аргумента; атрибуты его наследуют
     * @return атрибуты; пустой список, если тип не найден
     * @throws SQLException при ошибке обращения к словарю
     */
    private List<ArgumentInfo> typeAttributes(Connection con, String owner, String type, String inOut) throws SQLException {
        String sql = """
                select a.attr_name, nvl(s.table_owner, a.attr_type_owner), nvl(s.table_name, a.attr_type_name),
                       t.typecode, c.coll_type
                  from all_type_attrs a
                  left join all_synonyms s on a.attr_type_owner = 'PUBLIC'
                        and s.owner = 'PUBLIC' and s.synonym_name = a.attr_type_name
                  left join all_types t on a.attr_type_owner is not null
                        and t.owner = nvl(s.table_owner, a.attr_type_owner) and t.type_name = nvl(s.table_name, a.attr_type_name)
                  left join all_coll_types c on a.attr_type_owner is not null
                        and c.owner = nvl(s.table_owner, a.attr_type_owner) and c.type_name = nvl(s.table_name, a.attr_type_name)
                 where a.owner = ? and a.type_name = ?
                 order by a.attr_no""";
        List<ArgumentInfo> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, owner);
            ps.setString(2, type);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(sqlTypeArg(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), inOut));
                }
            }
        }
        return out;
    }

    /**
     * Читает тип элемента коллекции SQL ({@code TABLE OF} или {@code VARRAY}) из
     * {@code ALL_COLL_TYPES}; публичный синоним типа элемента раскрывается, как у атрибутов.
     *
     * @param con   соединение, на котором читается словарь
     * @param owner схема типа коллекции
     * @param type  имя типа коллекции
     * @param inOut направление родительского аргумента; элемент его наследует
     * @return описание элемента без имени или {@code null}, если коллекция не найдена
     * @throws SQLException при ошибке обращения к словарю
     */
    private ArgumentInfo collectionElement(Connection con, String owner, String type, String inOut) throws SQLException {
        String sql = """
                select nvl(s.table_owner, c.elem_type_owner), nvl(s.table_name, c.elem_type_name), t.typecode, cc.coll_type
                  from all_coll_types c
                  left join all_synonyms s on c.elem_type_owner = 'PUBLIC'
                        and s.owner = 'PUBLIC' and s.synonym_name = c.elem_type_name
                  left join all_types t on c.elem_type_owner is not null
                        and t.owner = nvl(s.table_owner, c.elem_type_owner) and t.type_name = nvl(s.table_name, c.elem_type_name)
                  left join all_coll_types cc on c.elem_type_owner is not null
                        and cc.owner = nvl(s.table_owner, c.elem_type_owner) and cc.type_name = nvl(s.table_name, c.elem_type_name)
                 where c.owner = ? and c.type_name = ?""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, owner);
            ps.setString(2, type);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? sqlTypeArg(null, rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), inOut)
                        : null;
            }
        }
    }

    /**
     * Строит {@link ArgumentInfo} для атрибута объекта или элемента коллекции по данным
     * {@code ALL_TYPE_ATTRS} или {@code ALL_COLL_TYPES}.
     *
     * <p>Тип записывается так, как его записал бы {@code ALL_ARGUMENTS}: {@code OBJECT},
     * {@code TABLE} или {@code VARRAY} для пользовательских типов ({@code VARYING ARRAY} из
     * словаря превращается в {@code VARRAY}), а для встроенного типа — его имя без размера в
     * скобках ({@code TIMESTAMP(6)} превращается в {@code TIMESTAMP}); владелец и имя типа у
     * встроенного типа обнуляются. Позиция не заполняется (0), уровень вложенности всегда 1.
     *
     * <p>Системные типы {@code SYS.XMLTYPE}, {@code SYS.ANYDATA} и подобные в
     * {@code ALL_TYPES} имеют собственный код ({@code XMLTYPE}, {@code ANYDATA}), а не
     * {@code OBJECT}; они записываются как {@code OPAQUE/<имя>}, как их называет
     * {@code ALL_ARGUMENTS}, чтобы планировщик узнал их и остановил старт с понятной причиной:
     * внутри объектного типа SQL такие атрибуты через JDBC не передать.
     *
     * @param name      имя атрибута или {@code null} у элемента коллекции
     * @param typeOwner схема типа
     * @param typeName  имя типа
     * @param typecode  {@code ALL_TYPES.TYPECODE} или {@code null}, если тип там не нашёлся
     * @param collType  {@code ALL_COLL_TYPES.COLL_TYPE} или {@code null}, если это не коллекция
     * @param inOut     направление, унаследованное от аргумента
     * @return описание атрибута или элемента без вложенных строк
     */
    private static ArgumentInfo sqlTypeArg(String name, String typeOwner, String typeName, String typecode,
                                           String collType, String inOut) {
        String dataType;
        if ("OBJECT".equals(typecode)) {
            dataType = "OBJECT";
        } else if ("COLLECTION".equals(typecode)) {
            dataType = "VARYING ARRAY".equals(collType) ? "VARRAY" : "TABLE";
        } else if (typecode != null) {
            // Системный непрозрачный тип: SYS.XMLTYPE, SYS.ANYDATA, ...
            dataType = "OPAQUE/" + typeName;
        } else if (typeName == null) {
            dataType = "UNKNOWN";
        } else {
            // Встроенный тип: NUMBER, VARCHAR2, DATE, TIMESTAMP(6), ...
            dataType = typeName.replaceAll("\\(.*\\)", "");
            typeOwner = null;
            typeName = null;
        }
        return new ArgumentInfo(name, 0, 1, dataType, null, inOut, false, typeOwner, typeName, null, null);
    }

    /**
     * Восстанавливает таблицу у аргументов и результата типа {@code %ROWTYPE}.
     *
     * <p>{@code %ROWTYPE} — запись со структурой строки таблицы: {@code P_ROW EMP%ROWTYPE}
     * означает «запись с полями, как столбцы EMP». На 11.2 {@code ALL_ARGUMENTS} перечисляет
     * поля такого аргумента, но оставляет {@code TYPE_OWNER} / {@code TYPE_NAME} пустыми, так что
     * имя таблицы сохраняется только в самом объявлении. Поэтому оно читается из
     * {@code ALL_SOURCE} (исходный текст объектов).
     *
     * <p>Исходник читается, только если такие аргументы есть. Обрабатываются аргументы верхнего
     * уровня и возвращаемое значение. Имя из объявления разрешается уже при старте
     * ({@link #resolveRowtype}): синоним раскрывается до таблицы, курсор пакета получает имя
     * пакета. Если разрешить не удалось (исходник зашифрован через {@code wrap}, таблица не
     * видна этому пользователю), аргумент остаётся без типа, и планировщик останавливает старт с
     * понятной причиной, а не падает на первом вызове.
     *
     * @param con соединение, на котором читается словарь
     * @param sp  подпрограмма, собранная из строк {@code ALL_ARGUMENTS}
     * @return {@code sp}, если восстанавливать нечего, иначе копия с именами таблиц
     * @throws SQLException при ошибке обращения к словарю
     */
    private SubprogramInfo fillRowtypes(Connection con, SubprogramInfo sp) throws SQLException {
        boolean needed = sp.arguments().stream().anyMatch(DictionaryReader::isAnonymousRecord)
                || (sp.returnValue() != null && isAnonymousRecord(sp.returnValue()));
        if (!needed) {
            return sp;
        }
        String source = sourceText(con, sp);
        String decl = findDeclaration(source, sp.name(), sp.overload());
        List<ArgumentInfo> args = new ArrayList<>();
        for (ArgumentInfo a : sp.arguments()) {
            args.add(isAnonymousRecord(a)
                    ? withRowtype(a, resolveRowtype(con, sp, source, rowtypeOf(decl, a.name())))
                    : a);
        }
        ArgumentInfo ret = sp.returnValue();
        if (ret != null && isAnonymousRecord(ret)) {
            ret = withRowtype(ret, resolveRowtype(con, sp, source, returnRowtypeOf(decl)));
        }
        return new SubprogramInfo(sp.owner(), sp.packageName(), sp.name(), sp.overload(), ret, args);
    }

    /**
     * Сколько синонимов подряд раскрывается, прежде чем цепочка считается неразрешимой.
     */
    private static final int MAX_SYNONYM_HOPS = 10;

    /**
     * Превращает имя из {@code ...%ROWTYPE}, как оно написано в объявлении, в полное имя,
     * которое можно написать в анонимном блоке.
     *
     * <p>Анонимный блок компилируется в схеме пользователя соединения, а не в схеме владельца
     * пакета, поэтому имя нужно разрешить так, как его видел компилятор пакета:
     * <ul>
     *   <li>{@code C_EMP}, объявленный в спецификации этого же пакета как {@code CURSOR}, — это
     *       курсор: {@code OWNER.PKG.C_EMP};</li>
     *   <li>иначе имя без точки — таблица или представление схемы владельца, её частный синоним
     *       или публичный синоним; синонимы раскрываются до таблицы;</li>
     *   <li>{@code X.Y} — таблица или синоним {@code Y} в схеме {@code X}, а если такого нет и
     *       {@code X} — пакет схемы владельца, то курсор этого пакета;</li>
     *   <li>имя из трёх частей возвращается как есть.</li>
     * </ul>
     *
     * @param con     соединение, на котором читается словарь
     * @param sp      подпрограмма, которой принадлежит объявление
     * @param source  исходный текст спецификации пакета или самой подпрограммы
     * @param written имя из объявления ({@link #rowtypeOf}) или {@code null}
     * @return полное имя таблицы или курсора; {@code null}, если имя не задано или не
     *         разрешилось (такой таблицы этот пользователь не видит, синоним ведёт по dblink)
     * @throws SQLException при ошибке обращения к словарю
     */
    private static String resolveRowtype(Connection con, SubprogramInfo sp, String source, String written)
            throws SQLException {
        if (written == null) {
            return null;
        }
        List<String> parts = nameParts(written);
        if (parts.size() == 1) {
            if (sp.packageName() != null && declaresCursor(source, parts.get(0))) {
                return sqlName(sp.owner(), sp.packageName(), parts.get(0));
            }
            return resolveTable(con, sp.owner(), parts.get(0), true);
        }
        if (parts.size() == 2) {
            String table = resolveTable(con, parts.get(0), parts.get(1), false);
            if (table != null || !isPackage(con, sp.owner(), parts.get(0))) {
                return table;
            }
            return sqlName(sp.owner(), parts.get(0), parts.get(1));
        }
        return sqlName(parts.toArray(String[]::new));
    }

    /**
     * Находит таблицу или представление по имени так, как это делает компилятор PL/SQL в схеме
     * {@code schema}: сначала объект самой схемы, затем её частный синоним, затем публичный
     * синоним. Синоним раскрывается по цепочке, пока не дойдёт до таблицы.
     *
     * <p>Каждый шаг — один запрос: три ветки {@code UNION ALL} упорядочены по приоритету, берётся
     * первая строка.
     *
     * @param con            соединение, на котором читается словарь
     * @param schema         схема, в которой разрешается имя
     * @param name           имя без схемы
     * @param publicSynonyms искать ли публичные синонимы (только для имени, написанного без схемы)
     * @return {@code OWNER.TABLE} (имена в кавычках, если без них нельзя) или {@code null}, если
     *         ничего не нашлось, синоним ведёт в
     *         другую базу (dblink) или цепочка синонимов длиннее {@link #MAX_SYNONYM_HOPS}
     * @throws SQLException при ошибке обращения к словарю
     */
    private static String resolveTable(Connection con, String schema, String name, boolean publicSynonyms)
            throws SQLException {
        String sql = """
                select 1, owner, object_name, cast(null as varchar2(128)) from all_objects
                 where owner = ? and object_name = ? and object_type in ('TABLE', 'VIEW')
                union all
                select 2, table_owner, table_name, db_link from all_synonyms
                 where owner = ? and synonym_name = ?
                union all
                select 3, table_owner, table_name, db_link from all_synonyms
                 where owner = 'PUBLIC' and synonym_name = ? and ? = 'Y'
                 order by 1""";
        for (int hop = 0; hop < MAX_SYNONYM_HOPS; hop++) {
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, schema);
                ps.setString(2, name);
                ps.setString(3, schema);
                ps.setString(4, name);
                ps.setString(5, name);
                ps.setString(6, publicSynonyms ? "Y" : "N");
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getString(4) != null) {
                        return null;
                    }
                    if (rs.getInt(1) == 1) {
                        return sqlName(schema, name);
                    }
                    schema = rs.getString(2);
                    name = rs.getString(3);
                    publicSynonyms = false; // у цели синонима схема указана всегда
                }
            }
        }
        return null;
    }

    /**
     * Проверяет, есть ли в схеме пакет с таким именем.
     *
     * @param con   соединение, на котором читается словарь
     * @param owner схема
     * @param name  имя пакета
     * @return {@code true}, если пакет виден текущему пользователю
     * @throws SQLException при ошибке обращения к словарю
     */
    private static boolean isPackage(Connection con, String owner, String name) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "select 1 from all_objects where owner = ? and object_name = ? and object_type = 'PACKAGE'")) {
            ps.setString(1, owner);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Проверяет, объявлен ли в исходнике курсор с таким именем: {@code CURSOR C_EMP IS ...}.
     *
     * <p>Комментарии и строковые литералы не учитываются; имя без кавычек ищется целым словом
     * без учёта регистра, имя в кавычках — точно.
     *
     * @param source исходный текст спецификации пакета
     * @param name   имя курсора так, как его хранит словарь
     * @return {@code true}, если курсор объявлен
     */
    static boolean declaresCursor(String source, String name) {
        return Pattern.compile("\\bCURSOR\\s+" + namePattern(name) + NAME_END).matcher(scan(source, true)[1]).find();
    }

    /**
     * Проверяет, является ли аргумент записью без имени типа: так на 11.2 выглядит
     * {@code %ROWTYPE}.
     *
     * @param a аргумент
     * @return {@code true} для {@code PL/SQL RECORD} с пустым {@code TYPE_NAME}
     */
    private static boolean isAnonymousRecord(ArgumentInfo a) {
        return "PL/SQL RECORD".equals(a.dataType()) && a.typeName() == null;
    }

    /**
     * Возвращает копию аргумента, у которой имя типа — найденная таблица.
     *
     * @param a         аргумент-запись
     * @param rowtypeOf полное имя таблицы {@code OWNER.TABLE} или {@code null}, если найти не удалось
     * @return {@code a} без изменений, если таблица не найдена; иначе копия, где {@code typeName}
     *         равен имени таблицы, а {@code typeOwner} и {@code typeSubname} пусты
     */
    private static ArgumentInfo withRowtype(ArgumentInfo a, String rowtypeOf) {
        if (rowtypeOf == null) {
            return a;
        }
        // typeSubname остаётся null: CallPlanner объявляет переменную как <typeName>%ROWTYPE.
        return new ArgumentInfo(a.name(), a.position(), a.dataLevel(), a.dataType(), a.plsType(), a.inOut(),
                a.defaulted(), null, rowtypeOf, null, a.children(), a.charLength());
    }

    /**
     * Читает из {@code ALL_SOURCE} исходник спецификации пакета (или самой автономной
     * подпрограммы), склеивая строки по порядку.
     *
     * @param con соединение, на котором читается словарь
     * @param sp  подпрограмма, чей исходник нужен
     * @return исходный текст как есть; пустая строка, если его не видно
     * @throws SQLException при ошибке обращения к словарю
     */
    private static String sourceText(Connection con, SubprogramInfo sp) throws SQLException {
        String sql = """
                select text from all_source
                 where owner = ? and name = ? and type in ('PACKAGE', 'PROCEDURE', 'FUNCTION')
                 order by line""";
        StringBuilder src = new StringBuilder();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, sp.owner());
            ps.setString(2, sp.packageName() != null ? sp.packageName() : sp.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    src.append(rs.getString(1));
                }
            }
        }
        return src.toString();
    }

    /**
     * Находит в исходном тексте PL/SQL объявление подпрограммы {@code name}: от слова
     * {@code PROCEDURE} или {@code FUNCTION} до {@code ;} или {@code IS}/{@code AS}, которые
     * завершают её заголовок.
     *
     * <p>Текст сначала размечается ({@link #scan}): комментарии не учитываются, а внутри
     * строковых литералов ничего не ищется, поэтому ни {@code 'PROCEDURE X'} в значении по
     * умолчанию, ни {@code DEFAULT ')'} не сбивают ни счёт перегрузок, ни поиск конца заголовка.
     * Имя ищется целым словом без учёта регистра, а имя, объявленное в двойных кавычках, — точно.
     * Перегрузки нумеруются в порядке объявления, как их нумерует {@code ALL_ARGUMENTS.OVERLOAD}:
     * берётся вхождение с номером {@code overload}, а если он {@code null} — первое.
     *
     * @param source   исходный текст пакета или подпрограммы
     * @param name     имя подпрограммы так, как его хранит словарь (без кавычек — в верхнем регистре)
     * @param overload номер перегрузки строкой или {@code null}
     * @return объявление без комментариев, в верхнем регистре везде, кроме имён в двойных
     *         кавычках; пустая строка, если объявление не найдено
     */
    static String findDeclaration(String source, String name, String overload) {
        String[] t = scan(source, true);
        Matcher m = Pattern.compile("\\b(?:PROCEDURE|FUNCTION)\\s+" + namePattern(name) + NAME_END).matcher(t[1]);
        int wanted = overload == null || overload.isEmpty() ? 1 : Integer.parseInt(overload);
        int seen = 0;
        while (m.find()) {
            if (++seen == wanted) {
                return t[0].substring(m.start(), headerEnd(t[1], m.end()));
            }
        }
        return "";
    }

    /**
     * Проверка «имя закончилось»: дальше не идёт символ, который мог бы продолжить
     * идентификатор, и не закрывающая кавычка.
     */
    private static final String NAME_END = "(?![\\p{L}\\p{N}_$#\"])";

    /**
     * Часть имени в тексте после {@link #scan}: имя в кавычках или имя без кавычек (оно уже в
     * верхнем регистре).
     */
    private static final String NAME_PART = "(?:\"[^\"]+\"|[\\p{L}\\p{N}_$#]+)";

    /**
     * Шаблон, который находит одно имя в тексте после {@link #scan}.
     *
     * <p>Имя в верхнем регистре (так словарь хранит имена, объявленные без кавычек) находится и
     * без кавычек, и в кавычках. Имя в другом регистре могло быть объявлено только в кавычках и
     * ищется только в них, точно: {@code "Load"} не спутается с {@code load}.
     *
     * @param name имя так, как его хранит словарь
     * @return фрагмент регулярного выражения
     */
    private static String namePattern(String name) {
        String quoted = "\"" + Pattern.quote(name) + "\"";
        return name.equals(name.toUpperCase(Locale.ROOT)) ? "(?:" + quoted + "|" + Pattern.quote(name) + ")" : quoted;
    }

    /**
     * Находит конец заголовка подпрограммы: первую {@code ;} или отдельное слово
     * {@code IS}/{@code AS} вне круглых скобок.
     *
     * <p>Скобки считаются, чтобы не остановиться на таких символах внутри списка аргументов.
     * Поиск идёт по тексту, в котором содержимое строковых литералов уже заменено пробелами.
     *
     * @param masked текст после {@link #scan} (второй элемент)
     * @param from   позиция сразу после имени подпрограммы
     * @return позиция конца заголовка или длина текста, если конец не найден
     */
    private static int headerEnd(String masked, int from) {
        int depth = 0;
        for (int i = from; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && (c == ';' || isKeywordAt(masked, i, "IS") || isKeywordAt(masked, i, "AS"))) {
                return i;
            }
        }
        return masked.length();
    }

    /**
     * Проверяет, начинается ли в позиции {@code i} отдельное ключевое слово {@code kw}.
     *
     * <p>До и после слова не должно быть символа, который мог бы входить в идентификатор, и
     * кавычки. Так {@code IS} в {@code P_ISSUE_DATE} и {@code AS} в {@code ASSET_T} словами не
     * считаются, а {@code )IS} без пробела — считается.
     *
     * @param text текст в верхнем регистре
     * @param i    позиция проверки
     * @param kw   ключевое слово в верхнем регистре
     * @return {@code true}, если в позиции {@code i} стоит слово {@code kw}
     */
    private static boolean isKeywordAt(String text, int i, String kw) {
        if (!text.startsWith(kw, i)) {
            return false;
        }
        if (i > 0 && (isIdentifierChar(text.charAt(i - 1)) || text.charAt(i - 1) == '"')) {
            return false;
        }
        int after = i + kw.length();
        return after >= text.length() || (!isIdentifierChar(text.charAt(after)) && text.charAt(after) != '"');
    }

    /**
     * Проверяет, может ли символ входить в идентификатор PL/SQL без кавычек.
     *
     * @param c символ
     * @return {@code true} для буквы, цифры, {@code _}, {@code $} и {@code #}
     */
    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    /**
     * Находит в объявлении имя, по которому объявлен аргумент {@code argName}:
     * {@code P_ROW OUT NOCOPY EMP%ROWTYPE} даёт {@code EMP}.
     *
     * <p>Понимает режимы {@code IN}, {@code OUT}, {@code IN OUT} и подсказку {@code NOCOPY}
     * (просьбу передавать параметр по ссылке, без копирования). Имя возвращается как написано
     * ({@code EMP}, {@code HR.EMP_HIST}, {@code C_EMP}, {@code "Emp"}); что оно означает —
     * таблицу, синоним или курсор, — решает {@link #resolveRowtype}.
     *
     * @param decl    объявление (результат {@link #findDeclaration})
     * @param argName имя аргумента так, как его хранит словарь
     * @return имя перед {@code %ROWTYPE} или {@code null}, если аргумент объявлен иначе
     */
    static String rowtypeOf(String decl, String argName) {
        Matcher m = Pattern.compile("(?<![\\p{L}\\p{N}_$#\"])" + namePattern(argName)
                + "\\s+(?:IN\\s+OUT\\s+|IN\\s+|OUT\\s+)?(?:NOCOPY\\s+)?(" + NAME_PART + "(?:\\." + NAME_PART + "){0,2})%ROWTYPE")
                .matcher(decl);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Находит в объявлении функции имя из {@code RETURN ...%ROWTYPE}:
     * {@code RETURN EMP%ROWTYPE} даёт {@code EMP}.
     *
     * @param decl объявление (результат {@link #findDeclaration})
     * @return имя перед {@code %ROWTYPE} или {@code null}, если функция возвращает не {@code %ROWTYPE}
     */
    static String returnRowtypeOf(String decl) {
        Matcher m = Pattern.compile("\\bRETURN\\s+(" + NAME_PART + "(?:\\." + NAME_PART + "){0,2})%ROWTYPE").matcher(decl);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Разбивает имя из объявления на части: {@code HR."Emp"} даёт {@code [HR, Emp]}.
     *
     * @param written имя как в объявлении: части без кавычек уже в верхнем регистре
     * @return части имени без кавычек
     */
    static List<String> nameParts(String written) {
        List<String> parts = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]+)\"|([^.\"]+)").matcher(written);
        while (m.find()) {
            parts.add(m.group(1) != null ? m.group(1) : m.group(2));
        }
        return parts;
    }

    /**
     * Склеивает части имени через точку так, чтобы результат можно было написать в блоке:
     * часть, которую нельзя написать без кавычек (строчные буквы, пробелы), берётся в кавычки.
     *
     * @param parts части имени, как их хранит словарь
     * @return имя для текста PL/SQL, например {@code APP."Emp"}
     */
    static String sqlName(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) {
                sb.append('.');
            }
            sb.append(part.matches("[A-Z][A-Z0-9_$#]*") ? part : "\"" + part.replace("\"", "\"\"") + "\"");
        }
        return sb.toString();
    }

    /**
     * Удаляет из текста PL/SQL комментарии: однострочные ({@code --} до конца строки) и
     * многострочные (<code>/* ... *&#47;</code>).
     *
     * <p>Комментарий заменяется пробелами той же длины (переводы строк сохраняются), так что
     * позиции в тексте не сдвигаются. Строковые литералы ({@code '...'}, {@code q'[...]'}) и имена
     * в двойных кавычках копируются как есть: {@code --} внутри них комментарием не считается.
     *
     * @param s исходный текст
     * @return текст без комментариев той же длины
     */
    static String stripComments(String s) {
        return scan(s, false)[0];
    }

    /**
     * Размечает исходный текст PL/SQL для поиска: возвращает две строки той же длины, что и
     * исходник, так что позиции в них совпадают символ в символ.
     *
     * <ul>
     *   <li>первая — комментарии ({@code --} до конца строки и <code>/* ... *&#47;</code>)
     *       заменены пробелами (переводы строк сохранены);</li>
     *   <li>вторая — то же, но и содержимое строковых литералов заменено пробелами (сами кавычки
     *       остаются). По ней ищутся ключевые слова и скобки.</li>
     * </ul>
     * Понимаются литералы {@code '...'} с удвоенной кавычкой внутри, литералы с другой кавычкой
     * {@code q'[...]'}, {@code q'{...}'}, {@code q'!...!'} (и национальные {@code nq'...'}) и имена в
     * двойных кавычках: внутри имени в кавычках ничего не распознаётся и регистр не меняется.
     *
     * @param s     исходный текст
     * @param upper перевести ли в верхний регистр всё, кроме имён в двойных кавычках (посимвольно,
     *              так что длина не меняется)
     * @return две строки той же длины, см. выше
     */
    static String[] scan(String s, boolean upper) {
        int n = s.length();
        char[] text = new char[n];
        char[] masked = new char[n];
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            int end;
            if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
                end = s.indexOf('\n', i);
                end = end < 0 ? n : end;
                blank(s, i, end, text, masked);
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                end = s.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                blank(s, i, end, text, masked);
            } else if (c == '"') {
                end = s.indexOf('"', i + 1);
                end = end < 0 ? n : end + 1;
                s.getChars(i, end, text, i);
                s.getChars(i, end, masked, i);
            } else if (isQQuoteAt(s, i)) {
                char open = s.charAt(i + 2);
                char close = switch (open) {
                    case '[' -> ']';
                    case '(' -> ')';
                    case '{' -> '}';
                    case '<' -> '>';
                    default -> open;
                };
                int closeAt = s.indexOf(close + "'", i + 3);
                end = closeAt < 0 ? n : closeAt + 2;
                literal(s, i, i + 3, closeAt < 0 ? n : closeAt, end, upper, text, masked);
            } else if (c == '\'') {
                int k = i + 1;
                int closeAt = -1;
                while (k < n) {
                    if (s.charAt(k) == '\'') {
                        if (k + 1 < n && s.charAt(k + 1) == '\'') {
                            k += 2;
                            continue;
                        }
                        closeAt = k;
                        break;
                    }
                    k++;
                }
                end = closeAt < 0 ? n : closeAt + 1;
                literal(s, i, i + 1, closeAt < 0 ? n : closeAt, end, upper, text, masked);
            } else {
                end = i + 1;
                text[i] = upper ? Character.toUpperCase(c) : c;
                masked[i] = text[i];
            }
            i = end;
        }
        return new String[]{new String(text), new String(masked)};
    }

    /**
     * Проверяет, начинается ли в позиции {@code i} литерал с другой кавычкой: {@code q'X...X'}
     * (в том числе национальный {@code nq'X...X'}, у которого {@code n} уже пройдена).
     *
     * @param s исходный текст
     * @param i позиция буквы {@code q}
     * @return {@code true}, если здесь начинается такой литерал
     */
    private static boolean isQQuoteAt(String s, int i) {
        char c = s.charAt(i);
        if ((c != 'q' && c != 'Q') || i + 2 >= s.length() || s.charAt(i + 1) != '\'') {
            return false;
        }
        if (i == 0 || !isIdentifierChar(s.charAt(i - 1))) {
            return true;
        }
        char prev = s.charAt(i - 1);
        return (prev == 'n' || prev == 'N') && (i == 1 || !isIdentifierChar(s.charAt(i - 2)));
    }

    /**
     * Заменяет комментарий пробелами в обеих размеченных строках, сохраняя переводы строк.
     *
     * @param s      исходный текст
     * @param from   начало комментария
     * @param to     позиция сразу после комментария
     * @param text   первая размеченная строка
     * @param masked вторая размеченная строка
     */
    private static void blank(String s, int from, int to, char[] text, char[] masked) {
        for (int k = from; k < to; k++) {
            char ch = s.charAt(k);
            text[k] = masked[k] = ch == '\n' || ch == '\r' ? ch : ' ';
        }
    }

    /**
     * Копирует строковый литерал в первую размеченную строку, а во вторую — только его кавычки,
     * заменяя содержимое пробелами.
     *
     * @param s            исходный текст
     * @param from         начало литерала (первая кавычка или буква {@code q})
     * @param contentStart первый символ содержимого
     * @param contentEnd   позиция сразу после содержимого
     * @param to           позиция сразу после литерала
     * @param upper        переводить ли в верхний регистр
     * @param text         первая размеченная строка
     * @param masked       вторая размеченная строка
     */
    private static void literal(String s, int from, int contentStart, int contentEnd, int to, boolean upper,
                                char[] text, char[] masked) {
        for (int k = from; k < to; k++) {
            char ch = s.charAt(k);
            text[k] = upper ? Character.toUpperCase(ch) : ch;
            boolean content = k >= contentStart && k < contentEnd;
            masked[k] = content ? (ch == '\n' || ch == '\r' ? ch : ' ') : text[k];
        }
    }

    /**
     * Собирает одну перегрузку из её строк {@code ALL_ARGUMENTS}.
     *
     * <p>Строки идут в порядке {@code SEQUENCE}; строка с {@code DATA_LEVEL} больше 0 описывает
     * часть ближайшей предыдущей строки уровнем выше (поле записи, тип элемента коллекции).
     * Дерево собирается через стек, в котором лежит путь от аргумента верхнего уровня до
     * последней строки. Строка уровня 0 с позицией 0 — возвращаемое значение функции. Пустая
     * строка (без имени и типа), которую словарь даёт процедуре без аргументов, пропускается.
     *
     * @param r    где лежит подпрограмма
     * @param rows строки одной перегрузки; список не пуст
     * @return описание перегрузки
     */
    private SubprogramInfo build(Resolved r, List<Row> rows) {
        ArgumentInfo ret = null;
        List<ArgumentInfo> top = new ArrayList<>();
        // Строки идут в порядке SEQUENCE; строки с DATA_LEVEL > 0 описывают предыдущую
        // строку уровнем выше (поля записи, тип элемента коллекции).
        Deque<ArgumentInfo> stack = new ArrayDeque<>();
        for (Row row : rows) {
            if (row.dataType == null && row.name == null) {
                continue; // у процедуры без аргументов одна пустая строка
            }
            ArgumentInfo a = new ArgumentInfo(row.name, row.position, row.level, row.dataType,
                    row.plsType, row.inOut, row.defaulted, row.typeOwner, row.typeName, row.typeSubname, null,
                    row.charLength);
            while (stack.size() > row.level) {
                stack.pop();
            }
            if (row.level == 0) {
                if (row.position == 0) {
                    ret = a;
                } else {
                    top.add(a);
                }
            } else if (!stack.isEmpty()) {
                stack.peek().children().add(a);
            }
            stack.push(a);
        }
        String overload = rows.get(0).overload;
        return new SubprogramInfo(r.owner(), r.packageName(), r.name(), overload, ret, top);
    }

    /**
     * Проверяет по {@code ALL_PROCEDURES}, существует ли подпрограмма, у которой нет строк в
     * {@code ALL_ARGUMENTS}, то есть процедура без аргументов.
     *
     * <p>В {@code ALL_PROCEDURES} у автономной подпрограммы её имя лежит в
     * {@code OBJECT_NAME}, а {@code PROCEDURE_NAME} пуст; у подпрограммы пакета в
     * {@code OBJECT_NAME} — имя пакета, а в {@code PROCEDURE_NAME} — имя подпрограммы. Строки с
     * пустым {@code PROCEDURE_NAME} есть и у самих пакетов, типов и триггеров, поэтому для
     * автономной подпрограммы дополнительно проверяется {@code OBJECT_TYPE}.
     *
     * @param con соединение, на котором читается словарь
     * @param r   где лежит подпрограмма
     * @return {@code true}, если такая подпрограмма есть
     * @throws SQLException при ошибке обращения к словарю
     */
    private boolean existsWithoutArguments(Connection con, Resolved r) throws SQLException {
        String sql = r.packageName() == null
                ? "select count(*) from all_procedures where owner = ? and object_name = ? and procedure_name is null"
                        + " and object_type in ('PROCEDURE', 'FUNCTION')"
                : "select count(*) from all_procedures where owner = ? and object_name = ? and procedure_name = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, r.owner());
            ps.setString(2, r.packageName() == null ? r.name() : r.packageName());
            if (r.packageName() != null) {
                ps.setString(3, r.name());
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /**
     * Склеивает непустые части имени через точку, пропуская {@code null} и пустые строки:
     * {@code (null, "PKG", "P")} даёт {@code PKG.P}.
     *
     * @param parts части имени: схема, пакет, подпрограмма
     * @return имя для {@code NAME_RESOLVE}
     */
    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append('.');
                }
                sb.append(p);
            }
        }
        return sb.toString();
    }

    /**
     * Одна строка {@code ALL_ARGUMENTS} в том виде, в каком её читает метод {@code rows}.
     *
     * @param overload    {@code OVERLOAD}: номер перегрузки или {@code null}
     * @param position    {@code POSITION}: 0 у возвращаемого значения, 1..n у аргументов
     * @param level       {@code DATA_LEVEL}: уровень вложенности, 0 у самого аргумента
     * @param name        {@code ARGUMENT_NAME}: имя аргумента или поля
     * @param dataType    {@code DATA_TYPE}: тип так, как его называет словарь
     * @param plsType     {@code PLS_TYPE}: тип PL/SQL, может быть {@code null}
     * @param inOut       {@code IN_OUT}: {@code IN}, {@code OUT} или {@code IN/OUT}
     * @param defaulted   {@code DEFAULTED = 'Y'}: у аргумента есть значение по умолчанию
     * @param typeOwner   {@code TYPE_OWNER}: схема пользовательского типа
     * @param typeName    {@code TYPE_NAME}: имя типа или пакета, где он объявлен
     * @param typeSubname {@code TYPE_SUBNAME}: имя типа внутри пакета
     * @param charLength  объявленная длина строки в символах или {@code null}
     */
    private record Row(String overload, int position, int level, String name, String dataType, String plsType,
                       String inOut, boolean defaulted, String typeOwner, String typeName, String typeSubname,
                       Integer charLength) {
    }
}
