package com.example.hr;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Бизнес-логика поверх {@link HrApi}: несколько вызовов PL/SQL в одной транзакции.
 */
@Service
public class HrService {

    /** Пакет {@code HR_API}; реализацию внедряет Spring. */
    private final HrApi hr;

    /**
     * Создаёт сервис.
     *
     * @param hr пакет {@code HR_API}
     */
    public HrService(HrApi hr) {
        this.hr = hr;
    }

    /**
     * Принимает сотрудника на работу и сразу увольняет его — в одной транзакции.
     *
     * <p>Оба вызова идут через одно соединение, то есть в одном сеансе Oracle. Если второй
     * вызов упадёт, откатится и первый: в таблице не останется «полупринятого» сотрудника.
     *
     * @param name имя
     * @return номер сотрудника
     */
    @Transactional
    public long hireForOneDay(String name) {
        long id = hr.hire(name);
        hr.fire(id);
        return id;
    }
}
