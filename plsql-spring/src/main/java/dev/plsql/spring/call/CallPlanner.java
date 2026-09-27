package dev.plsql.spring.call;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.BeanUtils;
import org.springframework.core.ResolvableType;
import org.springframework.util.ClassUtils;

import dev.plsql.spring.annotation.Arg;
import dev.plsql.spring.annotation.Procedure;
import dev.plsql.spring.meta.ArgKind;
import dev.plsql.spring.meta.ArgumentInfo;
import dev.plsql.spring.meta.SubprogramInfo;
import dev.plsql.spring.support.NameMatcher;
import dev.plsql.spring.support.Values;

/**
 * Превращает Java-метод и сигнатуру подпрограммы PL/SQL в {@link CallPlan} — готовый план
 * вызова.
 *
 * <p>Сигнатура (процедура или функция, её аргументы и их типы) берётся из словаря Oracle,
 * из представления {@code ALL_ARGUMENTS}. Планировщик сопоставляет параметры Java-метода с
 * аргументами, решает, откуда взять значения остальных аргументов, и генерирует текст
 * вызова. Всё это происходит один раз при старте приложения; если метод и сигнатура не
 * подходят друг к другу, бросается {@link PlanException} и старт останавливается.
 *
 * <p>Вызов всегда оформляется как анонимный блок PL/SQL (безымянный фрагмент
 * {@code DECLARE ... BEGIN ... END;}, который база выполняет как один оператор) с именованной
 * нотацией:
 * <pre>{@code
 * DECLARE
 *   v1 BOOLEAN;                               -- здесь живут типы, которые есть только в PL/SQL
 * BEGIN
 *   v1 := CASE ? WHEN 1 THEN TRUE WHEN 0 THEN FALSE END;
 *   ? := APP.PKG.F(NTENANT => ?, BFLAG => v1);
 *   ? := CASE WHEN v1 THEN 1 WHEN NOT v1 THEN 0 END;
 * END;
 * }</pre>
 * Каждый {@code ?} — плейсхолдер привязки: на его место JDBC подставляет значение из Java
 * или читает из него результат. В примере это по порядку: входное значение {@code BFLAG},
 * результат функции, {@code NTENANT} и выходное значение {@code BFLAG}.
 *
 * <p>Именованная нотация ({@code ИМЯ => значение}) делает вызов независимым от порядка
 * аргументов и позволяет не передавать аргументы, у которых в PL/SQL объявлено значение
 * {@code DEFAULT}. Локальные переменные блока — способ, которым на 11.2 можно добраться до
 * аргументов типов BOOLEAN, RECORD и XMLTYPE: JDBC этой версии не умеет их привязывать.
 * Java передаёт простое значение (число 1/0, текст в {@code CLOB}, поля записи по одному),
 * а блок перекладывает его в переменную нужного типа и обратно.
 */
public class CallPlanner {

    /**
     * Ключ, под которым в выходных значениях лежит результат функции.
     *
     * <p>С именем аргумента он не совпадает: обычный идентификатор PL/SQL не может
     * начинаться с {@code $}.
     */
    public static final String RETURN_KEY = "$return";

    /** Источник значений для аргументов, которых нет среди параметров Java-метода. */
    private final ArgumentDefaults defaults;

    /**
     * Создаёт планировщик.
     *
     * @param defaults источник значений для аргументов, которые Java-метод не передаёт
     *                 (например, {@code NTENANT}); {@link ArgumentDefaults#none()}, если таких нет
     */
    public CallPlanner(ArgumentDefaults defaults) {
        this.defaults = defaults;
    }

    /**
     * Сообщает, что Java-метод и сигнатура подпрограммы не подходят друг к другу; текст
     * сообщения объясняет почему.
     *
     * <p>Бросается при старте, пока строится план. {@code PlsqlApiInvocationHandler} собирает
     * такие сообщения по всем методам интерфейса и останавливает старт одной ошибкой со
     * списком всех проблем.
     */
    public static class PlanException extends RuntimeException {

        /**
         * Создаёт исключение с объяснением причины.
         *
         * @param message почему метод не подходит к подпрограмме
         */
        public PlanException(String message) {
            super(message);
        }
    }

    /**
     * Выбирает перегрузку подпрограммы, подходящую под параметры Java-метода, и строит план
     * её вызова.
     *
     * <p>Перегрузки — несколько подпрограмм пакета с одним именем и разными списками
     * аргументов; в {@code ALL_ARGUMENTS} они различаются колонкой {@code OVERLOAD}. План
     * пробуется построить для каждой перегрузки. Если подошли несколько (одинаковые имена
     * аргументов, но разные типы: {@code OVER(NUMBER)} / {@code OVER(VARCHAR2)}), остаются те,
     * чьи типы аргументов принимают типы параметров Java ({@code long} → {@code NUMBER}). Если
     * и после этого подходит не ровно одна, это ошибка при старте, а не угадывание.
     *
     * @param method    Java-метод интерфейса
     * @param overloads все перегрузки подпрограммы из словаря Oracle; пустой список значит,
     *                  что подпрограмма в базе не найдена
     * @return план вызова единственной подходящей перегрузки
     * @throws PlanException если список пуст, если не подошла ни одна перегрузка (сообщение
     *                       перечисляет причины по каждой) или если подошли несколько
     *                       равноценных
     */
    public CallPlan plan(Method method, List<SubprogramInfo> overloads) {
        if (overloads.isEmpty()) {
            throw new PlanException("not found in the database");
        }
        List<String> reasons = new ArrayList<>();
        List<CallPlan> fits = new ArrayList<>();
        for (SubprogramInfo sp : overloads) {
            try {
                fits.add(plan(method, sp));
            } catch (PlanException e) {
                reasons.add((sp.overload() != null ? "overload " + sp.overload() + ": " : "") + e.getMessage());
            }
        }
        if (fits.size() > 1) {
            // Одинаковые имена аргументов, разные типы (OVER(NUMBER) / OVER(VARCHAR2)):
            // оставляем перегрузки, чьи типы аргументов принимают типы параметров Java.
            List<CallPlan> typed = fits.stream().filter(p -> typesFit(method, p)).toList();
            if (!typed.isEmpty()) {
                fits = typed;
            }
        }
        if (fits.size() == 1) {
            return fits.get(0);
        }
        if (fits.isEmpty()) {
            throw new PlanException(String.join("; ", reasons));
        }
        throw new PlanException("matches " + fits.size() + " overloads equally: "
                + fits.stream().map(p -> p.target().toString()).toList() + "; name parameters with @Arg");
    }

