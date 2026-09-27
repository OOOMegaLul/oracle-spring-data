-- Run as PLSQL_IT. Every argument shape that is awkward on Oracle 11.2.
-- Statements are separated by a line with a single "/". Cyrillic comes from unistr()
-- so the file stays ASCII whatever the client character set.
create table lab_emp (
  id    number primary key,
  name  varchar2(100),
  hired date,
  flag  number(1)
)
/
insert into lab_emp values (1, unistr('\0418\0432\0430\043D\043E\0432'), date '2020-01-15', 1)
/
insert into lab_emp values (2, unistr('\041F\0435\0442\0440\043E\0432'), date '2021-06-01', 0)
/
insert into lab_emp values (3, 'Smith', date '2022-03-10', 1)
/
commit
/
create or replace synonym lab_emp_syn for lab_emp
/
create or replace type lab_obj as object (id number, name varchar2(100), dt date)
/
create or replace type lab_obj_tab as table of lab_obj
/
create or replace type lab_num_tab as table of number
/
create or replace package lab_pkg as
  type rec_t is record (id number, name varchar2(100), flag boolean, dt date);
  type xrec_t is record (id number, body xmltype);
  type num_ibt is table of number index by pls_integer;
  type str_ibt is table of varchar2(100) index by pls_integer;
  type rec_ibt is table of rec_t index by pls_integer;

  g_state varchar2(100);
  cursor c_emp is select id, name from lab_emp;

  function echo_bool(p_flag boolean) return boolean;
  procedure bool_inout(p_flag in out boolean);
  function make_rec(p_id number, p_name varchar2) return rec_t;
  procedure rec_inout(p_rec in out rec_t);
  procedure emp_row(p_id number, p_row out lab_emp%rowtype);
  procedure emp_row_syn(p_id number, p_row out lab_emp_syn%rowtype);
  procedure emp_brief(p_id number, p_row out c_emp%rowtype);
  function sum_ibt(p_vals num_ibt) return number;
  procedure ibt_out(p_n number, p_vals out str_ibt);
  function recs(p_n number) return rec_ibt;
  function objs(p_n number) return lab_obj_tab;
  function name_chars(p_objs lab_obj_tab) return number;
  function sum_nums(p_nums lab_num_tab) return number;
  function echo_obj(p_obj lab_obj) return lab_obj;
  procedure emps(p_min_id number, p_cur out sys_refcursor);
  function emps_f(p_min_id number) return sys_refcursor;
  procedure emps_inout(p_open number, p_min_id number, p_cur in out sys_refcursor);
  procedure set_state(p_val varchar2);
  function get_state return varchar2;
  procedure fail(p_msg varchar2);
  function clob_len(p_c clob) return number;
  function big_clob(p_n number) return clob;
  procedure with_defaults(p_a number, p_b varchar2 default 'B', p_c date default date '2000-01-01', p_out out varchar2);
  procedure over(p_x number, p_out out varchar2);
  procedure over(p_x varchar2, p_out out varchar2);
  function echo_str(p_s varchar2) return varchar2;
  procedure noop;
  procedure two_outs(p_in number, p_double out number, p_text out varchar2);
  function wrap_xml(p_x xmltype) return xmltype;
  procedure xml_out(p_n number, p_x out xmltype);
  procedure xrec_inout(p_r in out xrec_t);
  function xml_is_null(p_x xmltype) return number;
