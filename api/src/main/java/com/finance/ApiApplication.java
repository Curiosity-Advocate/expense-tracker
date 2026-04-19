package com.finance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.finance.config.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication // The "Magic" button
@EnableConfigurationProperties(JwtProperties.class)
public class ApiApplication {
    public static void main(String[] args) {
        // This line launches the Spring Container
        SpringApplication.run(ApiApplication.class, args);
    }
}
