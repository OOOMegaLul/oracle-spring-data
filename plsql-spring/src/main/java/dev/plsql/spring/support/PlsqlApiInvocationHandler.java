package dev.plsql.spring.support;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ResolvableType;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;
import org.springframework.jdbc.core.namedparam.ParsedSql;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.ClassUtils;

import dev.plsql.spring.annotation.Arg;
import dev.plsql.spring.annotation.PlsqlApi;
import dev.plsql.spring.annotation.Procedure;
import dev.plsql.spring.annotation.SqlQuery;
import dev.plsql.spring.call.CallPlan;
import dev.plsql.spring.call.CallPlanner;
import dev.plsql.spring.meta.SubprogramInfo;

/**
 * Реализация интерфейса с аннотацией {@link PlsqlApi}: обработчик вызовов, который стоит за
 * прокси этого интерфейса.
 *
 * <p>Прокси (JDK dynamic proxy) — объект, который JDK создаёт во время работы программы по
 * списку интерфейсов. Собственного кода методов у него нет: каждый вызов метода интерфейса
 * JDK передаёт в {@link #invoke} этого обработчика. Так приложение получает бин с методами
 * интерфейса, хотя класс-реализацию никто не писал.
 *
 * <p>Все методы планируются в {@link #create}, до того как приложение начнёт обслуживать
 * запросы. Метод, который не подходит к своей процедуре, останавливает старт с объяснением
 * причины, так же как Spring Data отвергает неправильный производный запрос (derived query).
 *
 * <p>Методы интерфейса бывают трёх видов:
 * <ul>
 *   <li>с {@link SqlQuery} — выполняют обычный SQL (запрос или DML) через {@link JdbcTemplate};</li>
 *   <li>остальные абстрактные методы — вызывают процедуру или функцию PL/SQL по готовому
 *       {@link CallPlan}, то есть по анонимному блоку PL/SQL, собранному при старте;</li>
 *   <li>default-методы — выполняются как обычный код Java и в базу сами не ходят.</li>
 * </ul>
 * Каждое обращение к базе выполняется как единица работы (см. {@code unitOfWork}).
 */
public final class PlsqlApiInvocationHandler implements InvocationHandler {

    private static final Logger log = LoggerFactory.getLogger(PlsqlApiInvocationHandler.class);

    /** Интерфейс, который реализует прокси; нужен для {@code toString}. */
    private final Class<?> api;
    /** Окружение времени выполнения: источник соединений, исполнитель вызовов, перевод ошибок. */
    private final PlsqlRuntime rt;
    /** Готовые планы вызова процедур, по методам интерфейса. */
    private final Map<Method, CallPlan> plans;
    /** Разобранные {@code @SqlQuery}, по методам интерфейса. */
    private final Map<Method, QueryPlan> queries;

    /**
     * Создаёт обработчик с уже готовыми планами. Вызывается только из {@link #create}, после
     * того как все методы интерфейса успешно сопоставлены с базой.
     *
     * @param api     интерфейс, который реализует прокси
     * @param rt      окружение времени выполнения: источник соединений, исполнитель, перевод ошибок
     * @param plans   планы вызова процедур, по методам интерфейса
     * @param queries разобранные {@code @SqlQuery}, по методам интерфейса
     */
    private PlsqlApiInvocationHandler(Class<?> api, PlsqlRuntime rt, Map<Method, CallPlan> plans,
                                      Map<Method, QueryPlan> queries) {
        this.api = api;
        this.rt = rt;
        this.plans = plans;
        this.queries = queries;
    }

