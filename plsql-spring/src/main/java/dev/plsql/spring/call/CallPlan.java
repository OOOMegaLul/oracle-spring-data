package dev.plsql.spring.call;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.core.ResolvableType;

import dev.plsql.spring.meta.ArgKind;
import dev.plsql.spring.meta.ArgumentInfo;
import dev.plsql.spring.meta.SubprogramInfo;

/**
 * Everything needed to call one method: the generated block and its binds.
 * Built once at startup, reused for every call.
 *
 * @param sql        anonymous PL/SQL block with positional {@code ?} placeholders
 * @param binds      one entry per placeholder, in order
 * @param recordOuts OUT record arguments whose fields come back as separate binds
 *                   ({@code ARG.FIELD}) and are folded back into one map
 * @param result     how the outputs become the method's return value
 */
public record CallPlan(
        SubprogramInfo target,
        String sql,
        List<Bind> binds,
        List<String> recordOuts,
        Result result) {

    /**
     * One placeholder.
     *
     * @param in      where the IN value comes from; null for pure OUT
     * @param outKey  name the OUT value is stored under; null for pure IN
     * @param outType Java type the OUT value is going to; drives REF CURSOR row mapping
     * @param kind    how to bind; for a record field this is the field's kind
     * @param arg     the argument (or record field) this placeholder carries
     */
    public record Bind(
            ArgKind kind,
            ArgumentInfo arg,
            Function<Object[], Object> in,
            String outKey,
            ResolvableType outType) {
    }

    /**
     * @param returnKey  the output that becomes the return value, or null
     * @param outsToType when non-null, all outputs are folded into this record/bean/map
     */
    public record Result(ResolvableType returnType, String returnKey, boolean outsToType) {

        public Object assemble(Map<String, Object> outs) {
            Class<?> raw = returnType.resolve(Object.class);
            if (raw == void.class || raw == Void.class) {
                return null;
            }
            if (outsToType) {
                return dev.plsql.spring.support.Values.convert(outs, returnType);
            }
            return dev.plsql.spring.support.Values.convert(outs.get(returnKey), returnType);
        }
    }
}
