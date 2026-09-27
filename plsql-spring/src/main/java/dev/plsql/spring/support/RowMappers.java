package dev.plsql.spring.support;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.BeanUtils;
import org.springframework.core.ResolvableType;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SingleColumnRowMapper;
import org.springframework.jdbc.support.JdbcUtils;

/**
 * Превращает строки результата запроса в объекты Java: record и бины — по имени колонки, по тем
 * же правилам, что и аргументы ({@code BEGIN_DATE} → {@code beginDate}, {@code SNAME} →
 * {@code name}, {@code @Arg("NRN")}), единственную колонку — в простой тип, всё остальное — в
 * {@code Map}.
 *
 * <p>Строки приходят из {@link ResultSet} — курсора JDBC по результату запроса; это и
 * результат {@code @SqlQuery}, и выходной {@code REF CURSOR} процедуры. {@link RowMapper} —
 * интерфейс Spring, который превращает текущую строку {@code ResultSet} в один объект.
 */
public final class RowMappers {

    /** Карта «колонка — значение» не хранит состояния, один экземпляр на всех. */
    private static final ColumnMapRowMapper COLUMN_MAP = new ColumnMapRowMapper();

    /**
     * {@link DataClassRowMapper} для классов без конструктора без аргументов, по одному на класс:
     * разбор конструктора класса делается один раз. {@link ClassValue}, а не карта: значение
     * привязано к самому классу и не мешает выгрузить его при переразвёртывании.
     */
    private static final ClassValue<RowMapper<?>> DATA_CLASS = new ClassValue<>() {
        /**
         * Создаёт преобразователь Spring для класса при первом обращении.
         *
         * @param type класс строки
         * @return {@link DataClassRowMapper} этого класса
         */
        @Override
        protected RowMapper<?> computeValue(Class<?> type) {
            return new DataClassRowMapper<>(type);
        }
    };

    /** Закрытый конструктор: класс — набор статических методов, экземпляры не создаются. */
    private RowMappers() {
    }

    /**
     * Выбирает {@link RowMapper} для типа одной строки.
     *
     * <ul>
     *   <li>{@code Map} или {@code Object} — {@link ColumnMapRowMapper}: карта «имя колонки —
     *       значение»;</li>
     *   <li>простой тип ({@code String}, число, дата, перечисление и т.п., по
     *       {@code BeanUtils.isSimpleValueType}) или примитив — {@link SingleColumnRowMapper}:
     *       значение единственной колонки строки;</li>
     *   <li>record или бин с конструктором без аргументов — по именам колонок, как аргументы
     *       процедуры: с {@code @Arg}, без учёта регистра, подчёркиваний и типовых префиксов;
     *       колонки сопоставляются один раз на результат запроса;</li>
     *   <li>иной класс (только конструктор с аргументами) — {@link DataClassRowMapper} Spring;
     *       {@code @Arg} и префиксы он не понимает.</li>
     * </ul>
     *
     * @param type тип одной строки
     * @return преобразователь строк для этого типа; его можно использовать из нескольких потоков
     */
    public static RowMapper<?> forType(Class<?> type) {
        if (Map.class.isAssignableFrom(type) || type == Object.class) {
            return COLUMN_MAP;
        }
        if (BeanUtils.isSimpleValueType(type) || type.isPrimitive()) {
            return new SingleColumnRowMapper<>(type);
        }
        if (type.isRecord() || hasDefaultConstructor(type)) {
            return new ByName(type);
        }
        return DATA_CLASS.get(type);
    }

    /**
     * Читает все оставшиеся строки {@link ResultSet} и превращает каждую в объект типа
     * {@code element}.
     *
     * <p>Если тип не удаётся определить (например, {@code List} без параметра типа), строки
     * становятся {@code Map}. Колонки сопоставляются с компонентами один раз, по первой строке.
     * {@code ResultSet} здесь не закрывается — это делает вызывающий код.
     *
     * @param rs      открытый результат запроса или курсора
     * @param element тип одной строки
     * @return список объектов в порядке строк; пустой, если строк нет
     * @throws SQLException если драйвер не смог прочитать строку
     */
    public static List<Object> mapAll(ResultSet rs, ResolvableType element) throws SQLException {
        RowMapper<?> mapper = forType(element.resolve(Map.class));
        if (mapper instanceof ByName byName) {
            mapper = byName.bound(rs.getMetaData());
        }
        List<Object> rows = new ArrayList<>();
        int n = 0;
        while (rs.next()) {
            rows.add(mapper.mapRow(rs, n++));
        }
        return rows;
    }

    /**
     * Проверяет, есть ли у класса конструктор без аргументов (нужен, чтобы создать бин).
     *
     * @param type класс
     * @return {@code true}, если такой конструктор объявлен
     */
    private static boolean hasDefaultConstructor(Class<?> type) {
        try {
            type.getDeclaredConstructor();
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Собирает record или бин из строки по именам колонок через тот же план сборки, что и
     * записи PL/SQL ({@link Values.Binder}).
     *
     * <p>Сам по себе объект не хранит состояния: план строится по списку колонок строки и
     * запоминается для этого списка, так что один экземпляр можно звать из нескольких потоков и
     * для разных запросов. {@link #bound} даёт вариант, который не смотрит на список колонок у
     * каждой строки.
     */
    private static final class ByName implements RowMapper<Object> {

        /** Тип одной строки. */
        private final Class<?> type;
        /** Готовые планы сборки по спискам колонок. */
        private final Map<List<String>, Values.Binder> plans = new ConcurrentHashMap<>();

        /**
         * Создаёт преобразователь для типа строки.
         *
         * @param type record или бин
         */
        ByName(Class<?> type) {
            this.type = type;
        }

        /**
         * Собирает объект из текущей строки, находя план по списку колонок.
         *
         * @param rs     результат запроса, стоящий на строке
         * @param rowNum номер строки, с нуля
         * @return объект строки
         * @throws SQLException если драйвер не смог прочитать строку
         */
        @Override
        public Object mapRow(ResultSet rs, int rowNum) throws SQLException {
            return bound(rs.getMetaData()).mapRow(rs, rowNum);
        }

        /**
         * Возвращает преобразователь для результата с этими колонками.
         *
         * @param md описание колонок результата
         * @return преобразователь, который не смотрит на колонки заново
         * @throws SQLException если драйвер не отдал описание колонок
         */
        RowMapper<Object> bound(ResultSetMetaData md) throws SQLException {
            List<String> labels = new ArrayList<>(md.getColumnCount());
            for (int i = 1; i <= md.getColumnCount(); i++) {
                labels.add(JdbcUtils.lookupColumnName(md, i));
            }
            Values.Binder binder = plans.computeIfAbsent(labels, l -> Values.Binder.of(type, l));
            Class<?>[] wanted = new Class<?>[labels.size()];
            for (int i = 0; i < wanted.length; i++) {
                wanted[i] = binder.typeOf(i);
            }
            return (rs, rowNum) -> {
                Object[] values = new Object[wanted.length];
                for (int i = 0; i < wanted.length; i++) {
                    if (wanted[i] != null) {
                        // Колонка читается в типе компонента, как у DataClassRowMapper: драйвер сам
                        // отдаёт OffsetDateTime со смещением, LocalDate без часового пояса и т.п.
                        values[i] = JdbcUtils.getResultSetValue(rs, i + 1, wanted[i]);
                    }
                }
                return binder.build(values);
            };
        }
    }
}
