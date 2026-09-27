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
 * Reads procedure signatures from the data dictionary.
 *
 * <p>Names are resolved with DBMS_UTILITY.NAME_RESOLVE, the same routine the PL/SQL
 * compiler uses, so private and public synonyms work exactly as they do in a
 * hand-written call.
 */
public class DictionaryReader {

    private static final String COLUMNS = """
            select object_name, overload, position, sequence, data_level, argument_name, data_type,
                   pls_type, in_out, defaulted, type_owner, type_name, type_subname
              from all_arguments
            """;

    private static final String ARGS_SQL = COLUMNS + """
             where owner = ?
               and object_name = ?
               and nvl(package_name, '~') = nvl(?, '~')
             order by overload nulls first, sequence""";

    private static final String PACKAGE_SQL = COLUMNS + """
             where owner = ? and package_name = ?
             order by object_name, overload nulls first, sequence""";

    /** Oracle's limit on an IN list. */
    private static final int IN_LIST = 900;

    /** Result of NAME_RESOLVE: where the code really lives. */
    public record Resolved(String owner, String packageName, String name) {
    }

    /** ALL_ARGUMENTS has no rows for code that does not compile, so "absent" and "broken" need telling apart. */
    public static class InvalidObjectException extends IllegalStateException {
        public InvalidObjectException(String owner, String name) {
            super(owner + "." + name + " is INVALID in the database (it does not compile); recompile it");
        }
    }