    /**
     * Создаёт реализацию интерфейса {@code api}: планирует каждый его метод и возвращает прокси.
     *
     * <p>Порядок работы:
     * <ul>
     *   <li>default- и static-методы пропускаются: у них есть собственный код Java;</li>
     *   <li>методы с {@link SqlQuery} разбираются сразу, без обращения к базе;</li>
     *   <li>для остальных методов имя подпрограммы даёт {@code subprogramName}, а схему и пакет —
     *       аннотация {@link PlsqlApi} (без неё или с пустым {@code packageName} ищутся автономные
     *       процедуры и функции). Сигнатуры — списки аргументов с типами, по одному на каждую
     *       перегрузку — читаются из словаря данных Oracle (системного каталога с описанием
     *       объектов базы) через {@link PlsqlRuntime#signatures()};</li>
     *   <li>{@link CallPlanner} подбирает для метода подходящую перегрузку и строит {@link CallPlan};
     *       исполнитель проверяет, что план выполним с его настройками (например, что выходная
     *       index-by таблица не потребует сотен мегабайт на вызов).</li>
     * </ul>
     *
     * <p>Сначала все сигнатуры интерфейса читаются за один заход; если это не удалось, каждая
     * читается отдельно, чтобы ошибка досталась тому методу, к которому относится. Ошибки не
     * прерывают разбор: проблемы всех методов собираются и выбрасываются одним исключением,
     * чтобы разработчик сразу увидел полный список расхождений. Сгенерированные блоки PL/SQL
     * пишутся в лог на уровне DEBUG.
     *
     * @param api интерфейс, методы которого нужно реализовать
     * @param rt  окружение времени выполнения: источник соединений, чтение сигнатур, планировщик
     * @param <T> тип интерфейса
     * @return прокси, реализующий {@code api}
     * @throws IllegalArgumentException если {@code api} не интерфейс
     * @throws IllegalStateException    если хотя бы один метод не соответствует базе; в сообщении
     *                                  перечислены все такие методы и причины
     */
    @SuppressWarnings("unchecked")
    public static <T> T create(Class<T> api, PlsqlRuntime rt) {
        if (!api.isInterface()) {
            throw new IllegalArgumentException(api.getName() + " is not an interface");
        }
        PlsqlApi ann = api.getAnnotation(PlsqlApi.class);
        String schema = ann == null || ann.schema().isEmpty() ? null : ann.schema();
        String pkg = ann == null || ann.packageName().isEmpty() ? null : ann.packageName();
        Map<Method, CallPlan> plans = new LinkedHashMap<>();
        Map<Method, QueryPlan> queries = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();

        List<Method> calls = new ArrayList<>();
        for (Method m : api.getMethods()) {
            if (m.isDefault() || java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            SqlQuery q = m.getAnnotation(SqlQuery.class);
            if (q != null) {
                if (m.isAnnotationPresent(Procedure.class)) {
                    problems.add(describe(m) + ": has both @SqlQuery and @Procedure; keep one");
                    continue;
                }
                try {
                    queries.put(m, QueryPlan.of(m, q.value()));
                } catch (RuntimeException e) {
                    problems.add(describe(m) + ": " + message(e));
                }
                continue;
            }
            calls.add(m);
        }

        // Все сигнатуры интерфейса читаются за один заход; по одному имени — только если это не
        // удалось, чтобы ошибка досталась тому методу, к которому она относится.
        Map<String, List<SubprogramInfo>> signatures = null;
        try {
            signatures = rt.signatures().findAll(schema, pkg, calls.stream().map(PlsqlApiInvocationHandler::subprogramName).distinct().toList());
        } catch (RuntimeException e) {
            log.debug("reading all signatures of {} failed, reading one by one", api.getName(), e);
        }
        for (Method m : calls) {
            String name = subprogramName(m);
            String target = (schema != null ? schema + "." : "") + (pkg != null ? pkg + "." : "") + name;
            try {
                List<SubprogramInfo> overloads = signatures != null ? signatures.get(name) : rt.signatures().find(schema, pkg, name);
                CallPlan plan = rt.planner().plan(m, overloads == null ? List.of() : overloads);
                rt.executor().verify(plan);
                plans.put(m, plan);
            } catch (CallPlanner.PlanException e) {
                problems.add(describe(m) + " -> " + target + ": " + e.getMessage());
            } catch (RuntimeException e) {
                problems.add(describe(m) + " -> " + target + ": " + message(e));
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(api.getName() + " does not match the database:\n  - "
                    + String.join("\n  - ", problems));
        }
        if (log.isDebugEnabled()) {
            plans.forEach((m, p) -> log.debug("{}:\n{}", describe(m), p.sql()));
        }
        return (T) Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api},
                new PlsqlApiInvocationHandler(api, rt, plans, queries));
    }

