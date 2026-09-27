package com.example.hr;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import dev.plsql.spring.support.PlsqlBusinessException;

/**
 * Приложение-пример: вызывает пакет {@code HR_API} и печатает, что получилось.
 */
@SpringBootApplication
public class HrApplication {

    /**
     * Запускает приложение.
     *
     * @param args аргументы командной строки
     */
    public static void main(String[] args) {
        SpringApplication.run(HrApplication.class, args);
    }

    /**
     * Показывает все виды вызовов по очереди, когда приложение поднялось.
     *
     * @param hr      пакет {@code HR_API}
     * @param service сервис с транзакцией
     * @param context пакет {@code APP_CONTEXT}
     * @return действие при старте
     */
    @Bean
    CommandLineRunner demo(HrApi hr, HrService service, AppContext context) {
        return args -> {
            System.out.println("Пользователь сеанса: " + context.currentUserName().orElse("(не задан)"));
            long id = hr.hire("Иванов Иван");
            System.out.println("Принят сотрудник №" + id);
            System.out.println("Имя: " + hr.findName(id).orElse("?"));
            System.out.println("Работает: " + hr.isActive(id));
            System.out.println("Несуществующий №-1: " + hr.findName(-1).orElse("нет такого"));

            long temp = service.hireForOneDay("Петров Пётр");
            System.out.println("Принят и уволен №" + temp + ", работает: " + hr.isActive(temp));

            hr.listActive().forEach(e -> System.out.println("В штате: " + e));

            try {
                hr.hire(null);
            } catch (PlsqlBusinessException e) {
                System.out.println("Ошибка из PL/SQL, код " + e.getErrorCode() + ": " + e.getMessage());
            }
        };
    }
}
