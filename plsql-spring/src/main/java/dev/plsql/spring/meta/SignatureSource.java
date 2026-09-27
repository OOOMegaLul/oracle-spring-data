package dev.plsql.spring.meta;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where subprogram signatures come from. The default reads the data dictionary
 * ({@link DictionarySignatureSource}); tests and offline tools can supply fixtures.
 */
@FunctionalInterface
public interface SignatureSource {

    /**
     * All overloads of a procedure or function.
     *
     * @param schema      owner, or null for the connecting user's name resolution (synonyms work)
     * @param packageName package, or null for a standalone subprogram
     * @param name        subprogram name
     * @return overloads; empty when the subprogram does not exist
     */
    List<SubprogramInfo> find(String schema, String packageName, String name);

    /**
     * Several subprograms of one package (or several standalone ones) at once: name ->
     * overloads, one entry per requested name. A dictionary-backed source reads them in a
     * few queries instead of a few per name.
     */
    default Map<String, List<SubprogramInfo>> findAll(String schema, String packageName, Collection<String> names) {
        Map<String, List<SubprogramInfo>> out = new LinkedHashMap<>();
        for (String n : names) {
            out.put(n, find(schema, packageName, n));
        }
        return out;
    }
}
