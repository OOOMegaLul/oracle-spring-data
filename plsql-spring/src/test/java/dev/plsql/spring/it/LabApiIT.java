package dev.plsql.spring.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import com.zaxxer.hikari.HikariDataSource;

import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.annotation.PlsqlApi;
import dev.plsql.spring.support.CharsetGuard;
import dev.plsql.spring.support.PlsqlApiInvocationHandler;
import dev.plsql.spring.support.PlsqlBusinessException;
import dev.plsql.spring.support.Values;

/**
 * Все формы аргументов {@code LAB_PKG} через библиотеку, на настоящей базе.
 *
 * <p>Один раз на класс создаются пул из двух соединений (autoCommit включён), фабрика с
 * настройками по умолчанию и реализация {@link LabApi}. Уже само создание реализации проверяет,
 * что каждый метод {@link LabApi} подходит к своей подпрограмме в словаре Oracle; тесты затем
 * вызывают методы и сверяют результат с тем, что делает PL/SQL-код из {@code it/schema-objects.sql}.
 */
class LabApiIT {

    /** Пул соединений к тестовой схеме; закрывается в {@link #tearDown()}. */
    static HikariDataSource ds;
    /** Фабрика с настройками по умолчанию (в том числе с политикой кодировки {@code FAIL}). */
    static PlsqlApiFactory factory;
    /** Реализация {@link LabApi}, созданная фабрикой. */
    static LabApi api;

    /**
     * Поднимает базу (при первом обращении к {@link ItDatabase}), создаёт пул, фабрику и
     * реализацию {@link LabApi}. Если какой-то метод {@link LabApi} не подходит к базе, все тесты
     * класса падают уже здесь.
     */
    @BeforeAll
    static void setUp() {
        ds = ItDatabase.pool(2, true);
        factory = PlsqlApiFactory.builder(ds).build();
        api = factory.create(LabApi.class);
    }

    /** Закрывает пул, если он был создан. */
    @AfterAll
    static void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    /**
     * Проверяет PL/SQL {@code BOOLEAN} на входе, на выходе и в IN OUT: {@code ECHO_BOOL} и
     * {@code BOOL_INOUT} возвращают отрицание аргумента, а сгенерированный блок действительно
     * превращает 1/0 в TRUE/FALSE через {@code CASE}.
     *
     * <p>Важно потому, что JDBC на 11.2 не умеет передавать {@code BOOLEAN} напрямую
     * (см. {@link RawJdbcIT#plainJdbcCannotBindPlsqlOnlyTypes()}), а в старом PL/SQL-коде такие
     * аргументы встречаются часто.
     */
    @Test
    void booleanArgumentsTravelThroughTheBlock() {
        assertThat(api.echoBool(true)).isFalse();
        assertThat(api.echoBool(false)).isTrue();
        assertThat(api.boolInout(true)).isFalse();
        assertThat(PlsqlApiInvocationHandler.sqlOf(api, "echoBool"))
                .contains("CASE ? WHEN 1 THEN TRUE WHEN 0 THEN FALSE END");
    }

    /**
     * Проверяет PL/SQL {@code RECORD} с полем {@code BOOLEAN}: запись как результат функции
     * ({@code MAKE_REC}) и как IN OUT-аргумент ({@code REC_INOUT}, где PL/SQL меняет {@code id},
     * {@code name} и {@code flag}, а {@code dt} возвращает как было).
     *
     * <p>Запись — чисто PL/SQL-тип, JDBC её не видит; библиотека собирает её поле за полем в
     * переменной блока. Проверка на настоящей базе подтверждает сопоставление полей по именам,
     * преобразование {@code BOOLEAN} и передачу дат в обе стороны.
     */
    @Test
    void recordsWithBooleanFields() {
        assertThat(api.makeRec(7, "seven")).isEqualTo(new LabApi.Rec(7L, "seven", true, LocalDate.of(2024, 2, 29)));
        assertThat(api.recInout(new LabApi.Rec(3L, "abc", true, LocalDate.of(2020, 1, 1))))
                .isEqualTo(new LabApi.Rec(30L, "ABC", false, LocalDate.of(2020, 1, 1)));
    }

