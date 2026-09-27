package dev.plsql.spring.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import dev.plsql.spring.call.CallPlanner;
import dev.plsql.spring.meta.ArgKind;
import dev.plsql.spring.meta.ArgumentInfo;
import dev.plsql.spring.meta.DictionaryReader;
import dev.plsql.spring.meta.DictionarySignatureSource;
import dev.plsql.spring.meta.SubprogramInfo;

/**
 * Как словарь данных Oracle описывает {@code LAB_PKG}, прочитанный через библиотеку
 * ({@link DictionarySignatureSource}).
 *
 * <p>Словарь данных — системные представления вроде {@code ALL_ARGUMENTS}, {@code ALL_TYPE_ATTRS},
 * {@code ALL_SOURCE}, {@code ALL_OBJECTS}, по которым библиотека при старте узнаёт сигнатуры
 * процедур и функций. Тесты проверяют этот разбор на настоящем словаре 11.2, в котором хватает
 * пробелов и особых случаев.
 */
class DictionaryIT {

    /** Пул из одного соединения к тестовой схеме; закрывается в {@link #tearDown()}. */
    static HikariDataSource ds;
    /** Источник сигнатур библиотеки, читающий словарь через {@link #ds}. */
    static DictionarySignatureSource source;

    /** Создаёт пул из одного соединения и источник сигнатур поверх него. */
    @BeforeAll
    static void setUp() {
        ds = ItDatabase.pool(1, true);
        source = new DictionarySignatureSource(ds);
    }

    /** Закрывает пул, если он был создан. */
    @AfterAll
    static void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    /**
     * Проверяет, что у OUT-аргумента {@code P_ROW LAB_EMP%ROWTYPE} процедуры {@code EMP_ROW} вид —
     * {@code RECORD}, объявленный тип — {@code PLSQL_IT.LAB_EMP}, а поля идут в порядке колонок
     * таблицы ({@code ID}, {@code NAME}, {@code HIRED}, {@code FLAG}).
     *
     * <p>На 11.2 {@code ALL_ARGUMENTS} перечисляет поля такого аргумента, но оставляет пустыми
     * {@code TYPE_OWNER} и {@code TYPE_NAME}; имя таблицы сохраняется только в тексте объявления, и
     * библиотека читает его из {@code ALL_SOURCE}. Без имени таблицы нельзя объявить переменную
     * в блоке вызова.
     */
    @Test
    void rowtypeArgumentGetsItsTableFromTheSource() {
        SubprogramInfo sp = only("EMP_ROW");
        ArgumentInfo row = sp.arguments().get(1);
        assertThat(row.kind()).isEqualTo(ArgKind.RECORD);
        assertThat(row.declaredType()).isEqualTo(ItDatabase.USER + ".LAB_EMP");
        assertThat(row.children()).extracting(ArgumentInfo::name).containsExactly("ID", "NAME", "HIRED", "FLAG");
    }

    /**
     * Проверяет, что у аргумента объектного типа {@code LAB_OBJ} ({@code ECHO_OBJ}) есть атрибуты
     * {@code ID}, {@code NAME}, {@code DT}, а у коллекции {@code LAB_OBJ_TAB} ({@code NAME_CHARS})
     * вид {@code SQL_COLLECTION} и элемент с теми же атрибутами.
     *
     * <p>{@code ALL_ARGUMENTS} описывает PL/SQL-записи поле за полем, но на объектных SQL-типах
     * останавливается: атрибуты берутся из {@code ALL_TYPE_ATTRS}, элементы коллекций — из
     * {@code ALL_COLL_TYPES}. Без атрибутов объект не собрать в {@code java.sql.Struct}.
     */
    @Test
    void objectAttributesComeFromTypeAttrs() {
        ArgumentInfo obj = only("ECHO_OBJ").arguments().get(0);
        assertThat(obj.kind()).isEqualTo(ArgKind.OBJECT);
        assertThat(obj.children()).extracting(ArgumentInfo::name).containsExactly("ID", "NAME", "DT");
        ArgumentInfo coll = only("NAME_CHARS").arguments().get(0);
        assertThat(coll.kind()).isEqualTo(ArgKind.SQL_COLLECTION);
        assertThat(coll.children().get(0).children()).extracting(ArgumentInfo::name).containsExactly("ID", "NAME", "DT");
    }