    /**
     * Строит план вызова одной конкретной перегрузки подпрограммы для Java-метода.
     *
     * <p>Порядок работы:
     * <ul>
     *   <li>проверяет, что все аргументы подпрограммы вообще можно передать
     *       ({@link #supportIssues});</li>
     *   <li>сопоставляет параметры Java-метода с аргументами PL/SQL;</li>
     *   <li>решает, куда пойдут выходные значения: результат функции, единственный
     *       OUT-аргумент или несколько OUT-аргументов, собранных в record, бин или
     *       {@code Map};</li>
     *   <li>для каждого аргумента выбирает источник входного значения и генерирует выражение,
     *       которое подставляется в вызов;</li>
     *   <li>собирает текст анонимного блока и список привязок.</li>
     * </ul>
     *
     * <p>OUT-аргумент — аргумент, через который подпрограмма возвращает значение вызывающему;
     * IN OUT — аргумент, который и принимает значение, и возвращает новое. Входной (IN или
     * IN OUT) аргумент, для которого нет параметра Java, получает значение по первому
     * подходящему правилу:
     * <ul>
     *   <li>из {@link ArgumentDefaults}, если там есть поставщик для этого аргумента;</li>
     *   <li>{@code NULL}, если аргумент IN OUT;</li>
     *   <li>не передаётся вовсе, если у него в PL/SQL объявлен {@code DEFAULT}: значение
     *       подставит сама база;</li>
     *   <li>{@code NULL}, если метод помечен {@code @Procedure(nullForMissing = true)};</li>
     *   <li>иначе — ошибка при старте.</li>
     * </ul>
     *
     * <p>Если это процедура, а метод не {@code void}: при одном OUT-аргументе его значение и
     * есть результат метода (кроме случая, когда тип результата — Java record-«контейнер», см.
     * {@code isMultiValueHolder}); иначе все OUT-аргументы собираются в тип результата по
     * именам, и каждый компонент record обязан совпасть с каким-нибудь OUT-аргументом.
     * OUT-аргументы, которые в результат не попадают (у {@code void}-метода или у функции),
     * всё равно привязываются и читаются, но их значения отбрасываются.
     *
     * @param method Java-метод интерфейса
     * @param sp     сигнатура подпрограммы (одна перегрузка) из словаря Oracle
     * @return план вызова
     * @throws PlanException если подпрограмму нельзя вызвать или метод к ней не подходит;
     *                       сообщение объясняет причину
     */
    public CallPlan plan(Method method, SubprogramInfo sp) {
        // Формы аргументов, которые вообще нельзя вызвать, — настоящая причина ошибки;
        // сообщаем о них раньше всего, что касается стороны Java.
        List<String> unsupported = supportIssues(sp);
        if (!unsupported.isEmpty()) {
            throw new PlanException("not callable: " + String.join("; ", unsupported));
        }
        Map<ArgumentInfo, Function<Object[], Object>> supplied = matchParameters(method, sp);
        Procedure procAnn = method.getAnnotation(Procedure.class);
        boolean nullForMissing = procAnn != null && procAnn.nullForMissing();

        ResolvableType returnType = ResolvableType.forMethodReturnType(method);
        Class<?> rawReturn = returnType.resolve(Object.class);
        boolean voidReturn = rawReturn == void.class || rawReturn == Void.class;

        // Какие выходные значения есть и в какой Java-тип пойдёт каждое.
        List<ArgumentInfo> outs = sp.arguments().stream().filter(ArgumentInfo::isOut).toList();
        Map<String, ResolvableType> outTypes = new LinkedHashMap<>();
        boolean outsToType = false;
        String returnKey = null;
        if (sp.isFunction()) {
            returnKey = RETURN_KEY;
            outTypes.put(returnKey, returnType);
        } else if (!voidReturn) {
            if (outs.isEmpty()) {
                throw new PlanException("returns " + rawReturn.getSimpleName() + " but the procedure has no OUT arguments");
            }
            if (outs.size() == 1 && !isMultiValueHolder(rawReturn, outs)) {
                returnKey = outs.get(0).name();
                outTypes.put(returnKey, returnType);
            } else {
                if (!canHoldSeveralValues(returnType)) {
                    throw new PlanException("returns " + rawReturn.getSimpleName() + " but the procedure has "
                            + outs.size() + " OUT arguments " + outs.stream().map(ArgumentInfo::name).toList()
                            + "; return a record, a bean or a Map with one component per OUT argument");
                }
                outsToType = true;
                for (ArgumentInfo o : outs) {
                    outTypes.put(o.name(), componentType(rawReturn, o.name()));
                }
                List<String> unmatched = unmatchedComponents(rawReturn, outs);
                if (!unmatched.isEmpty()) {
                    throw new PlanException(rawReturn.getSimpleName() + " components " + unmatched
                            + " match no OUT argument and would always be empty; OUT arguments are "
                            + outs.stream().map(ArgumentInfo::name).toList() + "; name them with @Arg");
                }
            }
        }

        // Текст блока и привязки собираются по ходу обхода аргументов в порядке объявления.
        Block b = new Block();
        List<String> callArgs = new ArrayList<>();

        for (ArgumentInfo a : sp.arguments()) {
            Function<Object[], Object> in = null;
            if (a.isIn()) {
                in = supplied.get(a);
                if (in != null && a.kind() == ArgKind.REF_CURSOR) {
                    throw new PlanException("argument " + a.name() + " is an IN OUT REF CURSOR; a cursor cannot be sent"
                            + " from Java, so the procedure always gets an unopened one: remove the parameter");
                }
                if (in == null) {
                    Supplier<Object> d = defaults.lookup(sp, a);
                    if (d != null) {
                        in = new FromDefaults(d);
                    } else if (a.isOut()) {
                        in = args -> null;
                    } else if (a.defaulted()) {
                        continue; // пусть сработает DEFAULT из PL/SQL
                    } else if (nullForMissing) {
                        in = args -> null;
                    } else {
                        throw new PlanException("required argument " + a.name() + " (" + a.dataType()
                                + ") is not supplied; add a parameter or an ArgumentDefaults entry");
                    }
                }
            }
            String outKey = a.isOut() ? a.name() : null;
            ResolvableType outType = outKey == null ? null : outTypes.getOrDefault(outKey, ResolvableType.forClass(Object.class));
            callArgs.add(a.name() + " => " + argument(b, a, in, outKey, outType));
        }

        String call = sp.qualifiedName() + (callArgs.isEmpty() ? "" : "(" + String.join(", ", callArgs) + ")");
        String callStmt;
        if (sp.isFunction()) {
            // Результат функции простого типа привязывается прямо: "? := F(...)"; BOOLEAN,
            // XMLTYPE и RECORD сначала попадают в переменную блока и читаются из неё.
            ArgumentInfo r = sp.returnValue();
            ResolvableType rt = outTypes.get(RETURN_KEY);
            if (bindsDirectly(r)) {
                checkIndexTable(r);
                b.returnBind(new CallPlan.Bind(r.kind(), r, null, RETURN_KEY, rt));
                callStmt = "? := " + call + ";";
            } else {
                String v = b.variable(localType(r));
                callStmt = v + " := " + call + ";";
                readBack(b, v, r, RETURN_KEY, rt);
            }
        } else {
            callStmt = call + ";";
        }

        String sql = b.render(callStmt);
        return new CallPlan(sp, sql, b.binds(), b.recordOuts(), new CallPlan.Result(returnType, returnKey, outsToType));
    }

