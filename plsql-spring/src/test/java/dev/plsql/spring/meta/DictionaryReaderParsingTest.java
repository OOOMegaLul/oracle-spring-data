package dev.plsql.spring.meta;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import dev.plsql.spring.test.Signatures;

/**
 * Разбор исходного текста PL/SQL в {@code DictionaryReader}. Тип {@code %ROWTYPE}
 * восстанавливается из текста объявления, потому что 11.2 не указывает его в
 * {@code ALL_ARGUMENTS}: поля такого аргумента там перечислены, а {@code TYPE_OWNER} и
 * {@code TYPE_NAME} пусты. В работе текст берётся из {@code ALL_SOURCE}, здесь его заменяет
 * строка. Заодно проверяется, как вид аргумента определяется по {@code DATA_TYPE}.
 */
class DictionaryReaderParsingTest {

    /**
     * Спецификация пакета-образца: закомментированные объявления {@code emp_row} с «чужими»
     * таблицами, две настоящие перегрузки {@code emp_row}, функция, возвращающая
     * {@code %ROWTYPE}, и процедура, у которой в значении по умолчанию стоит строковый литерал
     * {@code 'emp_row;'}; курсор {@code c_emp} (и закомментированный {@code c_old}) и процедура с
     * аргументом {@code c_emp%rowtype}.
     */
    static final String SPEC = """
            create or replace package hr_pkg as
              -- procedure emp_row(p_row out wrong_table%rowtype);  (commented out)
              /* function emp_row return also_wrong%rowtype; */
              procedure emp_row(p_id number, p_row out nocopy emp%rowtype);
              procedure emp_row(p_id varchar2, p_row in out hr.emp_hist%rowtype);
              function last_emp return emp%rowtype;
              procedure emp_rowtype_like(p_note varchar2 default 'emp_row;', p_x out dept%rowtype);
              cursor c_emp is select id, name from emp;
              -- cursor c_old is select * from emp;
              procedure cur_row(p_row out c_emp%rowtype);
            end;
            """;

    /**
     * Проверяет, что перегрузка находится по номеру в порядке объявления (так её нумерует
     * {@code ALL_ARGUMENTS.OVERLOAD}), закомментированные объявления пропускаются, имя ищется
     * без учёта регистра, а тип {@code %ROWTYPE} читается и после {@code OUT NOCOPY}, и после
     * {@code IN OUT}. Имя возвращается как написано ({@code EMP}, {@code HR.EMP_HIST}): что оно
     * значит — таблицу, синоним или курсор, — решается уже по словарю.
     */
    @Test
    void findsTheRightOverload() {
        String first = DictionaryReader.findDeclaration(SPEC, "EMP_ROW", "1");
        assertThat(first).startsWith("PROCEDURE EMP_ROW(P_ID NUMBER").doesNotContain("WRONG");
        assertThat(DictionaryReader.rowtypeOf(first, "P_ROW")).isEqualTo("EMP");

        String second = DictionaryReader.findDeclaration(SPEC, "EMP_ROW", "2");
        assertThat(DictionaryReader.rowtypeOf(second, "P_ROW")).isEqualTo("HR.EMP_HIST");
    }

    /** Проверяет, что {@code %ROWTYPE} в {@code RETURN} функции тоже читается. */
    @Test
    void functionReturningRowtype() {
        String decl = DictionaryReader.findDeclaration(SPEC, "LAST_EMP", null);
        assertThat(DictionaryReader.returnRowtypeOf(decl)).isEqualTo("EMP");
    }

    /**
     * Проверяет объявление {@code EMP_ROWTYPE_LIKE}, имя которого начинается с {@code EMP_ROW}, а
     * значение по умолчанию — строковый литерал {@code 'emp_row;'}. Объявление находится,
     * литерал остаётся в его тексте (это не комментарий, и точка с запятой внутри него не
     * обрывает заголовок), а тип {@code %ROWTYPE} следующего аргумента читается
     * ({@code DEPT}). Для отсутствующей подпрограммы возвращается пустая строка.
     */
    @Test
    void nameIsMatchedAsAWholeWordAndStringsAreNotComments() {
        String decl = DictionaryReader.findDeclaration(SPEC, "EMP_ROWTYPE_LIKE", null);
        assertThat(decl).contains("'EMP_ROW;'");
        assertThat(DictionaryReader.rowtypeOf(decl, "P_X")).isEqualTo("DEPT");
        assertThat(DictionaryReader.findDeclaration(SPEC, "NOT_THERE", null)).isEmpty();
    }

