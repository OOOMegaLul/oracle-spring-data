package dev.plsql.spring.call;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Struct;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.ResolvableType;

import dev.plsql.spring.meta.ArgKind;
import dev.plsql.spring.meta.ArgumentInfo;
import dev.plsql.spring.support.CharsetGuard;
import dev.plsql.spring.support.RowMappers;
import dev.plsql.spring.support.Values;
import oracle.jdbc.OracleCallableStatement;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.OracleTypes;

/**
 * Runs a {@link CallPlan} on a connection. Knows how each {@link ArgKind} is bound and
 * read with ojdbc, and frees every temporary LOB it creates: temporary LOBs that are
 * never freed stay in the session's temp tablespace until the connection closes, and a
 * pooled connection effectively never closes (measured: 50 calls, 50 LOBs left).
 */
public class CallExecutor {

    /** ORA-24338: the procedure left an IN OUT cursor unopened. */
    private static final int CURSOR_NOT_OPENED = 24338;

    private final int indexTableMaxLength;
    private final CharsetGuard charsetGuard;

    /**
     * @param indexTableMaxLength capacity reserved for an OUT index-by table
     * @param charsetGuard        checks text before it is sent
     */
    public CallExecutor(int indexTableMaxLength, CharsetGuard charsetGuard) {
        this.indexTableMaxLength = indexTableMaxLength;
        this.charsetGuard = charsetGuard;
    }

    public CallExecutor(int indexTableMaxLength) {
        this(indexTableMaxLength, CharsetGuard.none());
    }

    public Object execute(Connection con, CallPlan plan, Object[] args) throws SQLException {
        Object[] a = args == null ? new Object[0] : args;
        Map<String, Object> outs = new LinkedHashMap<>();
        List<Object> temporaries = new ArrayList<>();
        OracleConnection oc = con.unwrap(OracleConnection.class);
        Throwable failure = null;
        try (CallableStatement cs = con.prepareCall(plan.sql())) {
            List<CallPlan.Bind> binds = plan.binds();
            for (int i = 0; i < binds.size(); i++) {
                CallPlan.Bind b = binds.get(i);
                if (b.in() != null) {
                    bindIn(cs, oc, i + 1, b, b.in().apply(a), temporaries);
                }
                if (b.outKey() != null) {
                    registerOut(cs, i + 1, b);
                }
            }
            cs.execute();
            for (int i = 0; i < binds.size(); i++) {
                CallPlan.Bind b = binds.get(i);
                if (b.outKey() != null) {
                    outs.put(b.outKey(), readOut(cs, i + 1, b));
                }
            }
        } catch (SQLException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            free(temporaries, failure);
        }
        foldRecords(outs, plan.recordOuts());
        return plan.result().assemble(outs);
    }