    /** Resolves a package name alone (synonyms included). */
    public Resolved resolvePackage(Connection con, String schema, String packageName) throws SQLException {
        Resolved r = resolve(con, schema, null, packageName);
        return new Resolved(r.owner(), r.packageName() != null ? r.packageName() : r.name(), null);
    }

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
            int part1Type = cs.getInt(6);
            boolean isPackage = part1Type == 9;
            return new Resolved(owner, isPackage ? part1 : null, part2 != null ? part2 : part1);
        }
    }

    /**
     * All overloads of a subprogram. Empty list: the name does not exist.
     *
     * @throws InvalidObjectException when it exists but does not compile
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
     * Every subprogram of a package in one ALL_ARGUMENTS query: name -> overloads.
     *
     * @throws InvalidObjectException when the specification does not compile
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
     * Standalone subprograms of {@code owner} among {@code names}, a few queries for all of
     * them: name -> overloads. Names not returned do not exist in {@code owner} with arguments.
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

    /** ALL_OBJECTS.STATUS of a package, procedure or function; null when there is none. */
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

    /** Status of a package body; null when there is none. */
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

    /** object_name -> overload -> rows, in query order. */
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
                        rs.getString("type_subname"));
                out.computeIfAbsent(rs.getString("object_name"), k -> new LinkedHashMap<>())
                        .computeIfAbsent(String.valueOf(row.overload), k -> new ArrayList<>()).add(row);
            }
        }
        return out;
    }

    private List<SubprogramInfo> complete(Connection con, Resolved r, Map<String, List<Row>> byOverload) throws SQLException {
        List<SubprogramInfo> result = new ArrayList<>();
        for (List<Row> rows : byOverload.values()) {
            SubprogramInfo sp = fillRowtypes(con, build(r, rows));
            result.add(fillSqlTypes(con, sp));
        }
        return result;
    }

    // ------------------------------------------------------------ SQL object types

    /**
     * ALL_ARGUMENTS describes PL/SQL records field by field but stops at SQL object types:
     * an OBJECT argument has no attribute rows at all. Attributes come from ALL_TYPE_ATTRS,
     * collection elements from ALL_COLL_TYPES.
     */
    private SubprogramInfo fillSqlTypes(Connection con, SubprogramInfo sp) throws SQLException {
        List<ArgumentInfo> args = new ArrayList<>();
        for (ArgumentInfo a : sp.arguments()) {
            args.add(completeSqlType(con, a, 0));
        }
        ArgumentInfo ret = sp.returnValue() == null ? null : completeSqlType(con, sp.returnValue(), 0);
        return new SubprogramInfo(sp.owner(), sp.packageName(), sp.name(), sp.overload(), ret, args);
    }

    private ArgumentInfo completeSqlType(Connection con, ArgumentInfo a, int depth) throws SQLException {
        if (depth > 5) {
            return a;
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
                a.defaulted(), a.typeOwner(), a.typeName(), a.typeSubname(), completed);
    }

    private List<ArgumentInfo> typeAttributes(Connection con, String owner, String type, String inOut) throws SQLException {
        String sql = """
                select a.attr_name, a.attr_type_owner, a.attr_type_name, t.typecode, c.coll_type
                  from all_type_attrs a
                  left join all_types t on t.owner = a.attr_type_owner and t.type_name = a.attr_type_name
                  left join all_coll_types c on c.owner = a.attr_type_owner and c.type_name = a.attr_type_name
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

    private ArgumentInfo collectionElement(Connection con, String owner, String type, String inOut) throws SQLException {
        String sql = """
                select c.elem_type_owner, c.elem_type_name, t.typecode, cc.coll_type
                  from all_coll_types c
                  left join all_types t on t.owner = c.elem_type_owner and t.type_name = c.elem_type_name
                  left join all_coll_types cc on cc.owner = c.elem_type_owner and cc.type_name = c.elem_type_name
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

    private static ArgumentInfo sqlTypeArg(String name, String typeOwner, String typeName, String typecode,
                                           String collType, String inOut) {
        String dataType;
        if ("OBJECT".equals(typecode)) {
            dataType = "OBJECT";
        } else if ("COLLECTION".equals(typecode)) {
            dataType = "VARYING ARRAY".equals(collType) ? "VARRAY" : "TABLE";
        } else {
            // Built-in: NUMBER, VARCHAR2, DATE, TIMESTAMP(6), ...
            dataType = typeName.replaceAll("\\(.*\\)", "");
            typeOwner = null;
            typeName = null;
        }
        return new ArgumentInfo(name, 0, 1, dataType, null, inOut, false, typeOwner, typeName, null, null);
    }

    /**
     * On 11.2 ALL_ARGUMENTS lists the fields of a {@code %ROWTYPE} argument but leaves
     * TYPE_OWNER / TYPE_NAME empty, so the only place the table name survives is the
     * declaration itself. Read it from ALL_SOURCE.
     */
    private SubprogramInfo fillRowtypes(Connection con, SubprogramInfo sp) throws SQLException {
        boolean needed = sp.arguments().stream().anyMatch(DictionaryReader::isAnonymousRecord)
                || (sp.returnValue() != null && isAnonymousRecord(sp.returnValue()));
        if (!needed) {
            return sp;
        }
        String decl = declarationText(con, sp);
        List<ArgumentInfo> args = new ArrayList<>();
        for (ArgumentInfo a : sp.arguments()) {
            args.add(isAnonymousRecord(a) ? withRowtype(a, rowtypeOf(decl, a.name(), sp.owner())) : a);
        }
        ArgumentInfo ret = sp.returnValue();
        if (ret != null && isAnonymousRecord(ret)) {
            ret = withRowtype(ret, returnRowtypeOf(decl, sp.owner()));
        }
        return new SubprogramInfo(sp.owner(), sp.packageName(), sp.name(), sp.overload(), ret, args);
    }

    private static boolean isAnonymousRecord(ArgumentInfo a) {
        return "PL/SQL RECORD".equals(a.dataType()) && a.typeName() == null;
    }

    private static ArgumentInfo withRowtype(ArgumentInfo a, String rowtypeOf) {
        if (rowtypeOf == null) {
            return a;
        }
        // typeSubname stays null: CallPlanner declares the variable as <typeName>%ROWTYPE.
        return new ArgumentInfo(a.name(), a.position(), a.dataLevel(), a.dataType(), a.plsType(), a.inOut(),
                a.defaulted(), null, rowtypeOf, null, a.children());
    }

    /** Text of this subprogram's declaration (up to the first ';' or IS/AS), upper-cased. */
    private String declarationText(Connection con, SubprogramInfo sp) throws SQLException {
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
        return findDeclaration(src.toString(), sp.name(), sp.overload());
    }

    /**
     * The declaration of {@code name} in PL/SQL source: from PROCEDURE/FUNCTION to the
     * ';' or IS/AS that ends its header, comments removed, upper-cased. Overloads are
     * numbered in declaration order, as ALL_ARGUMENTS.OVERLOAD numbers them.
     */
    static String findDeclaration(String source, String name, String overload) {
        String text = stripComments(source).toUpperCase(Locale.ROOT);
        Matcher m = Pattern.compile("\\b(PROCEDURE|FUNCTION)\\s+\"?" + Pattern.quote(name.toUpperCase(Locale.ROOT))
                + "\"?(?![A-Z0-9_$#])").matcher(text);
        int wanted = overload == null ? 1 : Integer.parseInt(overload);
        int seen = 0;
        while (m.find()) {
            if (++seen == wanted) {
                return text.substring(m.start(), headerEnd(text, m.end()));
            }
        }
        return "";
    }

    private static int headerEnd(String text, int from) {
        int depth = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && (c == ';' || isKeywordAt(text, i, "IS") || isKeywordAt(text, i, "AS"))) {
                return i;
            }
        }
        return text.length();
    }

    private static boolean isKeywordAt(String text, int i, String kw) {
        if (!text.startsWith(kw, i) || i == 0 || !Character.isWhitespace(text.charAt(i - 1))) {
            return false;
        }
        int after = i + kw.length();
        return after >= text.length() || !isIdentifierChar(text.charAt(after));
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    /** {@code P_ROW OUT NOCOPY EMP%ROWTYPE} -> {@code OWNER.EMP}. */
    static String rowtypeOf(String decl, String argName, String owner) {
        Matcher m = Pattern.compile("(?<![A-Z0-9_$#])" + Pattern.quote(argName)
                + "\\s+(?:IN\\s+OUT\\s+|IN\\s+|OUT\\s+)?(?:NOCOPY\\s+)?([A-Z0-9_$#.\"]+)%ROWTYPE").matcher(decl);
        return m.find() ? qualify(m.group(1), owner) : null;
    }

    static String returnRowtypeOf(String decl, String owner) {
        Matcher m = Pattern.compile("\\bRETURN\\s+([A-Z0-9_$#.\"]+)%ROWTYPE").matcher(decl);
        return m.find() ? qualify(m.group(1), owner) : null;
    }

    private static String qualify(String name, String owner) {
        return name.contains(".") ? name : owner + "." + name;
    }

    /** Removes comments; string literals are kept (a "--" inside quotes is not a comment). */
    static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'') {
                int end = s.indexOf('\'', i + 1);
                while (end >= 0 && end + 1 < s.length() && s.charAt(end + 1) == '\'') {
                    end = s.indexOf('\'', end + 2);
                }
                end = end < 0 ? s.length() : end + 1;
                out.append(s, i, end);
                i = end;
            } else if (s.startsWith("--", i)) {
                int nl = s.indexOf('\n', i);
                i = nl < 0 ? s.length() : nl;
                out.append(' ');
            } else if (s.startsWith("/*", i)) {
                int close = s.indexOf("*/", i + 2);
                i = close < 0 ? s.length() : close + 2;
                out.append(' ');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private SubprogramInfo build(Resolved r, List<Row> rows) {
        ArgumentInfo ret = null;
        List<ArgumentInfo> top = new ArrayList<>();
        // Rows come in SEQUENCE order; DATA_LEVEL > 0 rows describe the previous
        // row one level up (record fields, collection element type).
        Deque<ArgumentInfo> stack = new ArrayDeque<>();
        for (Row row : rows) {
            if (row.dataType == null && row.name == null) {
                continue; // a procedure without arguments has one empty row
            }
            ArgumentInfo a = new ArgumentInfo(row.name, row.position, row.level, row.dataType,
                    row.plsType, row.inOut, row.defaulted, row.typeOwner, row.typeName, row.typeSubname, null);
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

    private boolean existsWithoutArguments(Connection con, Resolved r) throws SQLException {
        String sql = r.packageName() == null
                ? "select count(*) from all_procedures where owner = ? and object_name = ? and procedure_name is null"
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

    private record Row(String overload, int position, int level, String name, String dataType, String plsType,
                       String inOut, boolean defaulted, String typeOwner, String typeName, String typeSubname) {
    }
}
