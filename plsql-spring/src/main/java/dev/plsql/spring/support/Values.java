package dev.plsql.spring.support;

import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
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
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.support.DefaultConversionService;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import dev.plsql.spring.annotation.Arg;

/**
 * Conversions between Java values and what ojdbc hands back.
 */
public final class Values {

    private static final DefaultConversionService CONVERSION = new DefaultConversionService();

    private Values() {
    }

    // ---------------------------------------------------------------- Java -> JDBC

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
        if (v instanceof Double || v instanceof Float) {
            return BigDecimal.valueOf(((Number) v).doubleValue());
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.longValue());
        }
        return new BigDecimal(v.toString().trim());
    }

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
        throw new IllegalArgumentException("Cannot bind " + v.getClass().getName() + " as DATE");
    }

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
        String s = v.toString().trim().toUpperCase();
        return s.equals("Y") || s.equals("TRUE") || s.equals("1") ? 1 : 0;
    }

    /** Text for a VARCHAR2 / CLOB / XMLTYPE bind: enums by name, DOM nodes serialised. */
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
     * Accessors found by name matching, per class and PL/SQL name: the matching runs once,
     * not per call. ClassValue, not a map keyed by Class, so redeployed classes can unload.
     */
    private static final ClassValue<Map<String, Accessor>> ACCESSORS = new ClassValue<>() {
        @Override
        protected Map<String, Accessor> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    @FunctionalInterface
    private interface Accessor {
        Object get(Object source) throws ReflectiveOperationException;
    }

    private static final Accessor ABSENT = s -> null;

    /** Reads a property of a record, bean or map by PL/SQL name. */
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
            if (pd.getReadMethod() != null && !pd.getName().equals("class") && matches(pd.getName(), plsqlName)) {
                java.lang.reflect.Method m = pd.getReadMethod();
                m.setAccessible(true);
                return m::invoke;
            }
        }
        return ABSENT;
    }

    private static boolean matches(String javaName, String plsqlName) {
        return NameMatcher.find(javaName, List.of(plsqlName), x -> x) != null;
    }

    // ---------------------------------------------------------------- JDBC -> Java

    /** Converts what came back from the database to the declared Java type. */
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
            Collection<Object> out = new ArrayList<>();
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
        }
        if ((raw == Boolean.class || raw == boolean.class) && v instanceof Number n) {
            return n.intValue() != 0;
        }
        if ((raw == Boolean.class || raw == boolean.class) && v instanceof String s) {
            return s.equalsIgnoreCase("Y") || s.equals("1") || s.equalsIgnoreCase("TRUE");
        }
        if (raw.isEnum() && v instanceof String s) {
            return Enum.valueOf((Class) raw, s);
        }
        return CONVERSION.convert(v, raw);
    }

    /** Builds a record or bean from a map whose keys are PL/SQL names. */
    public static Object construct(Class<?> type, Map<String, Object> values) {
        List<String> keys = new ArrayList<>(values.keySet());
        if (type.isRecord()) {
            RecordComponent[] comps = type.getRecordComponents();
            Object[] args = new Object[comps.length];
            Class<?>[] types = new Class<?>[comps.length];
            for (int i = 0; i < comps.length; i++) {
                String key = keyFor(comps[i].getName(), comps[i].getAnnotation(Arg.class), keys);
                args[i] = convert(key == null ? null : values.get(key), ResolvableType.forType(comps[i].getGenericType()));
                types[i] = comps[i].getType();
            }
            try {
                Constructor<?> c = type.getDeclaredConstructor(types);
                c.setAccessible(true);
                return c.newInstance(args);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot create " + type.getName(), e);
            }
        }
        Object bean = BeanUtils.instantiateClass(type);
        BeanWrapper bw = PropertyAccessorFactory.forBeanPropertyAccess(bean);
        for (var pd : bw.getPropertyDescriptors()) {
            if (pd.getWriteMethod() == null) {
                continue;
            }
            String key = keyFor(pd.getName(), null, keys);
            if (key != null) {
                bw.setPropertyValue(pd.getName(), convert(values.get(key),
                        ResolvableType.forMethodParameter(pd.getWriteMethod(), 0)));
            }
        }
        return bean;
    }

    private static String keyFor(String javaName, Arg arg, List<String> keys) {
        if (arg != null) {
            return keys.stream().filter(k -> k.equalsIgnoreCase(arg.value())).findFirst().orElse(null);
        }
        return NameMatcher.find(javaName, keys, k -> k);
    }

    public static String clobToString(Clob c) {
        try {
            long len = c.length();
            String s = len == 0 ? "" : c.getSubString(1, (int) len);
            c.free();
            return s;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- XML

    /** Parses XML text with external entities and DTDs switched off (no XXE). */
    public static Document parse(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Not well-formed XML: " + e.getMessage(), e);
        }
    }

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
