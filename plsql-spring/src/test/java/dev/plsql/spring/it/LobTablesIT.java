package dev.plsql.spring.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.annotation.PlsqlApi;

/**
 * Index-by таблицы с полями {@code CLOB}, {@code BLOB}, {@code XMLTYPE}, {@code RAW},
 * {@code NVARCHAR2} и {@code NCHAR} и таблицы {@code NVARCHAR2}, {@code CLOB}, {@code BLOB} на
 * настоящей базе. Значения таких полей едут склеенным LOB с таблицей длин. Пакет
 * {@code LAB_LOB} из {@code it/schema-objects.sql}.
 */
class LobTablesIT {

    /** Иероглиф U+4E2D, который дописывают функции пакета. */
    static final String HAN = "\u4e2d";
    /** Национальный текст: кириллица, иероглиф, эмодзи (в Java эмодзи — два символа). */
    static final String TEXT = "\u0416 \u6587 \ud83d\ude00";

    /**
     * Элемент {@code DOC_T}.
     *
     * @param id    номер
     * @param body  {@code CLOB}
     * @param title {@code NVARCHAR2(50)}
     * @param data  {@code BLOB}
     * @param x     {@code XMLTYPE} текстом
     * @param tag   {@code RAW(16)}
     * @param code  {@code NCHAR(3)}
     */
    record Doc(Long id, String body, String title, byte[] data, String x, byte[] tag, String code) {
    }

    /** Функции {@code LAB_LOB}. */
    @PlsqlApi(packageName = "LAB_LOB")
    interface Lobs {
        /**
         * Таблица записей туда и обратно: к {@code id} прибавляется единица, к {@code body} —
         * {@code "!"}, к {@code title} — иероглиф.
         *
         * @param docs записи
         * @return записи после функции
         */
        List<Doc> docsEcho(List<Doc> docs);

        /**
         * Таблица записей из функции; ключи разреженные (10, 20, 30), у второй записи всё, кроме
         * {@code id}, — {@code NULL}, {@code body} — 40 000 символов.
         *
         * @param n сколько записей
         * @return записи по порядку ключей
         */
        List<Doc> docsOut(long n);

        /**
         * Таблица {@code NVARCHAR2}: к каждому значению дописывается иероглиф.
         *
         * @param vals значения
         * @return значения после функции
         */
        List<String> nstrs(List<String> vals);

        /**
         * Таблица {@code CLOB}: к каждому значению дописывается {@code "x"}.
         *
         * @param vals значения
         * @return значения после функции
         */
        List<String> clobs(List<String> vals);

        /**
         * Таблица {@code BLOB} туда и обратно.
         *
         * @param vals значения
         * @return те же значения
         */
        List<byte[]> blobs(List<byte[]> vals);
    }

    /** Пул к тестовой схеме. */
    static HikariDataSource ds;
    /** Реализация {@link Lobs}. */
    static Lobs lobs;

    /** Создаёт пул и реализацию; при этом каждый метод сверяется со словарём. */
    @BeforeAll
    static void setUp() {
        ds = ItDatabase.pool(1, true);
        lobs = PlsqlApiFactory.builder(ds).build().create(Lobs.class);
    }

    /** Закрывает пул. */
    @AfterAll
    static void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    /**
     * Проверяет таблицу записей с LOB на выходе: длинный {@code CLOB}, национальный текст, байты,
     * XML, {@code RAW} и {@code NCHAR(3)} (добит пробелами до объявленной длины) приходят на свои
     * места, а {@code NULL} остаётся {@code NULL}.
     */
    @Test
    void lobFieldsComeBack() {
        List<Doc> docs = lobs.docsOut(3);
        assertThat(docs).hasSize(3);
        Doc first = docs.get(0);
        assertThat(first.body()).hasSize(40_000).startsWith("aaa").endsWith("bbb");
        assertThat(first.title()).isEqualTo(HAN + "1");
        assertThat(first.data()).isEqualTo(HexFormat.of().parseHex("DEADBEEF01"));
        assertThat(first.x()).isEqualTo("<n>1</n>");
        assertThat(first.tag()).isEqualTo(new byte[]{0x0A, 0x0B});
        assertThat(first.code()).isEqualTo("C1 ");
        assertThat(docs.get(1)).isEqualTo(new Doc(2L, null, null, null, null, null, null));
        assertThat(docs.get(2).data()).isEqualTo(HexFormat.of().parseHex("DEADBEEF03"));
    }

    /**
     * Проверяет таблицу записей с LOB в обе стороны: каждое поле доходит до PL/SQL и обратно,
     * пустой {@code CLOB} — не {@code NULL} (функция дописала к нему {@code "!"}), национальный
     * текст не теряет ни одного символа. В {@code CLOB} — только кириллица: он хранится в
     * основной кодировке базы, а она бывает однобайтовой (CL8MSWIN1251).
     */
    @Test
    void lobFieldsTravelBothWays() {
        String big = "\u0436".repeat(20_000) + "\u0416 end";
        byte[] bytes = new byte[70_000];
        Arrays.fill(bytes, (byte) 7);
        List<Doc> back = lobs.docsEcho(List.of(
                new Doc(1L, big, TEXT, bytes, "<a>1</a>", new byte[]{1, 2}, "ab"),
                new Doc(5L, null, null, null, null, null, null),
                new Doc(9L, "", "", new byte[0], null, null, null)));
        Doc d = back.get(0);
        assertThat(d.id()).isEqualTo(2L);
        assertThat(d.body()).isEqualTo(big + "!");
        assertThat(d.title()).isEqualTo(TEXT + HAN);
        assertThat(d.data()).isEqualTo(bytes);
        assertThat(d.x()).isEqualTo("<a>1</a>");
        assertThat(d.tag()).isEqualTo(new byte[]{1, 2});
        assertThat(d.code()).isEqualTo("ab ");
        assertThat(back.get(1)).isEqualTo(new Doc(6L, null, null, null, null, null, null));
        assertThat(back.get(2).body()).isEqualTo("!");
        assertThat(back.get(2).title()).isNull();
        assertThat(back.get(2).data()).isEmpty();
    }

    /** Проверяет таблицы {@code NVARCHAR2}, {@code CLOB} и {@code BLOB} с {@code NULL} и пустыми значениями. */
    @Test
    void tablesOfLobsAndNationalStrings() {
        assertThat(lobs.nstrs(Arrays.asList(TEXT, null))).containsExactly(TEXT + HAN, null);
        assertThat(lobs.clobs(Arrays.asList("a", null, ""))).containsExactly("ax", null, "x");
        List<byte[]> blobs = lobs.blobs(Arrays.asList(new byte[]{1, 2, 3}, null, new byte[0]));
        assertThat(blobs).hasSize(3);
        assertThat(blobs.get(0)).isEqualTo(new byte[]{1, 2, 3});
        assertThat(blobs.get(1)).isNull();
        assertThat(blobs.get(2)).isEmpty();
        assertThat(lobs.nstrs(List.of())).isEmpty();
    }
}
