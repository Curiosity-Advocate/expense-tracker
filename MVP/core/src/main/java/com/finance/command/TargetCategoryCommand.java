package com.finance.command;

import com.finance.domain.ParticipationType;

import java.util.UUID;

// One element in the categories list of CreateTargetCommand.
// Uses categoryId (UUID) rather than category name — targets are
// user-set objectives and the UI would select from the category list.
public record TargetCategoryCommand(UUID categoryId, ParticipationType participation) {}