    // ------------------------------------------------------------------ аргументы

    /**
     * Генерирует всё, что нужно одному аргументу, и возвращает выражение, которое
     * подставляется в вызов после {@code ИМЯ =>}.
     *
     * <p>Типы, которые JDBC привязывает напрямую, превращаются просто в {@code ?}. Входной
     * BOOLEAN переводится из числа в логическое значение прямо в вызове:
     * {@code (CASE ? WHEN 1 THEN TRUE WHEN 0 THEN FALSE END)}. Остальное (выходной и IN OUT
     * BOOLEAN, XMLTYPE, RECORD, IN OUT REF CURSOR) идёт через локальную переменную блока: до
     * вызова в неё записывается входное значение, в вызов передаётся её имя, после вызова
     * значение читается обратно.
     *
     * <p>Входное значение IN OUT курсора не записывается: открытый курсор нельзя передать из
     * Java, поэтому процедура получает неоткрытую переменную {@code SYS_REFCURSOR}.
     *
     * @param b       собираемый блок
     * @param a       аргумент подпрограммы
     * @param in      откуда брать входное значение; {@code null}, если аргумент только выходной
     * @param outKey  ключ выходного значения; {@code null}, если аргумент только входной
     * @param outType Java-тип, в который пойдёт выходное значение
     * @return выражение для вызова: {@code ?}, выражение {@code CASE} или имя переменной блока
     * @throws PlanException если аргумент — REF CURSOR только на вход, если его тип не
     *                       поддержан или это index-by таблица с неподдерживаемыми элементами
     */
    private String argument(Block b, ArgumentInfo a, Function<Object[], Object> in, String outKey, ResolvableType outType) {
        ArgKind kind = a.kind();
        if (bindsDirectly(a)) {
            checkIndexTable(a);
            return b.inline(new CallPlan.Bind(kind, a, in, outKey, outType));
        }
        switch (kind) {
            case BOOLEAN -> {
                // Только входной BOOLEAN обходится без переменной.
                if (!a.isOut()) {
                    return "(CASE " + b.inline(new CallPlan.Bind(ArgKind.BOOLEAN, a, in, null, null))
                            + " WHEN 1 THEN TRUE WHEN 0 THEN FALSE END)";
                }
            }
            case REF_CURSOR -> {
                if (!a.isOut()) {
                    throw new PlanException("argument " + a.name() + " is an IN REF CURSOR; a cursor cannot be sent from Java");
                }
            }
            case XMLTYPE, RECORD -> {
                // Всегда через переменную блока.
            }
            default -> throw new PlanException("argument " + a.name() + " has unsupported type " + a.dataType());
        }
        String v = b.variable(localType(a));
        if (in != null && kind != ArgKind.REF_CURSOR) {
            writeInto(b, v, a, in);
        }
        if (outKey != null) {
            readBack(b, v, a, outKey, outType);
        }
        return v;
    }

    /**
     * Проверяет, привязывает ли JDBC аргумент прямо в вызов; всё остальное идёт через
     * переменную блока.
     *
     * <p>Напрямую идут скаляры (числа, строки, даты, {@code CLOB}, {@code BLOB}, {@code RAW}),
     * SQL-коллекции ({@code TABLE OF} / {@code VARRAY}, объявленные через {@code CREATE TYPE}),
     * объектные типы SQL, index-by таблицы и REF CURSOR, который только возвращается. REF
     * CURSOR — ссылка на открытый курсор, то есть на результат запроса, строки которого
     * читаются уже в Java.
     *
     * @param a аргумент или возвращаемое значение функции
     * @return {@code true}, если аргумент привязывается как {@code ?} прямо в вызове
     */
    private static boolean bindsDirectly(ArgumentInfo a) {
        ArgKind k = a.kind();
        return k.isScalar() || k == ArgKind.SQL_COLLECTION || k == ArgKind.OBJECT || k == ArgKind.INDEX_TABLE
                || (k == ArgKind.REF_CURSOR && !a.isIn());
    }

    /**
     * Генерирует операторы до вызова, которые переносят значение из Java в переменную PL/SQL
     * (или в поле записи) {@code target}.
     *
     * <ul>
     *   <li>BOOLEAN: Java передаёт 1/0, блок превращает их в {@code TRUE}/{@code FALSE};
     *       {@code NULL} остаётся {@code NULL}.</li>
     *   <li>XMLTYPE: Java передаёт текст во вспомогательную переменную {@code CLOB}, блок
     *       строит из него {@code XMLTYPE}, только если текст не {@code NULL}.</li>
     *   <li>RECORD: рекурсивно, поле за полем; значение поля читается из Java-объекта (record,
     *       бин или {@code Map}) по имени поля. Если у объекта нет такого свойства, поле
     *       получает {@code NULL}.</li>
     *   <li>остальное (скаляры): простое присваивание {@code target := ?;}.</li>
     * </ul>
     *
     * @param b      собираемый блок
     * @param target имя переменной или поля, например {@code v1} или {@code v1.ID}
     * @param a      аргумент или поле записи; определяет, как переносить значение
     * @param in     откуда брать значение из массива аргументов вызова Java-метода
     * @throws PlanException если у записи есть поле неподдерживаемого типа
     */
    private void writeInto(Block b, String target, ArgumentInfo a, Function<Object[], Object> in) {
        switch (a.kind()) {
            case BOOLEAN -> b.pre(target + " := CASE ", new CallPlan.Bind(ArgKind.BOOLEAN, a, in, null, null),
                    " WHEN 1 THEN TRUE WHEN 0 THEN FALSE END;");
            case XMLTYPE -> {
                // На 11.2 XMLTYPE(NULL) падает с ORA-06502, поэтому NULL явно остаётся NULL.
                String c = b.variable("CLOB");
                b.pre(c + " := ", new CallPlan.Bind(ArgKind.CLOB, a, in, null, null),
                        "; IF " + c + " IS NOT NULL THEN " + target + " := XMLTYPE(" + c + "); END IF;");
            }
            case RECORD -> {
                for (ArgumentInfo f : a.children()) {
                    checkField(a, f);
                    String name = f.name();
                    writeInto(b, target + "." + name, f, args -> Values.property(in.apply(args), name));
                }
            }
            default -> b.pre(target + " := ", new CallPlan.Bind(a.kind(), a, in, null, null), ";");
        }
    }

