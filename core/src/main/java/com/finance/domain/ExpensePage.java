package com.finance.domain;

import java.util.List;

// Paginated response wrapper.
// Mirrors the pagination envelope defined in api_design.md:
// {
//   "data": [...],
//   "pagination": {
//     "page": 1,
//     "pageSize": 20,
//     "totalItems": 143,
//     "totalPages": 8
//   }
// }
// Generic type parameter allows reuse for other paginated responses later
// e.g. Page<Category>, Page<Target>
public record ExpensePage(
        List<Expense> data,
        int page,
        int pageSize,
        long totalItems,
        int totalPages
) {}