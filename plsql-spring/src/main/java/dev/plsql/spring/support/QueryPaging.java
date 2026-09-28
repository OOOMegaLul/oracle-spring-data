package dev.plsql.spring.support;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.PageableExecutionUtils;

/**
 * Постраничные и отсортированные запросы {@code @SqlQuery}: параметры {@link Pageable} и
 * {@link Sort}, результат {@link Page}, {@link Slice} или {@code List} — как в Spring Data.
 *
 * <p>Oracle 11g не знает {@code OFFSET ... FETCH}, поэтому страница вырезается классическим для
 * него способом, через {@code ROWNUM}:
 *
 * <pre>
 * SELECT * FROM (
 *   SELECT q_.*, ROWNUM PLSQL_RN_ FROM (&lt;запрос&gt; ORDER BY ...
 *   ) q_ WHERE ROWNUM &lt;= ?
 * ) WHERE PLSQL_RN_ &gt; ?
 * </pre>
 *
 * <p>Служебная колонка {@code PLSQL_RN_} (номер строки) в результат не попадает. Для
 * {@code Page} общее число строк считает отдельный запрос {@code SELECT COUNT(*) FROM (<запрос>)},
 * и только когда без него не обойтись (как в Spring Data: на последней странице оно известно и
 * так). {@code Slice} вместо подсчёта берёт одну лишнюю строку: есть она — есть и следующая
 * страница.
 *
 * <p>Классы Spring Data Commons — необязательная зависимость библиотеки. Этот класс загружается,
 * только если они есть (проверяет {@code PlsqlApiInvocationHandler}).
 */
final class QueryPaging {

    /** Имя служебной колонки с номером строки; в результат метода она не попадает. */
    static final String ROW_NUMBER = "PLSQL_RN_";

    /**
     * Имя свойства, по которому разрешено сортировать: буква, затем буквы, цифры, {@code _ $ #}.
     * Имя из {@code Sort} часто приходит из адреса запроса ({@code ?sort=name}), поэтому всё
     * остальное отвергается: так в текст SQL не попадёт ничего, кроме имени колонки.
     */
    private static final Pattern COLUMN = Pattern.compile("[A-Za-z][A-Za-z0-9_$#]*");

    /**
     * Самое длинное имя колонки, которое знает Oracle (12.2 и новее; в 11g — 30, и на слишком
     * длинное имя база сама ответит ORA-00972). Проверяется имя уже после перевода
     * {@code fullName} → {@code FULL_NAME}: перевод удлиняет его на подчёркивания.
     */
    private static final int MAX_COLUMN = 128;

    /** Во что превращается результат запроса. */
    enum Result {
        /** {@code List}: только строки страницы. */
        LIST,
        /** {@link Page}: строки и общее число строк. */
        PAGE,
        /** {@link Slice}: строки и признак следующей страницы. */
        SLICE
    }

    /**
     * Что метод {@code @SqlQuery} делает со страницами: где его параметры {@link Pageable} и
     * {@link Sort} и что он возвращает.
     *
     * @param pageable номер параметра {@code Pageable} или {@code -1}
     * @param sort     номер параметра {@code Sort} или {@code -1}
     * @param result   вид результата
     */
    record Spec(int pageable, int sort, Result result) {
    }

    /** Класс только со статическими методами: экземпляры не создаются. */
    private QueryPaging() {
    }

