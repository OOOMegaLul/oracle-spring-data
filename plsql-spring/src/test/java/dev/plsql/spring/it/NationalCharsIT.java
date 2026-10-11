package dev.plsql.spring.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.annotation.PlsqlApi;

/**
 * Национальные строки ({@code NVARCHAR2}, {@code NCHAR}, {@code NCLOB}) на настоящей базе: текст
 * с символами вне однобайтовых кодировок (китайский иероглиф, эмодзи) доходит до PL/SQL и обратно.
 * Пакет {@code LAB_NCHAR} из {@code it/schema-objects.sql}; каждая его функция дописывает к тексту
 * иероглиф U+4E2D.
 *
 * <p>На XE основная кодировка базы — AL32UTF8, и потерю символов здесь не увидеть: тест сторожит,
 * что национальная привязка ({@code setNString}, временный {@code NCLOB}) работает. Что именно
 * она исправляет, показывает база с однобайтовой кодировкой (например, CL8MSWIN1251).
 */
class NationalCharsIT {

    /** Иероглиф U+4E2D, который дописывает каждая функция пакета. */
    static final String HAN = "\u4e2d";
    /** Текст, которого нет ни в одной однобайтовой кодировке: кириллица, иероглиф, эмодзи. */
    static final String TEXT = "\u0416 \u6587 \ud83d\ude00";

    /**
     * Запись {@code NREC_T}.
     *
     * @param id   номер
     * @param name {@code NVARCHAR2}
     */
    record NRec(Long id, String name) {
    }

    /** Функции и процедуры {@code LAB_NCHAR}. */
    @PlsqlApi(packageName = "LAB_NCHAR")
    interface Nchar {
        /**
         * {@code NVARCHAR2} туда и обратно.
         *
         * @param s текст
         * @return текст с иероглифом в конце
         */
        String echo(String s);

        /**
         * {@code NCHAR} на вход и {@code OUT NCHAR}.
         *
         * @param s текст
         * @return текст с иероглифом в конце
         */
        String fixed(String s);

        /**
         * {@code NCLOB} туда и обратно.
         *
         * @param c текст
         * @return текст с иероглифом в конце
         */
        String nclobEcho(String c);

        /**
         * Запись с полем {@code NVARCHAR2}.
         *
         * @param r запись
         * @return запись с иероглифом в конце имени
         */
        NRec recEcho(NRec r);
    }

    /** Пул к тестовой схеме. */
    static HikariDataSource ds;
    /** Реализация {@link Nchar}. */
    static Nchar nchar;

    /** Создаёт пул и реализацию; при этом каждый метод сверяется со словарём. */
    @BeforeAll
    static void setUp() {
        ds = ItDatabase.pool(1, true);
        nchar = PlsqlApiFactory.builder(ds).build().create(Nchar.class);
    }

    /** Закрывает пул. */
    @AfterAll
    static void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    /** Проверяет {@code NVARCHAR2}, {@code NCHAR} и {@code NCLOB} в обе стороны. */
    @Test
    void nationalScalarsKeepEveryCharacter() {
        assertThat(nchar.echo(TEXT)).isEqualTo(TEXT + HAN);
        assertThat(nchar.echo(null)).isEqualTo(HAN);
        assertThat(nchar.fixed(TEXT)).isEqualTo(TEXT + HAN);
        assertThat(nchar.nclobEcho(TEXT.repeat(5000))).isEqualTo(TEXT.repeat(5000) + HAN);
    }

    /** Проверяет поле {@code NVARCHAR2} записи в обе стороны. */
    @Test
    void nationalRecordField() {
        assertThat(nchar.recEcho(new NRec(1L, TEXT))).isEqualTo(new NRec(1L, TEXT + HAN));
    }
}
