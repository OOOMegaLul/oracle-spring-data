package dev.plsql.spring.support;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.Locale;
import java.util.Map;

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
 * <p>Кодировку базы задаёт её параметр NLS_CHARACTERSET. Проверяются только однобайтовые
 * кодировки из встроенного списка: для каждой есть кодировка Java с тем же набором символов, и
 * проверка сводится к {@link CharsetEncoder#canEncode(char)}. Для Unicode-баз (AL32UTF8 и т. п.)
 * и кодировок вне списка проверка не выполняется.
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

    /**
     * Кодировки Oracle, которые теряют символы, и кодировка Java с тем же набором символов
     * (репертуаром).
     */
    private static final Map<String, String> SINGLE_BYTE = Map.of(
            "CL8MSWIN1251", "windows-1251",
            "CL8ISO8859P5", "ISO-8859-5",
            "CL8KOI8R", "KOI8-R",
            "WE8MSWIN1252", "windows-1252",
            "WE8ISO8859P1", "ISO-8859-1",
            "WE8ISO8859P15", "ISO-8859-15",
            "EE8MSWIN1250", "windows-1250",
            "US7ASCII", "US-ASCII");

    /** Выключенная проверка, один экземпляр на всё приложение. */
    private static final CharsetGuard NONE = new CharsetGuard(null, null, Policy.IGNORE);

    /** Имя кодировки базы в терминах Oracle, для текста ошибки. */
    private final String oracleCharset;
    /** Кодировка Java с тем же набором символов; {@code null} — проверка выключена. */
    private final Charset charset;
    /**
     * Политика, с которой создан объект. В {@link #check} не читается: объект с кодировкой
     * создаётся только при {@link Policy#FAIL}.
     */
    private final Policy policy;
    /** Кодировщик на каждый поток: {@link CharsetEncoder} нельзя использовать из нескольких потоков. */
    private final ThreadLocal<CharsetEncoder> encoder;

    /**
     * Создаёт проверку. Снаружи используются {@link #none()} и {@link #forDatabase}.
     *
     * @param oracleCharset имя кодировки Oracle для сообщений; {@code null} у выключенной проверки
     * @param charset       кодировка Java; {@code null} выключает проверку
     * @param policy        политика
     */
    private CharsetGuard(String oracleCharset, Charset charset, Policy policy) {
        this.oracleCharset = oracleCharset;
        this.charset = charset;
        this.policy = policy;
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
     * {@link Policy#IGNORE}, кодировки нет во встроенном списке однобайтовых или в JVM нет
     * соответствующей кодировки Java. Имя кодировки сравнивается без учёта регистра.
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
        String javaName = SINGLE_BYTE.get(oracleCharset.toUpperCase(Locale.ROOT));
        if (javaName == null || !Charset.isSupported(javaName)) {
            return NONE;
        }
        return new CharsetGuard(oracleCharset, Charset.forName(javaName), policy);
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
     * эмодзи, занимает в Java два {@code char} — суррогатную пару; такой символ сразу считается
     * непредставимым.
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
            if (Character.isHighSurrogate(c)) {
                return i; // за пределами BMP в однобайтовой кодировке ничего нет
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
