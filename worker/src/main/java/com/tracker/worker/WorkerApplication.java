package com.finance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication // The "Magic" button
public class ApiApplication {
    public static void main(String[] args) {
        // This line launches the Spring Container
        SpringApplication.run(ApiApplication.class, args);
    }
}
