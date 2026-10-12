package com.researchdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ResearchDeskApplication {
    public static void main(String[] args) {
        if (java.util.Arrays.asList(args).contains("--migrate-only")) {
            org.flywaydb.core.Flyway.configure()
                .dataSource(System.getenv("DATABASE_URL"), System.getenv("DATABASE_USER"), System.getenv("DATABASE_PASSWORD"))
                .load().migrate();
            return;
        }
        SpringApplication.run(ResearchDeskApplication.class, args);
    }
}
