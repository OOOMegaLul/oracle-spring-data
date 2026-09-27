package dev.plsql.spring.session;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import oracle.jdbc.OracleConnection;

/**
 * Делает соединение из пула «принадлежащим» текущему пользователю до того, как кто-либо им
 * воспользуется.
 *
 * <p>Пул соединений держит открытые соединения с базой и выдаёт их по очереди разным
 * пользователям; сессия Oracle (состояние на стороне сервера, связанное с соединением) при этом
 * не пересоздаётся. Поэтому между пользователями пула утекают две вещи: кем база считает
 * работающего пользователя, и всё, что предыдущий пользователь оставил в переменных пакетов и во
 * временных таблицах уровня сессии. Состояние пакета — это значения переменных, объявленных на
 * уровне пакета PL/SQL: они живут, пока жива сессия. APEX скрывает и то и другое, потому что
 * сбрасывает сессию на каждый запрос страницы. Пул так не делает, поэтому эта обёртка делает это
 * при каждой выдаче соединения:
 * <ol>
 *   <li>{@code resetSql} (по умолчанию — переинициализация состояния всех пакетов,
 *       {@link #REINITIALIZE_PACKAGES}) — одно обращение к базе по сети (round trip). Вариант
 *       {@link #setResetProbeSql} заодно сообщает, какой ключ сейчас записан в сессии;</li>
 *   <li>пользователь попадает в CLIENT_IDENTIFIER через end-to-end metrics JDBC; они уходят в
 *       базу вместе со следующим вызовом и отдельного обращения не требуют. CLIENT_IDENTIFIER —
 *       метка сессии, которую видно в {@code V$SESSION}, в аудите и через
 *       {@code SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER')};</li>
 *   <li>{@code scopedSql}, если задан ({@link #setScopedInitSql}), — только когда в сессии записан
 *       другой ключ (без пробы — всегда); фиксируется отдельно;</li>
 *   <li>{@code initSql}, если задан, — для всего остального, что код ожидает найти в сессии:
 *       пользователь там, где его читает старый код ({@code apex_application.g_user}),
 *       организация ({@code APP_CONTEXT.SET_TENANT}), — ещё одно обращение.
 *       Его нельзя объединить в один блок со сбросом: состояние пакетов переинициализируется,
 *       когда заканчивается блок, который об этом попросил, поэтому значения, записанные в том
 *       же блоке, стираются.</li>
 * </ol>
 *
 * <p>Сброс по умолчанию касается только переменных пакетов; строки временных таблиц он не
 * удаляет — для них нужен свой {@code resetSql} или {@link #setScopedInitSql}.
 *
 * <p>Вне транзакции Spring каждый вызов берёт соединение заново, поэтому каждый вызов
 * начинается с чистой сессии. Код, который записывает состояние сессии в одном вызове и читает
 * его в следующем, должен работать внутри {@code @Transactional}: транзакция держит одно
 * соединение на весь запрос.
 */
public class SessionContextDataSource extends DelegatingDataSource {

    /**
     * Блок сброса по умолчанию. {@code DBMS_SESSION.MODIFY_PACKAGE_STATE(DBMS_SESSION.REINITIALIZE)}
     * переинициализирует состояние всех пакетов сессии: переменные пакетов теряют значения,
     * записанные предыдущим пользователем. Сброс применяется, когда заканчивается этот блок.
     */
    public static final String REINITIALIZE_PACKAGES =
            "begin dbms_session.modify_package_state(dbms_session.reinitialize); end;";

    /** Кто сейчас работает; значение уходит в CLIENT_IDENTIFIER при каждой выдаче. */
    private final Supplier<String> currentUser;
    /** Блок, который выполняется первым при каждой выдаче; {@code null} — не выполнять. */
    private String resetSql = REINITIALIZE_PACKAGES;
    /** Блок, который выполняется последним при каждой выдаче; {@code null} — не выполнять. */
    private String initSql;
    /** Поставщики значений для {@code ?} в {@code initSql}, по порядку. */
    private List<Supplier<?>> initBinds = List.of();
    /** {@code true}, если {@code resetSql} через свой единственный OUT-параметр сообщает ключ сессии. */
    private boolean resetReportsKey;
    /** Нужный ключ сессии (например, организация); сравнивается с тем, что сообщил сброс. */
    private Supplier<?> scopeKey;
    /** Блок, который записывает ключ в сессию; выполняется только при расхождении. */
    private String scopedSql;
    /** Поставщики значений для {@code ?} в {@code scopedSql}, по порядку. */
    private List<Supplier<?>> scopedBinds = List.of();