    /**
     * Генерирует операторы после вызова, которые переносят значение из переменной PL/SQL
     * (или поля записи) {@code source} в выходное значение с ключом {@code key}.
     *
     * <ul>
     *   <li>BOOLEAN: {@code TRUE}/{@code FALSE} превращаются в 1/0, {@code NULL} остаётся
     *       {@code NULL}.</li>
     *   <li>XMLTYPE: возвращается текстом через {@code getClobVal()}, {@code NULL} остаётся
     *       {@code NULL}.</li>
     *   <li>RECORD: рекурсивно, каждое поле возвращается отдельной привязкой с ключом
     *       {@code key.ПОЛЕ}; ключ записи запоминается, чтобы после вызова {@link CallExecutor}
     *       собрал поля обратно в одну карту.</li>
     *   <li>остальное: простое присваивание {@code ? := source;}.</li>
     * </ul>
     *
     * @param b      собираемый блок
     * @param source имя переменной или поля, например {@code v1} или {@code v1.ID}
     * @param a      аргумент, поле записи или возвращаемое значение функции
     * @param key    ключ, под которым сохранится выходное значение
     * @param type   Java-тип выходного значения; для полей записи {@code null}
     * @throws PlanException если у записи есть поле неподдерживаемого типа
     */
    private void readBack(Block b, String source, ArgumentInfo a, String key, ResolvableType type) {
        switch (a.kind()) {
            case BOOLEAN -> b.post(new CallPlan.Bind(ArgKind.BOOLEAN, a, null, key, type),
                    " := CASE WHEN " + source + " THEN 1 WHEN NOT " + source + " THEN 0 END;");
            case XMLTYPE -> b.post(new CallPlan.Bind(ArgKind.XMLTYPE, a, null, key, type),
                    " := CASE WHEN " + source + " IS NULL THEN NULL ELSE " + source + ".getClobVal() END;");
            case RECORD -> {
                for (ArgumentInfo f : a.children()) {
                    checkField(a, f);
                    readBack(b, source + "." + f.name(), f, key + "." + f.name(), null);
                }
                b.recordOut(key);
            }
            default -> b.post(new CallPlan.Bind(a.kind(), a, null, key, type), " := " + source + ";");
        }
    }

    /**
     * Возвращает тип PL/SQL, которым объявляется локальная переменная блока для этого
     * аргумента.
     *
     * <p>{@code SYS_REFCURSOR} — встроенный тип ссылки на курсор с любым набором колонок.
     *
     * @param a аргумент или возвращаемое значение функции
     * @return {@code BOOLEAN}, {@code XMLTYPE}, {@code SYS_REFCURSOR} или имя типа записи
     * @throws PlanException если для этого вида переменная не предусмотрена; текст сообщения
     *                       говорит о возвращаемом значении, потому что аргументы таких видов
     *                       отсекаются раньше
     */
    private static String localType(ArgumentInfo a) {
        return switch (a.kind()) {
            case BOOLEAN -> "BOOLEAN";
            case XMLTYPE -> "XMLTYPE";
            case REF_CURSOR -> "SYS_REFCURSOR";
            case RECORD -> recordType(a);
            default -> throw new PlanException("return type " + a.dataType() + " is not supported");
        };
    }

    /**
     * Возвращает имя типа записи для объявления переменной: {@code OWNER.PKG.REC_T} или
     * {@code OWNER.TABLE%ROWTYPE}.
     *
     * <p>RECORD — составной тип, который существует только в PL/SQL (аналог структуры) и
     * объявляется в пакете. {@code %ROWTYPE} — запись со структурой строки таблицы.
     *
     * @param a аргумент или возвращаемое значение типа RECORD
     * @return имя типа для секции {@code DECLARE}
     * @throws PlanException если имя типа не удалось узнать ни из {@code ALL_ARGUMENTS}, ни из
     *                       {@code ALL_SOURCE} (представления с исходным текстом PL/SQL)
     */
    private static String recordType(ArgumentInfo a) {
        String t = a.declaredType();
        if (t == null) {
            throw new PlanException("record " + (a.name() == null ? "return value" : a.name())
                    + " has no type name in ALL_ARGUMENTS, and its %ROWTYPE could not be resolved from ALL_SOURCE"
                    + " (wrapped source, or a table this user cannot see)");
        }
        // У аргументов %ROWTYPE TYPE_NAME указывает на таблицу, а TYPE_SUBNAME пуст.
        return a.typeSubname() == null ? t + "%ROWTYPE" : t;
    }

    /**
     * Проверяет, может ли поле записи пройти через переменную блока.
     *
     * <p>Поддержаны скаляры, BOOLEAN и XMLTYPE; вложенные записи, коллекции, объектные типы и
     * курсоры — нет.
     *
     * @param f поле записи
     * @return {@code true}, если поле поддержано
     */
    private static boolean fieldSupported(ArgumentInfo f) {
        return f.kind().isScalar() || f.kind() == ArgKind.BOOLEAN || f.kind() == ArgKind.XMLTYPE;
    }

    /**
     * Проверяет поле записи и останавливает планирование, если его тип не поддержан.
     *
     * @param rec запись, которой принадлежит поле
     * @param f   поле записи
     * @throws PlanException если поле не скаляр, не BOOLEAN и не XMLTYPE
     */
    private static void checkField(ArgumentInfo rec, ArgumentInfo f) {
        if (!fieldSupported(f)) {
            throw new PlanException("record " + rec.name() + " field " + f.name() + " is " + f.dataType()
                    + "; only scalar, BOOLEAN and XMLTYPE fields are supported");
        }
    }

    /**
     * Проверяет, что index-by таблицу можно привязать через JDBC; для остальных видов ничего
     * не делает.
     *
     * <p>Index-by таблица (ассоциативный массив {@code TABLE OF ... INDEX BY ...}) — коллекция,
     * которая существует только в PL/SQL. ojdbc передаёт её методом
     * {@code setPlsqlIndexTable}, но на 11.2 только с элементами {@code NUMBER} или
     * {@code VARCHAR2}. Тип элемента описан первым вложенным элементом аргумента.
     *
     * @param a аргумент или возвращаемое значение функции
     * @throws PlanException если это index-by таблица с элементами другого типа или тип
     *                       элементов неизвестен
     */
    private static void checkIndexTable(ArgumentInfo a) {
        if (a.kind() == ArgKind.INDEX_TABLE) {
            ArgumentInfo el = a.children().isEmpty() ? null : a.children().get(0);
            if (el == null || (el.kind() != ArgKind.NUMBER && el.kind() != ArgKind.STRING)) {
                throw new PlanException("index-by table " + (a.name() == null ? "return value" : a.name()) + " of " + (el == null ? "?" : el.dataType())
                        + " cannot be bound on 11.2; only tables of NUMBER or VARCHAR2 can");
            }
        }
    }

