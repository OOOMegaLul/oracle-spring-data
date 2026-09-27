package dev.plsql.spring.meta;

import java.util.List;

/**
 * One overload of a procedure or function as ALL_ARGUMENTS describes it.
 *
 * @param returnValue null for a procedure
 * @param arguments   top-level arguments in declaration order
 */
public record SubprogramInfo(
        String owner,
        String packageName,
        String name,
        String overload,
        ArgumentInfo returnValue,
        List<ArgumentInfo> arguments) {

    public boolean isFunction() {
        return returnValue != null;
    }

    public String qualifiedName() {
        return owner + "." + (packageName != null ? packageName + "." : "") + name;
    }

    @Override
    public String toString() {
        return qualifiedName() + (overload != null ? "#" + overload : "") + arguments
                + (returnValue != null ? " RETURN " + returnValue.dataType() : "");
    }
}
