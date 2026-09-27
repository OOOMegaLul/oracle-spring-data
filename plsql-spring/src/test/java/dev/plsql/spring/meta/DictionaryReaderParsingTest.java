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
     * {@code 'emp_row;'}.
     */
    static final String SPEC = """
            create or replace package hr_pkg as
              -- procedure emp_row(p_row out wrong_table%rowtype);  (commented out)
              /* function emp_row return also_wrong%rowtype; */
              procedure emp_row(p_id number, p_row out nocopy emp%rowtype);
              procedure emp_row(p_id varchar2, p_row in out hr.emp_hist%rowtype);
              function last_emp return emp%rowtype;
              procedure emp_rowtype_like(p_note varchar2 default 'emp_row;', p_x out dept%rowtype);
            end;
            """;

    /**
     * Проверяет, что перегрузка находится по номеру в порядке объявления (так её нумерует
     * {@code ALL_ARGUMENTS.OVERLOAD}), закомментированные объявления пропускаются, имя ищется
     * без учёта регистра, а тип {@code %ROWTYPE} читается и после {@code OUT NOCOPY}, и после
     * {@code IN OUT}. Таблица без схемы дополняется владельцем ({@code APP.EMP}), таблица со
     * схемой остаётся как есть ({@code HR.EMP_HIST}).
     */
    @Test
    void findsTheRightOverload() {
        String first = DictionaryReader.findDeclaration(SPEC, "EMP_ROW", "1");
        assertThat(first).startsWith("PROCEDURE EMP_ROW(P_ID NUMBER").doesNotContain("WRONG");
        assertThat(DictionaryReader.rowtypeOf(first, "P_ROW", "APP")).isEqualTo("APP.EMP");

        String second = DictionaryReader.findDeclaration(SPEC, "emp_row", "2");
        assertThat(DictionaryReader.rowtypeOf(second, "P_ROW", "APP")).isEqualTo("HR.EMP_HIST");
    }

    /** Проверяет, что {@code %ROWTYPE} в {@code RETURN} функции тоже читается и дополняется владельцем. */
    @Test
    void functionReturningRowtype() {
        String decl = DictionaryReader.findDeclaration(SPEC, "LAST_EMP", null);
        assertThat(DictionaryReader.returnRowtypeOf(decl, "APP")).isEqualTo("APP.EMP");
    }

    /**
     * Проверяет объявление {@code EMP_ROWTYPE_LIKE}, имя которого начинается с {@code EMP_ROW}, а
     * значение по умолчанию — строковый литерал {@code 'emp_row;'}. Объявление находится,
     * литерал остаётся в его тексте (это не комментарий, и точка с запятой внутри него не
     * обрывает заголовок), а тип {@code %ROWTYPE} следующего аргумента читается
     * ({@code APP.DEPT}). Для отсутствующей подпрограммы возвращается пустая строка.
     */
    @Test
    void nameIsMatchedAsAWholeWordAndStringsAreNotComments() {
        String decl = DictionaryReader.findDeclaration(SPEC, "EMP_ROWTYPE_LIKE", null);
        assertThat(decl).contains("'EMP_ROW;'");
        assertThat(DictionaryReader.rowtypeOf(decl, "P_X", "APP")).isEqualTo("APP.DEPT");
        assertThat(DictionaryReader.findDeclaration(SPEC, "NOT_THERE", null)).isEmpty();
    }

    /**
     * Проверяет, что заголовок процедуры с телом обрывается перед ключевым словом {@code IS},
     * с которого начинается тело, а буквы {@code IS} внутри имени {@code P_ISSUE_DATE} концом
     * заголовка не считаются. Текст объявления возвращается в верхнем регистре.
     */
    @Test
    void headerEndsAtIsOrAsButNotInsideNames() {
        String body = "procedure p(p_row out emp%rowtype, p_issue_date date) is begin null; end;";
        String decl = DictionaryReader.findDeclaration(body, "P", null);
        assertThat(decl).endsWith("P_ISSUE_DATE DATE) ");
    }

    /**
     * Проверяет удаление комментариев: строчный ({@code --} до конца строки) и блочный
     * комментарий заменяются пробелом, а строковые литералы, в том числе с {@code --} внутри и
     * с удвоенной кавычкой ({@code 'it''s'}), остаются нетронутыми.
     */
    @Test
    void stripCommentsKeepsLiterals() {
        assertThat(DictionaryReader.stripComments("a -- x\nb /* y */ c 'd -- e' 'it''s'"))
                .isEqualTo("a  \nb   c 'd -- e' 'it''s'");
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
}
