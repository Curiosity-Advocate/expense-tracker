package com.finance.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
    info = @Info(
        title       = "Expense Tracker API",
        version     = "v1",
        description = "Expense tracking and bank integration API"
    )
)
public class OpenApiConfig {
}