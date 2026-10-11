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
create or replace type lab_xobj as object (id number, body xmltype)
/
create or replace package lab_pkg as
  type rec_t is record (id number, name varchar2(100), flag boolean, dt date);
  type xrec_t is record (id number, body xmltype);
  type num_ibt is table of number index by pls_integer;
  type str_ibt is table of varchar2(100) index by pls_integer;
  type rec_ibt is table of rec_t index by pls_integer;
  type long_ibt is table of varchar2(32767) index by pls_integer;
  type addr_t is record (city varchar2(50), street varchar2(100), verified boolean);
  type person_t is record (id number, name varchar2(100), addr addr_t);
  type emp_card_t is record (emp lab_emp%rowtype, note varchar2(100));

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
  function long_len(p_vals long_ibt) return number;
  procedure long_out(p_n number, p_len number, p_vals out long_ibt);
  procedure emps_prefixed(p_cur out sys_refcursor);
  function xobj_id(p_obj lab_xobj) return number;
  function make_person(p_id number) return person_t;
  procedure person_inout(p_p in out person_t);
  function emp_card(p_id number) return emp_card_t;
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
  function long_len(p_vals long_ibt) return number is
    s number := 0;
    i pls_integer := p_vals.first;
  begin
    while i is not null loop s := s + nvl(length(p_vals(i)), 0); i := p_vals.next(i); end loop;
    return s;
  end;
  procedure long_out(p_n number, p_len number, p_vals out long_ibt) is
  begin
    for i in 1..p_n loop p_vals(i) := rpad('v', p_len, 'x'); end loop;
  end;
  procedure emps_prefixed(p_cur out sys_refcursor) is
  begin
    open p_cur for select id nrn, name sname, hired dhired from lab_emp order by id;
  end;
  function xobj_id(p_obj lab_xobj) return number is begin return p_obj.id; end;
  function make_person(p_id number) return person_t is
    r person_t;
  begin
    r.id := p_id;
    r.name := 'person ' || p_id;
    r.addr.city := unistr('\041E\043C\0441\043A');
    r.addr.street := 'Lenina ' || p_id;
    r.addr.verified := p_id > 0;
    return r;
  end;
  procedure person_inout(p_p in out person_t) is
  begin
    p_p.id := p_p.id + 1;
    p_p.addr.city := upper(p_p.addr.city);
    p_p.addr.verified := not p_p.addr.verified;
  end;
  function emp_card(p_id number) return emp_card_t is
    r emp_card_t;
  begin
    select * into r.emp from lab_emp where id = p_id;
    r.note := 'card ' || p_id;
    return r;
  end;
end lab_pkg;
/
create table lab_mark (txt varchar2(100))
/
-- Long calls (timeouts) and DBMS_OUTPUT. The busy loop checks the clock, not DBMS_LOCK:
-- PLSQL_IT has no grant on it, and DBMS_LOCK.SLEEP notices a cancel only when it wakes up.
create or replace package lab_run as
  procedure busy(p_seconds number, p_mark varchar2 default null);
  function slow_value(p_seconds number) return number;
  procedure say(p_text varchar2, p_fail number default 0);
  procedure mark(p_txt varchar2, p_len out number);
end lab_run;
/
create or replace package body lab_run as
  procedure spin(p_seconds number) is
    t pls_integer := dbms_utility.get_time;
  begin
    loop exit when dbms_utility.get_time - t >= p_seconds * 100; end loop;
  end;
  procedure busy(p_seconds number, p_mark varchar2 default null) is
  begin
    if p_mark is not null then insert into lab_mark values (p_mark); end if;
    spin(p_seconds);
  end;
  function slow_value(p_seconds number) return number is
  begin
    spin(p_seconds);
    return p_seconds;
  end;
  procedure say(p_text varchar2, p_fail number default 0) is
  begin
    dbms_output.put_line(p_text);
    dbms_output.put_line(null);
    dbms_output.put_line('end');
    if p_fail = 1 then raise_application_error(-20043, 'failed after output'); end if;
  end;
  procedure mark(p_txt varchar2, p_len out number) is
  begin
    if p_txt = 'bad' then raise_application_error(-20044, 'bad mark'); end if;
    insert into lab_mark values (p_txt);
    p_len := length(p_txt);
  end;
