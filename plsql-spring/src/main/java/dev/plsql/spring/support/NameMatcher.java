package dev.plsql.spring.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Matches Java names to PL/SQL names.
 *
 * <p>An exact match (ignoring case and underscores) wins. Otherwise the PL/SQL name may
 * carry a type prefix: {@code P_}, {@code V_}, {@code A_}, {@code I_}, {@code O_}, or the
 * one-letter type prefix ({@code NTENANT}, {@code SCODE}, {@code DBEGIN_DATE}).
 * Two candidates for one Java name is an error, never a silent guess.
 */
public final class NameMatcher {

    private static final String[] WORD_PREFIXES = {"P_", "V_", "A_", "I_", "O_", "IO_"};
    private static final String LETTER_PREFIXES = "NSDBFILRCTPX";

    private NameMatcher() {
    }

    public static String normalize(String s) {
        return s.replace("_", "").toUpperCase(Locale.ROOT);
    }

    /** Names the PL/SQL name can be matched by, strongest first. */
    static List<String> candidates(String plsqlName) {
        String upper = plsqlName.toUpperCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        out.add(normalize(upper));
        for (String p : WORD_PREFIXES) {
            if (upper.startsWith(p) && upper.length() > p.length()) {
                out.add(normalize(upper.substring(p.length())));
            }
        }
        if (upper.length() > 2 && LETTER_PREFIXES.indexOf(upper.charAt(0)) >= 0) {
            out.add(normalize(upper.substring(1)));
        }
        return out;
    }

    /**
     * Finds the element whose PL/SQL name matches the Java name.
     *
     * @return null when nothing matches
     * @throws IllegalStateException when two elements match equally well
     */
    public static <T> T find(String javaName, Collection<T> items, Function<T, String> plsqlName) {
        String j = normalize(javaName);
        for (int strength = 0; strength < 2; strength++) {
            List<T> hits = new ArrayList<>();
            for (T item : items) {
                String name = plsqlName.apply(item);
                if (name == null) {
                    continue;
                }
                List<String> c = candidates(name);
                boolean hit = strength == 0 ? c.get(0).equals(j) : c.subList(1, c.size()).contains(j);
                if (hit) {
                    hits.add(item);
                }
            }
            if (hits.size() == 1) {
                return hits.get(0);
            }
            if (hits.size() > 1) {
                throw new IllegalStateException("'" + javaName + "' matches several arguments: "
                        + hits.stream().map(plsqlName).toList() + "; name it with @Arg");
            }
        }
        return null;
    }
}