    /**
     * Оборачивает источник соединений (обычно пул) так, чтобы каждое выданное соединение
     * готовилось для текущего пользователя.
     *
     * <p>Сразу после создания выполняются только сброс пакетов ({@link #REINITIALIZE_PACKAGES}) и
     * запись пользователя в CLIENT_IDENTIFIER; остальное включается сеттерами.
     *
     * @param target      настоящий источник соединений, обычно пул (например, HikariCP)
     * @param currentUser возвращает имя текущего пользователя; вызывается при каждой выдаче
     *                    соединения, {@code null} от него записывается как пустая строка
     */
    public SessionContextDataSource(DataSource target, Supplier<String> currentUser) {
        super(target);
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
    }

    /**
     * Задаёт оператор, который выполняется первым при каждой выдаче соединения; {@code null} —
     * ничего не выполнять.
     *
     * <p>Выключает режим пробы ({@link #setResetProbeSql}): сброс больше не сообщает ключ сессии,
     * поэтому блок из {@link #setScopedInitSql} будет выполняться при каждой выдаче.
     *
     * @param resetSql оператор или блок PL/SQL без параметров; {@code null} — без сброса
     */
    public void setResetSql(String resetSql) {
        this.resetSql = resetSql;
        this.resetReportsKey = false;
    }

    /**
     * Задаёт блок сброса, единственный параметр которого — OUT: через него блок сообщает ключ,
     * который сейчас записан в сессии (например, организацию, сохранённую в таблице сессии).
     *
     * <p>Ключ читается за то же обращение к базе, что и сброс, поэтому блок из
     * {@link #setScopedInitSql} ничего не стоит, когда он не нужен. Параметр регистрируется как
     * {@code VARCHAR}, и строка сравнивается со строковым видом ({@code toString()}) нужного ключа.
     * Ключ стоит хранить в таблице, а не в переменной пакета: переменную пакета сбросит этот же блок.
     * Пример:
     * <pre>{@code
     * begin
     *   dbms_session.modify_package_state(dbms_session.reinitialize);
     *   ? := app_context.stored_tenant;  -- функция читает временную таблицу сессии
     * end;
     * }</pre>
     *
     * @param resetSql блок сброса с одним OUT-параметром {@code ?}
     */
    public void setResetProbeSql(String resetSql) {
        this.resetSql = resetSql;
        this.resetReportsKey = true;
    }

    /**
     * Задаёт блок, который выполняется после сброса, последним; каждый {@code ?} получает значение
     * от поставщика с тем же номером.
     *
     * <p>Поставщики ({@link Supplier}) вызываются при каждой выдаче соединения, поэтому значения
     * берутся для текущего пользователя, а не запоминаются один раз. Пример:
     * {@code setInitSql("begin apex_application.g_user := ?; end;", currentUser::get)}.
     *
     * @param initSql блок PL/SQL; {@code null} — ничего не выполнять
     * @param binds   поставщики значений для параметров {@code ?}, по порядку
     */
    public void setInitSql(String initSql, Supplier<?>... binds) {
        this.initSql = initSql;
        this.initBinds = List.of(binds);
    }

    /**
     * Задаёт блок, который выполняется, только когда {@code key} отличается от того, что проба
     * сброса сообщила о сессии, и фиксируется отдельно, своим {@code commit}.
     *
     * <p>Он нужен для состояния сессии, которое приходится записывать (строка во временной
     * таблице): запись на каждом запросе делает каждый запрос пишущей транзакцией, которая в конце
     * ждёт журнал повторного выполнения (redo log). Проба читает саму сессию, а не запоминает, что
     * записала эта обёртка, потому что это состояние может изменить и код PL/SQL.
     *
     * <p>Без пробы ({@link #setResetProbeSql}) сравнивать не с чем, и блок выполняется при каждой
     * выдаче. Явный {@code commit} выполняется только при {@code autoCommit=false}; при
     * {@code autoCommit=true} запись фиксирует сам драйвер.
     *
     * @param key   поставщик нужного ключа; сравнение идёт по {@code toString()}, {@code null}
     *              означает «ключа нет»
     * @param sql   блок, который записывает ключ в сессию
     * @param binds поставщики значений для {@code ?} в {@code sql}, по порядку
     */
    public void setScopedInitSql(Supplier<?> key, String sql, Supplier<?>... binds) {
        this.scopeKey = key;
        this.scopedSql = sql;
        this.scopedBinds = List.of(binds);
    }

