package dev.plsql.spring.support;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.Locale;
import java.util.Map;

/**
 * Stops text the database cannot store before it is sent.
 *
 * <p>A single-byte database (CL8MSWIN1251, for example) silently replaces every character
 * outside its code page with '?': a Kazakh 'Ә' or an emoji is lost with no error at all
 * (measured on 11.2.0.4). With {@link Policy#FAIL} such a value is rejected with the
 * argument name and the position of the first bad character instead.
 */
public final class CharsetGuard {

    public enum Policy {
        /** Throw {@link UnrepresentableCharacterException}. */
        FAIL,
        /** Let the database replace the character, as plain JDBC does. */
        IGNORE
    }

    /** Oracle character sets that lose characters, and the Java charset with the same repertoire. */
    private static final Map<String, String> SINGLE_BYTE = Map.of(
            "CL8MSWIN1251", "windows-1251",
            "CL8ISO8859P5", "ISO-8859-5",
            "CL8KOI8R", "KOI8-R",
            "WE8MSWIN1252", "windows-1252",
            "WE8ISO8859P1", "ISO-8859-1",
            "WE8ISO8859P15", "ISO-8859-15",
            "EE8MSWIN1250", "windows-1250",
            "US7ASCII", "US-ASCII");

    private static final CharsetGuard NONE = new CharsetGuard(null, null, Policy.IGNORE);

    private final String oracleCharset;
    private final Charset charset;
    private final Policy policy;
    private final ThreadLocal<CharsetEncoder> encoder;

    private CharsetGuard(String oracleCharset, Charset charset, Policy policy) {
        this.oracleCharset = oracleCharset;
        this.charset = charset;
        this.policy = policy;
        this.encoder = charset == null ? null : ThreadLocal.withInitial(charset::newEncoder);
    }

    /** Checks nothing. */
    public static CharsetGuard none() {
        return NONE;
    }

    /**
     * @param oracleCharset NLS_CHARACTERSET of the database; Unicode sets need no guard
     */
    public static CharsetGuard forDatabase(String oracleCharset, Policy policy) {
        if (oracleCharset == null || policy == Policy.IGNORE) {
            return NONE;
        }
        String javaName = SINGLE_BYTE.get(oracleCharset.toUpperCase(Locale.ROOT));
        if (javaName == null || !Charset.isSupported(javaName)) {
            return NONE;
        }
        return new CharsetGuard(oracleCharset, Charset.forName(javaName), policy);
    }

    public boolean isActive() {
        return charset != null;
    }

    /**
     * @param what argument name for the message
     * @throws UnrepresentableCharacterException when the text cannot be stored
     */
    public void check(String what, CharSequence text) {
        if (charset == null || text == null) {
            return;
        }
        int bad = firstUnrepresentable(text);
        if (bad >= 0) {
            int cp = Character.codePointAt(text, bad);
            throw new UnrepresentableCharacterException(String.format(
                    "%s: character '%s' (U+%04X) at position %d cannot be stored in a %s database",
                    what, new String(Character.toChars(cp)), cp, bad + 1, oracleCharset));
        }
    }

    private int firstUnrepresentable(CharSequence s) {
        CharsetEncoder e = encoder.get();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                continue;
            }
            if (Character.isHighSurrogate(c)) {
                return i; // nothing outside the BMP exists in a single-byte set
            }
            if (!e.canEncode(c)) {
                return i;
            }
        }
        return -1;
    }

    /** Thrown by {@link Policy#FAIL}. */
    public static class UnrepresentableCharacterException extends IllegalArgumentException {
        public UnrepresentableCharacterException(String message) {
            super(message);
        }
    }
}
