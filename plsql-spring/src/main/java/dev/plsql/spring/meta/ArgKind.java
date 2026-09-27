package dev.plsql.spring.meta;

/**
 * How an argument travels between Java and PL/SQL. Oracle 11.2 cannot bind PL/SQL-only
 * types (BOOLEAN, RECORD) from JDBC and has no plain JDBC mapping for XMLTYPE, so those
 * go through a local variable of the generated anonymous block instead of a direct bind.
 */
public enum ArgKind {
    NUMBER,
    STRING,
    DATE,
    TIMESTAMP,
    CLOB,
    BLOB,
    RAW,
    /** PL/SQL BOOLEAN: bound as 1/0 and converted inside the block. */
    BOOLEAN,
    /** XMLTYPE: bound as CLOB text and converted inside the block (NULL-safe). */
    XMLTYPE,
    REF_CURSOR,
    /** PL/SQL RECORD or %ROWTYPE: assembled field by field inside the block. */
    RECORD,
    /** PL/SQL index-by table: bound with setPlsqlIndexTable, scalars only. */
    INDEX_TABLE,
    /** SQL-level TABLE OF / VARRAY: bound as java.sql.Array. */
    SQL_COLLECTION,
    /** SQL-level OBJECT type: bound as java.sql.Struct. */
    OBJECT,
    UNSUPPORTED;

    /** Scalars bound straight into the call or into a record field. */
    public boolean isScalar() {
        return switch (this) {
            case NUMBER, STRING, DATE, TIMESTAMP, CLOB, BLOB, RAW -> true;
            default -> false;
        };
    }

    public static ArgKind of(ArgumentInfo a) {
        String t = a.dataType() == null ? "" : a.dataType();
        return switch (t) {
            case "NUMBER", "FLOAT", "BINARY_INTEGER", "PLS_INTEGER", "PL/SQL PLS INTEGER",
                 "PL/SQL BINARY INTEGER", "BINARY_FLOAT", "BINARY_DOUBLE", "INTEGER" -> NUMBER;
            case "VARCHAR2", "CHAR", "NVARCHAR2", "NCHAR", "LONG", "ROWID", "VARCHAR" -> STRING;
            case "DATE" -> DATE;
            case "TIMESTAMP", "TIMESTAMP WITH TIME ZONE", "TIMESTAMP WITH LOCAL TIME ZONE" -> TIMESTAMP;
            case "CLOB", "NCLOB" -> CLOB;
            case "BLOB" -> BLOB;
            case "RAW", "LONG RAW" -> RAW;
            case "PL/SQL BOOLEAN" -> BOOLEAN;
            case "OPAQUE/XMLTYPE" -> XMLTYPE;
            case "REF CURSOR" -> REF_CURSOR;
            case "PL/SQL RECORD" -> RECORD;
            case "PL/SQL TABLE" -> INDEX_TABLE;
            case "TABLE", "VARRAY" -> SQL_COLLECTION;
            case "OBJECT" -> OBJECT;
            default -> "XMLTYPE".equals(a.typeName()) ? XMLTYPE : UNSUPPORTED;
        };
    }
}
