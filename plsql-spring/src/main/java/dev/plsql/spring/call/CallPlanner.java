package dev.plsql.spring.call;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.BeanUtils;
import org.springframework.core.ResolvableType;
import org.springframework.util.ClassUtils;

import dev.plsql.spring.annotation.Arg;
import dev.plsql.spring.annotation.Procedure;
import dev.plsql.spring.meta.ArgKind;
import dev.plsql.spring.meta.ArgumentInfo;
import dev.plsql.spring.meta.SubprogramInfo;
import dev.plsql.spring.support.NameMatcher;
import dev.plsql.spring.support.Values;

/**
 * Turns a Java method plus a subprogram signature into a {@link CallPlan}.
 *
 * <p>The call is always an anonymous block with named notation:
 * <pre>
 * DECLARE
 *   v1 BOOLEAN;                               -- PL/SQL-only types live here
 * BEGIN
 *   v1 := CASE ? WHEN 1 THEN TRUE WHEN 0 THEN FALSE END;
 *   ? := APP.PKG.F(NTENANT => ?, BFLAG => v1);
 *   ? := CASE WHEN v1 THEN 1 WHEN NOT v1 THEN 0 END;
 * END;
 * </pre>
 * Named notation makes the call independent of argument order and lets defaulted
 * arguments be left out. Local variables are how 11.2 reaches BOOLEAN, RECORD and
 * XMLTYPE arguments, which JDBC cannot bind on that version.
 */
public class CallPlanner {

    /** Output key of a function's return value. */
    public static final String RETURN_KEY = "$return";

    private final ArgumentDefaults defaults;

    public CallPlanner(ArgumentDefaults defaults) {
        this.defaults = defaults;
    }

    /** Thrown when the method and the signature do not fit; the message says why. */
    public static class PlanException extends RuntimeException {
        public PlanException(String message) {
            super(message);
        }
    }

    /** Picks the overload that fits the method's parameters. */
    public CallPlan plan(Method method, List<SubprogramInfo> overloads) {
        if (overloads.isEmpty()) {
            throw new PlanException("not found in the database");
        }
        List<String> reasons = new ArrayList<>();
        List<CallPlan> fits = new ArrayList<>();
        for (SubprogramInfo sp : overloads) {
            try {
                fits.add(plan(method, sp));
            } catch (PlanException e) {
                reasons.add((sp.overload() != null ? "overload " + sp.overload() + ": " : "") + e.getMessage());
            }
        }
        if (fits.size() > 1) {
            // Same argument names, different types (OVER(NUMBER) / OVER(VARCHAR2)):
            // keep the overloads whose argument types accept the Java parameter types.
            List<CallPlan> typed = fits.stream().filter(p -> typesFit(method, p)).toList();
            if (!typed.isEmpty()) {
                fits = typed;
            }
        }
        if (fits.size() == 1) {
            return fits.get(0);
        }
        if (fits.isEmpty()) {
            throw new PlanException(String.join("; ", reasons));
        }
        throw new PlanException("matches " + fits.size() + " overloads equally: "
                + fits.stream().map(p -> p.target().toString()).toList() + "; name parameters with @Arg");
    }

