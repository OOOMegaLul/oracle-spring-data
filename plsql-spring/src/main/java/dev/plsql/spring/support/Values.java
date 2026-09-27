package dev.plsql.spring.support;

import java.io.StringReader;
import java.io.StringWriter;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.core.CollectionFactory;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.support.DefaultConversionService;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import dev.plsql.spring.annotation.Arg;

/**
 * Преобразования между значениями Java и тем, что принимает и возвращает ojdbc (драйвер
 * JDBC для Oracle).
 *
 * <p>Три группы методов:
 * <ul>
 *   <li>Java → JDBC: числа, даты, логические значения и текст в том виде, в каком их можно
 *       привязать к позиции {@code ?} в блоке, плюс чтение свойства record, бина или
 *       {@code Map} по имени PL/SQL;</li>
 *   <li>JDBC → Java: приведение того, что вернула база, к объявленному типу метода, в том
 *       числе сборка record или бина из карты «имя PL/SQL — значение»;</li>
 *   <li>XML: безопасный разбор текста в {@link Document} и обратная сериализация.</li>
 * </ul>
 */
public final class Values {

    /** Преобразования Spring «по умолчанию» для всего, что не разобрано вручную. */
    private static final DefaultConversionService CONVERSION = new DefaultConversionService();

    /** Закрытый конструктор: класс — набор статических методов, экземпляры не создаются. */
    private Values() {
    }

    // ---------------------------------------------------------------- Java -> JDBC: значения для привязки