    /**
     * Проверяет, что заголовок подпрограммы с телом обрывается перед ключевым словом {@code IS},
     * с которого начинается тело. Буквы {@code AS} в начале имени типа {@code ASSET_T} стоят
     * после пробела вне скобок, но это часть имени, а не ключевое слово, и заголовок на них не
     * обрывается. Текст объявления возвращается в верхнем регистре.
     */
    @Test
    void headerEndsAtIsOrAsButNotInsideNames() {
        String body = "procedure p(p_row out emp%rowtype, p_issue_date date) is begin null; end;";
        assertThat(DictionaryReader.findDeclaration(body, "P", null)).endsWith("P_ISSUE_DATE DATE) ");

        String fn = "function f(p_id number) return asset_t is begin return null; end;";
        assertThat(DictionaryReader.findDeclaration(fn, "F", null)).endsWith("RETURN ASSET_T ");
    }

    /**
     * Проверяет, что {@code %ROWTYPE} курсора пакета читается, а сам курсор узнаётся по
     * объявлению {@code CURSOR}: целым словом, без учёта регистра и не в комментарии.
     */
    @Test
    void packageCursorIsRecognised() {
        String decl = DictionaryReader.findDeclaration(SPEC, "CUR_ROW", null);
        assertThat(DictionaryReader.rowtypeOf(decl, "P_ROW")).isEqualTo("C_EMP");
        assertThat(DictionaryReader.declaresCursor(SPEC, "C_EMP")).isTrue();
        assertThat(DictionaryReader.declaresCursor(SPEC, "C_EM")).isFalse();
        assertThat(DictionaryReader.declaresCursor(SPEC, "C_OLD")).isFalse();
        assertThat(DictionaryReader.declaresCursor(SPEC, "EMP")).isFalse();
    }

    /**
     * Проверяет удаление комментариев: строчный ({@code --} до конца строки) и блочный
     * комментарий заменяются пробелами той же длины, так что позиции в тексте не сдвигаются, а
     * строковые литералы, в том числе с {@code --} внутри, с удвоенной кавычкой
     * ({@code 'it''s'}) и с другой кавычкой ({@code q'[-- ']'}), и имена в кавычках остаются
     * нетронутыми.
     */
    @Test
    void stripCommentsKeepsLiterals() {
        String src = "a -- x\nb /* y */ c 'd -- e' 'it''s' q'[-- ']' \"--\"";
        String out = DictionaryReader.stripComments(src);
        assertThat(out).hasSameSizeAs(src).isEqualTo("a     \nb         c 'd -- e' 'it''s' q'[-- ']' \"--\"");
    }

    /**
     * Проверяет, что строковые литералы не сбивают разбор заголовка: скобка в значении по
     * умолчанию ({@code DEFAULT ')'}) не закрывает список аргументов, а слово {@code PROCEDURE}
     * внутри литерала — в том числе в {@code q'[...]'} — не считается ещё одной перегрузкой.
     * {@code IS} сразу после скобки, без пробела, заголовок обрывает.
     */
    @Test
    void literalsDoNotConfuseTheHeader() {
        String spec = """
                create or replace package p as
                  c_note constant varchar2(60) := q'[procedure emp_row(p_x out wrong%rowtype);]';
                  procedure emp_row(p_sep varchar2 default ')', p_row out emp%rowtype);
                  procedure emp_row(p_note varchar2 default 'procedure emp_row', p_row out dept%rowtype);
                end;
                """;
        String first = DictionaryReader.findDeclaration(spec, "EMP_ROW", "1");
        assertThat(DictionaryReader.rowtypeOf(first, "P_ROW")).isEqualTo("EMP");
        String second = DictionaryReader.findDeclaration(spec, "EMP_ROW", "2");
        assertThat(DictionaryReader.rowtypeOf(second, "P_ROW")).isEqualTo("DEPT");

        String body = "procedure p(p_row out emp%rowtype)is begin null; end;";
        assertThat(DictionaryReader.findDeclaration(body, "P", null)).endsWith("EMP%ROWTYPE)");
    }

