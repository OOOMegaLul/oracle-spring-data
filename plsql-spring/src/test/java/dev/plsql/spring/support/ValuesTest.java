package dev.plsql.spring.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.w3c.dom.Document;

import dev.plsql.spring.annotation.Arg;

/**
 * Тесты {@link Values}: преобразования между значениями Java и тем, что возвращает ojdbc,
 * чтение свойств record, бина и карты по именам PL/SQL и безопасный разбор XML.
 */
class ValuesTest {

    /**
     * Строка результата: record, который собирается из карты «колонка → значение» по именам
     * колонок, а в другом тесте читается по именам аргументов с типовыми префиксами.
     *
     * @param id     колонка {@code ID} (или аргумент {@code NID})
     * @param name   колонка {@code NAME} (или аргумент {@code SNAME})
     * @param hired  колонка {@code HIRED}; дата приходит из базы как {@code Timestamp}
     * @param active колонка {@code ACTIVE}; логическое значение приходит числом 1/0
     */
    record Emp(long id, String name, LocalDate hired, boolean active) {
    }

    /**
     * Record, у которого имя аргумента задано явно через {@code @Arg}.
     *
     * @param folder соответствует аргументу {@code NFOLDER}
     */
    record Named(@Arg("NFOLDER") long folder) {
    }

    /** Обычный бин с геттером и сеттером: собирается из карты и читается по имени PL/SQL. */
    public static class Bean {
        private String code;

        /**
         * Возвращает код.
         *
         * @return значение свойства {@code code}
         */
        public String getCode() {
            return code;
        }

        /**
         * Задаёт код.
         *
         * @param code новое значение свойства {@code code}
         */
        public void setCode(String code) {
            this.code = code;
        }
    }

    /**
     * Сокращение для {@link Values#convert} с целевым типом без generic-параметров.
     *
     * @param v    значение, как его вернул бы ojdbc
     * @param type целевой тип Java
     * @return преобразованное значение
     */
    static Object convert(Object v, Class<?> type) {
        return Values.convert(v, ResolvableType.forClass(type));
    }

    /**
     * Проверяет числа и логические значения. Из базы: {@code BigDecimal} становится
     * {@code long}, 1 и {@code "Y"} — {@code true}, {@code null} для примитива — ноль, для
     * обёртки — {@code null}. В базу: дробное число и {@code boolean} становятся
     * {@code BigDecimal}, а {@code "true"} для аргумента {@code BOOLEAN} — числом 1 ({@code null}
     * так и остаётся {@code null}).
     */
    @Test
    void numbersAndBooleans() {
        assertThat(convert(new BigDecimal("42"), long.class)).isEqualTo(42L);
        assertThat(convert(BigDecimal.ONE, boolean.class)).isEqualTo(true);
        assertThat(convert("Y", Boolean.class)).isEqualTo(true);
        assertThat(convert(null, int.class)).isEqualTo(0);
        assertThat(convert(null, Long.class)).isNull();
        assertThat(Values.toNumber(3.5)).isEqualByComparingTo("3.5");
        assertThat(Values.toNumber(true)).isEqualByComparingTo("1");
        assertThat(Values.toBooleanNumber("true")).isEqualTo(1);
        assertThat(Values.toBooleanNumber(null)).isNull();
    }