    /**
     * Возвращает имя подпрограммы PL/SQL для метода Java.
     *
     * <p>Если на методе есть {@link Procedure} с непустым значением, берётся оно, в верхнем
     * регистре. Иначе имя метода переводится из camelCase в стиль Oracle ({@link #oracleName}):
     * {@code setParam} → {@code SET_PARAM}, {@code loadXMLData} → {@code LOAD_XML_DATA}.
     *
     * @param m метод интерфейса
     * @return имя процедуры или функции в верхнем регистре, без схемы и пакета
     */
    static String subprogramName(Method m) {
        Procedure p = m.getAnnotation(Procedure.class);
        if (p != null && !p.value().isEmpty()) {
            return p.value().toUpperCase(Locale.ROOT);
        }
        return oracleName(m.getName());
    }

    /**
     * Переводит имя из camelCase в стиль Oracle: слова через подчёркивание, всё в верхнем
     * регистре.
     *
     * <p>Новое слово начинается с заглавной буквы после строчной или цифры ({@code setParam} →
     * {@code SET_PARAM}) и с последней заглавной в ряду заглавных, если за ней идёт строчная:
     * так аббревиатура отделяется от следующего слова ({@code loadXMLData} →
     * {@code LOAD_XML_DATA}, {@code getHTTPStatus} → {@code GET_HTTP_STATUS}). Аббревиатура в
     * конце остаётся целой ({@code getURL} → {@code GET_URL}). Верхний регистр нужен потому, что
     * имена, объявленные без кавычек, Oracle хранит в словаре в верхнем регистре.
     *
     * @param javaName имя метода Java
     * @return имя в стиле Oracle
     */
    static String oracleName(String javaName) {
        return javaName.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("([A-Z])([A-Z][a-z])", "$1_$2")
                .toUpperCase(Locale.ROOT);
    }

    /**
     * Возвращает сгенерированный блок PL/SQL, который стоит за методом прокси, — для логов и тестов.
     *
     * <p>Метод ищется по имени, а если переданы типы параметров — ещё и по ним. Если методов с
     * таким именем несколько, а типы не переданы, это ошибка: какой из них имеется в виду, не
     * угадывается. Для объекта, который не является прокси этой библиотеки, падает с исключением
     * JDK ({@code IllegalArgumentException} или {@code ClassCastException}).
     *
     * @param proxy          прокси, созданный {@link #create}
     * @param methodName     имя метода Java
     * @param parameterTypes типы параметров, чтобы выбрать из перегрузок; можно не передавать
     * @return текст анонимного блока; {@code null}, если такого метода нет или он помечен
     *         {@code @SqlQuery}
     * @throws IllegalArgumentException если по имени подходят несколько методов, а типы не переданы
     */
    public static String sqlOf(Object proxy, String methodName, Class<?>... parameterTypes) {
        PlsqlApiInvocationHandler h = (PlsqlApiInvocationHandler) Proxy.getInvocationHandler(proxy);
        List<CallPlan> found = h.plans.entrySet().stream()
                .filter(e -> e.getKey().getName().equals(methodName))
                .filter(e -> parameterTypes.length == 0 || Arrays.equals(e.getKey().getParameterTypes(), parameterTypes))
                .map(Map.Entry::getValue).toList();
        if (found.size() > 1) {
            throw new IllegalArgumentException(found.size() + " methods are named " + methodName
                    + "; pass the parameter types");
        }
        return found.isEmpty() ? null : found.get(0).sql();
    }

