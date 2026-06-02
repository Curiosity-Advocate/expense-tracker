package com.finance.service;

import com.finance.domain.UserProfile;

import java.util.UUID;

public interface UserService {
    UserProfile getProfile(UUID userId);
    UserProfile updateDiscoverability(UUID userId, boolean isDiscoverable);
}