    /**
     * Проверяет даты: {@code Timestamp} из базы превращается в {@code LocalDate} (время
     * отбрасывается) и в {@code LocalDateTime}; {@code LocalDate} на входе становится полуночью
     * этого дня, а строка датой не считается и отвергается.
     */
    @Test
    void dates() {
        Timestamp ts = Timestamp.valueOf("2024-02-29 13:45:00");
        assertThat(convert(ts, LocalDate.class)).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(convert(ts, LocalDateTime.class)).isEqualTo(LocalDateTime.of(2024, 2, 29, 13, 45));
        assertThat(Values.toTimestamp(LocalDate.of(2024, 1, 2))).isEqualTo(Timestamp.valueOf("2024-01-02 00:00:00"));
        assertThatThrownBy(() -> Values.toTimestamp("2024-01-02")).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Проверяет, что карта «колонка → значение» становится record (каждый компонент
     * преобразуется к своему типу), список карт — списком record, а бин заполняется через
     * сеттер: колонка {@code SCODE} находит свойство {@code code} по типовому префиксу.
     */
    @Test
    void mapsBecomeRecordsAndBeans() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ID", BigDecimal.TEN);
        row.put("NAME", "Иванов");
        row.put("HIRED", Timestamp.valueOf("2020-01-15 00:00:00"));
        row.put("ACTIVE", BigDecimal.ONE);
        assertThat(convert(row, Emp.class)).isEqualTo(new Emp(10, "Иванов", LocalDate.of(2020, 1, 15), true));

        Object list = Values.convert(List.of(row), ResolvableType.forClassWithGenerics(List.class, Emp.class));
        assertThat(list).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);

        Bean b = (Bean) convert(Map.of("SCODE", "X1"), Bean.class);
        assertThat(b.getCode()).isEqualTo("X1");
    }

    /**
     * Проверяет, что для {@code Optional} значение {@code null} становится
     * {@code Optional.empty()}, а непустое значение заворачивается в {@code Optional.of}.
     */
    @Test
    void optionalWrapsNull() {
        ResolvableType opt = ResolvableType.forClassWithGenerics(Optional.class, String.class);
        assertThat(Values.convert(null, opt)).isEqualTo(Optional.empty());
        assertThat(Values.convert("x", opt)).isEqualTo(Optional.of("x"));
    }

    /**
     * Проверяет чтение свойства по имени PL/SQL из record, бина и карты: {@code NID} находит
     * {@code id}, {@code SNAME} — {@code name}, {@code SCODE} — ключ карты {@code code},
     * {@code P_CODE} — свойство бина {@code code}, {@code @Arg("NFOLDER")} задаёт имя явно, а
     * у источника {@code null} любое свойство равно {@code null}. Так record-параметр
     * раскладывается по аргументам процедуры.
     */
    @Test
    void propertiesByPlsqlName() {
        Emp e = new Emp(1, "A", null, true);
        assertThat(Values.property(e, "NID")).isEqualTo(1L);
        assertThat(Values.property(e, "SNAME")).isEqualTo("A");
        assertThat(Values.property(new Named(7), "NFOLDER")).isEqualTo(7L);
        assertThat(Values.property(Map.of("code", "c"), "SCODE")).isEqualTo("c");
        Bean b = new Bean();
        b.setCode("z");
        assertThat(Values.property(b, "P_CODE")).isEqualTo("z");
        assertThat(Values.property(null, "X")).isNull();
    }

    /**
     * Проверяет работу с XML: текст разбирается в {@code Document} и сериализуется обратно
     * без изменений (кириллица сохраняется, XML-декларация не добавляется), а документ с
     * {@code DOCTYPE} и внешней сущностью отвергается. Это защита от XXE: иначе разбор XML
     * мог бы прочитать локальный файл сервера.
     */
    @Test
    void xmlIsParsedSafely() {
        Document d = Values.parse("<a><b>Ё</b></a>");
        assertThat(d.getDocumentElement().getTagName()).isEqualTo("a");
        assertThat(Values.toText(d)).isEqualTo("<a><b>Ё</b></a>");
        assertThat(convert("<x/>", Document.class)).isInstanceOf(Document.class);
        assertThatThrownBy(() -> Values.parse("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Перечисление для проверки, что enum передаётся по имени. */
    enum Color { RED }

    /**
     * Проверяет, что enum уходит в базу своим именем ({@code RED}) и собирается обратно из
     * строки с тем же именем.
     */
    @Test
    void enumsTravelByName() {
        assertThat(Values.toText(Color.RED)).isEqualTo("RED");
        assertThat(convert("RED", Color.class)).isEqualTo(Color.RED);
    }
}
