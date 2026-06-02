package com.finance.controller;

import com.finance.service.ExpenseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Bug #1.7a — PATCH /api/v1/expenses/{id} must reject negative amount with 400,
// not silently store the negative value. The @Valid annotation on the controller
// parameter activates Bean Validation; the @Positive constraint on the DTO field
// is what actually rejects the value.
@WebMvcTest(controllers = ExpenseController.class,
        excludeAutoConfiguration = {
                org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class
        })
@AutoConfigureMockMvc(addFilters = false)
class ExpenseControllerValidationTest {

    @Autowired MockMvc mvc;

    @MockBean ExpenseService expenseService;

    @Test
    void patch_negativeAmount_returns400_validationError() throws Exception {
        UUID expenseId = UUID.randomUUID();

        mvc.perform(patch("/api/v1/expenses/" + expenseId)
                        .param("expenseDate", "2026-01-11")
                        .contentType("application/json")
                        .content("{\"amount\": -5.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("amount")));
    }

    // Bug #1.7b — POST /api/v1/expenses with a non-UUID idempotencyKey must return 400.
    // Without the @Pattern constraint, UUID.fromString throws inside the controller and
    // the catch-all handler returns 500 INTERNAL_ERROR.
    @Test
    void post_idempotencyKeyNotUuid_returns400_validationError() throws Exception {
        String body = """
                {
                  "amount": 100.00,
                  "merchantName": "Supermarket",
                  "expenseDate": "2026-01-11",
                  "paymentMethod": "CASH",
                  "categories": ["Groceries"],
                  "idempotencyKey": "not-a-uuid"
                }
                """;
        mvc.perform(post("/api/v1/expenses")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("must be a valid UUID")));
    }

    // Bug #1.7c — GET /api/v1/expenses/summary?groupBy=month must return 400.
    // Spring's enum binding is strict-case; without a MethodArgumentTypeMismatchException
    // handler the catch-all turns this into 500.
    @Test
    void summary_invalidGroupByEnumValue_returns400_validationError() throws Exception {
        mvc.perform(get("/api/v1/expenses/summary")
                        .param("groupBy", "month"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("groupBy")));
    }
}