    /**
     * Перечисляет причины, по которым эту подпрограмму нельзя вызвать через планировщик,
     * независимо от Java-метода. Пустой список значит, что все формы аргументов поддержаны.
     *
     * <p>Проверяются аргументы и возвращаемое значение функции:
     * <ul>
     *   <li>запись с неизвестным именем типа или с полями неподдерживаемых типов;</li>
     *   <li>index-by таблица с элементами не {@code NUMBER} и не {@code VARCHAR2};</li>
     *   <li>тип, который библиотека не знает вовсе (например, {@code OPAQUE/ANYDATA});</li>
     *   <li>REF CURSOR только на вход: открытый курсор нельзя передать из Java.</li>
     * </ul>
     * {@link #plan(Method, SubprogramInfo)} вызывает эту проверку первой: такие причины
     * важнее любых несовпадений со стороной Java.
     *
     * @param sp сигнатура подпрограммы
     * @return список проблем вида {@code ИМЯ: причина} (для возвращаемого значения —
     *         {@code RETURN: причина}); пустой, если проблем нет
     */
    public static List<String> supportIssues(SubprogramInfo sp) {
        List<String> issues = new ArrayList<>();
        List<ArgumentInfo> all = new ArrayList<>(sp.arguments());
        if (sp.returnValue() != null) {
            all.add(sp.returnValue());
        }
        for (ArgumentInfo a : all) {
            String who = a.name() == null ? "RETURN" : a.name();
            switch (a.kind()) {
                case RECORD -> {
                    if (a.declaredType() == null) {
                        issues.add(who + ": record type unknown");
                    }
                    for (ArgumentInfo f : a.children()) {
                        if (!fieldSupported(f)) {
                            issues.add(who + ": record field " + f.name() + " is " + f.dataType());
                        }
                    }
                }
                case INDEX_TABLE -> {
                    ArgumentInfo el = a.children().isEmpty() ? null : a.children().get(0);
                    if (el == null || (el.kind() != ArgKind.NUMBER && el.kind() != ArgKind.STRING)) {
                        issues.add(who + ": index-by table of " + (el == null ? "?" : el.dataType()));
                    }
                }
                case UNSUPPORTED -> issues.add(who + ": type " + a.dataType());
                case REF_CURSOR -> {
                    if (a.isIn() && !a.isOut()) {
                        issues.add(who + ": IN REF CURSOR");
                    }
                }
                default -> {
                }
            }
        }
        return issues;
    }

    // ------------------------------------------------------------------ сторона Java

    /**
     * Сопоставляет параметры Java-метода с аргументами подпрограммы.
     *
     * <p>Для каждого параметра:
     * <ul>
     *   <li>с {@code @Arg("ИМЯ")} ищется аргумент с этим именем без учёта регистра;</li>
     *   <li>без аннотации ищется по имени параметра через {@code NameMatcher}: без учёта
     *       регистра, подчёркиваний и префиксов вроде {@code P_} или однобуквенного типового
     *       ({@code tenant} → {@code NTENANT});</li>
     *   <li>если аргумент не нашёлся, а параметр — record или бин, его свойства считаются
     *       отдельными аргументами (как поля формы), и каждое свойство обязано совпасть с
     *       аргументом.</li>
     * </ul>
     * Настоящие имена параметров Java видны через рефлексию, только если код скомпилирован с
     * флагом {@code -parameters}; иначе они выглядят как {@code arg0}, и сообщение об ошибке
     * подсказывает это.
     *
     * @param method Java-метод интерфейса
     * @param sp     сигнатура подпрограммы
     * @return для каждого найденного аргумента — функция, которая достаёт его значение из
     *         массива аргументов вызова; в порядке параметров метода
     * @throws PlanException если параметр или свойство не нашли аргумента или нашли его
     *                       неоднозначно, если параметр попал в чисто выходной аргумент или
     *                       два параметра попали в один аргумент
     */
    private Map<ArgumentInfo, Function<Object[], Object>> matchParameters(Method method, SubprogramInfo sp) {
        Parameter[] params = method.getParameters();
        Map<ArgumentInfo, Function<Object[], Object>> supplied = new LinkedHashMap<>();
        for (int i = 0; i < params.length; i++) {
            Parameter p = params[i];
            Arg ann = p.getAnnotation(Arg.class);
            final int k = i;
            ArgumentInfo a = ann != null ? byName(sp, ann.value()) : find(p.getName(), sp);
            if (a == null && ann == null && isParameterObject(p.getType())) {
                // Record или бин, чьи свойства и есть аргументы, как у объекта формы.
                for (PropertyName prop : propertyNames(p.getType())) {
                    ArgumentInfo pa = prop.explicit() != null ? byName(sp, prop.explicit()) : find(prop.name(), sp);
                    if (pa == null) {
                        throw new PlanException("property '" + prop.name() + "' of " + p.getType().getSimpleName()
                                + " has no matching argument; arguments are " + names(sp.arguments()));
                    }
                    String argName = pa.name();
                    put(supplied, pa, args -> Values.property(args[k], argName),
                            "property '" + prop.name() + "' of " + p.getType().getSimpleName());
                }
                continue;
            }
            if (a == null) {
                throw new PlanException("parameter '" + (ann != null ? ann.value() : p.getName())
                        + "' has no matching argument; arguments are " + names(sp.arguments())
                        + (p.getName().matches("arg\\d+") ? " (compile with -parameters)" : ""));
            }
            put(supplied, a, args -> args[k], "parameter '" + p.getName() + "'");
        }
        return supplied;
    }

    /**
     * Запоминает источник значения для аргумента, проверяя, что такое сопоставление имеет
     * смысл.
     *
     * @param supplied уже найденные источники значений по аргументам
     * @param a        аргумент PL/SQL
     * @param source   функция, которая достаёт значение из массива аргументов вызова
     * @param label    что именно сопоставлено, для сообщения об ошибке, например
     *                 {@code parameter 'tenant'}
     * @throws PlanException если аргумент чисто выходной (OUT) или ему уже сопоставлен другой
     *                       параметр
     */
    private static void put(Map<ArgumentInfo, Function<Object[], Object>> supplied, ArgumentInfo a,
                            Function<Object[], Object> source, String label) {
        if (!a.isIn()) {
            // Чисто выходной (OUT) аргумент ничего не принимает: значение вызывающего молча
            // пропало бы.
            throw new PlanException(label + " maps to OUT argument " + a.name()
                    + ", which takes no value; OUT values come back through the return type");
        }
        if (supplied.putIfAbsent(a, source) != null) {
            throw new PlanException("two parameters map to " + a.name());
        }
    }

