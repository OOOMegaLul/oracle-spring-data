-- Run as a DBA. Statements are separated by a line with a single "/".
create user PLSQL_IT identified by plsql_it default tablespace USERS temporary tablespace TEMP quota unlimited on USERS
/
grant create session, create table, create procedure, create type, create sequence, create view, create synonym to PLSQL_IT
/
grant alter session to PLSQL_IT
/
-- v$mystat, v$statname, v$temporary_lobs for the measurements. A DBA other than SYS
-- cannot grant on SYS objects directly on 11g (ORA-01031), a system privilege it can.
grant select any dictionary to PLSQL_IT
/