    /**
     * Обрабатывает вызов метода прокси: сюда JDK передаёт каждый вызов метода интерфейса.
     *
     * <ul>
     *   <li>на {@code toString}, {@code hashCode} и {@code equals} из {@link Object} отвечает сам
     *       обработчик; равенство и хеш-код определяются идентичностью объекта прокси;</li>
     *   <li>default-метод интерфейса выполняется как обычный код Java
     *       ({@link InvocationHandler#invokeDefault});</li>
     *   <li>метод с {@code @SqlQuery} выполняет свой SQL;</li>
     *   <li>остальные методы вызывают процедуру по готовому плану.</li>
     * </ul>
     * Время и запроса, и вызова пишется в лог на уровне DEBUG.
     * Запрос и вызов процедуры выполняются как единица работы ({@code unitOfWork}). Повтор после
     * ORA-04068 разрешён только для процедур и только если включён
     * {@link PlsqlRuntime#retryDiscardedState()}.
     *
     * @param proxy  объект прокси, у которого вызвали метод
     * @param method вызванный метод интерфейса
     * @param args   аргументы вызова; {@code null}, если у метода нет параметров
     * @return результат метода, уже приведённый к его типу возврата
     * @throws Throwable исключение из default-метода или ошибка базы, переведённая в исключение
     *                   Spring ({@code DataAccessException} и его подклассы)
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> "PlsqlApi[" + api.getName() + "]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }
        long t0 = System.nanoTime();
        QueryPlan q = queries.get(method);
        String task = q != null ? describe(method) : plans.get(method).target().qualifiedName();
        try {
            if (q != null) {
                return unitOfWork(task, q.sql(), false, con -> query(con, q, args, task));
            }
            CallPlan plan = plans.get(method);
            return unitOfWork(task, plan.sql(), rt.retryDiscardedState(), con -> rt.executor().execute(con, plan, args));
        } catch (EmptyResultDataAccessException e) {
            // NULL в примитивный результат: называем метод, иначе по сообщению не понять, где это.
            throw new EmptyResultDataAccessException(describe(method) + ": " + e.getMessage(), e.getExpectedSize(), e);
        } finally {
            if (log.isDebugEnabled()) {
                log.debug("{} {} ms", task, (System.nanoTime() - t0) / 1_000_000);
            }
        }
    }

    /**
     * Работа, которую нужно выполнить на соединении с базой: вызов процедуры или запрос.
     *
     * <p>Отдельный интерфейс нужен потому, что стандартные функциональные интерфейсы Java не
     * позволяют бросать проверяемое исключение {@link SQLException}.
     */
    @FunctionalInterface
    private interface Work {
        /**
         * Выполняет работу на переданном соединении.
         *
         * @param con соединение текущей транзакции Spring или только что взятое из пула
         * @return результат метода интерфейса
         * @throws SQLException ошибка JDBC или базы
         */
        Object run(Connection con) throws SQLException;
    }