    /**
     * Ищет аргумент верхнего уровня по точному имени без учёта регистра (так работает
     * {@code @Arg}).
     *
     * @param sp   сигнатура подпрограммы
     * @param name имя аргумента PL/SQL
     * @return аргумент или {@code null}, если такого нет
     */
    private static ArgumentInfo byName(SubprogramInfo sp, String name) {
        return sp.arguments().stream().filter(x -> name.equalsIgnoreCase(x.name())).findFirst().orElse(null);
    }

    /**
     * Ищет аргумент по имени Java через {@code NameMatcher}: без учёта регистра, подчёркиваний
     * и типовых префиксов.
     *
     * @param javaName имя параметра или свойства Java
     * @param sp       сигнатура подпрограммы
     * @return аргумент или {@code null}, если ничего не подошло
     * @throws PlanException если имя одинаково хорошо подходит к нескольким аргументам
     */
    private static ArgumentInfo find(String javaName, SubprogramInfo sp) {
        return match(javaName, sp.arguments(), ArgumentInfo::name);
    }

    /**
     * Ищет имя Java среди имён PL/SQL через {@code NameMatcher}; неоднозначность становится
     * {@link PlanException}, чтобы попасть в общий отчёт о старте вместе с остальными ошибками.
     *
     * @param javaName имя параметра, свойства или компонента record
     * @param items    кандидаты
     * @param plsqlName имя кандидата в PL/SQL
     * @param <T>      тип кандидата
     * @return подошедший кандидат или {@code null}
     * @throws PlanException если имя одинаково хорошо подходит к нескольким кандидатам
     */
    private static <T> T match(String javaName, Collection<T> items, Function<T, String> plsqlName) {
        try {
            return NameMatcher.find(javaName, items, plsqlName);
        } catch (IllegalStateException e) {
            throw new PlanException(e.getMessage());
        }
    }

    /**
     * Проверяет, может ли тип результата принять несколько OUT-аргументов сразу: по компоненту
     * или свойству на каждый.
     *
     * <p>Подходят record, бин, {@code Map} и {@code Object} (тогда вернётся {@code Map}), в том
     * числе внутри {@code Optional}. Не подходят простые значения (числа, строки, даты), массивы,
     * коллекции и узлы DOM: в них несколько значений разложить нельзя.
     *
     * @param returnType тип результата Java-метода
     * @return {@code true}, если в тип можно собрать несколько OUT-аргументов
     */
    private static boolean canHoldSeveralValues(ResolvableType returnType) {
        Class<?> raw = returnType.resolve(Object.class);
        if (raw == java.util.Optional.class) {
            raw = returnType.getGeneric(0).resolve(Object.class);
        }
        return raw == Object.class || Map.class.isAssignableFrom(raw) || isParameterObject(raw);
    }

