package dev.plsql.spring.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Условие отбора строк для запроса {@code @SqlQuery}: передаётся параметром метода рядом с
 * {@code Pageable} и {@code Sort}, как {@code Specification} в Spring Data.
 *
 * <pre>{@code
 * @SqlQuery("select id, num, status, created from orders_v")
 * Page<Order> registry(Filter filter, Pageable pageable);
 *
 * registry(Filter.allOf(
 *         Filter.eq("status", "NEW"),
 *         Filter.between("created", from, to),
 *         Filter.containsIgnoreCase("num", text)), PageRequest.of(0, 50));
 * }</pre>
 *
 * <p>Условие — дерево: простые условия «поле, операция, значения» ({@link Condition}),
 * соединённые через {@link #and И}, {@link #or ИЛИ} и {@link #negate НЕ}. Запрос метода
 * оборачивается подзапросом, и условие проверяется по его колонкам, поэтому фильтровать можно и по
 * псевдонимам. Имя поля — имя свойства record строки ({@code createdAt}) или колонки запроса
 * ({@code CREATED_AT}); фильтровать можно только по колонкам, которые попадают в свойства record
 * (или перечислены в {@code @SqlQuery(filterable = ...)}), а в текст SQL попадает имя колонки из
 * описания запроса базой, а не строка из фильтра. Значения всегда идут параметрами ({@code ?}) и
 * приводятся к типу колонки: строку {@code "2024-01-31"} из адреса запроса можно сравнивать с
 * датой.
 *
 * <p>Объекты неизменяемы и потокобезопасны.
 */
public sealed interface Filter
        permits Filter.Condition, Filter.All, Filter.Any, Filter.Not, Filter.InSelect, Filter.Sql {

    /** Операция простого условия. */
    enum Operator {
        /** Равно. {@code LocalDate} для колонки-даты — весь этот день. */
        EQ,
        /** Не равно. */
        NE,
        /** Больше. */
        GT,
        /** Больше или равно. */
        GE,
        /** Меньше. */
        LT,
        /** Меньше или равно. {@code LocalDate} для колонки-даты — до конца этого дня. */
        LE,
        /** Одно из значений списка. */
        IN,
        /** Текст содержит значение. */
        CONTAINS,
        /** Текст начинается со значения. */
        STARTS_WITH,
        /** Пусто ({@code IS NULL}). */
        IS_NULL,
        /** Не пусто ({@code IS NOT NULL}). */
        IS_NOT_NULL
    }

    /**
     * Простое условие: поле, операция, значения.
     *
     * @param field      имя свойства record или колонки запроса
     * @param operator   операция
     * @param values     значения: одно для сравнений и текста, сколько угодно для {@code IN}, ни
     *                   одного для {@code IS_NULL}/{@code IS_NOT_NULL}; {@code null} среди них
     *                   нет (для пустоты есть {@code IS_NULL})
     * @param ignoreCase сравнивать текст без учёта регистра
     */
    record Condition(String field, Operator operator, List<Object> values, boolean ignoreCase) implements Filter {

        /**
         * Проверяет условие при создании.
         *
         * @param field      имя поля
         * @param operator   операция
         * @param values     значения
         * @param ignoreCase без учёта регистра
         * @throws IllegalArgumentException если поле пустое, значения {@code null} или их число
         *                                  не подходит к операции
         */
        public Condition {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("filter field is empty");
            }
            Objects.requireNonNull(operator, "operator");
            for (Object v : values) {
                if (v == null) {
                    throw new IllegalArgumentException("filter " + field + " " + operator
                            + ": null value; use Filter.isNull(\"" + field + "\")");
                }
            }
            values = List.copyOf(values);
            int n = values.size();
            boolean ok = switch (operator) {
                case IS_NULL, IS_NOT_NULL -> n == 0;
                case IN -> true;
                default -> n == 1;
            };
            if (!ok) {
                throw new IllegalArgumentException("filter " + field + " " + operator + ": " + n + " values");
            }
        }
    }

    /**
     * Все условия сразу (И). Пустой список — без условия: подходит любая строка.
     *
     * @param parts условия
     */
    record All(List<Filter> parts) implements Filter {

        /**
         * Копирует список.
         *
         * @param parts условия, без {@code null}
         */
        public All {
            parts = List.copyOf(parts);
        }
    }

    /**
     * Хотя бы одно из условий (ИЛИ). Пустой список не подходит ни к одной строке.
     *
     * @param parts условия
     */
    record Any(List<Filter> parts) implements Filter {

        /**
         * Копирует список.
         *
         * @param parts условия, без {@code null}
         */
        public Any {
            parts = List.copyOf(parts);
        }
    }

    /**
     * Отрицание условия (НЕ).
     *
     * @param filter условие
     */
    record Not(Filter filter) implements Filter {

        /**
         * Проверяет, что условие есть.
         *
         * @param filter условие, не {@code null}
         */
        public Not {
            Objects.requireNonNull(filter, "filter");
        }
    }

    /**
     * Значение поля входит в результат подзапроса: {@code поле IN (подзапрос)}. Для условий из
     * кода приложения, например обязательного ограничения ({@link QueryRestriction}):
     * «организация записи — среди доступных пользователю».
     *
     * @param field    имя поля
     * @param subquery текст подзапроса с {@code ?} для значений; пишется в коде, не из ввода
     *                 пользователя
     * @param values   значения для {@code ?} подзапроса по порядку
     */
    record InSelect(String field, String subquery, List<Object> values) implements Filter {

        /**
         * Проверяет условие при создании.
         *
         * @param field    имя поля
         * @param subquery подзапрос
         * @param values   значения
         * @throws IllegalArgumentException если поле или подзапрос пусты
         */
        public InSelect {
            if (field == null || field.isBlank() || subquery == null || subquery.isBlank()) {
                throw new IllegalArgumentException("in-select filter needs a field and a subquery");
            }
            values = List.copyOf(values);
        }
    }

    /**
     * Условие, написанное на SQL: для того, что не выразить остальными. Поля запроса пишутся в
     * фигурных скобках ({@code {status} <> {prevStatus}}) и проверяются так же, как имена полей
     * остальных условий; значения — через {@code ?}. Текст пишется в коде, не из ввода
     * пользователя.
     *
     * @param expression условие на SQL
     * @param values     значения для {@code ?} по порядку
     */
    record Sql(String expression, List<Object> values) implements Filter {

        /**
         * Проверяет условие при создании.
         *
         * @param expression условие
         * @param values     значения
         * @throws IllegalArgumentException если условие пустое
         */
        public Sql {
            if (expression == null || expression.isBlank()) {
                throw new IllegalArgumentException("sql filter is empty");
            }
            values = List.copyOf(values);
        }
    }

    /**
     * Возвращает условие, которому подходит любая строка.
     *
     * @return пустое {@link All}
     */
    static Filter none() {
        return new All(List.of());
    }

    /**
     * Собирает простое условие по операции — для фильтров, которые приходят из интерфейса как
     * «поле, операция, значения».
     *
     * @param field    имя поля
     * @param operator операция
     * @param values   значения
     * @return условие
     * @throws IllegalArgumentException если число значений не подходит к операции
     */
    static Filter of(String field, Operator operator, Object... values) {
        return new Condition(field, operator, Arrays.asList(values), false);
    }

    /**
     * Собирает простое условие по операции без учёта регистра текста.
     *
     * @param field    имя поля
     * @param operator операция
     * @param values   значения
     * @return условие
     * @throws IllegalArgumentException если число значений не подходит к операции
     */
    static Filter ofIgnoreCase(String field, Operator operator, Object... values) {
        return new Condition(field, operator, Arrays.asList(values), true);
    }

    /**
     * Поле равно значению.
     *
     * @param field имя поля
     * @param value значение, не {@code null}
     * @return условие
     */
    static Filter eq(String field, Object value) {
        return of(field, Operator.EQ, value);
    }

    /**
     * Текстовое поле равно значению без учёта регистра.
     *
     * @param field имя поля
     * @param value значение, не {@code null}
     * @return условие
     */
    static Filter eqIgnoreCase(String field, Object value) {
        return ofIgnoreCase(field, Operator.EQ, value);
    }

    /**
     * Поле не равно значению.
     *
     * @param field имя поля
     * @param value значение, не {@code null}
     * @return условие
     */
    static Filter ne(String field, Object value) {
        return of(field, Operator.NE, value);
    }

    /**
     * Поле больше значения.
     *
     * @param field имя поля
     * @param value значение
     * @return условие
     */
    static Filter gt(String field, Object value) {
        return of(field, Operator.GT, value);
    }

    /**
     * Поле больше или равно значению.
     *
     * @param field имя поля
     * @param value значение
     * @return условие
     */
    static Filter ge(String field, Object value) {
        return of(field, Operator.GE, value);
    }

    /**
     * Поле меньше значения.
     *
     * @param field имя поля
     * @param value значение
     * @return условие
     */
    static Filter lt(String field, Object value) {
        return of(field, Operator.LT, value);
    }

    /**
     * Поле меньше или равно значению.
     *
     * @param field имя поля
     * @param value значение
     * @return условие
     */
    static Filter le(String field, Object value) {
        return of(field, Operator.LE, value);
    }

    /**
     * Поле в диапазоне, границы включительно. Пустая граница ({@code null}) не ограничивает:
     * {@code between("sum", 100, null)} — «от 100». Обе пустые — без условия. Для колонки-даты
     * {@code LocalDate} в верхней границе включает весь этот день.
     *
     * @param field имя поля
     * @param from  нижняя граница или {@code null}
     * @param to    верхняя граница или {@code null}
     * @return условие
     */
    static Filter between(String field, Object from, Object to) {
        if (from == null && to == null) {
            return none();
        }
        if (from == null) {
            return le(field, to);
        }
        if (to == null) {
            return ge(field, from);
        }
        return new All(List.of(ge(field, from), le(field, to)));
    }

    /**
     * Поле равно одному из значений. Пустой список не подходит ни к одной строке; длинный список
     * делится на части по 1000 (больше в одном {@code IN} Oracle не принимает).
     *
     * @param field  имя поля
     * @param values значения
     * @return условие
     */
    static Filter in(String field, Collection<?> values) {
        return new Condition(field, Operator.IN, new ArrayList<>(values), false);
    }

    /**
     * Текст поля содержит значение. Знаки {@code %} и {@code _} в значении ищутся как есть.
     *
     * @param field имя текстового поля
     * @param text  искомый текст
     * @return условие
     */
    static Filter contains(String field, String text) {
        return of(field, Operator.CONTAINS, text);
    }

    /**
     * Текст поля содержит значение без учёта регистра.
     *
     * @param field имя текстового поля
     * @param text  искомый текст
     * @return условие
     */
    static Filter containsIgnoreCase(String field, String text) {
        return ofIgnoreCase(field, Operator.CONTAINS, text);
    }

    /**
     * Текст поля начинается со значения.
     *
     * @param field  имя текстового поля
     * @param prefix начало
     * @return условие
     */
    static Filter startsWith(String field, String prefix) {
        return of(field, Operator.STARTS_WITH, prefix);
    }

    /**
     * Текст поля начинается со значения без учёта регистра.
     *
     * @param field  имя текстового поля
     * @param prefix начало
     * @return условие
     */
    static Filter startsWithIgnoreCase(String field, String prefix) {
        return ofIgnoreCase(field, Operator.STARTS_WITH, prefix);
    }

    /**
     * Поле пусто ({@code IS NULL}).
     *
     * @param field имя поля
     * @return условие
     */
    static Filter isNull(String field) {
        return of(field, Operator.IS_NULL);
    }

    /**
     * Поле не пусто ({@code IS NOT NULL}).
     *
     * @param field имя поля
     * @return условие
     */
    static Filter isNotNull(String field) {
        return of(field, Operator.IS_NOT_NULL);
    }

    /**
     * Значение поля входит в результат подзапроса (см. {@link InSelect}).
     *
     * @param field    имя поля
     * @param subquery подзапрос с {@code ?}; пишется в коде, не из ввода пользователя
     * @param values   значения для {@code ?}
     * @return условие
     */
    static Filter inSelect(String field, String subquery, Object... values) {
        return new InSelect(field, subquery, Arrays.asList(values));
    }

    /**
     * Условие на SQL (см. {@link Sql}).
     *
     * @param expression условие с полями в фигурных скобках и {@code ?}; пишется в коде, не из
     *                   ввода пользователя
     * @param values     значения для {@code ?}
     * @return условие
     */
    static Filter sql(String expression, Object... values) {
        return new Sql(expression, Arrays.asList(values));
    }

    /**
     * Все условия сразу (И). {@code null} среди них пропускаются: так удобно собирать фильтр из
     * необязательных полей формы.
     *
     * @param parts условия; {@code null} пропускается
     * @return условие; без условий — {@link #none()}
     */
    static Filter allOf(Filter... parts) {
        return new All(Arrays.stream(parts).filter(Objects::nonNull).toList());
    }

    /**
     * Хотя бы одно из условий (ИЛИ). {@code null} среди них пропускаются.
     *
     * @param parts условия; {@code null} пропускается
     * @return условие; без условий не подходит ни к одной строке
     */
    static Filter anyOf(Filter... parts) {
        return new Any(Arrays.stream(parts).filter(Objects::nonNull).toList());
    }

    /**
     * Это условие и ещё одно.
     *
     * @param other второе условие
     * @return условие «оба»
     */
    default Filter and(Filter other) {
        return allOf(this, Objects.requireNonNull(other, "other"));
    }

    /**
     * Это условие или другое.
     *
     * @param other второе условие
     * @return условие «хотя бы одно»
     */
    default Filter or(Filter other) {
        return anyOf(this, Objects.requireNonNull(other, "other"));
    }

    /**
     * Отрицание этого условия.
     *
     * @return условие «не это»
     */
    default Filter negate() {
        return new Not(this);
    }
}
