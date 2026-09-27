package dev.plsql.spring.it;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.w3c.dom.Document;

import dev.plsql.spring.annotation.Arg;
import dev.plsql.spring.annotation.PlsqlApi;
import dev.plsql.spring.annotation.Procedure;
import dev.plsql.spring.annotation.SqlQuery;

/** LAB_PKG from it/schema-objects.sql: every argument shape that is awkward on Oracle 11.2. */
@PlsqlApi(packageName = "LAB_PKG")
public interface LabApi {

    record Rec(Long id, String name, Boolean flag, LocalDate dt) {
    }

    record Emp(long id, String name, LocalDate hired, boolean flag) {
    }

    record Obj(Long id, String name, LocalDate dt) {
    }

    record TwoOuts(BigDecimal double_, String text) {
    }

    record XRec(Long id, String body) {
    }

    boolean echoBool(boolean flag);

    boolean boolInout(boolean flag);

    Rec makeRec(long id, String name);

    Rec recInout(Rec rec);

    Emp empRow(long id);

    BigDecimal sumIbt(List<Long> vals);

    List<String> ibtOut(int n);

    List<Obj> objs(int n);

    long countObjs(List<Obj> objs);

    long sumNums(List<Long> nums);

    Obj echoObj(Obj obj);

    List<Emp> emps(long minId);

    List<Emp> empsF(long minId);

    List<Emp> empsInout(int open, long minId);

    void setState(String val);

    String getState();

    void fail(String msg);

    long clobLen(String c);

    String bigClob(int n);

    String withDefaults(long a);

    String withDefaults(long a, String b);

    @Procedure("OVER")
    String overNumber(@Arg("P_X") long x);

    @Procedure("OVER")
    String overString(@Arg("P_X") String x);

    String echoStr(String s);

    void noop();

    TwoOuts twoOuts(long in);

    String wrapXml(String x);

    @Procedure("WRAP_XML")
    Document wrapXmlDom(@Arg("P_X") Document x);

    @Procedure("WRAP_XML")
    Optional<String> wrapXmlOptional(@Arg("P_X") String x);

    String xmlOut(long n);

    XRec xrecInout(XRec r);

    int xmlIsNull(String x);

    @SqlQuery("select id, name, hired, flag from lab_emp where id = :id")
    Optional<Emp> findEmp(long id);

    @SqlQuery("select count(*) from lab_emp")
    long countEmps();

    /** Starts with a comment: rows or update count is decided by JDBC, not by the first word. */
    @SqlQuery("/* no-op */ update lab_emp set flag = flag where id = :id")
    int touchEmp(long id);

    @SqlQuery("select id, name, hired, flag from lab_emp where name = :name")
    List<Emp> findByName(String name);

    default String describe(long id) {
        return findEmp(id).map(Emp::name).orElse("none");
    }
}