    public CallPlan plan(Method method, SubprogramInfo sp) {
        // Shapes that cannot be called at all are the real reason; report them before
        // anything about the Java side.
        List<String> unsupported = supportIssues(sp);
        if (!unsupported.isEmpty()) {
            throw new PlanException("not callable: " + String.join("; ", unsupported));
        }
        Map<ArgumentInfo, Function<Object[], Object>> supplied = matchParameters(method, sp);
        Procedure procAnn = method.getAnnotation(Procedure.class);
        boolean nullForMissing = procAnn != null && procAnn.nullForMissing();

        ResolvableType returnType = ResolvableType.forMethodReturnType(method);
        Class<?> rawReturn = returnType.resolve(Object.class);
        boolean voidReturn = rawReturn == void.class || rawReturn == Void.class;

        // Which outputs exist, and what Java type each one is going to.
        List<ArgumentInfo> outs = sp.arguments().stream().filter(ArgumentInfo::isOut).toList();
        Map<String, ResolvableType> outTypes = new LinkedHashMap<>();
        boolean outsToType = false;
        String returnKey = null;
        if (sp.isFunction()) {
            returnKey = RETURN_KEY;
            outTypes.put(returnKey, returnType);
        } else if (!voidReturn) {
            if (outs.isEmpty()) {
                throw new PlanException("returns " + rawReturn.getSimpleName() + " but the procedure has no OUT arguments");
            }
            if (outs.size() == 1 && !isMultiValueHolder(rawReturn, outs)) {
                returnKey = outs.get(0).name();
                outTypes.put(returnKey, returnType);
            } else {
                outsToType = true;
                for (ArgumentInfo o : outs) {
                    outTypes.put(o.name(), componentType(rawReturn, o.name()));
                }
                List<String> unmatched = unmatchedComponents(rawReturn, outs);
                if (!unmatched.isEmpty()) {
                    throw new PlanException(rawReturn.getSimpleName() + " components " + unmatched
                            + " match no OUT argument and would always be empty; OUT arguments are "
                            + outs.stream().map(ArgumentInfo::name).toList() + "; name them with @Arg");
                }
            }
        }

        Block b = new Block();
        List<String> callArgs = new ArrayList<>();

        for (ArgumentInfo a : sp.arguments()) {
            Function<Object[], Object> in = null;
            if (a.isIn()) {
                in = supplied.get(a);
                if (in == null) {
                    Supplier<Object> d = defaults.lookup(sp, a);
                    if (d != null) {
                        in = args -> d.get();
                    } else if (a.isOut()) {
                        in = args -> null;
                    } else if (a.defaulted()) {
                        continue; // leave it to the PL/SQL default
                    } else if (nullForMissing) {
                        in = args -> null;
                    } else {
                        throw new PlanException("required argument " + a.name() + " (" + a.dataType()
                                + ") is not supplied; add a parameter or an ArgumentDefaults entry");
                    }
                }
            }
            String outKey = a.isOut() ? a.name() : null;
            ResolvableType outType = outKey == null ? null : outTypes.getOrDefault(outKey, ResolvableType.forClass(Object.class));
            callArgs.add(a.name() + " => " + argument(b, a, in, outKey, outType));
        }

        String call = sp.qualifiedName() + (callArgs.isEmpty() ? "" : "(" + String.join(", ", callArgs) + ")");
        String callStmt;
        if (sp.isFunction()) {
            ArgumentInfo r = sp.returnValue();
            ResolvableType rt = outTypes.get(RETURN_KEY);
            if (bindsDirectly(r)) {
                checkIndexTable(r);
                b.returnBind(new CallPlan.Bind(r.kind(), r, null, RETURN_KEY, rt));
                callStmt = "? := " + call + ";";
            } else {
                String v = b.variable(localType(r));
                callStmt = v + " := " + call + ";";
                readBack(b, v, r, RETURN_KEY, rt);
            }
        } else {
            callStmt = call + ";";
        }

        String sql = b.render(callStmt);
        return new CallPlan(sp, sql, b.binds(), b.recordOuts(), new CallPlan.Result(returnType, returnKey, outsToType));
    }

    // ------------------------------------------------------------------ arguments

    /** Emits whatever one argument needs and returns the expression passed in the call. */
    private String argument(Block b, ArgumentInfo a, Function<Object[], Object> in, String outKey, ResolvableType outType) {
        ArgKind kind = a.kind();
        if (bindsDirectly(a)) {
            checkIndexTable(a);
            return b.inline(new CallPlan.Bind(kind, a, in, outKey, outType));
        }
        switch (kind) {
            case BOOLEAN -> {
                if (!a.isOut()) {
                    return "(CASE " + b.inline(new CallPlan.Bind(ArgKind.BOOLEAN, a, in, null, null))
                            + " WHEN 1 THEN TRUE WHEN 0 THEN FALSE END)";
                }
            }
            case REF_CURSOR -> {
                if (!a.isOut()) {
                    throw new PlanException("argument " + a.name() + " is an IN REF CURSOR; a cursor cannot be sent from Java");
                }
            }
            case XMLTYPE, RECORD -> {
            }
            default -> throw new PlanException("argument " + a.name() + " has unsupported type " + a.dataType());
        }
        String v = b.variable(localType(a));
        if (in != null && kind != ArgKind.REF_CURSOR) {
            writeInto(b, v, a, in);
        }
        if (outKey != null) {
            readBack(b, v, a, outKey, outType);
        }
        return v;
    }