    /**
     * Выполняет работу на соединении текущей транзакции Spring.
     *
     * <p>Соединение берётся через {@link DataSourceUtils}: внутри транзакции Spring
     * ({@code @Transactional}) это соединение, привязанное к транзакции, вне её — соединение из
     * пула. Пул соединений держит открытые соединения с базой и выдаёт их по очереди разным
     * запросам, потому что открывать новое соединение с Oracle долго.
     *
     * <p>Если транзакции нет ({@link TransactionSynchronizationManager#isActualTransactionActive()}),
     * а пул выдаёт соединения с {@code autoCommit=false}, вызов становится отдельной единицей
     * работы, то есть транзакцией из одного вызова: при успехе фиксируется ({@code commit}), при
     * ошибке откатывается ({@code rollback}). Иначе пул откатил бы его при возврате соединения.
     * Внутри транзакции Spring фиксацией и откатом управляет она сама, здесь они не выполняются.
     *
     * <p>ORA-04068 означает, что пакет перекомпилировали, пока сессия держала его состояние
     * (значения переменных пакета): Oracle сбрасывает это состояние и откатывает изменения данных
     * неудачного вызова.
     * Если {@code retry} включён и вызов идёт вне транзакции Spring, такой вызов (и родственные
     * ошибки, см. {@link PlsqlExceptionTranslator#isStateDiscarded}) повторяется один раз на заново
     * взятом соединении: пул и {@code SessionContextDataSource} готовят его сессию с нуля. Внутри
     * транзакции Spring повтора нет: соединение то же, а всё, что предыдущие вызовы транзакции
     * положили в переменные пакетов (контекст пользователя, организацию), уже стёрто, и повтор
     * молча работал бы без этого контекста. Ошибка уходит наружу, и транзакция откатывается. Остальные
     * {@link SQLException} переводятся в исключения Spring через {@link PlsqlExceptionTranslator}.
     * Исключения времени выполнения и ошибки JVM пробрасываются как есть, после отката своей
     * единицы работы.
     *
     * @param task  название операции для сообщений об ошибке
     * @param sql   текст блока или запроса, для сообщений об ошибке
     * @param retry повторять ли один раз после ORA-04068
     * @param work  сама работа с соединением
     * @return результат {@code work}
     */
    private Object unitOfWork(String task, String sql, boolean retry, Work work) {
        for (int attempt = 0; ; attempt++) {
            Connection con = DataSourceUtils.getConnection(rt.dataSource());
            boolean inTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            boolean own = false;
            try {
                own = !inTransaction && !con.getAutoCommit();
                Object result = work.run(con);
                if (own) {
                    con.commit();
                }
                return result;
            } catch (SQLException e) {
                rollbackQuietly(con, own);
                if (retry && attempt == 0 && !inTransaction && PlsqlExceptionTranslator.isStateDiscarded(e)) {
                    log.warn("{}: package state discarded (ORA-{}), calling again", task, e.getErrorCode());
                    continue;
                }
                throw rt.translator().translate(task, sql, e);
            } catch (RuntimeException | Error e) {
                rollbackQuietly(con, own);
                throw e;
            } finally {
                DataSourceUtils.releaseConnection(con, rt.dataSource());
            }
        }
    }

    /**
     * Откатывает транзакцию, если её начал этот вызов, и не бросает исключений.
     *
     * <p>Ошибка отката только пишется в лог: наружу должна уйти исходная ошибка вызова, а не
     * ошибка отката, которая бы её заслонила.
     *
     * @param con соединение
     * @param own {@code true}, если единица работы принадлежит этому вызову; иначе ничего не делается
     */
    private static void rollbackQuietly(Connection con, boolean own) {
        if (!own) {
            return;
        }
        try {
            con.rollback();
        } catch (SQLException e) {
            log.warn("rollback failed", e);
        }
    }