    /**
     * Преобразует значение из Java в число для привязки к аргументу {@code NUMBER}.
     *
     * <p>Правила:
     * <ul>
     *   <li>{@link BigDecimal} возвращается как есть;</li>
     *   <li>{@link Boolean} — {@code 1} или {@code 0};</li>
     *   <li>{@link BigInteger} — без потерь;</li>
     *   <li>{@link Double} и {@link Float} — через их десятичную запись, так что {@code 0.1} и
     *       {@code 0.1f} остаются {@code 0.1}; {@code NaN} и бесконечность в {@code NUMBER} не
     *       помещаются и дают {@link NumberFormatException};</li>
     *   <li>{@code Byte}, {@code Short}, {@code Integer}, {@code Long} и их атомарные версии —
     *       через {@code longValue()};</li>
     *   <li>любой другой {@link Number} (например, {@code DoubleAdder}) — через его десятичную
     *       запись {@code toString()}, чтобы не потерять дробную часть;</li>
     *   <li>всё остальное — через {@code toString()} без пробелов по краям, например строка
     *       {@code "12.5"}.</li>
     * </ul>
     *
     * @param v значение из Java, может быть {@code null}
     * @return число или {@code null}, если значение {@code null}
     * @throws NumberFormatException если текст значения — не число
     */
    public static BigDecimal toNumber(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Boolean b) {
            return b ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        if (v instanceof BigInteger b) {
            return new BigDecimal(b);
        }
        if (v instanceof Double d) {
            return new BigDecimal(Double.toString(d));
        }
        if (v instanceof Float f) {
            return new BigDecimal(Float.toString(f));
        }
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte
                || v instanceof java.util.concurrent.atomic.AtomicLong
                || v instanceof java.util.concurrent.atomic.AtomicInteger) {
            return BigDecimal.valueOf(((Number) v).longValue());
        }
        return new BigDecimal(v.toString().trim());
    }

    /**
     * Преобразует дату или момент времени из Java в {@link Timestamp} для привязки к
     * аргументу {@code DATE} или {@code TIMESTAMP}.
     *
     * <p>{@code DATE} в Oracle хранит не только дату, но и время с точностью до секунды,
     * поэтому и для него передаётся {@code Timestamp}. Поддерживаются {@code Timestamp}
     * (как есть), {@link LocalDateTime}, {@link LocalDate} (полночь этого дня),
     * {@link java.util.Date} (включая {@code java.sql.Date}), {@link Instant},
     * {@link OffsetDateTime} и {@link ZonedDateTime} (через момент времени, пояс не
     * сохраняется). Последние три обозначают момент, а не дату и время на часах, поэтому дата
     * и время, которые увидит база, считаются в часовом поясе JVM.
     * Других типов (например, {@code LocalTime}) здесь нет.
     *
     * @param v значение из Java, может быть {@code null}
     * @return значение для привязки или {@code null}, если значение {@code null}
     * @throws IllegalArgumentException если тип значения не поддерживается
     */
    public static Timestamp toTimestamp(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Timestamp t) {
            return t;
        }
        if (v instanceof LocalDateTime t) {
            return Timestamp.valueOf(t);
        }
        if (v instanceof LocalDate d) {
            return Timestamp.valueOf(d.atStartOfDay());
        }
        if (v instanceof java.util.Date d) {
            return new Timestamp(d.getTime());
        }
        if (v instanceof Instant i) {
            return Timestamp.from(i);
        }
        if (v instanceof OffsetDateTime o) {
            return Timestamp.from(o.toInstant());
        }
        if (v instanceof ZonedDateTime z) {
            return Timestamp.from(z.toInstant());
        }
        throw new IllegalArgumentException("Cannot bind " + v.getClass().getName() + " as DATE");
    }

    /**
     * Преобразует логическое значение из Java в число {@code 1} или {@code 0} для аргумента
     * PL/SQL {@code BOOLEAN}.
     *
     * <p>JDBC на Oracle 11.2 не умеет передавать PL/SQL {@code BOOLEAN}, поэтому значение
     * уходит числом, а сгенерированный блок превращает его в {@code TRUE} или {@code FALSE}.
     * {@link Boolean} даёт {@code 1} или {@code 0}, число — {@code 1}, если оно не ноль.
     * Строка читается по {@link #parseBoolean}.
     *
     * @param v значение из Java, может быть {@code null}
     * @return {@code 1}, {@code 0} или {@code null}, если значение {@code null}
     * @throws IllegalArgumentException если строку нельзя прочитать как логическое значение
     */
    public static Integer toBooleanNumber(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (v instanceof Number n) {
            return n.intValue() != 0 ? 1 : 0;
        }
        return parseBoolean(v.toString()) ? 1 : 0;
    }

    /**
     * Читает логическое значение из строки одинаково в обе стороны (в базу и из базы).
     *
     * <p>Без учёта регистра и пробелов по краям: {@code Y}, {@code YES}, {@code T},
     * {@code TRUE}, {@code 1} — истина; {@code N}, {@code NO}, {@code F}, {@code FALSE},
     * {@code 0} — ложь. Остальное — ошибка, а не молчаливая «ложь»: опечатка в флаге иначе
     * незаметно поменяла бы смысл.
     *
     * @param s строка
     * @return логическое значение
     * @throws IllegalArgumentException если строка не из списка выше
     */
    public static boolean parseBoolean(String s) {
        return switch (s.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "Y", "YES", "T", "TRUE", "1" -> true;
            case "N", "NO", "F", "FALSE", "0" -> false;
            default -> throw new IllegalArgumentException("cannot read '" + s + "' as a boolean;"
                    + " expected Y/N, TRUE/FALSE or 1/0");
        };
    }

    /**
     * Преобразует значение в текст для привязки к {@code VARCHAR2}, {@code CLOB} или
     * {@code XMLTYPE}: перечисления — по имени, узлы DOM — сериализацией в XML.
     *
     * <p>Для перечисления берётся {@code name()}, а не {@code toString()}, чтобы
     * переопределённый {@code toString()} не менял то, что уходит в базу. Остальные
     * значения превращаются в текст через {@code toString()}.
     *
     * @param v значение из Java, может быть {@code null}
     * @return текст или {@code null}, если значение {@code null}
     * @throws IllegalArgumentException если узел DOM не удалось сериализовать
     */
    public static String toText(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Enum<?> e) {
            return e.name();
        }
        if (v instanceof Node n) {
            return serialize(n);
        }
        return v.toString();
    }

    /**
     * Методы доступа к свойствам, найденные сопоставлением имён, по классу и имени PL/SQL:
     * сопоставление выполняется один раз, а не при каждом вызове.
     *
     * <p>Используется {@link ClassValue}, а не карта с ключом {@code Class}: {@code ClassValue}
     * привязывает значение к самому классу и не мешает выгрузить его, поэтому классы
     * приложения после повторного развёртывания могут выгружаться.
     */
    private static final ClassValue<Map<String, Accessor>> ACCESSORS = new ClassValue<>() {
        /**
         * Создаёт пустой кэш методов доступа при первом обращении к классу.
         *
         * @param type класс, для которого нужен кэш
         * @return пустая потокобезопасная карта «имя PL/SQL — метод доступа»
         */
        @Override
        protected Map<String, Accessor> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    /**
     * Способ прочитать одно свойство объекта: метод доступа компонента record или
     * getter бина, найденный по имени PL/SQL.
     */
    @FunctionalInterface
    private interface Accessor {
        /**
         * Читает свойство из объекта.
         *
         * @param source объект, из которого читается свойство
         * @return значение свойства, может быть {@code null}
         * @throws ReflectiveOperationException если метод доступа нельзя вызвать или он сам
         *                                      выбросил исключение
         */
        Object get(Object source) throws ReflectiveOperationException;
    }

    /**
     * Метод доступа для имени, которому ничего не подошло: всегда возвращает {@code null}.
     * Кэшируется так же, как найденные, чтобы не искать заново при каждом вызове.
     */
    private static final Accessor ABSENT = s -> null;

    /**
     * Читает свойство record, бина или {@code Map} по имени PL/SQL.
     *
     * <p>Имя сопоставляется так же, как имена параметров с аргументами (см.
     * {@link NameMatcher}): {@code code} подходит к {@code SCODE} и {@code P_CODE}. У
     * компонента record с {@link Arg} сравнивается имя из аннотации, только без учёта
     * регистра. У {@code Map} ключ подходит, если он совпадает без учёта регистра или
     * сопоставляется по тем же правилам; берётся первый подошедший ключ. Если ничего не
     * подошло, возвращается {@code null}, без ошибки.
     *
     * <p>Найденный метод доступа запоминается для пары «класс — имя PL/SQL» (см.
     * {@code ACCESSORS}).
     *
     * @param source    record, бин или {@code Map}; может быть {@code null}
     * @param plsqlName имя аргумента, поля записи или атрибута PL/SQL, например {@code NRN}
     * @return значение свойства или {@code null}, если объекта или свойства нет
     * @throws IllegalStateException если метод доступа не удалось вызвать или он выбросил
     *                               исключение
     */
    public static Object property(Object source, String plsqlName) {
        if (source == null) {
            return null;
        }
        if (source instanceof Map<?, ?> m) {
            for (Object k : m.keySet()) {
                if (k.toString().equalsIgnoreCase(plsqlName) || matches(k.toString(), plsqlName)) {
                    return m.get(k);
                }
            }
            return null;
        }
        Accessor accessor = ACCESSORS.get(source.getClass()).computeIfAbsent(plsqlName, n -> accessor(source.getClass(), n));
        try {
            return accessor.get(source);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read " + plsqlName + " from " + source.getClass().getName(), e);
        }
    }

    /**
     * Ищет в классе метод доступа, который соответствует имени PL/SQL.
     *
     * <p>У record перебираются компоненты, у бина — читаемые свойства, кроме {@code class}.
     * Если на компоненте (у бина — на поле, getter'е или setter'е) есть {@link Arg}, его значение
     * сравнивается с именем без учёта регистра, иначе имя сопоставляется по правилам
     * {@link NameMatcher}. Найденный метод делается доступным через {@code setAccessible(true)},
     * чтобы работали и непубличные классы.
     *
     * @param type      класс record или бина
     * @param plsqlName имя PL/SQL, которое нужно найти
     * @return метод доступа или {@code ABSENT}, если ничего не подошло
     */
    private static Accessor accessor(Class<?> type, String plsqlName) {
        if (type.isRecord()) {
            for (RecordComponent rc : type.getRecordComponents()) {
                Arg a = rc.getAnnotation(Arg.class);
                if (a != null ? a.value().equalsIgnoreCase(plsqlName) : matches(rc.getName(), plsqlName)) {
                    java.lang.reflect.Method m = rc.getAccessor();
                    m.setAccessible(true);
                    return m::invoke;
                }
            }
            return ABSENT;
        }
        for (var pd : BeanUtils.getPropertyDescriptors(type)) {
            if (pd.getReadMethod() == null || pd.getName().equals("class")) {
                continue;
            }
            Arg a = argOf(type, pd);
            if (a != null ? a.value().equalsIgnoreCase(plsqlName) : matches(pd.getName(), plsqlName)) {
                java.lang.reflect.Method m = pd.getReadMethod();
                m.setAccessible(true);
                return m::invoke;
            }
        }
        return ABSENT;
    }

    /**
     * Проверяет, есть ли у класса свойство для имени PL/SQL — то же, что прочитает
     * {@link #property}. Нужна для проверок при старте.
     *
     * @param type      класс record или бина
     * @param plsqlName имя поля записи или атрибута PL/SQL
     * @return {@code true}, если свойство нашлось
     */
    public static boolean hasProperty(Class<?> type, String plsqlName) {
        return ACCESSORS.get(type).computeIfAbsent(plsqlName, n -> accessor(type, n)) != ABSENT;
    }

    /**
     * Находит {@link Arg} свойства бина: на поле с тем же именем (в классе или его предках), на
     * getter'е или на setter'е.
     *
     * @param type класс бина
     * @param pd   описание свойства
     * @return аннотация или {@code null}
     */
    public static Arg argOf(Class<?> type, PropertyDescriptor pd) {
        for (Method m : new Method[]{pd.getReadMethod(), pd.getWriteMethod()}) {
            if (m != null && m.isAnnotationPresent(Arg.class)) {
                return m.getAnnotation(Arg.class);
            }
        }
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(pd.getName());
                return f.getAnnotation(Arg.class);
            } catch (NoSuchFieldException e) {
                // поле может лежать в предке или отсутствовать вовсе
            }
        }
        return null;
    }

    /**
     * Проверяет, подходит ли имя Java к имени PL/SQL по правилам {@link NameMatcher}:
     * совпадение без учёта регистра и подчёркиваний или после отбрасывания префикса
     * ({@code P_}, {@code V_}, однобуквенного префикса типа и т.п.).
     *
     * @param javaName  имя в Java, например {@code beginDate}
     * @param plsqlName имя в PL/SQL, например {@code DBEGIN_DATE}
     * @return {@code true}, если имена соответствуют друг другу
     */
    private static boolean matches(String javaName, String plsqlName) {
        return NameMatcher.find(javaName, List.of(plsqlName), x -> x) != null;
    }

    // ---------------------------------------------------------------- JDBC -> Java: результат вызова

    /**
     * Приводит то, что вернула база, к объявленному типу Java.
     *
     * <p>Правила, в порядке проверки:
     * <ul>
     *   <li>{@code Optional<X>} — значение приводится к {@code X} и заворачивается в
     *       {@code Optional};</li>
     *   <li>{@code null} для примитивного типа становится значением по умолчанию
     *       ({@code 0}, {@code false}), иначе остаётся {@code null};</li>
     *   <li>{@link Clob} сначала читается в строку и освобождается;</li>
     *   <li>если нужен {@code Object} или значение уже нужного типа (кроме коллекций и карт,
     *       у которых надо привести элементы), оно возвращается как есть;</li>
     *   <li>коллекция: каждый элемент приводится к типу элемента; результат — коллекция
     *       объявленного вида ({@code List} — {@link ArrayList}, {@code Set} —
     *       {@code LinkedHashSet}, {@code SortedSet} — {@code TreeSet}, конкретный класс —
     *       он сам); одиночное значение становится коллекцией из одного элемента;</li>
     *   <li>{@code Map} (запись, объект, строка курсора или все выходы вызова) копируется в
     *       новую карту либо превращается в record или бин через {@link #construct};</li>
     *   <li>строка для {@link Document} или {@link Node} разбирается как XML;</li>
     *   <li>{@link Timestamp} превращается в {@link LocalDate}, {@link LocalDateTime} или
     *       {@link Instant};</li>
     *   <li>для {@code boolean} число даёт {@code true}, если оно не ноль, а строка читается
     *       по {@link #parseBoolean};</li>
     *   <li>для перечисления строка ищется среди имён констант;</li>
     *   <li>всё остальное (например, {@link BigDecimal} в {@code long}) преобразует
     *       {@link DefaultConversionService} Spring.</li>
     * </ul>
     *
     * @param v      значение из базы, может быть {@code null}
     * @param target объявленный тип Java, включая параметры типа ({@code List<Employee>})
     * @return значение нужного типа или {@code null}
     * @throws IllegalArgumentException если строку нельзя разобрать как XML или в
     *                                  перечислении нет константы с таким именем
     * @throws IllegalStateException если CLOB не удалось прочитать или record не удалось
     *                               создать
     * @throws org.springframework.core.convert.ConversionException если Spring не умеет
     *                               преобразовать значение в нужный тип
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object convert(Object v, ResolvableType target) {
        Class<?> raw = target.resolve(Object.class);
        if (raw == Optional.class) {
            return Optional.ofNullable(convert(v, target.getGeneric(0)));
        }
        if (v == null) {
            return raw.isPrimitive() ? primitiveDefault(raw) : null;
        }
        if (v instanceof Clob c) {
            v = clobToString(c);
        }
        if (raw == Object.class || (raw.isInstance(v) && !(v instanceof Collection) && !(v instanceof Map))) {
            return v;
        }
        if (Collection.class.isAssignableFrom(raw)) {
            ResolvableType el = target.asCollection().getGeneric(0);
            Collection<Object> out = raw == Collection.class || raw == List.class || raw == Iterable.class
                    ? new ArrayList<>() : CollectionFactory.createCollection(raw, el.resolve(), 16);
            for (Object o : asIterable(v)) {
                out.add(convert(o, el));
            }
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            if (Map.class.isAssignableFrom(raw)) {
                return new LinkedHashMap<>(m);
            }
            return construct(raw, (Map<String, Object>) m);
        }
        if (v instanceof String s && (raw == Document.class || raw == Node.class)) {
            return parse(s);
        }
        if (v instanceof Timestamp t) {
            if (raw == LocalDate.class) {
                return t.toLocalDateTime().toLocalDate();
            }
            if (raw == LocalDateTime.class) {
                return t.toLocalDateTime();
            }
            if (raw == Instant.class) {
                return t.toInstant();
            }
            if (raw == OffsetDateTime.class) {
                return t.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
            }
            if (raw == ZonedDateTime.class) {
                return t.toInstant().atZone(ZoneId.systemDefault());
            }
        }
        if ((raw == Boolean.class || raw == boolean.class) && v instanceof Number n) {
            return n.intValue() != 0;
        }
        if ((raw == Boolean.class || raw == boolean.class) && v instanceof String s) {
            return parseBoolean(s);
        }
        if (raw.isEnum() && v instanceof String s) {
            return Enum.valueOf((Class) raw, s);
        }
        return CONVERSION.convert(v, raw);
    }

    /**
     * Создаёт record или бин из карты, ключи которой — имена PL/SQL.
     *
     * <p>Для record каждому компоненту ищется ключ (с {@link Arg} — по имени из аннотации
     * без учёта регистра, иначе по правилам {@link NameMatcher}), значение приводится к
     * типу компонента через {@link #convert}, и вызывается канонический конструктор.
     * Компонент без ключа получает {@code null} (или значение по умолчанию для примитива).
     *
     * <p>Бин создаётся конструктором без аргументов, после чего заполняются свойства с
     * setter'ом, для которых нашёлся ключ (с учётом {@link Arg} на поле, getter'е или
     * setter'е); остальные свойства не трогаются. Ключи, которым не нашлось места, молча
     * пропускаются.
     *
     * @param type   класс record или бина
     * @param values значения по именам PL/SQL, например {@code {NRN=1, SNAME=...}}
     * @return новый объект
     * @throws IllegalStateException если record не удалось создать или к одному имени Java
     *                               одинаково подходят два ключа
     */
    public static Object construct(Class<?> type, Map<String, Object> values) {
        List<String> keys = new ArrayList<>(values.keySet());
        Object[] byKey = new Object[keys.size()];
        for (int i = 0; i < byKey.length; i++) {
            byKey[i] = values.get(keys.get(i));
        }
        return Binder.of(type, keys).build(byKey);
    }

    /**
     * Готовый план сборки record или бина из значений по именам PL/SQL (полей записи,
     * OUT-аргументов, колонок курсора): для каждого компонента record или свойства бина заранее
     * найдено, какое имя ему достаётся.
     *
     * <p>План строится один раз на набор имён и применяется к любому числу строк: так строки
     * курсора не сопоставляют имена заново каждая.
     */
    static final class Binder {

        /** Класс, который собирается. */
        private final Class<?> type;
        /** Канонический конструктор record или {@code null} у бина. */
        private final Constructor<?> constructor;
        /** Имена компонентов record или свойств бина, по порядку. */
        private final String[] names;
        /** Полные типы компонентов или свойств, для приведения значений. */
        private final ResolvableType[] types;
        /** Для каждого компонента — номер имени среди ключей или -1, если ключа нет. */
        private final int[] keyIndex;

        /**
         * Запоминает готовый план.
         *
         * @param type        класс, который собирается
         * @param constructor канонический конструктор record или {@code null} у бина
         * @param names       имена компонентов или свойств
         * @param types       их полные типы
         * @param keyIndex    номер ключа для каждого из них или -1
         */
        private Binder(Class<?> type, Constructor<?> constructor, String[] names, ResolvableType[] types,
                       int[] keyIndex) {
            this.type = type;
            this.constructor = constructor;
            this.names = names;
            this.types = types;
            this.keyIndex = keyIndex;
        }

        /**
         * Строит план для класса и набора имён.
         *
         * <p>Компонент с {@link Arg} получает ключ, равный значению аннотации без учёта
         * регистра; без аннотации — ключ, подходящий по правилам {@link NameMatcher}. Если одно
         * имя встречается среди ключей несколько раз, берётся первое.
         *
         * @param type класс record или бина (у бина нужен конструктор без аргументов)
         * @param keys имена PL/SQL в том порядке, в каком потом придут значения
         * @return план сборки
         * @throws IllegalStateException если к одному компоненту одинаково подходят два имени
         */
        static Binder of(Class<?> type, List<String> keys) {
            List<String> names = new ArrayList<>();
            List<ResolvableType> types = new ArrayList<>();
            List<Arg> args = new ArrayList<>();
            Constructor<?> ctor = null;
            if (type.isRecord()) {
                RecordComponent[] comps = type.getRecordComponents();
                Class<?>[] raw = new Class<?>[comps.length];
                for (int i = 0; i < comps.length; i++) {
                    names.add(comps[i].getName());
                    types.add(ResolvableType.forType(comps[i].getGenericType()));
                    args.add(comps[i].getAnnotation(Arg.class));
                    raw[i] = comps[i].getType();
                }
                try {
                    ctor = type.getDeclaredConstructor(raw);
                    ctor.setAccessible(true);
                } catch (NoSuchMethodException e) {
                    throw new IllegalStateException("Cannot create " + type.getName(), e);
                }
            } else {
                for (PropertyDescriptor pd : BeanUtils.getPropertyDescriptors(type)) {
                    if (pd.getWriteMethod() != null) {
                        names.add(pd.getName());
                        types.add(ResolvableType.forMethodParameter(pd.getWriteMethod(), 0));
                        args.add(argOf(type, pd));
                    }
                }
            }
            // Повторяющееся имя (две колонки ID в соединении таблиц) — не неоднозначность:
            // достаётся первое, как у JDBC.
            List<String> distinct = List.copyOf(new java.util.LinkedHashSet<>(keys));
            int[] index = new int[names.size()];
            for (int i = 0; i < index.length; i++) {
                String key = keyFor(type, names.get(i), args.get(i), distinct);
                index[i] = key == null ? -1 : keys.indexOf(key);
            }
            return new Binder(type, ctor, names.toArray(String[]::new), types.toArray(ResolvableType[]::new), index);
        }

        /**
         * Собирает объект из значений, пришедших в порядке ключей плана.
         *
         * @param byKey значения по номерам ключей
         * @return новый record или бин
         * @throws IllegalStateException если record не удалось создать
         */
        Object build(Object[] byKey) {
            if (constructor != null) {
                Object[] args = new Object[names.length];
                for (int i = 0; i < args.length; i++) {
                    args[i] = convert(keyIndex[i] < 0 ? null : byKey[keyIndex[i]], types[i]);
                }
                try {
                    return constructor.newInstance(args);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Cannot create " + type.getName(), e);
                }
            }
            Object bean = BeanUtils.instantiateClass(type);
            BeanWrapper bw = PropertyAccessorFactory.forBeanPropertyAccess(bean);
            for (int i = 0; i < names.length; i++) {
                if (keyIndex[i] >= 0) {
                    bw.setPropertyValue(names[i], convert(byKey[keyIndex[i]], types[i]));
                }
            }
            return bean;
        }

        /**
         * Возвращает тип компонента, которому достался ключ с этим номером; нужен, чтобы строки
         * курсора читали только нужные колонки и сразу в нужном типе (так
         * {@code TIMESTAMP WITH TIME ZONE} приходит в {@code OffsetDateTime} со своим смещением, а
         * не через часовой пояс JVM).
         *
         * @param key номер ключа
         * @return класс компонента или {@code null}, если ключ никому не достался
         */
        Class<?> typeOf(int key) {
            for (int i = 0; i < keyIndex.length; i++) {
                if (keyIndex[i] == key) {
                    return types[i].resolve(Object.class);
                }
            }
            return null;
        }
    }

    /**
     * Находит среди ключей тот, что соответствует компоненту или свойству Java.
     *
     * <p>С аннотацией {@link Arg} берётся первый ключ, равный её значению без учёта
     * регистра; без неё — ключ, подходящий по правилам {@link NameMatcher}.
     *
     * @param type     класс, чей это компонент (для сообщения об ошибке)
     * @param javaName имя компонента record или свойства бина
     * @param arg      аннотация {@link Arg} или {@code null}
     * @param keys     имена PL/SQL, из которых выбирать
     * @return подходящий ключ или {@code null}, если такого нет
     * @throws IllegalStateException если без аннотации одинаково подходят два ключа
     */
    private static String keyFor(Class<?> type, String javaName, Arg arg, List<String> keys) {
        if (arg != null) {
            return keys.stream().filter(k -> k.equalsIgnoreCase(arg.value())).findFirst().orElse(null);
        }
        try {
            return NameMatcher.find(javaName, keys, k -> k);
        } catch (IllegalStateException e) {
            throw new IllegalStateException(type.getSimpleName() + "." + javaName + ": " + e.getMessage()
                    + (type.isRecord() ? " on the record component" : " on the field or getter"), e);
        }
    }

    /**
     * Читает {@link Clob} целиком в строку и освобождает его.
     *
     * <p>Позиции в LOB считаются с единицы; пустой {@code CLOB} даёт пустую строку. После
     * чтения вызывается {@code free()}: если это временный LOB, который вернула процедура,
     * он иначе оставался бы во временном табличном пространстве сессии до закрытия
     * соединения. Освобождается он и тогда, когда прочитать не удалось.
     *
     * @param c значение {@code CLOB} из драйвера
     * @return содержимое CLOB
     * @throws IllegalStateException с исходным {@link SQLException} внутри, если CLOB не
     *                               удалось прочитать или освободить
     */
    public static String clobToString(Clob c) {
        try {
            try {
                long len = c.length();
                return len == 0 ? "" : c.getSubString(1, (int) len);
            } finally {
                c.free();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- XML

    /**
     * Разбирает текст XML в {@link Document} с отключёнными внешними сущностями и DTD
     * (защита от XXE).
     *
     * <p>XXE (XML External Entity) — атака, при которой документ с объявлением
     * {@code DOCTYPE} заставляет парсер прочитать локальный файл или сходить по сети.
     * Поэтому любой {@code DOCTYPE} здесь запрещён, XInclude и раскрытие ссылок на
     * сущности выключены, включён режим безопасной обработки. Разбор учитывает
     * пространства имён.
     *
     * @param xml текст XML
     * @return разобранный документ
     * @throws IllegalArgumentException если текст — не корректный XML или содержит
     *                                  {@code DOCTYPE}
     * @throws IllegalStateException    если XML-парсер на classpath не умеет запрещать
     *                                  {@code DOCTYPE}: разбирать им небезопасно
     */
    public static Document parse(String xml) {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        try {
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
        } catch (Exception e) {
            throw new IllegalStateException("the XML parser " + f.getClass().getName()
                    + " cannot be configured to reject DOCTYPE, so it cannot parse safely: " + e.getMessage(), e);
        }
        try {
            return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Not well-formed XML: " + e.getMessage(), e);
        }
    }

    /**
     * Сериализует узел DOM в текст XML без объявления {@code <?xml ...?>} в начале.
     *
     * @param node документ или другой узел DOM
     * @return текст XML
     * @throws IllegalArgumentException если узел не удалось сериализовать
     */
    public static String serialize(Node node) {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            var t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter w = new StringWriter();
            t.transform(new DOMSource(node), new StreamResult(w));
            return w.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot serialise XML node: " + e.getMessage(), e);
        }
    }

    /**
     * Представляет значение как последовательность элементов для приведения коллекции.
     *
     * <p>{@link Iterable} возвращается как есть, любой массив (в том числе массив
     * примитивов) копируется в список через {@link Array java.lang.reflect.Array}, а
     * одиночное значение становится списком из одного элемента.
     *
     * @param v значение, не {@code null}
     * @return элементы значения
     */
    private static Iterable<?> asIterable(Object v) {
        if (v instanceof Iterable<?> it) {
            return it;
        }
        if (v.getClass().isArray()) {
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < Array.getLength(v); i++) {
                list.add(Array.get(v, i));
            }
            return list;
        }
        return List.of(v);
    }

    /**
     * Возвращает значение по умолчанию для примитивного типа, когда из базы пришёл
     * {@code NULL}: в примитив нельзя положить {@code null}.
     *
     * <p>{@code boolean} — {@code false}, {@code char} — {@code '\0'}, {@code void} —
     * {@code null}, числовые типы — ноль нужного типа.
     *
     * @param c примитивный тип
     * @return значение по умолчанию (упакованное) или {@code null} для {@code void}
     */
    private static Object primitiveDefault(Class<?> c) {
        if (c == boolean.class) {
            return false;
        }
        if (c == void.class) {
            return null;
        }
        if (c == char.class) {
            return '\0';
        }
        return CONVERSION.convert(0, c);
    }
}