end lab_run;
/
-- Index-by tables the driver cannot bind itself: records, TIMESTAMP, BOOLEAN; a table as a record
-- field. Keys of ROWS_OUT are sparse (10, 20, 30) on purpose.
create or replace package lab_tab as
  type addr_t is record (city varchar2(50), zip number);
  type row_t is record (id number, name varchar2(50), flag boolean, hired date, stamp timestamp,
                        zoned timestamp with time zone, addr addr_t);
  type row_tab is table of row_t index by pls_integer;
  type ts_tab is table of timestamp index by pls_integer;
  type bool_tab is table of boolean index by pls_integer;
  type date_tab is table of date index by pls_integer;
  type order_t is record (id number, lines row_tab);
  function rows_out(p_n number) return row_tab;
  function rows_echo(p_rows row_tab) return row_tab;
  procedure rows_inout(p_rows in out row_tab);
  function stamps(p_vals ts_tab) return ts_tab;
  function flags(p_vals bool_tab) return bool_tab;
  function dates(p_vals date_tab) return date_tab;
  function order_echo(p_o order_t) return order_t;
end lab_tab;
/
create or replace package body lab_tab as
  function rows_out(p_n number) return row_tab is
    r row_tab;
  begin
    for i in 1..p_n loop
      r(i * 10).id := i;
      r(i * 10).name := 'row ' || i;
      r(i * 10).flag := mod(i, 2) = 0;
      r(i * 10).hired := date '2024-01-01' + i;
      r(i * 10).stamp := timestamp '2024-01-01 10:00:00.123456789' + numtodsinterval(i, 'HOUR');
      r(i * 10).zoned := to_timestamp_tz('2024-01-01 10:00:00.5 +03:00', 'YYYY-MM-DD HH24:MI:SS.FF TZH:TZM');
      r(i * 10).addr.city := 'city ' || i;
      r(i * 10).addr.zip := 100 + i;
    end loop;
    return r;
  end;
  function rows_echo(p_rows row_tab) return row_tab is
    r row_tab := p_rows;
    i pls_integer := r.first;
  begin
    while i is not null loop r(i).id := r(i).id + 1; i := r.next(i); end loop;
    return r;
  end;
  procedure rows_inout(p_rows in out row_tab) is
    i pls_integer := p_rows.first;
  begin
    while i is not null loop p_rows(i).name := upper(p_rows(i).name); i := p_rows.next(i); end loop;
  end;
  function stamps(p_vals ts_tab) return ts_tab is
    r ts_tab;
  begin
    for i in 1..p_vals.count loop r(i) := p_vals(i) + interval '1' day; end loop;
    return r;
  end;
  function flags(p_vals bool_tab) return bool_tab is
    r bool_tab;
  begin
    for i in 1..p_vals.count loop r(i) := not p_vals(i); end loop;
    return r;
  end;
  function dates(p_vals date_tab) return date_tab is
    r date_tab;
  begin
    for i in 1..p_vals.count loop r(i) := p_vals(i) + 1; end loop;
    return r;
  end;
  function order_echo(p_o order_t) return order_t is
    r order_t := p_o;
  begin
    r.id := nvl(r.id, 0) + r.lines.count;
    return r;
  end;
end lab_tab;
/
-- Edge cases of index-by tables: DATE with a time of day, tables the driver binds itself as record
-- fields, a table of records keyed by VARCHAR2, TIMESTAMP WITH LOCAL TIME ZONE, a table of %ROWTYPE.
create or replace package lab_tab2 as
  type date_tab is table of date index by pls_integer;
  type num_tab is table of number index by pls_integer;
  type str_tab is table of varchar2(20) index by pls_integer;
  type when_t is record (d date, n number);
  type when_tab is table of when_t index by pls_integer;
  type holder_t is record (id number, nums num_tab, names str_tab, days date_tab);
  type named_tab is table of when_t index by varchar2(30);
  type ltz_tab is table of timestamp with local time zone index by pls_integer;
  type emp_tab is table of lab_emp%rowtype index by pls_integer;
  function later(p_vals date_tab) return date_tab;
  function whens(p_vals when_tab) return when_tab;
  function holder_echo(p_h holder_t) return holder_t;
  function named(p_n number) return named_tab;
  function named_first(p_vals named_tab) return varchar2;
  function ltz(p_vals ltz_tab) return ltz_tab;
  function emps return emp_tab;