    /**
     * Проверяет, принимают ли аргументы плана типы параметров Java-метода; нужен, чтобы
     * выбрать между перегрузками с одинаковыми именами аргументов.
     *
     * <p>Какой параметр попал в какой аргумент, в плане прямо не записано: там есть только
     * функции {@code in}. Поэтому для каждого параметра по очереди строится пробный массив
     * аргументов, где на месте этого параметра стоит метка {@code MARKER}, а остальные
     * элементы — {@code null}, и на нём вычисляются все входные привязки верхнего уровня.
     * Привязка, вернувшая метку, берёт значение прямо из этого параметра, и её тип PL/SQL
     * обязан принимать тип параметра (см. {@link #accepts}).
     *
     * <p>Поля записей (в {@code ALL_ARGUMENTS} у них {@code DATA_LEVEL} больше 0) и свойства
     * объектов-параметров так не проверяются. Поставщики из {@link ArgumentDefaults} при этом не
     * вызываются: при старте их значения ещё не имеют смысла (нет запроса, нет пользователя).
     *
     * @param m    Java-метод
     * @param plan план вызова одной перегрузки
     * @return {@code false}, если хотя бы один параметр попал в аргумент несовместимого типа
     */
    private static boolean typesFit(Method m, CallPlan plan) {
        Parameter[] params = m.getParameters();
        for (int i = 0; i < params.length; i++) {
            Object[] probe = new Object[params.length];
            probe[i] = MARKER;
            for (CallPlan.Bind b : plan.binds()) {
                if (b.in() != null && !(b.in() instanceof FromDefaults) && b.arg().dataLevel() == 0
                        && safeApply(b, probe) == MARKER
                        && !accepts(b.arg().kind(), params[i].getType())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Источник входного значения из {@link ArgumentDefaults}. Отдельный тип нужен, чтобы
     * {@code typesFit} мог отличить его от параметра метода и не вызывать поставщика при старте.
     *
     * @param supplier поставщик значения; вызывается при каждом вызове метода
     */
    private record FromDefaults(Supplier<Object> supplier) implements Function<Object[], Object> {

        /**
         * Возвращает текущее значение поставщика; аргументы вызова не нужны.
         *
         * @param args аргументы вызова Java-метода
         * @return значение поставщика
         */
        @Override
        public Object apply(Object[] args) {
            return supplier.get();
        }
    }

    /**
     * Метка для {@code typesFit}: по ней видно, из какого параметра привязка берёт значение.
     */
    private static final Object MARKER = new Object();

    /**
     * Вычисляет входное значение привязки на пробном массиве так, чтобы ошибка не сорвала
     * проверку типов.
     *
     * @param b     привязка с функцией {@code in}
     * @param probe пробный массив аргументов
     * @return вычисленное значение или {@code null}, если функция бросила исключение
     */
    private static Object safeApply(CallPlan.Bind b, Object[] probe) {
        try {
            return b.in().apply(probe);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Проверяет, может ли значение этого Java-типа передаваться как аргумент PL/SQL данного
     * вида.
     *
     * <p>Примитивы сравниваются как их обёртки ({@code long} как {@code Long}). Соответствие:
     * <ul>
     *   <li>{@code NUMBER} — наследники {@code Number};</li>
     *   <li>строки и {@code CLOB} — {@code CharSequence}, перечисления, {@code Character};</li>
     *   <li>{@code XMLTYPE} — {@code CharSequence} или узел DOM ({@code org.w3c.dom.Node});</li>
     *   <li>{@code DATE}, {@code TIMESTAMP} — {@code LocalDate}, {@code LocalDateTime},
     *       {@code Instant}, {@code OffsetDateTime}, {@code ZonedDateTime} и {@code java.util.Date}
     *       с наследниками: ровно то, что умеет передать {@code Values.toTimestamp};</li>
     *   <li>{@code BOOLEAN} — {@code Boolean};</li>
     *   <li>{@code BLOB}, {@code RAW} — {@code byte[]};</li>
     *   <li>SQL-коллекции и index-by таблицы — {@code Collection} или массив;</li>
     *   <li>{@code RECORD} и объектный тип SQL — всё, что {@code BeanUtils} не считает простым
     *       значением (record, бин, {@code Map} и т.п.);</li>
     *   <li>остальные виды (например, REF CURSOR) — любой тип.</li>
     * </ul>
     *
     * @param kind вид аргумента PL/SQL
     * @param t    тип параметра Java
     * @return {@code true}, если тип подходит
     */
    static boolean accepts(ArgKind kind, Class<?> t) {
        Class<?> c = ClassUtils.resolvePrimitiveIfNecessary(t);
        return switch (kind) {
            case NUMBER -> Number.class.isAssignableFrom(c);
            case STRING, CLOB -> CharSequence.class.isAssignableFrom(c) || c.isEnum() || c == Character.class;
            case XMLTYPE -> CharSequence.class.isAssignableFrom(c) || org.w3c.dom.Node.class.isAssignableFrom(c);
            case DATE, TIMESTAMP -> c == java.time.LocalDate.class || c == java.time.LocalDateTime.class
                    || c == java.time.Instant.class || c == java.time.OffsetDateTime.class
                    || c == java.time.ZonedDateTime.class || java.util.Date.class.isAssignableFrom(c);
            case BOOLEAN -> c == Boolean.class;
            case BLOB, RAW -> c == byte[].class;
            case SQL_COLLECTION, INDEX_TABLE -> Collection.class.isAssignableFrom(c) || c.isArray();
            case RECORD, OBJECT -> !BeanUtils.isSimpleValueType(c);
            default -> true;
        };
    }

    /**
     * Проверяет, может ли параметр быть объектом-параметром: record или бином, чьи свойства
     * передаются как отдельные аргументы процедуры.
     *
     * <p>Не подходят простые значения (числа, строки, даты, перечисления и т.п.), массивы,
     * примитивы, коллекции, {@code Map} и узлы DOM.
     *
     * @param t тип параметра Java
     * @return {@code true}, если свойства этого типа нужно сопоставлять с аргументами
     */
    private static boolean isParameterObject(Class<?> t) {
        return !BeanUtils.isSimpleValueType(t) && !t.isArray() && !t.isPrimitive()
                && !Collection.class.isAssignableFrom(t) && !Map.class.isAssignableFrom(t)
                && !org.w3c.dom.Node.class.isAssignableFrom(t);
    }

    /**
     * Описывает свойство объекта-параметра и явное имя аргумента для него, если оно задано.
     *
     * @param name     имя свойства Java (компонента record или свойства бина)
     * @param explicit имя аргумента PL/SQL из {@code @Arg} на компоненте record, иначе
     *                 {@code null}
     */
    private record PropertyName(String name, String explicit) {
    }

    /**
     * Возвращает свойства объекта-параметра, которые нужно сопоставить с аргументами.
     *
     * <p>Для record это все компоненты с учётом {@code @Arg} на компоненте. Для бина — все
     * свойства, у которых есть геттер, кроме {@code class}; {@code @Arg} здесь не
     * поддерживается (аннотация ставится только на параметры и компоненты record).
     *
     * @param t тип объекта-параметра
     * @return свойства в порядке объявления компонентов record или в порядке, который отдаёт
     *         {@code BeanUtils}
     */
    private static List<PropertyName> propertyNames(Class<?> t) {
        if (t.isRecord()) {
            return Arrays.stream(t.getRecordComponents()).map(rc -> {
                Arg a = rc.getAnnotation(Arg.class);
                return new PropertyName(rc.getName(), a == null ? null : a.value());
            }).toList();
        }
        return Arrays.stream(BeanUtils.getPropertyDescriptors(t))
                .filter(pd -> pd.getReadMethod() != null && !pd.getName().equals("class"))
                .map(PropertyDescriptor::getName).map(n -> new PropertyName(n, null)).toList();
    }

    /**
     * Проверяет, служит ли тип результата «контейнером» для OUT-аргумента, а не самим его
     * значением.
     *
     * <p>Смотрится только первый компонент record и только по имени через {@code NameMatcher};
     * {@code @Arg} на компоненте здесь не учитывается.
     *
     * @param type тип результата Java-метода
     * @param outs OUT-аргументы процедуры (метод вызывается, только когда он ровно один)
     * @return {@code true}, если тип — Java record, чей первый компонент совпадает по имени с
     *         OUT-аргументом, а сам OUT-аргумент не RECORD
     */
    private static boolean isMultiValueHolder(Class<?> type, List<ArgumentInfo> outs) {
        // Единственный OUT типа RECORD ложится прямо на тип результата; Java record, чей
        // компонент совпадает по имени с OUT-аргументом, вместо этого считается контейнером.
        if (!type.isRecord()) {
            return false;
        }
        RecordComponent[] rc = type.getRecordComponents();
        return rc.length > 0 && match(rc[0].getName(), outs, ArgumentInfo::name) != null
                && outs.get(0).kind() != ArgKind.RECORD;
    }

    /**
     * Возвращает компоненты record-результата, которые не заполняет ни один OUT-аргумент.
     *
     * <p>Такой компонент всегда оставался бы пустым, поэтому планировщик считает его ошибкой.
     * Компонент с {@code @Arg} сравнивается с именами OUT-аргументов точно (без учёта
     * регистра), без аннотации — через {@code NameMatcher}. Если имя компонента одинаково
     * хорошо подходит к нескольким OUT-аргументам, это {@link PlanException}. Бин и {@code Map}
     * не проверяются.
     *
     * @param holder тип результата Java-метода
     * @param outs   OUT-аргументы процедуры
     * @return имена компонентов без пары; пустой список, если тип не record
     */
    private static List<String> unmatchedComponents(Class<?> holder, List<ArgumentInfo> outs) {
        if (!holder.isRecord()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (RecordComponent rc : holder.getRecordComponents()) {
            Arg a = rc.getAnnotation(Arg.class);
            boolean matched = a != null
                    ? outs.stream().anyMatch(o -> a.value().equalsIgnoreCase(o.name()))
                    : match(rc.getName(), outs, ArgumentInfo::name) != null;
            if (!matched) {
                out.add(rc.getName());
            }
        }
        return out;
    }

    /**
     * Возвращает Java-тип компонента record-результата, в который пойдёт OUT-аргумент.
     *
     * <p>По этому типу, например, строки REF CURSOR превращаются в элементы
     * {@code List<Employee>}.
     *
     * @param holder  тип результата Java-метода
     * @param outName имя OUT-аргумента
     * @return тип первого подходящего компонента (с параметрами дженериков) или {@code Object},
     *         если компонент не найден или результат не record
     */
    private static ResolvableType componentType(Class<?> holder, String outName) {
        if (holder.isRecord()) {
            for (RecordComponent rc : holder.getRecordComponents()) {
                Arg a = rc.getAnnotation(Arg.class);
                if (a != null ? a.value().equalsIgnoreCase(outName)
                        : match(rc.getName(), List.of(outName), x -> x) != null) {
                    return ResolvableType.forType(rc.getGenericType());
                }
            }
        }
        return ResolvableType.forClass(Object.class);
    }

    /**
     * Описывает аргументы для сообщений об ошибках.
     *
     * @param args аргументы подпрограммы
     * @return строки вида {@code NTENANT IN} или {@code P_B IN DEFAULT}
     */
    private static List<String> names(List<ArgumentInfo> args) {
        return args.stream().map(a -> a.name() + " " + a.inOut() + (a.defaulted() ? " DEFAULT" : "")).toList();
    }

    /**
     * Собирает анонимный блок: объявления, операторы до и после вызова и привязки в порядке
     * текста.
     *
     * <p>Каждый оператор до или после вызова содержит ровно один {@code ?}. JDBC нумерует
     * плейсхолдеры по порядку появления в тексте, поэтому {@code render} складывает привязки в
     * том же порядке: сначала операторы до вызова, затем результат функции
     * ({@code ? := F(...)}), затем плейсхолдеры внутри вызова, затем операторы после вызова.
     */
    private static final class Block {

        /** Строки секции {@code DECLARE}, например {@code v1 BOOLEAN;}. */
        private final List<String> declarations = new ArrayList<>();

        /** Операторы до вызова: запись входных значений в переменные блока. */
        private final List<Statement> before = new ArrayList<>();

        /** Привязки, стоящие прямо в тексте вызова, в порядке аргументов. */
        private final List<CallPlan.Bind> inCall = new ArrayList<>();

        /** Привязка результата функции, если он возвращается прямо в {@code ?}. */
        private CallPlan.Bind returnBind;

        /** Операторы после вызова: чтение выходных значений из переменных блока. */
        private final List<Statement> after = new ArrayList<>();

        /** Ключи выходных записей, чьи поля потом собираются обратно в одну карту. */
        private final List<String> recordOuts = new ArrayList<>();

        /** Итоговые привязки в порядке {@code ?} в тексте; заполняются в {@code render}. */
        private final List<CallPlan.Bind> ordered = new ArrayList<>();

        /** Счётчик для имён переменных {@code v1}, {@code v2}, ... */
        private int vars;

        /**
         * Описывает один оператор блока с единственным плейсхолдером: {@code head ? tail}.
         *
         * @param head текст до {@code ?}
         * @param bind привязка для этого {@code ?}
         * @param tail текст после {@code ?}
         */
        private record Statement(String head, CallPlan.Bind bind, String tail) {
        }

        /**
         * Объявляет новую локальную переменную блока и возвращает её имя.
         *
         * @param type тип PL/SQL, например {@code BOOLEAN} или {@code APP.PKG.REC_T}
         * @return имя переменной: {@code v1}, {@code v2} и т.д.
         */
        String variable(String type) {
            String name = "v" + (++vars);
            declarations.add(name + " " + type + ";");
            return name;
        }

        /**
         * Регистрирует привязку, стоящую прямо в тексте вызова.
         *
         * @param bind привязка
         * @return {@code ?} для подстановки в текст вызова
         */
        String inline(CallPlan.Bind bind) {
            inCall.add(bind);
            return "?";
        }

        /**
         * Запоминает привязку результата функции, который возвращается прямо в {@code ?}.
         *
         * @param bind привязка результата
         */
        void returnBind(CallPlan.Bind bind) {
            returnBind = bind;
        }

        /**
         * Добавляет оператор перед вызовом: {@code head ? tail}.
         *
         * @param head текст до {@code ?}
         * @param bind привязка для этого {@code ?}
         * @param tail текст после {@code ?}
         */
        void pre(String head, CallPlan.Bind bind, String tail) {
            before.add(new Statement(head, bind, tail));
        }

        /**
         * Добавляет после вызова оператор {@code ? := <expr>;}, который читает выходное
         * значение.
         *
         * @param bind привязка, в которую попадёт значение
         * @param tail текст после {@code ?}, начиная с {@code :=}
         */
        void post(CallPlan.Bind bind, String tail) {
            after.add(new Statement("", bind, tail));
        }

        /**
         * Запоминает ключ выходной записи, чьи поля потом собираются в одну карту.
         *
         * @param key ключ записи: имя аргумента или {@link CallPlanner#RETURN_KEY}
         */
        void recordOut(String key) {
            recordOuts.add(key);
        }

        /**
         * Собирает итоговый текст блока и заполняет список привязок в порядке {@code ?}.
         *
         * <p>Секция {@code DECLARE} выводится, только если объявлены переменные. Метод
         * рассчитан на один вызов: повторный вызов добавил бы привязки в список ещё раз.
         *
         * @param callStmt оператор вызова, например {@code ? := APP.PKG.F(P_X => ?);}
         * @return текст анонимного блока
         */
        String render(String callStmt) {
            StringBuilder sb = new StringBuilder();
            if (!declarations.isEmpty()) {
                sb.append("DECLARE\n");
                declarations.forEach(d -> sb.append("  ").append(d).append('\n'));
            }
            sb.append("BEGIN\n");
            append(sb, before);
            if (returnBind != null) {
                ordered.add(returnBind);
            }
            ordered.addAll(inCall);
            sb.append("  ").append(callStmt).append('\n');
            append(sb, after);
            sb.append("END;");
            return sb.toString();
        }

        /**
         * Дописывает операторы в текст блока, а их привязки — в итоговый список.
         *
         * @param sb         собираемый текст
         * @param statements операторы до или после вызова
         */
        private void append(StringBuilder sb, List<Statement> statements) {
            for (Statement s : statements) {
                sb.append("  ").append(s.head()).append('?').append(s.tail()).append('\n');
                ordered.add(s.bind());
            }
        }

        /**
         * Возвращает привязки в порядке {@code ?}; имеет смысл только после {@code render}.
         *
         * @return список привязок
         */
        List<CallPlan.Bind> binds() {
            return ordered;
        }

        /**
         * Возвращает ключи выходных записей.
         *
         * @return ключи записей, чьи поля нужно собрать обратно в карты
         */
        List<String> recordOuts() {
            return recordOuts;
        }
    }
}