    /**
     * Проверяет, что результат {@code WRAP_XML} распознаётся как {@code XMLTYPE}, а направление
     * третьего аргумента {@code EMPS_INOUT} ({@code P_CUR}) — как {@code IN/OUT}.
     *
     * <p>{@code XMLTYPE} в словаре выглядит не как обычный скаляр, и его нужно узнать особо, чтобы
     * передавать через {@code CLOB}; направление курсора решает, можно ли вообще вызвать
     * подпрограмму (IN-курсор из Java не передать).
     */
    @Test
    void xmltypeAndCursors() {
        assertThat(only("WRAP_XML").returnValue().kind()).isEqualTo(ArgKind.XMLTYPE);
        assertThat(only("EMPS_INOUT").arguments().get(2).inOut()).isEqualTo("IN/OUT");
    }

    /**
     * Проверяет флаг DEFAULT у аргументов {@code WITH_DEFAULTS} (у {@code P_A} нет, у {@code P_B} и
     * {@code P_C} есть, у {@code P_OUT} нет), что у {@code OVER} находятся обе перегрузки и что у
     * {@code NOOP} нет аргументов.
     *
     * <p>По флагу DEFAULT библиотека решает, можно ли не передавать аргумент; перегрузки нужно видеть
     * все, чтобы выбрать подходящую; а у процедуры без аргументов в {@code ALL_ARGUMENTS} всё же есть
     * одна пустая строка, которую нельзя принять за аргумент.
     */
    @Test
    void defaultsAndOverloads() {
        assertThat(only("WITH_DEFAULTS").arguments()).extracting(ArgumentInfo::defaulted)
                .containsExactly(false, true, true, false);
        assertThat(source.find(null, "LAB_PKG", "OVER")).hasSize(2);
        assertThat(only("NOOP").arguments()).isEmpty();
    }

    /**
     * Проверяет, что отсутствующее имя даёт пустой список, а не исключение: нет подпрограммы в
     * существующем пакете, нет самого пакета, нет автономной процедуры.
     *
     * <p>Отсутствие — нормальный ответ словаря, а не сбой его чтения: о нём потом сообщает
     * планировщик вызовов («not found in the database») для конкретного метода интерфейса. Ошибку
     * ORA-06564 («объект не существует») от {@code DBMS_UTILITY.NAME_RESOLVE} библиотека для этого
     * перехватывает.
     */
    @Test
    void missingNamesAreEmptyNotErrors() {
        assertThat(source.find(null, "LAB_PKG", "NO_SUCH")).isEmpty();
        assertThat(source.find(null, "NO_SUCH_PKG", "X")).isEmpty();
        assertThat(source.find(null, null, "NO_SUCH_PROC")).isEmpty();
    }

    /**
     * Проверяет список неподдерживаемых форм ({@link CallPlanner#supportIssues}): у {@code RECS}
     * (index-by таблица записей) ровно одна проблема с упоминанием {@code index-by table}, а у
     * подпрограмм с {@code BOOLEAN}, {@code RECORD}, {@code %ROWTYPE}, {@code XMLTYPE}, записью с
     * полем {@code XMLTYPE}, IN OUT-курсором и объектным типом проблем нет.
     *
     * <p>Тест фиксирует границу поддержки на реальных сигнатурах: перечисленные трудные формы
     * вызываются, а index-by таблица записей нет, и причина называется понятно.
     */
    @Test
    void onlyIndexTablesOfRecordsAreUnsupported() {
        assertThat(CallPlanner.supportIssues(only("RECS"))).singleElement().asString().contains("index-by table");
        for (String name : List.of("ECHO_BOOL", "REC_INOUT", "EMP_ROW", "WRAP_XML", "XREC_INOUT", "EMPS_INOUT", "ECHO_OBJ")) {
            assertThat(CallPlanner.supportIssues(only(name))).as(name).isEmpty();
        }
    }

    /**
     * Возвращает единственную версию подпрограммы {@code LAB_PKG} с этим именем; если версий не
     * одна (подпрограммы нет или у неё есть перегрузки), тест падает.
     *
     * @param name имя подпрограммы, например {@code EMP_ROW}
     * @return описание подпрограммы из словаря
     */
    private static SubprogramInfo only(String name) {
        List<SubprogramInfo> all = source.find(null, "LAB_PKG", name);
        assertThat(all).as(name).hasSize(1);
        return all.get(0);
    }

