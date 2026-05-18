package com.finance.service;

import java.util.UUID;

// Called immediately after a successful registration to create the system
// accounts that every user needs before they can log their first expense.
public interface UserSetupService {
    void setupNewUser(UUID userId);
}
