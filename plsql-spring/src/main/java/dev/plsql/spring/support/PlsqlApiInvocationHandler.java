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
 * The implementation behind a {@link PlsqlApi} interface.
 *
 * <p>All methods are planned in {@link #create}, before the application starts serving.
 * A method that does not fit its procedure fails the start with the reason, the way
 * Spring Data rejects a bad derived query.
 */
public final class PlsqlApiInvocationHandler implements InvocationHandler {

    private static final Logger log = LoggerFactory.getLogger(PlsqlApiInvocationHandler.class);

    private final Class<?> api;
    private final PlsqlRuntime rt;
    private final Map<Method, CallPlan> plans;
    private final Map<Method, QueryPlan> queries;

    private PlsqlApiInvocationHandler(Class<?> api, PlsqlRuntime rt, Map<Method, CallPlan> plans,
                                      Map<Method, QueryPlan> queries) {
        this.api = api;
        this.rt = rt;
        this.plans = plans;
        this.queries = queries;
    }

    /** Plans every method of the interface; throws with all problems listed at once. */
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

        // All signatures of the interface in one go; per name only if that fails, so the
        // error lands on the method it belongs to.
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

    /** {@code setParam} -> {@code SET_PARAM}, unless {@link Procedure} names it. */
    static String subprogramName(Method m) {
        Procedure p = m.getAnnotation(Procedure.class);
        if (p != null && !p.value().isEmpty()) {
            return p.value().toUpperCase(Locale.ROOT);
        }
        return m.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    /** The generated PL/SQL block behind a method, for logs and tests; null for @SqlQuery. */
    public static String sqlOf(Object proxy, String methodName) {
        PlsqlApiInvocationHandler h = (PlsqlApiInvocationHandler) Proxy.getInvocationHandler(proxy);
        return h.plans.entrySet().stream().filter(e -> e.getKey().getName().equals(methodName))
                .map(e -> e.getValue().sql()).findFirst().orElse(null);
    }

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

    @FunctionalInterface
    private interface Work {
        Object run(Connection con) throws SQLException;
    }

    /**
     * Runs on the transaction's connection. Outside a Spring transaction with a pool in
     * autoCommit=false mode, the call is its own unit of work: committed on success,
     * rolled back on failure, instead of being rolled back by the pool on return.
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
     * A {@code @SqlQuery}, parsed once. Whether it returns rows or an update count is decided
     * by JDBC when it runs, not by guessing from the first keyword (comments, hints, WITH,
     * PL/SQL blocks all start with something else).
     */
    private record QueryPlan(String sql, ParsedSql parsed, String[] names, ResolvableType returnType,
                             boolean many, boolean optional, ResolvableType element, RowMapper<?> mapper) {

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
            // Throws here, at startup, when the SQL names a parameter the method does not have.
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
        // Same mapping as procedure calls: a trigger's RAISE_APPLICATION_ERROR is a business error here too.
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

    private static String describe(Method m) {
        return m.getDeclaringClass().getSimpleName() + "." + m.getName();
    }

    private static String firstLine(String s) {
        int nl = s == null ? -1 : s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