    /**
     * Проверяет, что код, который не компилируется, называется невалидным, а не «не найден».
     *
     * <p>Тест создаёт автономную процедуру {@code LAB_BROKEN} с вызовом несуществующего объекта в
     * теле и пакет {@code LAB_BROKEN_PKG} с несуществующим типом в спецификации. Oracle сохраняет
     * оба объекта в состоянии INVALID, и в {@code ALL_ARGUMENTS} для них нет строк — как для
     * отсутствующих. Библиотека смотрит статус в {@code ALL_OBJECTS} и бросает исключение с текстом
     * {@code ... is INVALID} (для процедуры проверяется и класс:
     * {@code DictionaryReader.InvalidObjectException}). В конце оба объекта удаляются.
     *
     * <p>Сообщение «не найден» про существующую процедуру заставило бы искать ошибку не там;
     * «INVALID» сразу говорит, что её нужно исправить и перекомпилировать.
     *
     * @throws Exception при ошибке JDBC
     */
    @Test
    void codeThatDoesNotCompileIsReportedAsInvalidNotMissing() throws Exception {
        try (Connection c = ItDatabase.connect(); Statement st = c.createStatement()) {
            st.execute("create or replace procedure lab_broken(p_x number) is begin no_such_thing; end;");
            st.execute("create or replace package lab_broken_pkg as procedure p(p_x no_such_type); end;");
            try {
                assertThatThrownBy(() -> source.find(null, null, "LAB_BROKEN"))
                        .isInstanceOf(DictionaryReader.InvalidObjectException.class)
                        .hasMessageContaining("LAB_BROKEN is INVALID");
                assertThatThrownBy(() -> source.find(null, "LAB_BROKEN_PKG", "P"))
                        .isInstanceOf(DictionaryReader.InvalidObjectException.class)
                        .hasMessageContaining("LAB_BROKEN_PKG is INVALID");
            } finally {
                dropAll(st, "drop procedure lab_broken", "drop package lab_broken_pkg");
            }
        }
    }

    /**
     * Проверяет пакетное чтение сигнатур ({@code findAll}): в ответе есть запись на каждое
     * запрошенное имя, в том числе пустая для отсутствующего.
     *
     * <p>Для пакета: {@code ECHO_BOOL} (одна версия), {@code OVER} (две перегрузки) и
     * {@code NO_SUCH} (пусто). Для автономных подпрограмм тест создаёт функцию
     * {@code LAB_STANDALONE(P_X)} и процедуру без аргументов {@code LAB_NOARGS}: функция находится с
     * аргументом {@code P_X}, процедура — с пустым списком аргументов, {@code NO_SUCH_PROC} — пусто.
     * Процедуру без аргументов общий запрос к {@code ALL_ARGUMENTS} может не вернуть, и библиотека
     * добирает её отдельно по имени. В конце созданные объекты удаляются.
     *
     * <p>При старте библиотека читает пакет одним запросом, а автономные подпрограммы — одним
     * запросом на 900 имён, вместо нескольких обращений к базе на каждый метод; тест проверяет, что
     * при этом ни одно имя не теряется.
     *
     * @throws Exception при ошибке JDBC
     */
    @Test
    void batchReadGivesEveryRequestedName() throws Exception {
        Map<String, List<SubprogramInfo>> pkg = source.findAll(null, "LAB_PKG", List.of("ECHO_BOOL", "OVER", "NO_SUCH"));
        assertThat(pkg.get("ECHO_BOOL")).hasSize(1);
        assertThat(pkg.get("OVER")).hasSize(2);
        assertThat(pkg.get("NO_SUCH")).isEmpty();
        try (Connection c = ItDatabase.connect(); Statement st = c.createStatement()) {
            st.execute("create or replace function lab_standalone(p_x number) return number is begin return p_x + 1; end;");
            st.execute("create or replace procedure lab_noargs is begin null; end;");
            try {
                Map<String, List<SubprogramInfo>> sa = source.findAll(null, null,
                        List.of("LAB_STANDALONE", "LAB_NOARGS", "NO_SUCH_PROC"));
                assertThat(sa.get("LAB_STANDALONE")).singleElement().satisfies(sp -> {
                    assertThat(sp.isFunction()).isTrue();
                    assertThat(sp.arguments()).extracting(ArgumentInfo::name).containsExactly("P_X");
                });
                assertThat(sa.get("LAB_NOARGS")).singleElement().satisfies(sp -> assertThat(sp.arguments()).isEmpty());
                assertThat(sa.get("NO_SUCH_PROC")).isEmpty();
            } finally {
                dropAll(st, "drop function lab_standalone", "drop procedure lab_noargs");
            }
        }
    }
    /**
     * Удаляет временные объекты теста: каждый отдельно, чтобы неудачное удаление одного не
     * оставило второй и не спрятало ошибку самого теста. Ошибки удаления прикладываются друг к
     * другу и бросаются в конце.
     *
     * @param st    оператор на соединении теста
     * @param drops команды {@code DROP}
     * @throws SQLException первая ошибка удаления, с остальными как подавленными
     */
    private static void dropAll(Statement st, String... drops) throws SQLException {
        SQLException first = null;
        for (String d : drops) {
            try {
                st.execute(d);
            } catch (SQLException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
