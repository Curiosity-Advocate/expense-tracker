package com.finance.command;

// Both fields are nullable — only present fields are updated.
// System categories reject this command at the service layer.
public record UpdateCategoryCommand(String name, String description) {}