    /**
     * Разобранный {@code @SqlQuery}: SQL разбирается один раз, при старте.
     *
     * <p>Возвращает ли оператор строки или число изменённых строк, решает JDBC во время
     * выполнения, а не угадывание по первому ключевому слову: комментарии, подсказки оптимизатору
     * (hints), {@code WITH} и блоки PL/SQL начинаются с чего-то другого.
     *
     * @param sql        исходный текст с именованными параметрами вида {@code :deptId}
     * @param parsed     результат разбора Spring: где в тексте стоят именованные параметры
     * @param names      имя параметра SQL для каждого параметра метода, по порядку: значение
     *                   {@code @Arg} или имя параметра Java
     * @param returnType полный тип возврата метода, вместе с generic-параметрами
     * @param many       метод возвращает коллекцию: нужны все строки
     * @param optional   метод возвращает {@code Optional}: одна строка или пусто
     * @param element    тип одной строки результата: элемент коллекции, содержимое {@code Optional}
     *                   или сам тип возврата; строки превращаются в него через
     *                   {@link RowMappers#mapAll}
     * @param noResult   метод объявлен как {@code void}: результат не нужен
     */
    private record QueryPlan(String sql, ParsedSql parsed, String[] names, ResolvableType returnType,
                             boolean many, boolean optional, ResolvableType element, boolean noResult) {

        /**
         * Разбирает SQL метода и определяет, как отдавать результат.
         *
         * <p>Именованные параметры ({@code :name}) разбирает {@link NamedParameterUtils} Spring.
         * Имя параметра Java доступно, только если код скомпилирован с ключом {@code -parameters};
         * иначе JDK отдаёт {@code arg0}, {@code arg1}..., и тогда нужен {@code @Arg}. Сразу
         * выполняется пробная подстановка значений {@code null}: если SQL ссылается на параметр,
         * которого у метода нет, исключение возникает здесь, при старте, а не при первом вызове.
         *
         * <p>По типу возврата: коллекция — все строки; {@code Optional} — одна строка или пусто;
         * иначе одна строка (или число изменённых строк для DML).
         *
         * @param m   метод интерфейса с {@code @SqlQuery}
         * @param sql текст из аннотации
         * @return готовый план запроса
         * @throws org.springframework.dao.InvalidDataAccessApiUsageException если в SQL есть
         *         параметр, которого нет у метода
         */
        static QueryPlan of(Method m, String sql) {
            ParsedSql parsed = NamedParameterUtils.parseSqlStatement(sql);
            Parameter[] ps = m.getParameters();
            String[] names = new String[ps.length];
            MapSqlParameterSource probe = new MapSqlParameterSource();
            for (int i = 0; i < ps.length; i++) {
                Arg a = ps[i].getAnnotation(Arg.class);
                names[i] = a != null ? a.value() : ps[i].getName();
                probe.addValue(names[i], null);
            }
            // Бросает исключение здесь, при старте, если SQL ссылается на параметр, которого у метода нет.
            NamedParameterUtils.buildValueArray(parsed, probe, null);
            ResolvableType rt = ResolvableType.forMethodReturnType(m);
            Class<?> raw = rt.resolve(Object.class);
            boolean many = Collection.class.isAssignableFrom(raw);
            boolean optional = raw == Optional.class;
            ResolvableType element = many ? rt.asCollection().getGeneric(0) : optional ? rt.getGeneric(0) : rt;
            boolean noResult = raw == void.class || raw == Void.class;
            return new QueryPlan(sql, parsed, names, rt, many, optional, element, noResult);
        }
    }