    /** Kinds JDBC binds straight into the call; everything else goes through a variable. */
    private static boolean bindsDirectly(ArgumentInfo a) {
        ArgKind k = a.kind();
        return k.isScalar() || k == ArgKind.SQL_COLLECTION || k == ArgKind.OBJECT || k == ArgKind.INDEX_TABLE
                || (k == ArgKind.REF_CURSOR && !a.isIn());
    }

    /** Java value -> PL/SQL variable (or record field) {@code target}. */
    private void writeInto(Block b, String target, ArgumentInfo a, Function<Object[], Object> in) {
        switch (a.kind()) {
            case BOOLEAN -> b.pre(target + " := CASE ", new CallPlan.Bind(ArgKind.BOOLEAN, a, in, null, null),
                    " WHEN 1 THEN TRUE WHEN 0 THEN FALSE END;");
            case XMLTYPE -> {
                // XMLTYPE(NULL) raises ORA-06502 on 11.2, so NULL stays NULL explicitly.
                String c = b.variable("CLOB");
                b.pre(c + " := ", new CallPlan.Bind(ArgKind.CLOB, a, in, null, null),
                        "; IF " + c + " IS NOT NULL THEN " + target + " := XMLTYPE(" + c + "); END IF;");
            }
            case RECORD -> {
                for (ArgumentInfo f : a.children()) {
                    checkField(a, f);
                    String name = f.name();
                    writeInto(b, target + "." + name, f, args -> Values.property(in.apply(args), name));
                }
            }
            default -> b.pre(target + " := ", new CallPlan.Bind(a.kind(), a, in, null, null), ";");
        }
    }

    /** PL/SQL variable (or record field) {@code source} -> output {@code key}. */
    private void readBack(Block b, String source, ArgumentInfo a, String key, ResolvableType type) {
        switch (a.kind()) {
            case BOOLEAN -> b.post(new CallPlan.Bind(ArgKind.BOOLEAN, a, null, key, type),
                    " := CASE WHEN " + source + " THEN 1 WHEN NOT " + source + " THEN 0 END;");
            case XMLTYPE -> b.post(new CallPlan.Bind(ArgKind.XMLTYPE, a, null, key, type),
                    " := CASE WHEN " + source + " IS NULL THEN NULL ELSE " + source + ".getClobVal() END;");
            case RECORD -> {
                for (ArgumentInfo f : a.children()) {
                    checkField(a, f);
                    readBack(b, source + "." + f.name(), f, key + "." + f.name(), null);
                }
                b.recordOut(key);
            }
            default -> b.post(new CallPlan.Bind(a.kind(), a, null, key, type), " := " + source + ";");
        }
    }

    private static String localType(ArgumentInfo a) {
        return switch (a.kind()) {
            case BOOLEAN -> "BOOLEAN";
            case XMLTYPE -> "XMLTYPE";
            case REF_CURSOR -> "SYS_REFCURSOR";
            case RECORD -> recordType(a);
            default -> throw new PlanException("return type " + a.dataType() + " is not supported");
        };
    }

    private static String recordType(ArgumentInfo a) {
        String t = a.declaredType();
        if (t == null) {
            throw new PlanException("record " + (a.name() == null ? "return value" : a.name())
                    + " has no type name in ALL_ARGUMENTS or ALL_SOURCE");
        }
        // %ROWTYPE arguments point TYPE_NAME at the table and leave TYPE_SUBNAME empty.
        return a.typeSubname() == null ? t + "%ROWTYPE" : t;
    }

