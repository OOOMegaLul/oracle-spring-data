package dev.plsql.spring.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;

import com.zaxxer.hikari.HikariDataSource;

import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.annotation.PlsqlApi;
import dev.plsql.spring.annotation.SqlQuery;

/**
 * Постраничные запросы {@code @SqlQuery} на настоящем Oracle 11.2: {@code Page}, {@code Slice},
 * {@code Sort} через {@code ROWNUM}. Строки берутся из {@code CONNECT BY LEVEL}, таблица не нужна.
 */
class PagingIT {

    /**
     * Строка запроса.
     *
     * @param n    номер
     * @param word слово {@code w<номер>}
     */
    record Num(long n, String word) {
    }

    /** Запросы со страницами и сортировкой. */
    @PlsqlApi
    interface Numbers {
        /**
         * Страница чисел от 1 до {@code count}.
         *
         * @param count    сколько чисел
         * @param pageable страница и сортировка
         * @return страница
         */
        @SqlQuery("select level n, 'w' || level word from dual connect by level <= :count")
        Page<Num> page(long count, Pageable pageable);

        /**
         * Срез тех же чисел.
         *
         * @param count    сколько чисел
         * @param pageable страница
         * @return срез
         */
        @SqlQuery("select level n, 'w' || level word from dual connect by level <= :count")
        Slice<Num> slice(long count, Pageable pageable);

        /**
         * Все числа в заданном порядке.
         *
         * @param count сколько чисел
         * @param sort  сортировка
         * @return числа
         */
        @SqlQuery("select level n, 'w' || level word from dual connect by level <= :count")
        List<Num> sorted(long count, Sort sort);

        /**
         * Одна колонка: служебная колонка номера строки не должна мешать.
         *
         * @param count    сколько чисел
         * @param pageable страница
         * @return страница чисел
         */
        @SqlQuery("select level from dual connect by level <= :count")
        Page<Long> plain(long count, Pageable pageable);

        /**
         * Строки картами: служебной колонки в них нет.
         *
         * @param count    сколько чисел
         * @param pageable страница
         * @return строки страницы
         */
        @SqlQuery("select level n from dual connect by level <= :count")
        List<Map<String, Object>> maps(long count, Pageable pageable);
    }

    /** Пул к тестовой схеме. */
    static HikariDataSource ds;
    /** Реализация {@link Numbers}. */
    static Numbers numbers;

    /** Создаёт пул и реализацию. */
    @BeforeAll
    static void setUp() {
        ds = ItDatabase.pool(1, true);
        numbers = PlsqlApiFactory.builder(ds).build().create(Numbers.class);
    }

    /** Закрывает пул. */
    @AfterAll
    static void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    /**
     * Проверяет страницы: вторая страница по 5 из 23 — числа 6..10, всего 23 строки и 5 страниц;
     * последняя — 21..23; с сортировкой по убыванию первая страница — 23..19.
     */
    @Test
    void pages() {
        Page<Num> p = numbers.page(23, PageRequest.of(1, 5));
        assertThat(p.getContent()).extracting(Num::n).containsExactly(6L, 7L, 8L, 9L, 10L);
        assertThat(p.getContent().get(0)).isEqualTo(new Num(6, "w6"));
        assertThat(p.getTotalElements()).isEqualTo(23);
        assertThat(p.getTotalPages()).isEqualTo(5);

        assertThat(numbers.page(23, PageRequest.of(4, 5)).getContent()).extracting(Num::n).containsExactly(21L, 22L, 23L);
        assertThat(numbers.page(23, PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "n"))).getContent())
                .extracting(Num::n).containsExactly(23L, 22L, 21L, 20L, 19L);
        assertThat(numbers.page(3, Pageable.unpaged()).getContent()).hasSize(3);
    }

    /** Проверяет срез: у предпоследней страницы следующая есть, у последней — нет. */
    @Test
    void slices() {
        Slice<Num> s = numbers.slice(23, PageRequest.of(3, 5));
        assertThat(s.getContent()).extracting(Num::n).containsExactly(16L, 17L, 18L, 19L, 20L);
        assertThat(s.hasNext()).isTrue();
        assertThat(numbers.slice(23, PageRequest.of(4, 5)).hasNext()).isFalse();
    }

    /**
     * Проверяет сортировку без страниц: по слову по убыванию ({@code w9} > {@code w10} как строки),
     * а имя, похожее на SQL, отвергается до обращения к базе.
     */
    @Test
    void sortWithoutPages() {
        assertThat(numbers.sorted(10, Sort.by(Sort.Direction.DESC, "word"))).extracting(Num::word)
                .startsWith("w9", "w8", "w7");
        assertThatThrownBy(() -> numbers.sorted(10, Sort.by("n; drop table lab_emp")))
                .isInstanceOf(InvalidDataAccessApiUsageException.class);
    }

    /**
     * Проверяет, что служебная колонка {@code PLSQL_RN_} не видна: страница из одной колонки
     * читается как числа, а в картах только колонка запроса.
     */
    @Test
    void rowNumberColumnStaysHidden() {
        assertThat(numbers.plain(10, PageRequest.of(1, 3)).getContent()).containsExactly(4L, 5L, 6L);
        assertThat(numbers.maps(10, PageRequest.of(0, 2))).allSatisfy(m -> assertThat(m).containsOnlyKeys("N"));
    }
}
