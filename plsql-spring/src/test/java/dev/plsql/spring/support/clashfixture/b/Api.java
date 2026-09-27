package dev.plsql.spring.support.clashfixture.b;

import dev.plsql.spring.annotation.PlsqlApi;

/**
 * Интерфейс с тем же простым именем, что и в соседнем пакете {@code a}: имена
 * бинов у них должны различаться.
 */
@PlsqlApi(packageName = "PKG")
public interface Api {
    /**
     * Процедура {@code PKG.TOUCH}.
     *
     * @param tenant идёт в {@code NTENANT}
     */
    void touch(long tenant);
}