    private static boolean fieldSupported(ArgumentInfo f) {
        return f.kind().isScalar() || f.kind() == ArgKind.BOOLEAN || f.kind() == ArgKind.XMLTYPE;
    }

    private static void checkField(ArgumentInfo rec, ArgumentInfo f) {
        if (!fieldSupported(f)) {
            throw new PlanException("record " + rec.name() + " field " + f.name() + " is " + f.dataType()
                    + "; only scalar, BOOLEAN and XMLTYPE fields are supported");
        }
    }

    private static void checkIndexTable(ArgumentInfo a) {
        if (a.kind() == ArgKind.INDEX_TABLE) {
            ArgumentInfo el = a.children().isEmpty() ? null : a.children().get(0);
            if (el == null || (el.kind() != ArgKind.NUMBER && el.kind() != ArgKind.STRING)) {
                throw new PlanException("index-by table " + (a.name() == null ? "return value" : a.name()) + " of " + (el == null ? "?" : el.dataType())
                        + " cannot be bound on 11.2; only tables of NUMBER or VARCHAR2 can");
            }
        }
    }

    /**
     * Why a subprogram cannot be called by this planner, independent of any Java method.
     * Empty means every argument shape is supported.
     */
    public static List<String> supportIssues(SubprogramInfo sp) {
        List<String> issues = new ArrayList<>();
        List<ArgumentInfo> all = new ArrayList<>(sp.arguments());
        if (sp.returnValue() != null) {
            all.add(sp.returnValue());
        }
        for (ArgumentInfo a : all) {
            String who = a.name() == null ? "RETURN" : a.name();
            switch (a.kind()) {
                case RECORD -> {
                    if (a.declaredType() == null) {
                        issues.add(who + ": record type unknown");
                    }
                    for (ArgumentInfo f : a.children()) {
                        if (!fieldSupported(f)) {
                            issues.add(who + ": record field " + f.name() + " is " + f.dataType());
                        }
                    }
                }
                case INDEX_TABLE -> {
                    ArgumentInfo el = a.children().isEmpty() ? null : a.children().get(0);
                    if (el == null || (el.kind() != ArgKind.NUMBER && el.kind() != ArgKind.STRING)) {
                        issues.add(who + ": index-by table of " + (el == null ? "?" : el.dataType()));
                    }
                }
                case UNSUPPORTED -> issues.add(who + ": type " + a.dataType());
                case REF_CURSOR -> {
                    if (a.isIn() && !a.isOut()) {
                        issues.add(who + ": IN REF CURSOR");
                    }
                }
                default -> {
                }
            }
        }
        return issues;
    }

    // ------------------------------------------------------------------ Java side

    private Map<ArgumentInfo, Function<Object[], Object>> matchParameters(Method method, SubprogramInfo sp) {
        Parameter[] params = method.getParameters();
        Map<ArgumentInfo, Function<Object[], Object>> supplied = new LinkedHashMap<>();
        for (int i = 0; i < params.length; i++) {
            Parameter p = params[i];
            Arg ann = p.getAnnotation(Arg.class);
            final int k = i;
            ArgumentInfo a = ann != null ? byName(sp, ann.value()) : find(p.getName(), sp);
            if (a == null && ann == null && isParameterObject(p.getType())) {
                // A record or bean whose properties are the arguments, like a form object.
                for (PropertyName prop : propertyNames(p.getType())) {
                    ArgumentInfo pa = prop.explicit() != null ? byName(sp, prop.explicit()) : find(prop.name(), sp);
                    if (pa == null) {
                        throw new PlanException("property '" + prop.name() + "' of " + p.getType().getSimpleName()
                                + " has no matching argument; arguments are " + names(sp.arguments()));
                    }
                    String argName = pa.name();
                    put(supplied, pa, args -> Values.property(args[k], argName),
                            "property '" + prop.name() + "' of " + p.getType().getSimpleName());
                }
                continue;
            }
            if (a == null) {
                throw new PlanException("parameter '" + (ann != null ? ann.value() : p.getName())
                        + "' has no matching argument; arguments are " + names(sp.arguments())
                        + (p.getName().matches("arg\\d+") ? " (compile with -parameters)" : ""));
            }
            put(supplied, a, args -> args[k], "parameter '" + p.getName() + "'");
        }
        return supplied;
    }

