package dev.plsql.spring.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Сопоставляет имена Java с именами PL/SQL.
 *
 * <p>Точное совпадение (без учёта регистра и подчёркиваний) побеждает. Иначе имя PL/SQL может
 * нести префикс типа: {@code P_}, {@code V_}, {@code A_}, {@code I_}, {@code O_}, {@code IO_}
 * или однобуквенный префикс типа ({@code NTENANT}, {@code SCODE}, {@code DBEGIN_DATE}).
 * Две кандидатуры для одного имени Java — ошибка, а не молчаливое угадывание.
 *
 * <p>Такие префиксы — распространённое соглашение об именах в PL/SQL: например, {@code P_} —
 * параметр, {@code V_} — переменная, а буквы {@code N}, {@code S}, {@code D} в начале имени —
 * число, строка, дата. Поэтому параметр Java {@code tenant} находит аргумент {@code NTENANT}, а
 * {@code beginDate} — {@code DBEGIN_DATE}.
 */
public final class NameMatcher {

    /** Префиксы-слова, которые снимаются с имени PL/SQL (имя к этому моменту в верхнем регистре). */
    private static final String[] WORD_PREFIXES = {"P_", "V_", "A_", "I_", "O_", "IO_"};
    /** Буквы, которые считаются однобуквенным префиксом типа, если начинают имя длиннее двух символов. */
    private static final String LETTER_PREFIXES = "NSDBFILRCTPX";

    /** Закрытый конструктор: класс содержит только статические методы. */
    private NameMatcher() {
    }

    /**
     * Приводит имя к виду для сравнения: убирает подчёркивания и переводит в верхний регистр.
     *
     * <p>{@code beginDate} и {@code BEGIN_DATE} дают одно и то же {@code BEGINDATE}.
     *
     * @param s имя Java или PL/SQL
     * @return нормализованное имя
     */
    public static String normalize(String s) {
        return s.replace("_", "").toUpperCase(Locale.ROOT);
    }

    /**
     * Возвращает варианты, по которым имя PL/SQL может совпасть с именем Java, от самого сильного.
     *
     * <p>Первый элемент — само имя после {@link #normalize}. Дальше идут имя без префикса-слова
     * ({@code P_ID} → {@code ID}) и имя без первой буквы, если это буква типа, а имя длиннее двух
     * символов ({@code NTENANT} → {@code TENANT}). Варианты могут повторяться.
     *
     * @param plsqlName имя в PL/SQL: аргумент, поле записи, колонка
     * @return непустой список нормализованных вариантов; первый — само имя
     */
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
     * Находит элемент, чьё имя PL/SQL совпадает с именем Java.
     *
     * <p>Поиск идёт в два прохода: сначала точные совпадения, затем совпадения после снятия
     * префикса. Если на проходе найден ровно один элемент, он и возвращается; если несколько —
     * это ошибка, и до следующего прохода дело не доходит. Элементы без имени ({@code null},
     * например возвращаемое значение функции) пропускаются.
     *
     * @param javaName  имя параметра, компонента записи или свойства Java
     * @param items     элементы, среди которых ищется совпадение
     * @param plsqlName как получить имя PL/SQL у элемента
     * @param <T>       тип элемента
     * @return найденный элемент или {@code null}, если ничего не совпало
     * @throws IllegalStateException если два элемента совпадают одинаково хорошо; сообщение
     *                               советует назвать аргумент явно через {@code @Arg}
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
