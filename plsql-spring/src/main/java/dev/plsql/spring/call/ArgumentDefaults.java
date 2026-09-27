package dev.plsql.spring.call;

import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import dev.plsql.spring.meta.ArgumentInfo;
import dev.plsql.spring.meta.SubprogramInfo;

/**
 * Supplies arguments the Java method does not declare.
 *
 * <p>Legacy PL/SQL APIs often take the same context in nearly every call: a tenant, the
 * current user, a mode ({@code NTENANT}, {@code NMODE}). Declaring them on every method
 * is noise, so an application registers one bean that knows where they come from.
 */
@FunctionalInterface
public interface ArgumentDefaults {

    /** @return the value supplier, or null when this argument has no default */
    Supplier<Object> lookup(SubprogramInfo subprogram, ArgumentInfo argument);

    static ArgumentDefaults none() {
        return (s, a) -> null;
    }

    /** Defaults by argument name, any case: {@code Map.of("NTENANT", tenant::current)}. */
    static ArgumentDefaults byName(Map<String, Supplier<Object>> values) {
        Map<String, Supplier<Object>> upper = new java.util.HashMap<>();
        values.forEach((k, v) -> {
            if (upper.put(k.toUpperCase(Locale.ROOT), v) != null) {
                throw new IllegalArgumentException("argument " + k + " is given twice");
            }
        });
        return (s, a) -> a.name() == null ? null : upper.get(a.name().toUpperCase(Locale.ROOT));
    }
}
