package com.finance.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record CategoryAllocation(UUID categoryId, String categoryName, BigDecimal weightAmount) {}