end lab_pkg;
/
create or replace package body lab_pkg as
  function echo_bool(p_flag boolean) return boolean is begin return not p_flag; end;
  procedure bool_inout(p_flag in out boolean) is begin p_flag := not p_flag; end;
  function make_rec(p_id number, p_name varchar2) return rec_t is
    r rec_t;
  begin
    r.id := p_id; r.name := p_name; r.flag := p_id > 0; r.dt := date '2024-02-29';
    return r;
  end;
  procedure rec_inout(p_rec in out rec_t) is
  begin
    p_rec.id := p_rec.id * 10;
    p_rec.name := upper(p_rec.name);
    p_rec.flag := not p_rec.flag;
  end;
  procedure emp_row(p_id number, p_row out lab_emp%rowtype) is
  begin
    select * into p_row from lab_emp where id = p_id;
  end;
  procedure emp_row_syn(p_id number, p_row out lab_emp_syn%rowtype) is
  begin
    select * into p_row from lab_emp where id = p_id;
  end;
  procedure emp_brief(p_id number, p_row out c_emp%rowtype) is
  begin
    select id, name into p_row from lab_emp where id = p_id;
  end;
  function sum_ibt(p_vals num_ibt) return number is
    s number := 0;
    i pls_integer := p_vals.first;
  begin
    while i is not null loop s := s + p_vals(i); i := p_vals.next(i); end loop;
    return s;
  end;
  procedure ibt_out(p_n number, p_vals out str_ibt) is
  begin
    for i in 1..p_n loop p_vals(i) := 'item ' || i; end loop;
  end;
  function recs(p_n number) return rec_ibt is
    t rec_ibt;
  begin
    for i in 1..p_n loop t(i) := make_rec(i, 'r' || i); end loop;
    return t;
  end;
  function objs(p_n number) return lab_obj_tab is
    t lab_obj_tab := lab_obj_tab();
  begin
    for i in 1..p_n loop
      t.extend;
      t(i) := lab_obj(i, unistr('\041E\0431\044A\0435\043A\0442 ') || i, date '2024-01-01' + i);
    end loop;
    return t;
  end;
  function name_chars(p_objs lab_obj_tab) return number is
    n number := 0;
  begin
    for i in 1..p_objs.count loop n := n + nvl(length(p_objs(i).name), 0); end loop;
    return n;
  end;
  function sum_nums(p_nums lab_num_tab) return number is
    s number := 0;
  begin
    for i in 1..p_nums.count loop s := s + p_nums(i); end loop;
    return s;
  end;
  function echo_obj(p_obj lab_obj) return lab_obj is
  begin
    return lab_obj(p_obj.id + 1, p_obj.name || '!', p_obj.dt + 1);
  end;
  procedure emps(p_min_id number, p_cur out sys_refcursor) is
  begin
    open p_cur for select id, name, hired, flag from lab_emp where id >= p_min_id order by id;
  end;
  function emps_f(p_min_id number) return sys_refcursor is
    c sys_refcursor;
  begin
    open c for select id, name, hired, flag from lab_emp where id >= p_min_id order by id;
    return c;
  end;
  procedure emps_inout(p_open number, p_min_id number, p_cur in out sys_refcursor) is
  begin
    if p_open = 1 then
      open p_cur for select id, name, hired, flag from lab_emp where id >= p_min_id order by id;
    end if;
  end;
  procedure set_state(p_val varchar2) is begin g_state := p_val; end;
  function get_state return varchar2 is begin return g_state; end;
  procedure fail(p_msg varchar2) is begin raise_application_error(-20042, p_msg); end;
  function clob_len(p_c clob) return number is begin return dbms_lob.getlength(p_c); end;
  function big_clob(p_n number) return clob is
    c clob;
  begin
    dbms_lob.createtemporary(c, true);
    for i in 1..p_n loop dbms_lob.writeappend(c, 10, '0123456789'); end loop;
    return c;
  end;
  procedure with_defaults(p_a number, p_b varchar2 default 'B', p_c date default date '2000-01-01', p_out out varchar2) is
  begin
    p_out := p_a || '/' || p_b || '/' || to_char(p_c, 'YYYY-MM-DD');
  end;
  procedure over(p_x number, p_out out varchar2) is begin p_out := 'number ' || p_x; end;
  procedure over(p_x varchar2, p_out out varchar2) is begin p_out := 'varchar2 ' || p_x; end;
  function echo_str(p_s varchar2) return varchar2 is begin return p_s; end;
  procedure noop is begin null; end;
  procedure two_outs(p_in number, p_double out number, p_text out varchar2) is
  begin
    p_double := p_in * 2;
    p_text := 'got ' || p_in;
  end;
  function wrap_xml(p_x xmltype) return xmltype is
  begin
    if p_x is null then return null; end if;
    return xmltype('<wrapped>' || p_x.getClobVal() || '</wrapped>');
  end;
  procedure xml_out(p_n number, p_x out xmltype) is
  begin
    p_x := xmltype('<n>' || p_n || '</n>');
  end;
  procedure xrec_inout(p_r in out xrec_t) is
  begin
    p_r.id := p_r.id + 1;
    if p_r.body is not null then
      p_r.body := xmltype('<wrapped>' || p_r.body.getClobVal() || '</wrapped>');
    end if;
  end;
  function xml_is_null(p_x xmltype) return number is
  begin
    return case when p_x is null then 1 else 0 end;
  end;
end lab_pkg;
/