    /**
     * Проверяет OUT-аргумент {@code LAB_EMP%ROWTYPE} ({@code EMP_ROW}): строка с {@code id = 1}
     * приходит целиком, включая кириллическое имя и {@code NUMBER(1)}, превращённый в
     * {@code boolean}, а в сгенерированном блоке переменная объявлена как {@code LAB_EMP%ROWTYPE}.
     *
     * <p>На 11.2 {@code ALL_ARGUMENTS} не сообщает, от какой таблицы взят {@code %ROWTYPE}, и
     * библиотека находит таблицу в исходном тексте пакета ({@code ALL_SOURCE}); тест показывает, что
     * этот разбор работает на настоящем словаре.
     */
    @Test
    void rowtypeTableIsTakenFromSource() {
        LabApi.Emp e = api.empRow(1);
        assertThat(e).isEqualTo(new LabApi.Emp(1, "Иванов", LocalDate.of(2020, 1, 15), true));
        assertThat(PlsqlApiInvocationHandler.sqlOf(api, "empRow")).contains("LAB_EMP%ROWTYPE");
    }

    /**
     * Проверяет {@code %ROWTYPE}, объявленный через синоним ({@code EMP_ROW_SYN}) и через курсор
     * пакета ({@code EMP_BRIEF}). Блок компилируется в схеме пользователя соединения, поэтому имя
     * из объявления нужно разрешить так, как его видел компилятор пакета: синоним раскрывается до
     * таблицы, курсор получает имя пакета. Раньше такой метод проходил проверку при старте и
     * падал на первом вызове.
     */
    @Test
    void rowtypeThroughASynonymOrAPackageCursor() {
        assertThat(api.empRowSyn(1)).isEqualTo(new LabApi.Emp(1, "Иванов", LocalDate.of(2020, 1, 15), true));
        assertThat(PlsqlApiInvocationHandler.sqlOf(api, "empRowSyn")).contains("PLSQL_IT.LAB_EMP%ROWTYPE");
        assertThat(api.empBrief(3)).isEqualTo(new LabApi.Brief(3, "Smith"));
        assertThat(PlsqlApiInvocationHandler.sqlOf(api, "empBrief")).contains("PLSQL_IT.LAB_PKG.C_EMP%ROWTYPE");
    }

    /**
     * Проверяет index-by таблицы скаляров: {@code NUMBER} на входе ({@code SUM_IBT}, в том числе
     * пустая таблица) и {@code VARCHAR2} на выходе ({@code IBT_OUT}).
     *
     * <p>Такие таблицы ({@code TABLE OF ... INDEX BY PLS_INTEGER}) существуют только в PL/SQL и
     * передаются особым вызовом драйвера Oracle ({@code setPlsqlIndexTable}); пустой список — частый
     * граничный случай, который не должен ломать вызов.
     */
    @Test
    void indexByTablesOfScalars() {
        assertThat(api.sumIbt(List.of(1L, 2L, 39L))).isEqualByComparingTo("42");
        assertThat(api.ibtOut(3)).containsExactly("item 1", "item 2", "item 3");
        assertThat(api.sumIbt(List.of())).isEqualByComparingTo("0");
    }