    /**
     * Смотрит на параметры и тип результата метода и решает, постраничный ли он.
     *
     * @param m метод с {@code @SqlQuery}
     * @return описание или {@code null}, если у метода нет ни {@code Pageable}, ни {@code Sort}, ни
     *         результата {@code Page}/{@code Slice}
     * @throws IllegalStateException если параметров {@code Pageable} или {@code Sort} больше одного,
     *                               результат {@code Page}/{@code Slice} без {@code Pageable} или
     *                               {@code Pageable}/{@code Sort} при результате не-коллекции
     */
    static Spec of(Method m) {
        int pageable = -1;
        int sort = -1;
        Class<?>[] types = m.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (Pageable.class.isAssignableFrom(types[i])) {
                if (pageable >= 0) {
                    throw new IllegalStateException("more than one Pageable parameter");
                }
                pageable = i;
            } else if (Sort.class.isAssignableFrom(types[i])) {
                if (sort >= 0) {
                    throw new IllegalStateException("more than one Sort parameter");
                }
                sort = i;
            }
        }
        Class<?> raw = m.getReturnType();
        Result result = Page.class.isAssignableFrom(raw) ? Result.PAGE
                : Slice.class.isAssignableFrom(raw) ? Result.SLICE : Result.LIST;
        if (result != Result.LIST && pageable < 0) {
            throw new IllegalStateException("returns " + raw.getSimpleName() + " but has no Pageable parameter");
        }
        if (result == Result.LIST && pageable < 0 && sort < 0) {
            return null;
        }
        if (result == Result.LIST && !java.util.Collection.class.isAssignableFrom(raw)) {
            throw new IllegalStateException("Pageable and Sort need a List, Page or Slice result, not "
                    + raw.getSimpleName());
        }
        return new Spec(pageable, sort, result);
    }

    /**
     * Проверяет, служебный ли это параметр ({@code Pageable} или {@code Sort}), который не
     * передаётся в SQL как именованный параметр.
     *
     * @param type тип параметра метода
     * @return {@code true} для {@code Pageable} и {@code Sort}
     */
    static boolean isPagingParameter(Class<?> type) {
        return Pageable.class.isAssignableFrom(type) || Sort.class.isAssignableFrom(type);
    }

    /**
     * Выполнение запроса: текст с {@code ?} и значения по порядку превращаются в строки.
     */
    @FunctionalInterface
    interface Rows {
        /**
         * Выполняет запрос и превращает строки в объекты.
         *
         * @param sql            текст с {@code ?}
         * @param values         значения по порядку
         * @param hiddenRowNumber последняя колонка — служебный номер строки, её не отдавать
         * @return строки
         */
        List<Object> fetch(String sql, Object[] values, boolean hiddenRowNumber);
    }

    /**
     * Подсчёт строк: текст с {@code ?} и значения по порядку превращаются в число.
     */
    @FunctionalInterface
    interface Count {
        /**
         * Выполняет {@code SELECT COUNT(*)}.
         *
         * @param sql    текст с {@code ?}
         * @param values значения по порядку
         * @return число строк
         */
        long count(String sql, Object[] values);
    }

    /**
     * Выполняет запрос постранично и собирает результат метода.
     *
     * <p>{@code null} вместо {@code Pageable} значит «без страниц», вместо {@code Sort} — «без
     * сортировки». Сортировка из {@code Pageable} и из параметра {@code Sort} складывается, первой
     * идёт сортировка {@code Pageable}.
     *
     * @param spec   описание метода
     * @param sql    текст запроса метода, уже с {@code ?} вместо именованных параметров
     * @param values значения параметров запроса по порядку
     * @param args   аргументы вызова метода
     * @param rows   как выполнить запрос
     * @param count  как посчитать строки
     * @return {@code List}, {@link Page} или {@link Slice}
     * @throws InvalidDataAccessApiUsageException если имя в {@code Sort} не похоже на имя колонки
     */
    static Object run(Spec spec, String sql, Object[] values, Object[] args, Rows rows, Count count) {
        Pageable pageable = spec.pageable() >= 0 && args[spec.pageable()] != null
                ? (Pageable) args[spec.pageable()] : Pageable.unpaged();
        Sort sort = pageable.getSort();
        if (spec.sort() >= 0 && args[spec.sort()] != null) {
            sort = sort.and((Sort) args[spec.sort()]);
        }
        // Перед каждой закрывающей скобкой — перевод строки: иначе однострочный комментарий
        // в конце запроса ("... -- примечание") закомментировал бы и её.
        String ordered = sort.isSorted() ? "SELECT * FROM (" + sql + "\n) q_ ORDER BY " + orderBy(sort) : sql;
        if (pageable.isUnpaged()) {
            List<Object> all = rows.fetch(ordered, values, false);
            return switch (spec.result()) {
                case LIST -> all;
                case PAGE -> new PageImpl<>(all, pageable, all.size());
                case SLICE -> new SliceImpl<>(all, pageable, false);
            };
        }
        long first = pageable.getOffset();
        int size = pageable.getPageSize();
        long last = first + size + (spec.result() == Result.SLICE ? 1 : 0);
        String paged = "SELECT * FROM (SELECT q_.*, ROWNUM " + ROW_NUMBER + " FROM (" + ordered
                + "\n) q_ WHERE ROWNUM <= ?) WHERE " + ROW_NUMBER + " > ?";
        Object[] pagedValues = Arrays.copyOf(values, values.length + 2);
        pagedValues[values.length] = last;
        pagedValues[values.length + 1] = first;
        List<Object> content = rows.fetch(paged, pagedValues, true);
        return switch (spec.result()) {
            case LIST -> content;
            case SLICE -> {
                boolean more = content.size() > size;
                yield new SliceImpl<>(more ? new ArrayList<>(content.subList(0, size)) : content, pageable, more);
            }
            case PAGE -> PageableExecutionUtils.getPage(content, pageable,
                    () -> count.count("SELECT COUNT(*) FROM (" + sql + "\n)", values));
        };
    }

    /**
     * Собирает {@code ORDER BY} по колонкам запроса, завёрнутого как {@code q_}.
     *
     * <p>Имя свойства переводится в имя колонки, как имя метода в имя процедуры
     * ({@code fullName} → {@code FULL_NAME}), и берётся в кавычки: колонки запроса без кавычек
     * Oracle хранит в верхнем регистре. {@code ignoreCase} сравнивает через {@code UPPER}, а
     * {@code NULLS FIRST}/{@code NULLS LAST} из {@code Sort} передаются как есть.
     *
     * @param sort сортировка, не пустая
     * @return текст после {@code ORDER BY}
     * @throws InvalidDataAccessApiUsageException если имя не похоже на имя колонки
     */
    static String orderBy(Sort sort) {
        List<String> parts = new ArrayList<>();
        for (Sort.Order o : sort) {
            String property = o.getProperty();
            if (!COLUMN.matcher(property).matches()) {
                throw new InvalidDataAccessApiUsageException("cannot sort by '" + property
                        + "': only a column name of letters, digits and _ $ # is allowed");
            }
            String name = PlsqlApiInvocationHandler.oracleName(property);
            if (name.length() > MAX_COLUMN) {
                throw new InvalidDataAccessApiUsageException("cannot sort by '" + property + "': the column name "
                        + name + " is longer than " + MAX_COLUMN + " characters");
            }
            String column = "q_.\"" + name + "\"";
            StringBuilder sb = new StringBuilder(o.isIgnoreCase() ? "UPPER(" + column + ")" : column);
            sb.append(o.isAscending() ? " ASC" : " DESC");
            switch (o.getNullHandling()) {
                case NULLS_FIRST -> sb.append(" NULLS FIRST");
                case NULLS_LAST -> sb.append(" NULLS LAST");
                default -> {
                }
            }
            parts.add(sb.toString());
        }
        return String.join(", ", parts);
    }
}