    /**
     * Выполняет SQL метода с {@code @SqlQuery} на переданном соединении и приводит результат к
     * типу возврата метода.
     *
     * <p>Шаги:
     * <ul>
     *   <li>строковые аргументы проверяет {@link CharsetGuard}: символ, которого нет в кодовой
     *       странице базы, останавливает вызов до отправки;</li>
     *   <li>значения {@code enum} передаются своим именем ({@code name()});</li>
     *   <li>именованные параметры заменяются на {@code ?}, значения выстраиваются в том же порядке;</li>
     *   <li>SQL выполняется через {@link JdbcTemplate} поверх уже взятого соединения
     *       ({@link SingleConnectionDataSource}, которому запрещено его закрывать), чтобы запрос шёл
     *       в той же единице работы;</li>
     *   <li>ошибки базы переводит тот же {@link PlsqlExceptionTranslator}, что и у процедур:
     *       {@code RAISE_APPLICATION_ERROR} (так код PL/SQL сообщает бизнес-ошибку с кодом
     *       ORA-20000..20999) из триггера тоже становится бизнес-ошибкой.</li>
     * </ul>
     *
     * <p>Результат: если оператор не вернул строк (DML: {@code insert}, {@code update}...),
     * возвращается число изменённых строк, приведённое к типу возврата ({@code int}, {@code long},
     * {@code boolean} — «изменилась ли хоть одна»), или {@code null} для {@code void}. Если вернул
     * строки: для коллекции — список, для {@code Optional} — {@code Optional}, иначе единственная
     * строка или {@code null}, если строк нет. Нет строк (или в строке {@code NULL}), а метод
     * возвращает примитив ({@code int}, {@code long}...) — это ошибка, а не молчаливый 0.
     *
     * @param con  соединение текущей единицы работы
     * @param q    разобранный запрос
     * @param args аргументы вызова в порядке параметров метода
     * @param task название метода для сообщений об ошибках
     * @return результат, приведённый к типу возврата метода
     * @throws IncorrectResultSizeDataAccessException если метод ждёт одну строку, а пришло несколько
     * @throws InvalidDataAccessApiUsageException если оператор вернул число изменённых строк, а
     *         метод ждёт коллекцию, {@code Optional} или объект
     * @throws EmptyResultDataAccessException если метод возвращает примитив, а строк нет или в
     *         строке {@code NULL}
     * @throws CharsetGuard.UnrepresentableCharacterException если строковый аргумент нельзя сохранить
     *         в кодировке базы
     */
    private Object query(Connection con, QueryPlan q, Object[] args, String task) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        for (int i = 0; i < q.names().length; i++) {
            Object v = args[i];
            if (v instanceof CharSequence s) {
                rt.charsetGuard().check(q.names()[i], s);
            }
            params.addValue(q.names()[i], v instanceof Enum<?> e ? e.name() : v);
        }
        String sql = NamedParameterUtils.substituteNamedParameters(q.parsed(), params);
        Object[] values = NamedParameterUtils.buildValueArray(q.parsed(), params, null);
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(con, true));
        // Тот же перевод ошибок, что и у вызовов процедур: RAISE_APPLICATION_ERROR из триггера
        // здесь тоже бизнес-ошибка.
        jdbc.setExceptionTranslator((ignored, s, ex) -> {
            RuntimeException r = rt.translator().translate(task, s, ex);
            if (r instanceof DataAccessException d) {
                return d;
            }
            throw r; // свой переводчик может вернуть и не DataAccessException
        });
        PreparedStatementCreator creator = c -> {
            PreparedStatement ps = c.prepareStatement(sql);
            new ArgumentPreparedStatementSetter(values).setValues(ps);
            return ps;
        };
        return jdbc.execute(creator, (PreparedStatementCallback<Object>) ps -> {
            if (!ps.execute()) {
                if (q.noResult()) {
                    return null;
                }
                Class<?> raw = ClassUtils.resolvePrimitiveIfNecessary(q.returnType().resolve(Object.class));
                if (!Number.class.isAssignableFrom(raw) && raw != Boolean.class && raw != Object.class) {
                    throw new InvalidDataAccessApiUsageException(task + ": the statement returned an update count,"
                            + " not rows; declare the method void, int, long or boolean");
                }
                return Values.convert(ps.getUpdateCount(), q.returnType());
            }
            if (q.noResult()) {
                return null;
            }
            List<Object> rows;
            try (ResultSet rs = ps.getResultSet()) {
                rows = RowMappers.mapAll(rs, q.element());
            }
            if (q.many()) {
                return rows;
            }
            if (rows.size() > 1) {
                throw new IncorrectResultSizeDataAccessException(1, rows.size());
            }
            Object one = rows.isEmpty() ? null : rows.get(0);
            if (rows.isEmpty() && q.element().resolve(Object.class).isPrimitive()) {
                throw new EmptyResultDataAccessException("the query returned no rows; declare "
                        + ClassUtils.resolvePrimitiveIfNecessary(q.element().resolve(Object.class)).getSimpleName()
                        + " or Optional instead", 1);
            }
            return q.optional() ? Optional.ofNullable(one) : Values.convert(one, q.element());
        });
    }

    /**
     * Возвращает короткое имя метода для сообщений: {@code Интерфейс.метод}.
     *
     * @param m метод
     * @return простое имя интерфейса, объявившего метод, и имя метода через точку
     */
    private static String describe(Method m) {
        return m.getDeclaringClass().getSimpleName() + "." + m.getName();
    }

    /**
     * Возвращает первую строку сообщения исключения для списка проблем при старте: сообщения
     * JDBC часто многострочные, а в списке нужна одна строка на метод.
     *
     * @param e исключение
     * @return первая строка сообщения; имя класса исключения, если сообщения нет
     */
    private static String message(Throwable e) {
        String s = e.getMessage();
        if (s == null || s.isBlank()) {
            return e.getClass().getSimpleName();
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
