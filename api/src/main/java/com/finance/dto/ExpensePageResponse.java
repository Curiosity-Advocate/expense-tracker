package com.finance.dto;

import java.util.List;

public record ExpensePageResponse(
        List<ExpenseResponse> data,
        int page,
        int pageSize,
        long totalItems,
        int totalPages) {}
