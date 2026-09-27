package dev.plsql.spring.meta;

import java.util.ArrayList;
import java.util.List;

/**
 * One row of ALL_ARGUMENTS, plus its nested rows (record fields, collection element).
 *
 * @param name     argument name; null for a function's return value
 * @param position 0 for a function's return value, 1..n for arguments
 * @param dataType ALL_ARGUMENTS.DATA_TYPE, e.g. NUMBER, PL/SQL BOOLEAN, REF CURSOR
 * @param inOut    IN, OUT or IN/OUT
 */
public record ArgumentInfo(
        String name,
        int position,
        int dataLevel,
        String dataType,
        String plsType,
        String inOut,
        boolean defaulted,
        String typeOwner,
        String typeName,
        String typeSubname,
        List<ArgumentInfo> children) {

    public ArgumentInfo {
        children = children == null ? new ArrayList<>() : children;
    }

    public boolean isIn() {
        return inOut.startsWith("IN");
    }

    public boolean isOut() {
        return inOut.endsWith("OUT");
    }

    public ArgKind kind() {
        return ArgKind.of(this);
    }

    /** Fully qualified type for DECLARE: OWNER.PKG.REC_T, or OWNER.TABLE%ROWTYPE. */
    public String declaredType() {
        if (typeSubname != null) {
            return typeOwner + "." + typeName + "." + typeSubname;
        }
        if (typeName != null) {
            return (typeOwner != null ? typeOwner + "." : "") + typeName;
        }
        return null;
    }

    /** Fully qualified SQL type name for createStruct / createOracleArray. */
    public String sqlTypeName() {
        return (typeOwner != null ? typeOwner + "." : "") + typeName;
    }

    @Override
    public String toString() {
        return (name == null ? "<return>" : name) + " " + inOut + " " + dataType
                + (typeName != null ? " (" + declaredType() + ")" : "")
                + (defaulted ? " DEFAULT" : "");
    }
}
