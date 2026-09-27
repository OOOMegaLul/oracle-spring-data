package dev.plsql.spring.test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.plsql.spring.meta.ArgumentInfo;
import dev.plsql.spring.meta.SignatureSource;
import dev.plsql.spring.meta.SubprogramInfo;

/**
 * Signature fixtures for unit tests, shaped the way ALL_ARGUMENTS describes them.
 *
 * <pre>
 * Signatures.proc("PKG", "P_X").in("NTENANT", "NUMBER").out("NRN", "NUMBER").build()
 * </pre>
 */
public final class Signatures {

    public static final String OWNER = "APP";

    private final String pkg;
    private final String name;
    private final ArgumentInfo returnValue;
    private final List<ArgumentInfo> args = new ArrayList<>();
    private String overload;

    private Signatures(String pkg, String name, ArgumentInfo returnValue) {
        this.pkg = pkg;
        this.name = name;
        this.returnValue = returnValue;
    }

    public static Signatures proc(String pkg, String name) {
        return new Signatures(pkg, name, null);
    }

    public static Signatures func(String pkg, String name, String returnType) {
        return new Signatures(pkg, name, arg(null, returnType, "OUT"));
    }

    public static Signatures func(String pkg, String name, ArgumentInfo returnValue) {
        return new Signatures(pkg, name, returnValue);
    }

    public Signatures overload(String n) {
        this.overload = n;
        return this;
    }

    public Signatures in(String argName, String type) {
        return add(arg(argName, type, "IN"));
    }

    public Signatures inDefault(String argName, String type) {
        return add(new ArgumentInfo(argName, 0, 0, type, null, "IN", true, null, null, null, null));
    }

    public Signatures out(String argName, String type) {
        return add(arg(argName, type, "OUT"));
    }

    public Signatures inOut(String argName, String type) {
        return add(arg(argName, type, "IN/OUT"));
    }

    public Signatures add(ArgumentInfo a) {
        args.add(a);
        return this;
    }

    public SubprogramInfo build() {
        List<ArgumentInfo> positioned = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            ArgumentInfo a = args.get(i);
            positioned.add(new ArgumentInfo(a.name(), i + 1, a.dataLevel(), a.dataType(), a.plsType(), a.inOut(),
                    a.defaulted(), a.typeOwner(), a.typeName(), a.typeSubname(), a.children()));
        }
        return new SubprogramInfo(OWNER, pkg, name, overload, returnValue, positioned);
    }

    // ------------------------------------------------------------------ arguments

    public static ArgumentInfo arg(String name, String type, String inOut) {
        return new ArgumentInfo(name, 0, 0, type, null, inOut, false, null, null, null, null);
    }

    /** PL/SQL RECORD declared in a package: TYPE_OWNER.TYPE_NAME.TYPE_SUBNAME. */
    public static ArgumentInfo record(String name, String inOut, String pkg, String type, ArgumentInfo... fields) {
        return new ArgumentInfo(name, 0, 0, "PL/SQL RECORD", null, inOut, false, OWNER, pkg, type,
                children(inOut, fields));
    }

    /** %ROWTYPE as DictionaryReader leaves it: TYPE_NAME = OWNER.TABLE, no subname. */
    public static ArgumentInfo rowtype(String name, String inOut, String table, ArgumentInfo... fields) {
        return new ArgumentInfo(name, 0, 0, "PL/SQL RECORD", null, inOut, false, null, OWNER + "." + table, null,
                children(inOut, fields));
    }

    public static ArgumentInfo field(String name, String type) {
        return new ArgumentInfo(name, 0, 1, type, null, "IN", false, null, null, null, null);
    }

    public static ArgumentInfo indexTable(String name, String inOut, String elementType) {
        return new ArgumentInfo(name, 0, 0, "PL/SQL TABLE", null, inOut, false, OWNER, "PKG", "T_TAB",
                List.of(new ArgumentInfo(null, 1, 1, elementType, null, inOut, false, null, null, null, null)));
    }

    public static ArgumentInfo object(String name, String inOut, String type, ArgumentInfo... attrs) {
        return new ArgumentInfo(name, 0, 0, "OBJECT", null, inOut, false, OWNER, type, null, children(inOut, attrs));
    }

    public static ArgumentInfo xml(String name, String inOut) {
        return new ArgumentInfo(name, 0, 0, "OPAQUE/XMLTYPE", null, inOut, false, "PUBLIC", "XMLTYPE", null, null);
    }

    private static List<ArgumentInfo> children(String inOut, ArgumentInfo... fields) {
        return new ArrayList<>(Arrays.stream(fields).map(f -> new ArgumentInfo(f.name(), f.position(), 1, f.dataType(),
                f.plsType(), inOut, false, f.typeOwner(), f.typeName(), f.typeSubname(), f.children())).toList());
    }

    /** A source that knows only the given subprograms. */
    public static SignatureSource source(SubprogramInfo... known) {
        return (schema, pkg, name) -> Arrays.stream(known)
                .filter(s -> s.name().equalsIgnoreCase(name))
                .filter(s -> pkg == null ? s.packageName() == null : pkg.equalsIgnoreCase(s.packageName()))
                .toList();
    }
}
