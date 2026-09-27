-- Демо-объекты для примера quickstart. Выполнить в своей схеме (SQL Developer, sqlplus).
-- Блоки PL/SQL заканчиваются строкой с одной косой чертой "/", как принято в sqlplus.

create table employees (
  id        number primary key,
  full_name varchar2(100) not null,
  hired     date,
  active    number(1) default 1 not null
);

create sequence employees_seq;

create or replace package hr_api as
  -- Принимает сотрудника на работу и возвращает его номер. Дата приёма по умолчанию — сегодня.
  procedure hire(p_name varchar2, p_hired date default sysdate, p_id out number);
  -- Имя сотрудника или NULL, если такого нет.
  function find_name(p_id number) return varchar2;
  -- Работает ли сотрудник. BOOLEAN: через обычный JDBC на Oracle 11 его не передать.
  function is_active(p_id number) return boolean;
  -- Все работающие сотрудники.
  procedure list_active(p_cur out sys_refcursor);
  -- Увольняет сотрудника.
  procedure fire(p_id number);
end hr_api;
/

create or replace package body hr_api as
  procedure hire(p_name varchar2, p_hired date default sysdate, p_id out number) is
  begin
    if p_name is null then
      raise_application_error(-20001, 'Имя сотрудника не задано');
    end if;
    insert into employees (id, full_name, hired)
    values (employees_seq.nextval, p_name, p_hired)
    returning id into p_id;
  end;

  function find_name(p_id number) return varchar2 is
    r employees.full_name%type;
  begin
    select full_name into r from employees where id = p_id;
    return r;
  exception
    when no_data_found then
      return null;
  end;

  function is_active(p_id number) return boolean is
    a employees.active%type;
  begin
    select active into a from employees where id = p_id;
    return a = 1;
  end;

  procedure list_active(p_cur out sys_refcursor) is
  begin
    open p_cur for
      select id, full_name, hired from employees where active = 1 order by id;
  end;

  procedure fire(p_id number) is
  begin
    update employees set active = 0 where id = p_id;
  end;
end hr_api;
/

-- Для профиля session: «кто работает» хранится в переменной пакета, как в коде под APEX.
create or replace package app_context as
  procedure set_user(p_user varchar2);
  function current_user_name return varchar2;
end app_context;
/

create or replace package body app_context as
  g_user varchar2(100);

  procedure set_user(p_user varchar2) is
  begin
    g_user := p_user;
  end;

  function current_user_name return varchar2 is
  begin
    return g_user;
  end;
end app_context;
/