end lab_tab2;
/
create or replace package body lab_tab2 as
  function later(p_vals date_tab) return date_tab is
    r date_tab;
  begin
    for i in 1..p_vals.count loop r(i) := p_vals(i) + 1/24; end loop;
    return r;
  end;
  function whens(p_vals when_tab) return when_tab is
    r when_tab := p_vals;
  begin
    for i in 1..r.count loop r(i).d := r(i).d + 1/24; end loop;
    return r;
  end;
  function holder_echo(p_h holder_t) return holder_t is
    r holder_t := p_h;
  begin
    r.id := r.nums.count;
    for i in 1..r.nums.count loop r.nums(i) := r.nums(i) + 1; end loop;
    for i in 1..r.names.count loop r.names(i) := upper(r.names(i)); end loop;
    for i in 1..r.days.count loop r.days(i) := r.days(i) + 1/24; end loop;
    return r;
  end;
  function named(p_n number) return named_tab is
    r named_tab;
  begin
    for i in reverse 1..p_n loop r('k' || i).n := i; end loop;
    return r;
  end;
  function named_first(p_vals named_tab) return varchar2 is
  begin
    return p_vals.first || ':' || p_vals(p_vals.first).n;
  end;
  function ltz(p_vals ltz_tab) return ltz_tab is
    r ltz_tab;
  begin
    for i in 1..p_vals.count loop r(i) := p_vals(i) + interval '1' hour; end loop;
    return r;
  end;
  function emps return emp_tab is
    r emp_tab;
  begin
    select * bulk collect into r from lab_emp order by id;
    return r;
  end;
end lab_tab2;
/
-- SQL collections and SQL object types as record fields.
create or replace package lab_bag as
  type bag_t is record (id number, nums lab_num_tab, objs lab_obj_tab, one lab_obj);
  function bag_echo(p_b bag_t) return bag_t;
  procedure bag_fill(p_n number, p_b out bag_t);
end lab_bag;
/
create or replace package body lab_bag as
  function bag_echo(p_b bag_t) return bag_t is
    r bag_t := p_b;
  begin
    r.id := case when r.nums is null then -1 else r.nums.count end;
    if r.nums is not null then
      for i in 1..r.nums.count loop r.nums(i) := r.nums(i) + 1; end loop;
    end if;
    if r.objs is not null then
      for i in 1..r.objs.count loop r.objs(i).name := upper(r.objs(i).name); end loop;
    end if;
    if r.one is not null then r.one.id := r.one.id + 1; end if;
    return r;
  end;
  procedure bag_fill(p_n number, p_b out bag_t) is
  begin
    p_b.id := p_n;
    p_b.nums := lab_num_tab();
    p_b.objs := lab_obj_tab();
    for i in 1..p_n loop
      p_b.nums.extend; p_b.nums(i) := i * 10;
      p_b.objs.extend; p_b.objs(i) := lab_obj(i, 'obj ' || i, date '2024-01-01' + i);
    end loop;
    p_b.one := lab_obj(0, 'one', null);
  end;
end lab_bag;
-- National character types: NVARCHAR2, NCHAR, NCLOB, a record with an NVARCHAR2 field. Each echo
-- appends U+4E2D, which is outside single-byte character sets.
create or replace package lab_nchar as
  type nrec_t is record (id number, name nvarchar2(50));
  function echo(p_s nvarchar2) return nvarchar2;
  procedure fixed(p_s in nchar, p_out out nchar);
  function nclob_echo(p_c nclob) return nclob;
  function rec_echo(p_r nrec_t) return nrec_t;
end lab_nchar;
/
create or replace package body lab_nchar as
  function echo(p_s nvarchar2) return nvarchar2 is
  begin
    return p_s || unistr('\4E2D');
  end;
  procedure fixed(p_s in nchar, p_out out nchar) is
  begin
    p_out := rtrim(p_s) || unistr('\4E2D');
  end;
  function nclob_echo(p_c nclob) return nclob is
  begin
    return p_c || unistr('\4E2D');
  end;
  function rec_echo(p_r nrec_t) return nrec_t is
    r nrec_t := p_r;
  begin
    r.name := r.name || unistr('\4E2D');
    return r;
  end;
end lab_nchar;
/
