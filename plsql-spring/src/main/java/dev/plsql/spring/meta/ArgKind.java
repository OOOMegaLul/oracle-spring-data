package dev.plsql.spring.meta;

/**
 * Описывает, как аргумент передаётся между Java и PL/SQL.
 *
 * <p>Вид определяется по столбцу {@code DATA_TYPE} словаря {@code ALL_ARGUMENTS} (см.
 * {@link #of(ArgumentInfo)}); по виду планировщик вызова решает, как передать значение.
 * Большинство типов идёт прямым bind'ом: значение подставляется JDBC в параметр {@code ?}
 * вызова.
 *
 * <p>Oracle 11.2 не умеет передавать из JDBC типы, существующие только в PL/SQL
 * ({@code BOOLEAN}, {@code RECORD}), и не имеет простого отображения JDBC для
 * {@code XMLTYPE}. Поэтому такие значения идут не прямым bind'ом, а через локальную
 * переменную сгенерированного анонимного блока: из Java передаётся значение простого типа,
 * а сам блок превращает его в нужный тип PL/SQL и обратно.
 */
public enum ArgKind {
    /**
     * Число: {@code NUMBER}, {@code FLOAT}, {@code INTEGER}, {@code BINARY_INTEGER},
     * {@code PLS_INTEGER}, {@code BINARY_FLOAT}, {@code BINARY_DOUBLE}. Передаётся прямым bind'ом.
     */
    NUMBER,
    /**
     * Строка: {@code VARCHAR2}, {@code VARCHAR}, {@code CHAR}, {@code NVARCHAR2}, {@code NCHAR},
     * {@code LONG}, {@code ROWID}. Передаётся прямым bind'ом.
     */
    STRING,
    /** Дата {@code DATE}; в Oracle она хранит и время с точностью до секунды. Идёт прямым bind'ом. */
    DATE,
    /**
     * Метка времени {@code TIMESTAMP}, в том числе {@code WITH TIME ZONE} и
     * {@code WITH LOCAL TIME ZONE}. Передаётся прямым bind'ом.
     */
    TIMESTAMP,
    /**
     * Большой текст {@code CLOB} или {@code NCLOB} (LOB, large object: значение, которое может
     * занимать гигабайты). На вход передаётся через временный LOB, созданный на время вызова.
     */
    CLOB,
    /** Большие двоичные данные {@code BLOB}. На вход передаются через временный LOB. */
    BLOB,
    /** Двоичные данные {@code RAW} или {@code LONG RAW}. Передаются прямым bind'ом как {@code byte[]}. */
    RAW,
    /**
     * {@code BOOLEAN} из PL/SQL: передаётся как 1/0 и превращается в {@code TRUE}/{@code FALSE}
     * внутри блока.
     */
    BOOLEAN,
    /**
     * {@code XMLTYPE}: передаётся как текст {@code CLOB} и превращается в {@code XMLTYPE} внутри
     * блока, при этом {@code NULL} остаётся {@code NULL}.
     */
    XMLTYPE,
    /**
     * Курсор ({@code REF CURSOR}, {@code SYS_REFCURSOR}): указатель на открытый запрос, из
     * которого Java читает строки результата. Передать курсор из Java в PL/SQL нельзя, поэтому
     * поддержаны только {@code OUT}, {@code IN OUT} и возвращаемое значение функции.
     */
    REF_CURSOR,
    /**
     * Запись PL/SQL ({@code RECORD}) или {@code %ROWTYPE} (запись со структурой строки
     * таблицы): собирается поле за полем внутри блока.
     */
    RECORD,
    /**
     * Index-by таблица PL/SQL (ассоциативный массив {@code TABLE OF ... INDEX BY}):
     * передаётся через {@code setPlsqlIndexTable}, только со скалярными элементами. Планировщик
     * пропускает лишь числа и строки ({@code NUMBER}, {@code VARCHAR2} и подобные).
     */
    INDEX_TABLE,
    /**
     * Коллекция уровня SQL, созданная командой {@code CREATE TYPE}: {@code TABLE OF} или
     * {@code VARRAY}. Передаётся как {@code java.sql.Array}.
     */
    SQL_COLLECTION,
    /**
     * Объектный тип уровня SQL ({@code CREATE TYPE ... AS OBJECT}): передаётся как
     * {@code java.sql.Struct}.
     */
    OBJECT,
    /**
     * Тип, который библиотека передавать не умеет (например, {@code OPAQUE/ANYDATA}).
     *
     * <p>Перегрузка с таким аргументом считается невызываемой; если другой подходящей
     * перегрузки нет, старт приложения останавливается с объяснением причины.
     */
    UNSUPPORTED;

    /**
     * Проверяет, относится ли вид к простым (скалярным) типам.
     *
     * <p>Скаляры передаются прямым bind'ом в вызов или в поле записи: числа, строки, даты,
     * метки времени, {@code CLOB}, {@code BLOB} и {@code RAW}. {@code BOOLEAN} и
     * {@code XMLTYPE} скалярами здесь не считаются, потому что идут через переменную блока.
     *
     * @return {@code true} для {@link #NUMBER}, {@link #STRING}, {@link #DATE}, {@link #TIMESTAMP},
     *         {@link #CLOB}, {@link #BLOB} и {@link #RAW}
     */
    public boolean isScalar() {
        return switch (this) {
            case NUMBER, STRING, DATE, TIMESTAMP, CLOB, BLOB, RAW -> true;
            default -> false;
        };
    }

    /**
     * Определяет вид аргумента по его типу из словаря Oracle.
     *
     * <p>Сравнивает {@code ALL_ARGUMENTS.DATA_TYPE} со списком известных имён. Словарь
     * пишет имена по-своему: например, {@code PL/SQL BOOLEAN}, {@code PL/SQL RECORD},
     * {@code PL/SQL TABLE} (index-by таблица) или {@code OPAQUE/XMLTYPE}. Если имя не
     * распознано, но тип называется {@code XMLTYPE} ({@code TYPE_NAME}), возвращает
     * {@link #XMLTYPE}; иначе {@link #UNSUPPORTED}. {@code DATA_TYPE}, равный {@code null},
     * считается пустой строкой.
     *
     * @param a аргумент, прочитанный из словаря
     * @return вид аргумента; никогда не {@code null}
     */
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
