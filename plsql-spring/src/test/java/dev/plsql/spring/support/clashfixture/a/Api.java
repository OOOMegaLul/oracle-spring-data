package dev.plsql.spring.support.clashfixture.a;

import dev.plsql.spring.annotation.PlsqlApi;

/**
 * Интерфейс с тем же простым именем, что и в соседнем пакете {@code b}: имена
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