    /**
     * Frees temporary LOBs. When the call already failed, a failure to free is attached to
     * that error instead of replacing it: the original error decides retries and what the
     * caller sees.
     */
    private static void free(List<Object> temporaries, Throwable failure) throws SQLException {
        SQLException first = null;
        for (Object t : temporaries) {
            try {
                if (t instanceof Clob c) {
                    c.free();
                } else if (t instanceof Blob bl) {
                    bl.free();
                }
            } catch (SQLException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        if (first != null) {
            if (failure != null) {
                failure.addSuppressed(first);
            } else {
                throw first;
            }
        }
    }

    // ------------------------------------------------------------------ IN

    private void bindIn(CallableStatement cs, OracleConnection oc, int idx, CallPlan.Bind b, Object v,
                        List<Object> temporaries) throws SQLException {
        switch (b.kind()) {
            case NUMBER -> {
                if (v == null) {
                    cs.setNull(idx, Types.NUMERIC);
                } else {
                    cs.setBigDecimal(idx, Values.toNumber(v));
                }
            }
            case BOOLEAN -> {
                Integer n = Values.toBooleanNumber(v);
                if (n == null) {
                    cs.setNull(idx, Types.NUMERIC);
                } else {
                    cs.setInt(idx, n);
                }
            }
            case STRING -> {
                if (v == null) {
                    cs.setNull(idx, Types.VARCHAR);
                } else {
                    String s = Values.toText(v);
                    guard(b.arg(), s);
                    cs.setString(idx, s);
                }
            }
            case DATE, TIMESTAMP -> {
                if (v == null) {
                    cs.setNull(idx, Types.TIMESTAMP);
                } else {
                    cs.setTimestamp(idx, Values.toTimestamp(v));
                }
            }
            case CLOB -> {
                if (v == null) {
                    cs.setNull(idx, Types.CLOB);
                } else {
                    String s = Values.toText(v);
                    guard(b.arg(), s);
                    Clob c = oc.createClob();
                    temporaries.add(c);
                    c.setString(1, s);
                    cs.setClob(idx, c);
                }
            }
            case BLOB -> {
                if (v == null) {
                    cs.setNull(idx, Types.BLOB);
                } else {
                    Blob bl = oc.createBlob();
                    temporaries.add(bl);
                    bl.setBytes(1, (byte[]) v);
                    cs.setBlob(idx, bl);
                }
            }
            case RAW -> {
                if (v == null) {
                    cs.setNull(idx, Types.VARBINARY);
                } else {
                    cs.setBytes(idx, (byte[]) v);
                }
            }
            case SQL_COLLECTION -> {
                if (v == null) {
                    cs.setNull(idx, Types.ARRAY, b.arg().sqlTypeName());
                } else {
                    cs.setArray(idx, toArray(oc, b.arg(), v));
                }
            }
            case OBJECT -> {
                if (v == null) {
                    cs.setNull(idx, Types.STRUCT, b.arg().sqlTypeName());
                } else {
                    cs.setObject(idx, toStruct(oc, b.arg(), v));
                }
            }
            case INDEX_TABLE -> {
                ArgumentInfo el = b.arg().children().get(0);
                Object[] values = elements(v, el, b.arg());
                OracleCallableStatement ocs = cs.unwrap(OracleCallableStatement.class);
                ocs.setPlsqlIndexTable(idx, values, Math.max(values.length, 1), values.length,
                        el.kind() == ArgKind.NUMBER ? OracleTypes.NUMBER : OracleTypes.VARCHAR,
                        el.kind() == ArgKind.NUMBER ? 0 : 4000);
            }
            default -> throw new IllegalStateException("cannot bind " + b.kind() + " as IN");
        }
    }

    private void guard(ArgumentInfo arg, String s) {
        String type = arg.dataType() == null ? "" : arg.dataType();
        if (!type.startsWith("N")) { // NVARCHAR2 / NCHAR / NCLOB use the national character set
            charsetGuard.check(arg.name() == null ? "value" : arg.name(), s);
        }
    }

    private Object[] elements(Object v, ArgumentInfo el, ArgumentInfo table) {
        Collection<?> c = asCollection(v);
        Object[] out = el.kind() == ArgKind.NUMBER ? new BigDecimal[c.size()] : new String[c.size()];
        int i = 0;
        for (Object o : c) {
            if (el.kind() == ArgKind.NUMBER) {
                out[i++] = Values.toNumber(o);
            } else {
                String s = o == null ? null : Values.toText(o);
                if (s != null) {
                    charsetGuard.check(table.name() + "[" + (i + 1) + "]", s);
                }
                out[i++] = s;
            }
        }
        return out;
    }

    private static Collection<?> asCollection(Object v) {
        if (v == null) {
            return List.of();
        }
        if (v instanceof Collection<?> col) {
            return col;
        }
        if (v instanceof Object[] arr) {
            return java.util.Arrays.asList(arr);
        }
        throw new IllegalArgumentException("expected a collection or an array, got " + v.getClass().getName());
    }

    private Array toArray(OracleConnection oc, ArgumentInfo coll, Object v) throws SQLException {
        ArgumentInfo el = coll.children().isEmpty() ? null : coll.children().get(0);
        Collection<?> c = asCollection(v);
        Object[] out = new Object[c.size()];
        int i = 0;
        for (Object o : c) {
            out[i++] = el == null ? o : toSqlValue(oc, el, o);
        }
        return oc.createOracleArray(coll.sqlTypeName(), out);
    }

    private Struct toStruct(OracleConnection oc, ArgumentInfo obj, Object v) throws SQLException {
        Object[] attrs = new Object[obj.children().size()];
        for (int i = 0; i < attrs.length; i++) {
            ArgumentInfo f = obj.children().get(i);
            attrs[i] = toSqlValue(oc, f, Values.property(v, f.name()));
        }
        return oc.createStruct(obj.sqlTypeName(), attrs);
    }

    private Object toSqlValue(OracleConnection oc, ArgumentInfo t, Object v) throws SQLException {
        if (v == null) {
            return null;
        }
        return switch (t.kind()) {
            case NUMBER -> Values.toNumber(v);
            case DATE, TIMESTAMP -> Values.toTimestamp(v);
            case STRING -> {
                String s = Values.toText(v);
                guard(t, s);
                yield s;
            }
            case OBJECT -> toStruct(oc, t, v);
            case SQL_COLLECTION -> toArray(oc, t, v);
            default -> v;
        };
    }

    // ------------------------------------------------------------------ OUT

    private void registerOut(CallableStatement cs, int idx, CallPlan.Bind b) throws SQLException {
        switch (b.kind()) {
            case NUMBER, BOOLEAN -> cs.registerOutParameter(idx, Types.NUMERIC);
            case STRING -> cs.registerOutParameter(idx, Types.VARCHAR);
            case DATE, TIMESTAMP -> cs.registerOutParameter(idx, Types.TIMESTAMP);
            case CLOB, XMLTYPE -> cs.registerOutParameter(idx, Types.CLOB);
            case BLOB -> cs.registerOutParameter(idx, Types.BLOB);
            case RAW -> cs.registerOutParameter(idx, Types.VARBINARY);
            case REF_CURSOR -> cs.registerOutParameter(idx, OracleTypes.CURSOR);
            case SQL_COLLECTION -> cs.registerOutParameter(idx, Types.ARRAY, b.arg().sqlTypeName());
            case OBJECT -> cs.registerOutParameter(idx, Types.STRUCT, b.arg().sqlTypeName());
            case INDEX_TABLE -> {
                ArgumentInfo el = b.arg().children().get(0);
                cs.unwrap(OracleCallableStatement.class).registerIndexTableOutParameter(idx, indexTableMaxLength,
                        el.kind() == ArgKind.NUMBER ? OracleTypes.NUMBER : OracleTypes.VARCHAR,
                        el.kind() == ArgKind.NUMBER ? 0 : 4000);
            }
            default -> throw new IllegalStateException("cannot read " + b.kind() + " as OUT");
        }
    }

    private Object readOut(CallableStatement cs, int idx, CallPlan.Bind b) throws SQLException {
        return switch (b.kind()) {
            case NUMBER -> cs.getBigDecimal(idx);
            case BOOLEAN -> {
                BigDecimal n = cs.getBigDecimal(idx);
                yield n == null ? null : n.intValue() != 0;
            }
            case STRING -> cs.getString(idx);
            case DATE, TIMESTAMP -> cs.getTimestamp(idx);
            case CLOB, XMLTYPE -> {
                Clob c = cs.getClob(idx);
                yield c == null ? null : Values.clobToString(c);
            }
            case BLOB -> {
                Blob bl = cs.getBlob(idx);
                if (bl == null) {
                    yield null;
                }
                byte[] bytes = bl.getBytes(1, (int) bl.length());
                bl.free();
                yield bytes;
            }
            case RAW -> cs.getBytes(idx);
            case REF_CURSOR -> readCursor(cs, idx, b.outType());
            case SQL_COLLECTION -> {
                Array a = cs.getArray(idx);
                yield a == null ? null : fromArray(a, b.arg());
            }
            case OBJECT -> {
                Object o = cs.getObject(idx);
                yield o == null ? null : fromStruct((Struct) o, b.arg());
            }
            case INDEX_TABLE -> List.of((Object[]) cs.unwrap(OracleCallableStatement.class).getPlsqlIndexTable(idx));
            default -> throw new IllegalStateException("cannot read " + b.kind());
        };
    }

    private static List<Object> readCursor(CallableStatement cs, int idx, ResolvableType target) throws SQLException {
        ResultSet rs;
        try {
            rs = (ResultSet) cs.getObject(idx);
        } catch (SQLException e) {
            if (e.getErrorCode() == CURSOR_NOT_OPENED) {
                return null;
            }
            throw e;
        }
        if (rs == null) {
            return null;
        }
        try (rs) {
            return RowMappers.mapAll(rs, elementType(target));
        }
    }

    private List<Object> fromArray(Array a, ArgumentInfo coll) throws SQLException {
        ArgumentInfo el = coll.children().isEmpty() ? null : coll.children().get(0);
        Object[] raw = (Object[]) a.getArray();
        List<Object> out = new ArrayList<>(raw.length);
        for (Object o : raw) {
            out.add(o instanceof Struct s && el != null ? fromStruct(s, el) : o);
        }
        a.free();
        return out;
    }

    private Map<String, Object> fromStruct(Struct s, ArgumentInfo obj) throws SQLException {
        Object[] attrs = s.getAttributes();
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < attrs.length && i < obj.children().size(); i++) {
            ArgumentInfo f = obj.children().get(i);
            Object v = attrs[i];
            if (v instanceof Struct inner) {
                v = fromStruct(inner, f);
            } else if (v instanceof Array arr) {
                v = fromArray(arr, f);
            }
            m.put(f.name(), v);
        }
        return m;
    }

    private static ResolvableType elementType(ResolvableType t) {
        if (t == null) {
            return ResolvableType.forClass(Map.class);
        }
        Class<?> raw = t.resolve(Object.class);
        if (Collection.class.isAssignableFrom(raw)) {
            return t.asCollection().getGeneric(0);
        }
        if (raw == java.util.Optional.class) {
            return elementType(t.getGeneric(0));
        }
        return ResolvableType.forClass(Map.class);
    }

    /** {@code ARG.FIELD} outputs become one map under {@code ARG}. */
    static void foldRecords(Map<String, Object> outs, List<String> records) {
        for (String rec : records) {
            Map<String, Object> m = new LinkedHashMap<>();
            String prefix = rec + ".";
            outs.entrySet().removeIf(e -> {
                if (e.getKey().startsWith(prefix)) {
                    m.put(e.getKey().substring(prefix.length()), e.getValue());
                    return true;
                }
                return false;
            });
            outs.put(rec, m);
        }
    }
}
