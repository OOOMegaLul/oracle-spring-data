package dev.plsql.spring.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;

/**
 * Постраничные запросы {@link QueryPaging} без базы: разбор метода, текст {@code ORDER BY} и
 * обёртки с {@code ROWNUM}, подсчёт строк только когда он нужен.
 */
class QueryPagingTest {

    /** Методы с разными сочетаниями {@code Pageable}, {@code Sort} и результата. */
    interface Shapes {
        /**
         * Страница.
         *
         * @param p страница
         * @return страница строк
         */
        Page<Map<String, Object>> page(Pageable p);

        /**
         * Срез с параметром перед страницей.
         *
         * @param id параметр запроса
         * @param p  страница
         * @return срез строк
         */
        Slice<Map<String, Object>> slice(long id, Pageable p);

        /**
         * Только сортировка.
         *
         * @param s сортировка
         * @return строки
         */
        List<Map<String, Object>> sorted(Sort s);

        /**
         * Ни страницы, ни сортировки.
         *
         * @param id параметр запроса
         * @return строки
         */
        List<Map<String, Object>> plain(long id);

        /**
         * {@code Page} без {@code Pageable} — ошибка.
         *
         * @return страница
         */
        Page<Map<String, Object>> pageWithoutPageable();

        /**
         * Сортировка при результате-одной строке — ошибка.
         *
         * @param s сортировка
         * @return строка
         */
        Map<String, Object> single(Sort s);
    }

    /**
     * Находит метод {@link Shapes} по имени.
     *
     * @param name имя метода
     * @return метод
     */
    private static java.lang.reflect.Method method(String name) {
        return java.util.Arrays.stream(Shapes.class.getMethods()).filter(m -> m.getName().equals(name))
                .findFirst().orElseThrow();
    }

    /**
     * Проверяет разбор метода: где {@code Pageable} и {@code Sort} и какой результат; метод без них
     * не постраничный; {@code Page} без {@code Pageable} и сортировка одной строки отвергаются.
     */
    @Test
    void methodShapes() {
        assertThat(QueryPaging.of(method("page"))).isEqualTo(new QueryPaging.Spec(0, -1, QueryPaging.Result.PAGE));
        assertThat(QueryPaging.of(method("slice"))).isEqualTo(new QueryPaging.Spec(1, -1, QueryPaging.Result.SLICE));
        assertThat(QueryPaging.of(method("sorted"))).isEqualTo(new QueryPaging.Spec(-1, 0, QueryPaging.Result.LIST));
        assertThat(QueryPaging.of(method("plain"))).isNull();
        assertThatThrownBy(() -> QueryPaging.of(method("pageWithoutPageable")))
                .hasMessageContaining("returns Page but has no Pageable parameter");
        assertThatThrownBy(() -> QueryPaging.of(method("single")))
                .hasMessageContaining("need a List, Page or Slice result");
    }

    /**
     * Проверяет {@code ORDER BY}: имя свойства становится колонкой в кавычках ({@code fullName} →
     * {@code "FULL_NAME"}), направление, {@code UPPER} для {@code ignoreCase} и {@code NULLS LAST}.
     */
    @Test
    void orderByUsesQueryColumns() {
        Sort sort = Sort.by(Sort.Order.desc("fullName").ignoreCase(), Sort.Order.asc("ID").nullsLast());
        assertThat(QueryPaging.orderBy(sort)).isEqualTo("UPPER(q_.\"FULL_NAME\") DESC, q_.\"ID\" ASC NULLS LAST");
    }

    /**
     * Проверяет, что в {@code ORDER BY} не попадает ничего, кроме имени колонки: имя из адреса
     * запроса с SQL-текстом, кавычкой или точкой отвергается.
     */
    @Test
    void sortCannotInjectSql() {
        for (String bad : List.of("id; drop table t", "id\"", "a.b", "1id", "x".repeat(31), "id desc")) {
            assertThatThrownBy(() -> QueryPaging.orderBy(Sort.by(bad)))
                    .as(bad).isInstanceOf(InvalidDataAccessApiUsageException.class);
        }
    }

