package dev.plsql.spring.support;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Не даёт отправить в базу текст, который она не сможет сохранить.
 *
 * <p>Однобайтовая база (например, CL8MSWIN1251) молча заменяет каждый символ вне своей кодовой
 * страницы на {@code '?'}: казахская «Ә» или эмодзи теряются вообще без ошибки (измерено на
 * 11.2.0.4). Кодовая страница — набор не более чем из 256 символов, которые однобайтовая кодировка
 * умеет хранить; у CL8MSWIN1251 это ASCII, кириллица и несколько типографских знаков. С
 * {@link Policy#FAIL} такое значение отвергается, а в сообщении называются имя аргумента и
 * позиция первого непредставимого символа.
 *
 * <p>Кодировку базы задаёт её параметр NLS_CHARACTERSET. Проверяются кодировки из встроенного
 * списка — однобайтовые (кириллица, латиница, греческий, иврит, арабский, балтийские, турецкий,
 * вьетнамский, тайский) и многобайтовые восточноазиатские (GBK, Big5, Shift_JIS, EUC-JP,
 * EUC-KR): для каждой есть кодировка Java с тем же набором символов, и проверка сводится к
 * {@link CharsetEncoder#canEncode}. Unicode-базам (AL32UTF8, UTF8, AL16UTF16) проверка не
 * нужна. Про кодировку, которой нет в списке, в лог пишется предупреждение: текст для неё не
 * проверяется.
 *
 * <p>Объект можно использовать из нескольких потоков: у каждого потока свой
 * {@link CharsetEncoder}, потому что кодировщик Java хранит внутреннее состояние.
 */
public final class CharsetGuard {

    /**
     * Что делать с текстом, который кодировка базы не может сохранить.
     */
    public enum Policy {
        /** Бросать {@link UnrepresentableCharacterException}, не отправляя текст в базу. */
        FAIL,
        /** Отправлять как есть и дать базе заменить символ на {@code '?'}, как делает обычный JDBC. */
        IGNORE
    }

    private static final Logger log = LoggerFactory.getLogger(CharsetGuard.class);

    /**
     * Кодировки Oracle, которые теряют символы, и кодировка Java с тем же набором символов
     * (репертуаром).
     */
    private static final Map<String, String> KNOWN = Map.ofEntries(
            Map.entry("US7ASCII", "US-ASCII"),
            Map.entry("CL8MSWIN1251", "windows-1251"),
            Map.entry("CL8ISO8859P5", "ISO-8859-5"),
            Map.entry("CL8KOI8R", "KOI8-R"),
            Map.entry("CL8KOI8U", "KOI8-U"),
            Map.entry("RU8PC866", "IBM866"),
            Map.entry("WE8MSWIN1252", "windows-1252"),
            Map.entry("WE8ISO8859P1", "ISO-8859-1"),
            Map.entry("WE8ISO8859P15", "ISO-8859-15"),
            Map.entry("WE8PC850", "IBM850"),
            Map.entry("US8PC437", "IBM437"),
            Map.entry("EE8MSWIN1250", "windows-1250"),
            Map.entry("EE8ISO8859P2", "ISO-8859-2"),
            Map.entry("SE8ISO8859P3", "ISO-8859-3"),
            Map.entry("NEE8ISO8859P4", "ISO-8859-4"),
            Map.entry("EL8MSWIN1253", "windows-1253"),
            Map.entry("EL8ISO8859P7", "ISO-8859-7"),
            Map.entry("TR8MSWIN1254", "windows-1254"),
            Map.entry("WE8ISO8859P9", "ISO-8859-9"),
            Map.entry("IW8MSWIN1255", "windows-1255"),
            Map.entry("IW8ISO8859P8", "ISO-8859-8"),
            Map.entry("AR8MSWIN1256", "windows-1256"),
            Map.entry("AR8ISO8859P6", "ISO-8859-6"),
            Map.entry("BLT8MSWIN1257", "windows-1257"),
            Map.entry("BLT8ISO8859P13", "ISO-8859-13"),
            Map.entry("VN8MSWIN1258", "windows-1258"),
            Map.entry("TH8TISASCII", "TIS-620"),
            Map.entry("ZHS16GBK", "GBK"),
            Map.entry("ZHS32GB18030", "GB18030"),
            Map.entry("ZHT16BIG5", "Big5"),
            Map.entry("ZHT16MSWIN950", "x-windows-950"),
            Map.entry("ZHT16HKSCS", "Big5-HKSCS"),
            Map.entry("JA16SJIS", "Shift_JIS"),
            Map.entry("JA16EUC", "EUC-JP"),
            Map.entry("KO16MSWIN949", "x-windows-949"),
            Map.entry("KO16KSC5601", "EUC-KR"));

    /** Кодировки Oracle, в которых хранится любой символ Unicode: им проверка не нужна. */
    private static final Set<String> UNICODE = Set.of("AL32UTF8", "UTF8", "AL16UTF16", "UTFE");

    /** Выключенная проверка, один экземпляр на всё приложение. */
    private static final CharsetGuard NONE = new CharsetGuard(null, null);

    /** Имя кодировки базы в терминах Oracle, для текста ошибки. */
    private final String oracleCharset;
    /** Кодировка Java с тем же набором символов; {@code null} — проверка выключена. */
    private final Charset charset;
    /** Кодировщик на каждый поток: {@link CharsetEncoder} нельзя использовать из нескольких потоков. */
    private final ThreadLocal<CharsetEncoder> encoder;

    /**
     * Создаёт проверку. Снаружи используются {@link #none()} и {@link #forDatabase}.
     *
     * <p>Политики здесь нет: включённая проверка всегда бросает исключение ({@link Policy#FAIL}),
     * а {@link Policy#IGNORE} даёт выключенную.
     *
     * @param oracleCharset имя кодировки Oracle для сообщений; {@code null} у выключенной проверки
     * @param charset       кодировка Java; {@code null} выключает проверку
     */
    private CharsetGuard(String oracleCharset, Charset charset) {
        this.oracleCharset = oracleCharset;
        this.charset = charset;
        this.encoder = charset == null ? null : ThreadLocal.withInitial(charset::newEncoder);
    }

    /**
     * Возвращает проверку, которая ничего не проверяет.
     *
     * @return общий выключенный экземпляр
     */
    public static CharsetGuard none() {
        return NONE;
    }

    /**
     * Возвращает проверку для базы с указанной кодировкой.
     *
     * <p>Возвращает выключенную проверку ({@link #none()}), если кодировка не задана, политика —
     * {@link Policy#IGNORE}, база в Unicode, кодировки нет во встроенном списке или в JVM нет
     * соответствующей кодировки Java. В двух последних случаях в лог пишется предупреждение:
     * проверку ждали, а её нет. Имя кодировки сравнивается без учёта регистра.
     *
     * @param oracleCharset NLS_CHARACTERSET базы, например {@code CL8MSWIN1251}; Unicode-кодировкам
     *                      проверка не нужна
     * @param policy        политика
     * @return проверка для этой кодировки или выключенная проверка
     */
    public static CharsetGuard forDatabase(String oracleCharset, Policy policy) {
        if (oracleCharset == null || policy == Policy.IGNORE) {
            return NONE;
        }
        String name = oracleCharset.toUpperCase(Locale.ROOT);
        if (UNICODE.contains(name)) {
            return NONE;
        }
        String javaName = KNOWN.get(name);
        if (javaName == null || !Charset.isSupported(javaName)) {
            log.warn("database character set {} is not known to the charset check; text sent to it is not checked"
                    + " and characters outside it will be stored as '?'", oracleCharset);
            return NONE;
        }
        return new CharsetGuard(oracleCharset, Charset.forName(javaName));
    }

    /**
     * Сообщает, включена ли проверка.
     *
     * @return {@code true}, если текст действительно проверяется
     */
    public boolean isActive() {
        return charset != null;
    }

    /**
     * Проверяет, что текст можно сохранить в кодировке базы.
     *
     * <p>Ничего не делает, если проверка выключена или текст равен {@code null}. Позиция символа в
     * сообщении считается с единицы.
     *
     * @param what имя аргумента для сообщения об ошибке
     * @param text проверяемый текст
     * @throws UnrepresentableCharacterException если текст нельзя сохранить; в сообщении — символ,
     *                                           его код Unicode, позиция и кодировка базы
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

    /**
     * Ищет первый символ, которого нет в кодировке базы.
     *
     * <p>Символы ASCII ({@code c < 0x80}) пропускаются без проверки: они есть во всех кодировках
     * списка. Символ за пределами BMP (основной плоскости Unicode, кодов до U+FFFF), например
     * эмодзи, занимает в Java два {@code char} — суррогатную пару и проверяется парой целиком:
     * в однобайтовых кодировках его нет, а, например, GB18030 его хранит. Одиночная половина
     * пары непредставима.
     *
     * @param s проверяемый текст
     * @return индекс первого непредставимого символа (с нуля) или {@code -1}, если все символы
     *         допустимы
     */
    private int firstUnrepresentable(CharSequence s) {
        CharsetEncoder e = encoder.get();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                continue;
            }
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                // Символ вне BMP (эмодзи и т.п.): проверяется парой; в однобайтовых кодировках его нет.
                if (!e.canEncode(s.subSequence(i, i + 2))) {
                    return i;
                }
                i++;
                continue;
            }
            if (!e.canEncode(c)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Бросается при политике {@link Policy#FAIL}, когда текст содержит символ, которого нет в
     * кодировке базы.
     *
     * <p>Наследует {@link IllegalArgumentException}: это ошибка входных данных, и возникает она до
     * отправки в базу, поэтому в исключения Spring для ошибок базы не переводится.
     */
    public static class UnrepresentableCharacterException extends IllegalArgumentException {
        /**
         * Создаёт исключение с готовым текстом.
         *
         * @param message текст с именем аргумента, символом, его кодом, позицией и кодировкой базы
         */
        public UnrepresentableCharacterException(String message) {
            super(message);
        }
    }
}