    /**
     * Проверяет объектный SQL-тип и SQL-коллекции: коллекцию объектов как результат ({@code OBJS}),
     * коллекцию чисел на входе ({@code SUM_NUMS}), объект на входе и выходе ({@code ECHO_OBJ}) и
     * коллекцию объектов на входе ({@code NAME_CHARS}; функция считает суммарную длину имён:
     * {@code Ёж} и {@code Ёлка} дают 2 + 4 = 6 символов).
     *
     * <p>Эти типы объявлены через {@code CREATE TYPE} и передаются как {@code java.sql.Struct} и
     * {@code java.sql.Array}; тест проверяет сборку атрибутов по именам, кириллицу и даты в обе
     * стороны.
     */
    @Test
    void sqlObjectsAndCollections() {
        assertThat(api.objs(2)).containsExactly(
                new LabApi.Obj(1L, "Объект 1", LocalDate.of(2024, 1, 2)),
                new LabApi.Obj(2L, "Объект 2", LocalDate.of(2024, 1, 3)));
        assertThat(api.sumNums(List.of(5L, 6L))).isEqualTo(11);
        assertThat(api.echoObj(new LabApi.Obj(1L, "x", LocalDate.of(2024, 1, 1))))
                .isEqualTo(new LabApi.Obj(2L, "x!", LocalDate.of(2024, 1, 2)));
        assertThat(api.nameChars(List.of(new LabApi.Obj(1L, "Ёж", null), new LabApi.Obj(2L, "Ёлка", null)))).isEqualTo(6);
    }

    /**
     * Проверяет {@code REF CURSOR} во всех поддерживаемых положениях: OUT-аргумент ({@code EMPS}),
     * результат функции ({@code EMPS_F}) и IN OUT ({@code EMPS_INOUT}); все три дают строки с
     * {@code id} 2 и 3. Если процедура оставила IN OUT-курсор неоткрытым, метод возвращает
     * {@code null}, а не падает.
     *
     * <p>Курсор — основной способ, которым PL/SQL-код отдаёт наборы строк; строки курсора
     * превращаются в record по именам колонок.
     */
    @Test
    void refCursorsOutReturnAndInOut() {
        List<LabApi.Emp> out = api.emps(2);
        assertThat(out).extracting(LabApi.Emp::id).containsExactly(2L, 3L);
        assertThat(api.empsF(2)).isEqualTo(out);
        assertThat(api.empsInout(1, 2)).isEqualTo(out);
        assertThat(api.empsInout(0, 2)).as("cursor left unopened").isEmpty();
    }

    /**
     * Проверяет {@code XMLTYPE}: вход и результат как {@code String} ({@code WRAP_XML}),
     * {@code null} в обе стороны ({@code WRAP_XML}, {@code XML_IS_NULL}, {@code Optional}),
     * OUT-аргумент ({@code XML_OUT}), DOM-{@code Document}, запись с полем {@code XMLTYPE}
     * ({@code XREC_INOUT}, в том числе с пустым полем), а также то, что некорректный XML даёт ошибку
     * Oracle ({@code ORA-...}), а не теряется молча.
     *
     * <p>JDBC на 11.2 не передаёт {@code XMLTYPE}, а {@code XMLTYPE(NULL)} там падает; тест
     * подтверждает, что обход через {@code CLOB} и сохранение {@code NULL} работают на настоящей базе.
     */
    @Test
    void xmltype() {
        assertThat(api.wrapXml("<a>Ёж</a>")).isEqualTo("<wrapped><a>Ёж</a></wrapped>");
        assertThat(api.wrapXml(null)).isNull();
        assertThat(api.xmlIsNull(null)).isEqualTo(1);
        assertThat(api.xmlIsNull("<x/>")).isEqualTo(0);
        assertThat(api.xmlOut(5)).isEqualTo("<n>5</n>");
        assertThat(api.wrapXmlOptional(null)).isEmpty();
        Document dom = api.wrapXmlDom(Values.parse("<b>1</b>"));
        assertThat(dom.getDocumentElement().getTagName()).isEqualTo("wrapped");
        assertThat(api.xrecInout(new LabApi.XRec(1L, "<c/>"))).isEqualTo(new LabApi.XRec(2L, "<wrapped><c/></wrapped>"));
        assertThat(api.xrecInout(new LabApi.XRec(1L, null))).isEqualTo(new LabApi.XRec(2L, null));
        assertThatThrownBy(() -> api.wrapXml("<not closed>")).hasMessageContaining("ORA-");
    }