    /**
     * Проверяет имена в двойных кавычках: регистр в них сохраняется, имя подпрограммы в
     * кавычках находится, таблица {@code "Emp"} разбирается на части без кавычек, а в текст блока
     * такие части снова идут в кавычках.
     */
    @Test
    void quotedNamesKeepTheirCase() {
        String spec = "create package \"Hr\" as procedure \"Load\"(p_row out \"Hr\".\"Emp\"%rowtype); end;";
        String decl = DictionaryReader.findDeclaration(spec, "Load", null);
        String written = DictionaryReader.rowtypeOf(decl, "P_ROW");
        assertThat(written).isEqualTo("\"Hr\".\"Emp\"");
        assertThat(DictionaryReader.nameParts(written)).containsExactly("Hr", "Emp");
        assertThat(DictionaryReader.sqlName("APP", "Emp", "EMP_2")).isEqualTo("APP.\"Emp\".EMP_2");
    }

    /**
     * Проверяет, как вид аргумента ({@link ArgKind}) определяется по
     * {@code ALL_ARGUMENTS.DATA_TYPE}: {@code PL/SQL BOOLEAN}, {@code OPAQUE/XMLTYPE},
     * {@code BINARY_INTEGER} как число, {@code REF CURSOR}, {@code VARRAY} как коллекция SQL,
     * {@code OPAQUE/ANYDATA} как неподдерживаемый тип. {@code CLOB} считается простым типом с
     * прямой привязкой, {@code XMLTYPE} — нет.
     */
    @Test
    void argKindsFromDictionaryNames() {
        assertThat(Signatures.arg("A", "PL/SQL BOOLEAN", "IN").kind()).isEqualTo(ArgKind.BOOLEAN);
        assertThat(Signatures.xml("A", "IN").kind()).isEqualTo(ArgKind.XMLTYPE);
        assertThat(Signatures.arg("A", "BINARY_INTEGER", "IN").kind()).isEqualTo(ArgKind.NUMBER);
        assertThat(Signatures.arg("A", "REF CURSOR", "OUT").kind()).isEqualTo(ArgKind.REF_CURSOR);
        assertThat(Signatures.arg("A", "VARRAY", "IN").kind()).isEqualTo(ArgKind.SQL_COLLECTION);
        assertThat(Signatures.arg("A", "OPAQUE/ANYDATA", "IN").kind()).isEqualTo(ArgKind.UNSUPPORTED);
        assertThat(ArgKind.CLOB.isScalar()).isTrue();
        assertThat(ArgKind.XMLTYPE.isScalar()).isFalse();
    }
    /**
     * Проверяет, что имя подпрограммы ищется целым словом: {@code EMP_ROWS}, объявленная раньше, не
     * принимается за {@code EMP_ROW} и не сдвигает счёт перегрузок.
     */
    @Test
    void nameIsMatchedAsAWholeWord() {
        String spec = "procedure emp_rows(p_row out wrong%rowtype); procedure emp_row(p_row out right_t%rowtype);";
        String decl = DictionaryReader.findDeclaration(spec, "EMP_ROW", "1");
        assertThat(DictionaryReader.rowtypeOf(decl, "P_ROW")).isEqualTo("RIGHT_T");
    }
    /**
     * Проверяет, что имя в другом регистре (объявленное в кавычках, {@code "Load"}) ищется только в
     * кавычках: процедура {@code load} без кавычек, объявленная раньше, — другая процедура.
     */
    @Test
    void mixedCaseNamesMatchOnlyQuoted() {
        String spec = "procedure load(p_row out dept%rowtype); procedure \"Load\"(p_row out emp%rowtype);";
        assertThat(DictionaryReader.rowtypeOf(DictionaryReader.findDeclaration(spec, "Load", null), "P_ROW")).isEqualTo("EMP");
        assertThat(DictionaryReader.rowtypeOf(DictionaryReader.findDeclaration(spec, "LOAD", null), "P_ROW")).isEqualTo("DEPT");
    }
}
