package dev.plsql.spring.meta;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import dev.plsql.spring.test.Signatures;

/** %ROWTYPE is recovered from the source text, because 11.2 leaves it out of ALL_ARGUMENTS. */
class DictionaryReaderParsingTest {

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

    @Test
    void findsTheRightOverload() {
        String first = DictionaryReader.findDeclaration(SPEC, "EMP_ROW", "1");
        assertThat(first).startsWith("PROCEDURE EMP_ROW(P_ID NUMBER").doesNotContain("WRONG");
        assertThat(DictionaryReader.rowtypeOf(first, "P_ROW", "APP")).isEqualTo("APP.EMP");

        String second = DictionaryReader.findDeclaration(SPEC, "emp_row", "2");
        assertThat(DictionaryReader.rowtypeOf(second, "P_ROW", "APP")).isEqualTo("HR.EMP_HIST");
    }

    @Test
    void functionReturningRowtype() {
        String decl = DictionaryReader.findDeclaration(SPEC, "LAST_EMP", null);
        assertThat(DictionaryReader.returnRowtypeOf(decl, "APP")).isEqualTo("APP.EMP");
    }

    @Test
    void nameIsMatchedAsAWholeWordAndStringsAreNotComments() {
        String decl = DictionaryReader.findDeclaration(SPEC, "EMP_ROWTYPE_LIKE", null);
        assertThat(decl).contains("'EMP_ROW;'");
        assertThat(DictionaryReader.rowtypeOf(decl, "P_X", "APP")).isEqualTo("APP.DEPT");
        assertThat(DictionaryReader.findDeclaration(SPEC, "NOT_THERE", null)).isEmpty();
    }

    @Test
    void headerEndsAtIsOrAsButNotInsideNames() {
        String body = "procedure p(p_row out emp%rowtype, p_issue_date date) is begin null; end;";
        String decl = DictionaryReader.findDeclaration(body, "P", null);
        assertThat(decl).endsWith("P_ISSUE_DATE DATE) ");
    }

    @Test
    void stripCommentsKeepsLiterals() {
        assertThat(DictionaryReader.stripComments("a -- x\nb /* y */ c 'd -- e' 'it''s'"))
                .isEqualTo("a  \nb   c 'd -- e' 'it''s'");
    }

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
