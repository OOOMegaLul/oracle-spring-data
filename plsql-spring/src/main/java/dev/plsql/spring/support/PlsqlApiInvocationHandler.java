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
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;
import org.springframework.jdbc.core.namedparam.ParsedSql;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
     *   <li>{@link CallPlanner} подбирает для метода подходящую перегрузку и строит {@link CallPlan}.</li>
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
                try {
                    queries.put(m, QueryPlan.of(m, q.value()));
                } catch (RuntimeException e) {
                    problems.add(describe(m) + ": " + firstLine(e.getMessage()));
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
            String target = (pkg != null ? pkg + "." : "") + name;
            try {
                List<SubprogramInfo> overloads = signatures != null ? signatures.get(name) : rt.signatures().find(schema, pkg, name);
                plans.put(m, rt.planner().plan(m, overloads == null ? List.of() : overloads));
            } catch (CallPlanner.PlanException e) {
                problems.add(describe(m) + " -> " + target + ": " + e.getMessage());
            } catch (RuntimeException e) {
                problems.add(describe(m) + " -> " + target + ": " + firstLine(e.getMessage()));
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
     * регистре. Иначе имя метода переводится из camelCase в стиль Oracle: перед заглавной буквой,
     * которая стоит после строчной буквы или цифры, вставляется подчёркивание, и всё имя
     * переводится в верхний регистр: {@code setParam} → {@code SET_PARAM}. Несколько заглавных
     * подряд не разделяются: {@code loadXMLData} → {@code LOAD_XMLDATA}. Верхний регистр нужен
     * потому, что имена, объявленные без кавычек, Oracle хранит в словаре в верхнем регистре.
     *
     * @param m метод интерфейса
     * @return имя процедуры или функции в верхнем регистре, без схемы и пакета
     */
    static String subprogramName(Method m) {
        Procedure p = m.getAnnotation(Procedure.class);
        if (p != null && !p.value().isEmpty()) {
            return p.value().toUpperCase(Locale.ROOT);
        }
        return m.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    /**
     * Возвращает сгенерированный блок PL/SQL, который стоит за методом прокси, — для логов и тестов.
     *
     * <p>Метод ищется только по имени. Если в интерфейсе несколько перегруженных методов с таким
     * именем, возвращается блок одного из них (порядок методов в JDK не определён). Для объекта,
     * который не является прокси этой библиотеки, падает с исключением JDK
     * ({@code IllegalArgumentException} или {@code ClassCastException}).
     *
     * @param proxy      прокси, созданный {@link #create}
     * @param methodName имя метода Java
     * @return текст анонимного блока; {@code null}, если такого метода нет или он помечен
     *         {@code @SqlQuery}
     */
    public static String sqlOf(Object proxy, String methodName) {
        PlsqlApiInvocationHandler h = (PlsqlApiInvocationHandler) Proxy.getInvocationHandler(proxy);
        return h.plans.entrySet().stream().filter(e -> e.getKey().getName().equals(methodName))
                .map(e -> e.getValue().sql()).findFirst().orElse(null);
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
     *   <li>остальные методы вызывают процедуру по готовому плану; время вызова пишется в лог на
     *       уровне DEBUG.</li>
     * </ul>
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
        QueryPlan q = queries.get(method);
        if (q != null) {
            return unitOfWork(describe(method), q.sql(), false, con -> query(con, q, args));
        }
        CallPlan plan = plans.get(method);
        long t0 = System.nanoTime();
        try {
            return unitOfWork(plan.target().qualifiedName(), plan.sql(), rt.retryDiscardedState(),
                    con -> rt.executor().execute(con, plan, args));
        } finally {
            if (log.isDebugEnabled()) {
                log.debug("{} {} ms", plan.target().qualifiedName(), (System.nanoTime() - t0) / 1_000_000);
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
     * Если {@code retry} включён, такой вызов (и родственные ошибки, см.
     * {@link PlsqlExceptionTranslator#isStateDiscarded}) повторяется один раз; соединение при этом
     * запрашивается заново, внутри транзакции это то же самое соединение. Остальные
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
            boolean own = false;
            try {
                own = !TransactionSynchronizationManager.isActualTransactionActive() && !con.getAutoCommit();
                Object result = work.run(con);
                if (own) {
                    con.commit();
                }
                return result;
            } catch (SQLException e) {
                rollbackQuietly(con, own);
                if (retry && attempt == 0 && PlsqlExceptionTranslator.isStateDiscarded(e)) {
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
     *                   или сам тип возврата
     * @param mapper     как превратить строку {@code ResultSet} в {@code element}: запись или бин —
     *                   по именам колонок, простой тип — из единственной колонки, {@code Map} или
     *                   {@code Object} — в {@code Map}; {@code null} для {@code void}-методов
     */
    private record QueryPlan(String sql, ParsedSql parsed, String[] names, ResolvableType returnType,
                             boolean many, boolean optional, ResolvableType element, RowMapper<?> mapper) {

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
         * иначе одна строка (или число изменённых строк для DML). Для {@code void} и {@code Void}
         * преобразователь строк не создаётся.
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
            Class<?> elementClass = element.resolve(Object.class);
            RowMapper<?> mapper = elementClass == void.class || elementClass == Void.class
                    ? null : RowMappers.forType(elementClass);
            return new QueryPlan(sql, parsed, names, rt, many, optional, element, mapper);
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
     * возвращается число изменённых строк, приведённое к типу возврата, или {@code null} для
     * {@code void}. Если вернул строки: для коллекции — список, для {@code Optional} —
     * {@code Optional}, иначе единственная строка или {@code null}, если строк нет.
     *
     * @param con  соединение текущей единицы работы
     * @param q    разобранный запрос
     * @param args аргументы вызова в порядке параметров метода
     * @return результат, приведённый к типу возврата метода
     * @throws IncorrectResultSizeDataAccessException если метод ждёт одну строку, а пришло несколько
     * @throws CharsetGuard.UnrepresentableCharacterException если строковый аргумент нельзя сохранить
     *         в кодировке базы
     */
    private Object query(Connection con, QueryPlan q, Object[] args) {
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
        jdbc.setExceptionTranslator((task, s, ex) ->
                rt.translator().translate(task, s, ex) instanceof DataAccessException d ? d : null);
        PreparedStatementCreator creator = c -> {
            PreparedStatement ps = c.prepareStatement(sql);
            new ArgumentPreparedStatementSetter(values).setValues(ps);
            return ps;
        };
        return jdbc.execute(creator, (PreparedStatementCallback<Object>) ps -> {
            if (!ps.execute()) {
                Class<?> raw = q.returnType().resolve(Object.class);
                return raw == void.class || raw == Void.class ? null : Values.convert(ps.getUpdateCount(), q.returnType());
            }
            if (q.mapper() == null) {
                return null;
            }
            List<Object> rows = new ArrayList<>();
            try (ResultSet rs = ps.getResultSet()) {
                int n = 0;
                while (rs.next()) {
                    rows.add(q.mapper().mapRow(rs, n++));
                }
            }
            if (q.many()) {
                return rows;
            }
            if (rows.size() > 1) {
                throw new IncorrectResultSizeDataAccessException(1, rows.size());
            }
            Object one = rows.isEmpty() ? null : rows.get(0);
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
     * Возвращает первую строку текста. Сообщения об ошибках JDBC часто многострочные, а в списке
     * проблем при старте нужна одна строка на метод.
     *
     * @param s текст, может быть {@code null}
     * @return текст до первого перевода строки; весь текст, если перевода строки нет;
     *         {@code null}, если {@code s} равен {@code null}
     */
    private static String firstLine(String s) {
        int nl = s == null ? -1 : s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