    /**
     * Берёт соединение из обёрнутого источника и готовит его для текущего пользователя.
     *
     * @return подготовленное соединение
     * @throws SQLException если соединение не удалось получить или подготовить; во втором
     *                      случае оно уже закрыто
     */
    @Override
    public Connection getConnection() throws SQLException {
        return prepare(super.getConnection());
    }

    /**
     * Берёт соединение с явно указанными учётными данными и готовит его для текущего пользователя.
     *
     * @param username имя пользователя базы
     * @param password пароль
     * @return подготовленное соединение
     * @throws SQLException если соединение не удалось получить или подготовить; во втором
     *                      случае оно уже закрыто
     */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return prepare(super.getConnection(username, password));
    }

    /**
     * Подставляет значения поставщиков в параметры {@code ?} оператора, по порядку, с первого.
     *
     * @param cs    подготовленный вызов
     * @param binds поставщики значений; каждый вызывается прямо сейчас
     * @throws SQLException если драйвер не принял значение
     */
    private static void bind(CallableStatement cs, List<Supplier<?>> binds) throws SQLException {
        for (int i = 0; i < binds.size(); i++) {
            cs.setObject(i + 1, binds.get(i).get());
        }
    }

    /**
     * Готовит только что выданное соединение для текущего пользователя.
     *
     * <p>Шаги по порядку:
     * <ul>
     *   <li>сброс ({@code resetSql}); в режиме пробы он же возвращает ключ, записанный в сессии;</li>
     *   <li>пользователь записывается в CLIENT_IDENTIFIER через свойство {@code OCSID.CLIENTID}
     *       драйвера Oracle (интерфейс {@link OracleConnection}; {@code unwrap} достаёт настоящее
     *       соединение драйвера из обёртки пула). Свойство уходит в базу вместе со следующим
     *       вызовом;</li>
     *   <li>блок ключа сессии ({@code scopedSql}) — если в сессии другой ключ или пробы нет; после
     *       него {@code commit}, если соединение не в режиме {@code autoCommit};</li>
     *   <li>начальный блок ({@code initSql}).</li>
     * </ul>
     *
     * <p>Если любой шаг упал, соединение закрывается (у пула это значит «возвращается в пул»), и
     * ошибка пробрасывается дальше: полуподготовленное соединение наружу не отдаётся. Если и
     * закрыть не удалось, эта вторая ошибка прикладывается к первой как подавленная.
     *
     * @param con только что выданное соединение
     * @return то же соединение, готовое к работе
     * @throws SQLException если какой-либо шаг завершился ошибкой; соединение к этому моменту закрыто
     */
    private Connection prepare(Connection con) throws SQLException {
        try {
            String user = currentUser.get();
            String current = null;
            if (resetSql != null) {
                try (CallableStatement cs = con.prepareCall(resetSql)) {
                    if (resetReportsKey) {
                        cs.registerOutParameter(1, java.sql.Types.VARCHAR);
                    }
                    cs.execute();
                    if (resetReportsKey) {
                        current = cs.getString(1);
                    }
                }
            }
            OracleConnection oc = con.unwrap(OracleConnection.class);
            oc.setClientInfo("OCSID.CLIENTID", user == null ? "" : user);
            if (scopedSql != null) {
                Object key = scopeKey.get();
                String wanted = key == null ? null : key.toString();
                if (!resetReportsKey || !Objects.equals(current, wanted)) {
                    try (CallableStatement cs = con.prepareCall(scopedSql)) {
                        bind(cs, scopedBinds);
                        cs.execute();
                    }
                    if (!con.getAutoCommit()) {
                        con.commit(); // других незафиксированных изменений на свежем соединении нет
                    }
                }
            }
            if (initSql != null) {
                try (CallableStatement cs = con.prepareCall(initSql)) {
                    bind(cs, initBinds);
                    cs.execute();
                }
            }
            return con;
        } catch (SQLException | RuntimeException e) {
            try {
                con.close();
            } catch (SQLException closeFailure) {
                e.addSuppressed(closeFailure); // наружу идёт причина, а не ошибка закрытия
            }
            throw e;
        }
    }
}
