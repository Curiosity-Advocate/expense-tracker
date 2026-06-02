package com.finance.alert;

// Mirrors the CHECK constraint on job_execution_state.status. Adding a value
// here requires a new Flyway migration that drops and recreates the constraint.
public enum JobExecutionStatus {
    RUNNING,
    SUCCESS,
    ALERTED
}
