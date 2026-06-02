package com.finance.query;

import com.finance.domain.TargetType;

// All fields nullable — controller only sets what the caller provided as query params.
public record TargetQuery(Integer periodYear, Integer periodMonth, TargetType targetType) {}