    private static void put(Map<ArgumentInfo, Function<Object[], Object>> supplied, ArgumentInfo a,
                            Function<Object[], Object> source, String label) {
        if (!a.isIn()) {
            // An OUT-only argument takes nothing in: the caller's value would silently vanish.
            throw new PlanException(label + " maps to OUT argument " + a.name()
                    + ", which takes no value; OUT values come back through the return type");
        }
        if (supplied.putIfAbsent(a, source) != null) {
            throw new PlanException("two parameters map to " + a.name());
        }
    }

    private static ArgumentInfo byName(SubprogramInfo sp, String name) {
        return sp.arguments().stream().filter(x -> name.equalsIgnoreCase(x.name())).findFirst().orElse(null);
    }

    private static ArgumentInfo find(String javaName, SubprogramInfo sp) {
        try {
            return NameMatcher.find(javaName, sp.arguments(), ArgumentInfo::name);
        } catch (IllegalStateException e) {
            throw new PlanException(e.getMessage());
        }
    }

    private static boolean typesFit(Method m, CallPlan plan) {
        Parameter[] params = m.getParameters();
        for (int i = 0; i < params.length; i++) {
            Object[] probe = new Object[params.length];
            probe[i] = MARKER;
            for (CallPlan.Bind b : plan.binds()) {
                if (b.in() != null && b.arg().dataLevel() == 0 && safeApply(b, probe) == MARKER
                        && !accepts(b.arg().kind(), params[i].getType())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static final Object MARKER = new Object();

    private static Object safeApply(CallPlan.Bind b, Object[] probe) {
        try {
            return b.in().apply(probe);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Whether a Java type can travel as this kind of PL/SQL argument. */
    static boolean accepts(ArgKind kind, Class<?> t) {
        Class<?> c = ClassUtils.resolvePrimitiveIfNecessary(t);
        return switch (kind) {
            case NUMBER -> Number.class.isAssignableFrom(c);
            case STRING, CLOB -> CharSequence.class.isAssignableFrom(c) || c.isEnum() || c == Character.class;
            case XMLTYPE -> CharSequence.class.isAssignableFrom(c) || org.w3c.dom.Node.class.isAssignableFrom(c);
            case DATE, TIMESTAMP -> java.time.temporal.Temporal.class.isAssignableFrom(c) || java.util.Date.class.isAssignableFrom(c);
            case BOOLEAN -> c == Boolean.class;
            case BLOB, RAW -> c == byte[].class;
            case SQL_COLLECTION, INDEX_TABLE -> Collection.class.isAssignableFrom(c) || c.isArray();
            case RECORD, OBJECT -> !BeanUtils.isSimpleValueType(c);
            default -> true;
        };
    }

    private static boolean isParameterObject(Class<?> t) {
        return !BeanUtils.isSimpleValueType(t) && !t.isArray() && !t.isPrimitive()
                && !Collection.class.isAssignableFrom(t) && !Map.class.isAssignableFrom(t)
                && !org.w3c.dom.Node.class.isAssignableFrom(t);
    }

    private record PropertyName(String name, String explicit) {
    }

    private static List<PropertyName> propertyNames(Class<?> t) {
        if (t.isRecord()) {
            return Arrays.stream(t.getRecordComponents()).map(rc -> {
                Arg a = rc.getAnnotation(Arg.class);
                return new PropertyName(rc.getName(), a == null ? null : a.value());
            }).toList();
        }
        return Arrays.stream(BeanUtils.getPropertyDescriptors(t))
                .filter(pd -> pd.getReadMethod() != null && !pd.getName().equals("class"))
                .map(PropertyDescriptor::getName).map(n -> new PropertyName(n, null)).toList();
    }

    private static boolean isMultiValueHolder(Class<?> type, List<ArgumentInfo> outs) {
        // A single OUT RECORD maps straight onto the return type; a Java record whose
        // component matches the OUT argument's name is treated as a holder instead.
        if (!type.isRecord()) {
            return false;
        }
        RecordComponent[] rc = type.getRecordComponents();
        return rc.length > 0 && NameMatcher.find(rc[0].getName(), outs, ArgumentInfo::name) != null
                && outs.get(0).kind() != ArgKind.RECORD;
    }

    /** Components of a result record that no OUT argument fills. */
    private static List<String> unmatchedComponents(Class<?> holder, List<ArgumentInfo> outs) {
        if (!holder.isRecord()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (RecordComponent rc : holder.getRecordComponents()) {
            Arg a = rc.getAnnotation(Arg.class);
            boolean matched = a != null
                    ? outs.stream().anyMatch(o -> a.value().equalsIgnoreCase(o.name()))
                    : NameMatcher.find(rc.getName(), outs, ArgumentInfo::name) != null;
            if (!matched) {
                out.add(rc.getName());
            }
        }
        return out;
    }

    private static ResolvableType componentType(Class<?> holder, String outName) {
        if (holder.isRecord()) {
            for (RecordComponent rc : holder.getRecordComponents()) {
                Arg a = rc.getAnnotation(Arg.class);
                if (a != null ? a.value().equalsIgnoreCase(outName)
                        : NameMatcher.find(rc.getName(), List.of(outName), x -> x) != null) {
                    return ResolvableType.forType(rc.getGenericType());
                }
            }
        }
        return ResolvableType.forClass(Object.class);
    }

    private static List<String> names(List<ArgumentInfo> args) {
        return args.stream().map(a -> a.name() + " " + a.inOut() + (a.defaulted() ? " DEFAULT" : "")).toList();
    }

    /** The block being built: declarations, statements before and after the call, binds in text order. */
    private static final class Block {
        private final List<String> declarations = new ArrayList<>();
        private final List<Statement> before = new ArrayList<>();
        private final List<CallPlan.Bind> inCall = new ArrayList<>();
        private CallPlan.Bind returnBind;
        private final List<Statement> after = new ArrayList<>();
        private final List<String> recordOuts = new ArrayList<>();
        private final List<CallPlan.Bind> ordered = new ArrayList<>();
        private int vars;

        private record Statement(String head, CallPlan.Bind bind, String tail) {
        }

        String variable(String type) {
            String name = "v" + (++vars);
            declarations.add(name + " " + type + ";");
            return name;
        }

        String inline(CallPlan.Bind bind) {
            inCall.add(bind);
            return "?";
        }

        void returnBind(CallPlan.Bind bind) {
            returnBind = bind;
        }

        void pre(String head, CallPlan.Bind bind, String tail) {
            before.add(new Statement(head, bind, tail));
        }

        /** {@code ? := <expr>;} after the call. */
        void post(CallPlan.Bind bind, String tail) {
            after.add(new Statement("", bind, tail));
        }

        void recordOut(String key) {
            recordOuts.add(key);
        }

        String render(String callStmt) {
            StringBuilder sb = new StringBuilder();
            if (!declarations.isEmpty()) {
                sb.append("DECLARE\n");
                declarations.forEach(d -> sb.append("  ").append(d).append('\n'));
            }
            sb.append("BEGIN\n");
            append(sb, before);
            if (returnBind != null) {
                ordered.add(returnBind);
            }
            ordered.addAll(inCall);
            sb.append("  ").append(callStmt).append('\n');
            append(sb, after);
            sb.append("END;");
            return sb.toString();
        }

        private void append(StringBuilder sb, List<Statement> statements) {
            for (Statement s : statements) {
                sb.append("  ").append(s.head()).append('?').append(s.tail()).append('\n');
                ordered.add(s.bind());
            }
        }

        List<CallPlan.Bind> binds() {
            return ordered;
        }

        List<String> recordOuts() {
            return recordOuts;
        }
    }
}