    /**
     * Проверяет текст и значения страницы: вторая страница по 5 строк — {@code ROWNUM <= 10} и
     * номер строки больше 5, значения страницы идут после значений запроса, служебная колонка
     * прячется. Страница полная, поэтому общее число строк считается отдельным запросом.
     */
    @Test
    void pageWrapsTheQueryWithRownum() {
        List<String> sqls = new ArrayList<>();
        List<Object[]> binds = new ArrayList<>();
        QueryPaging.Rows rows = (sql, values, hidden) -> {
            sqls.add(sql);
            binds.add(values);
            assertThat(hidden).isTrue();
            return new ArrayList<>(java.util.Collections.nCopies(5, (Object) "row"));
        };
        Page<?> page = (Page<?>) QueryPaging.run(new QueryPaging.Spec(1, -1, QueryPaging.Result.PAGE),
                "select * from t where x = ?", new Object[]{7}, new Object[]{7, PageRequest.of(1, 5, Sort.by("x"))},
                rows, (sql, values) -> {
                    sqls.add(sql);
                    return 23;
                });

        assertThat(sqls.get(0)).isEqualTo("SELECT * FROM (SELECT q_.*, ROWNUM PLSQL_RN_ FROM (SELECT * FROM ("
                + "select * from t where x = ?) q_ ORDER BY q_.\"X\" ASC) q_ WHERE ROWNUM <= ?) WHERE PLSQL_RN_ > ?");
        assertThat(binds.get(0)).containsExactly(7, 10L, 5L);
        assertThat(sqls.get(1)).isEqualTo("SELECT COUNT(*) FROM (select * from t where x = ?)");
        assertThat(page.getTotalElements()).isEqualTo(23);
    }

    /**
     * Проверяет, что общее число строк не считается, когда оно известно и так: страница неполная
     * (последняя), и что срез берёт одну лишнюю строку, чтобы узнать о следующей странице.
     */
    @Test
    void countOnlyWhenNeededAndSliceLooksOneAhead() {
        QueryPaging.Count mustNotCount = (sql, values) -> {
            throw new AssertionError("count is not needed");
        };
        Page<?> last = (Page<?>) QueryPaging.run(new QueryPaging.Spec(0, -1, QueryPaging.Result.PAGE),
                "select 1 from dual", new Object[0], new Object[]{PageRequest.of(2, 5)},
                (sql, values, hidden) -> List.of("a", "b"), mustNotCount);
        assertThat(last.getTotalElements()).isEqualTo(12);

        List<Object[]> binds = new ArrayList<>();
        Slice<?> slice = (Slice<?>) QueryPaging.run(new QueryPaging.Spec(0, -1, QueryPaging.Result.SLICE),
                "select 1 from dual", new Object[0], new Object[]{PageRequest.of(0, 2)},
                (sql, values, hidden) -> {
                    binds.add(values);
                    return List.of("a", "b", "c");
                }, mustNotCount);
        assertThat(binds.get(0)).containsExactly(3L, 0L);
        assertThat(slice.getContent()).isEqualTo(List.of("a", "b"));
        assertThat(slice.hasNext()).isTrue();
    }

    /**
     * Проверяет {@code null} и {@code Pageable.unpaged()}: запрос выполняется без обёртки с
     * {@code ROWNUM}, служебной колонки нет, страница содержит все строки.
     */
    @Test
    void unpagedRunsTheQueryAsIs() {
        List<String> sqls = new ArrayList<>();
        QueryPaging.Rows rows = (sql, values, hidden) -> {
            sqls.add(sql);
            assertThat(hidden).isFalse();
            return List.of("a", "b");
        };
        Page<?> page = (Page<?>) QueryPaging.run(new QueryPaging.Spec(0, -1, QueryPaging.Result.PAGE),
                "select 1 from dual", new Object[0], new Object[]{null}, rows, (s, v) -> 0);
        assertThat(sqls).containsExactly("select 1 from dual");
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(QueryPaging.run(new QueryPaging.Spec(0, -1, QueryPaging.Result.LIST),
                "select 1 from dual", new Object[0], new Object[]{Pageable.unpaged()}, rows, (s, v) -> 0))
                .isEqualTo(List.of("a", "b"));
    }
}
