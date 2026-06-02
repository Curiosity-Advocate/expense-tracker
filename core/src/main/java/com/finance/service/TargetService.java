package com.finance.service;

import com.finance.command.CreateTargetCommand;
import com.finance.domain.Target;
import com.finance.domain.TargetStatus;
import com.finance.query.TargetQuery;

import java.util.List;
import java.util.UUID;

public interface TargetService {
    Target createTarget(UUID userId, CreateTargetCommand command);
    List<Target> listTargets(UUID userId, TargetQuery query);
    TargetStatus getTargetStatus(UUID userId, UUID targetId);
    void deleteTarget(UUID userId, UUID targetId);
}