    /**
     * Проверяет, что {@code RAISE_APPLICATION_ERROR(-20042, ...)} из {@code LAB_PKG.FAIL} приходит
     * как {@link PlsqlBusinessException} с кодом 20042 и сообщением ровно в том виде, в каком его
     * написал PL/SQL-код: без префикса {@code ORA-20042:} и без стека {@code ORA-06512}.
     *
     * <p>Такой текст можно показывать пользователю как есть; кириллица в сообщении заодно проверяет
     * кодировку.
     */
    @Test
    void businessErrorsKeepOnlyTheMessage() {
        assertThatThrownBy(() -> api.fail("Отпуск пересекается с командировкой"))
                .isInstanceOfSatisfying(PlsqlBusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(20042);
                    assertThat(e.getMessage()).isEqualTo("Отпуск пересекается с командировкой");
                });
    }

    /**
     * Проверяет большие {@code CLOB} в обе стороны: 100 000 кириллических символов на входе
     * ({@code CLOB_LEN} возвращает их число) и 100 000 символов на выходе ({@code BIG_CLOB(10000)}).
     *
     * <p>Это больше 32 767 байт — предела {@code VARCHAR2} в PL/SQL, — поэтому такой текст можно
     * передать только как LOB, а не как обычную строку.
     */
    @Test
    void clobsBothWays() {
        String big = "я".repeat(100_000);
        assertThat(api.clobLen(big)).isEqualTo(100_000);
        assertThat(api.bigClob(10_000)).hasSize(100_000);
    }

    /**
     * Проверяет аргументы со значениями по умолчанию ({@code WITH_DEFAULTS} с одним и с двумя
     * параметрами), выбор перегрузки {@code OVER} по типу параметра Java ({@code long} —
     * {@code NUMBER}, {@code String} — {@code VARCHAR2}), несколько OUT-аргументов в одном record
     * ({@code TWO_OUTS}) и процедуру без аргументов ({@code NOOP}).
     *
     * <p>Всё это частые формы в старых PL/SQL API: длинные списки аргументов с DEFAULT, перегрузки
     * с одинаковыми именами аргументов и процедуры, возвращающие несколько значений.
     */
    @Test
    void defaultsOverloadsAndSeveralOuts() {
        assertThat(api.withDefaults(1)).isEqualTo("1/B/2000-01-01");
        assertThat(api.withDefaults(1, "Z")).isEqualTo("1/Z/2000-01-01");
        assertThat(api.overNumber(5)).isEqualTo("number 5");
        assertThat(api.overString("5")).isEqualTo("varchar2 5");
        LabApi.TwoOuts t = api.twoOuts(21);
        assertThat(t.double_()).isEqualByComparingTo(BigDecimal.valueOf(42));
        assertThat(t.text()).isEqualTo("got 21");
        api.noop();
    }

    /**
     * Проверяет методы с {@code @SqlQuery} и {@code default}-метод: поиск одной строки
     * ({@code Optional}, найдена и не найдена), скаляр {@code count(*)}, список по кириллическому
     * имени, {@code default}-метод поверх запроса и UPDATE, начинающийся с комментария, который
     * возвращает число затронутых строк (1 и 0).
     *
     * <p>Интерфейс может смешивать вызовы процедур и обычный SQL; тест подтверждает, что запросы
     * работают на той же базе и что вид результата (строки или число изменённых строк) определяется
     * при выполнении, а не по первому слову запроса.
     */
    @Test
    void queriesAndDefaultMethods() {
        assertThat(api.findEmp(3)).get().extracting(LabApi.Emp::name).isEqualTo("Smith");
        assertThat(api.findEmp(99)).isEmpty();
        assertThat(api.countEmps()).isEqualTo(3);
        assertThat(api.findByName("Петров")).extracting(LabApi.Emp::id).containsExactly(2L);
        assertThat(api.describe(1)).isEqualTo("Иванов");
        assertThat(api.touchEmp(1)).isEqualTo(1);
        assertThat(api.touchEmp(99)).isZero();
    }

    /**
     * Проверяет защиту от молчаливой порчи текста кодировкой базы.
     *
     * <p>В базе {@code CL8MSWIN1251} казахская буква {@code Ә} (U+04D8) непредставима. С политикой
     * по умолчанию ({@code FAIL}) библиотека отказывается отправлять такой текст и в процедуру
     * ({@code UnrepresentableCharacterException} с именем аргумента {@code P_S}, кодом символа и
     * позицией 1), и в {@code @SqlQuery}; с политикой {@code IGNORE} текст уходит, и база, как при
     * обычном JDBC, молча заменяет символ на {@code ?}. В базе с Unicode (например {@code AL32UTF8})
     * тот же текст проходит без изменений. Какие символы база хранит, тест берёт у той же
     * проверки кодировки, поэтому он верен и для других однобайтовых баз (WE8MSWIN1252 и т.п.):
     * строка из хранимых символов ({@code Ёжик € № — «» é} без лишних) возвращается как есть.
     *
     * <p>Без этой проверки данные теряются без всякой ошибки, и это видно только позже, по
     * вопросительным знакам в базе.
     */
    @Test
    void textOutsideTheDatabaseCharsetIsRejected() {
        String kazakh = "Әлем";
        CharsetGuard guard = CharsetGuard.forDatabase(ItDatabase.charset(), CharsetGuard.Policy.FAIL);
        if (!representable(guard, kazakh)) {
            assertThatThrownBy(() -> api.echoStr(kazakh))
                    .isInstanceOf(CharsetGuard.UnrepresentableCharacterException.class)
                    .hasMessageContaining("P_S").hasMessageContaining("U+04D8").hasMessageContaining("position 1");
            assertThatThrownBy(() -> api.findByName(kazakh))
                    .isInstanceOf(CharsetGuard.UnrepresentableCharacterException.class);
            LabApi lenient = PlsqlApiFactory.builder(ds).charsetPolicy(CharsetGuard.Policy.IGNORE).build().create(LabApi.class);
            assertThat(lenient.echoStr(kazakh)).startsWith("?");
        } else {
            assertThat(api.echoStr(kazakh)).isEqualTo(kazakh);
        }
        // Строка из тех символов, которые кодировка базы хранит, проходит туда и обратно без потерь.
        StringBuilder kept = new StringBuilder();
        "Ёжик € № — «» é".codePoints().mapToObj(Character::toString).filter(ch -> representable(guard, ch)).forEach(kept::append);
        assertThat(api.echoStr(kept.toString())).isEqualTo(kept.toString());
    }

    /**
     * Проверяет, сохранит ли база с этой проверкой кодировки такой текст.
     *
     * @param guard проверка кодировки тестовой базы
     * @param text  текст
     * @return {@code true}, если проверка текст пропускает
     */
    private static boolean representable(CharsetGuard guard, String text) {
        try {
            guard.check("text", text);
            return true;
        } catch (CharsetGuard.UnrepresentableCharacterException e) {
            return false;
        }
    }

    /**
     * Интерфейс, который намеренно не совпадает с {@code LAB_PKG} тремя разными способами; нужен
     * тесту {@link #mismatchesAreReportedTogetherAtCreation()}. Реализация для него никогда не
     * создаётся.
     */
    @PlsqlApi(packageName = "LAB_PKG")
    interface Unsupported {
        /**
         * Функция {@code LAB_PKG.RECS} существует, но возвращает index-by таблицу записей
         * ({@code REC_IBT}), а такую форму библиотека не поддерживает: на 11.2 её не передать через
         * JDBC.
         *
         * @param n число записей
         * @return никогда не возвращает: реализация интерфейса не создаётся
         */
        List<Object> recs(int n);

        /** Процедуры {@code LAB_PKG.NO_SUCH_PROCEDURE} в базе нет. */
        void noSuchProcedure();

        /**
         * Функция {@code LAB_PKG.ECHO_STR} есть, но у неё только аргумент {@code P_S}: параметру
         * {@code extra} не с чем сопоставиться.
         *
         * @param s     подходит к {@code P_S}
         * @param extra лишний параметр, которому нет аргумента в PL/SQL
         * @return никогда не возвращает: реализация интерфейса не создаётся
         */
        String echoStr(String s, int extra);

        /**
         * Функция {@code LAB_PKG.XOBJ_ID} принимает объект {@code LAB_XOBJ} с атрибутом
         * {@code XMLTYPE}: внутри {@code Struct} его через JDBC не передать.
         *
         * @param obj объект
         * @return номер объекта
         */
        long xobjId(Map<String, Object> obj);
    }

    /**
     * Проверяет, что при создании реализации {@link Unsupported} библиотека сверяет все методы со
     * словарём и бросает одно {@code IllegalStateException}, в котором перечислены все три проблемы
     * сразу: неподдерживаемая index-by таблица записей, несуществующая процедура и параметр без
     * аргумента.
     *
     * <p>Ошибка должна случаться при старте приложения, а не при первом вызове в работе, и сразу по
     * всем методам, чтобы расхождения не приходилось чинить по одному.
     */
    @Test
    void mismatchesAreReportedTogetherAtCreation() {
        assertThatThrownBy(() -> factory.create(Unsupported.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported.recs")
                .hasMessageContaining("index-by table")
                .hasMessageContaining("Unsupported.noSuchProcedure")
                .hasMessageContaining("not found")
                .hasMessageContaining("'extra' has no matching argument")
                .hasMessageContaining("Unsupported.xobjId")
                .hasMessageContaining("LAB_XOBJ.BODY is OPAQUE/XMLTYPE, which cannot be passed inside a SQL object");
    }
    /**
     * Проверяет index-by таблицы длинных строк ({@code VARCHAR2(32767)}): на входе строка в
     * 10 000 символов, на выходе строки по 5 000 (раньше длина элемента была зашита в 4000).
     * Выходная таблица с резервом по умолчанию — 10 000 элементов по 32766 символов, около
     * 1,5 ГБ на вызов — отвергается при создании с советом, до скольки снизить
     * {@code indexTableMaxLength}; с {@code indexTableMaxLength(100)} она работает.
     */
    @Test
    void longStringsInIndexByTables() {
        assertThat(api.longLen(List.of("a", "b".repeat(10_000)))).isEqualByComparingTo("10001");

        LongTables small = PlsqlApiFactory.builder(ds).indexTableMaxLength(100).build().create(LongTables.class);
        List<String> out = small.longOut(3, 5_000);
        assertThat(out).hasSize(3).allSatisfy(v -> assertThat(v).hasSize(5_000).startsWith("vxx"));

        assertThatThrownBy(() -> factory.create(LongTables.class))
                .hasMessageContaining("OUT index-by table P_VALS of VARCHAR2(32766)")
                .hasMessageContaining("lower plsql.index-table-max-length to 1525");
    }

    /**
     * Выходная index-by таблица {@code VARCHAR2(32767)}: драйвер резервирует под каждый из
     * {@code indexTableMaxLength} элементов объявленную длину, поэтому такой интерфейс создаётся
     * только фабрикой с небольшим {@code indexTableMaxLength}.
     */
    @PlsqlApi(packageName = "LAB_PKG")
    interface LongTables {
        /**
         * Вызывает процедуру {@code LAB_PKG.LONG_OUT(P_N, P_LEN, P_VALS OUT LONG_IBT)}: {@code n}
         * строк длиной {@code len}.
         *
         * @param n   сколько строк
         * @param len длина каждой
         * @return строки
         */
        List<String> longOut(int n, int len);
    }

    /**
     * Проверяет, что колонки курсора с типовыми префиксами ({@code NRN}, {@code SNAME},
     * {@code DHIRED}) ложатся на компоненты record без префиксов, как и аргументы процедур.
     */
    @Test
    void cursorColumnsFollowTheArgumentNamingRules() {
        assertThat(api.empsPrefixed()).first()
                .isEqualTo(new LabApi.Brief2(1, "Иванов", LocalDate.of(2020, 1, 15)));
    }
}
