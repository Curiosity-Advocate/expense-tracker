package com.finance.bankintegration.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

// JdbcTemplate-backed repo for dead_letters. Same JSONB-cast pattern as
// RawBankTransactionRepository — the payload column needs ?::jsonb to
// bind a String into a JSONB column.
//
// Runs on the app pool with RLS. Caller must have set
// app.current_user_id; the WITH CHECK on the RLS policy forces user_id
// to match, so a wrong user_id binding would fail loudly.
@Repository
public class DeadLetterRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public DeadLetterRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id,
                        UUID userId,
                        String jobType,
                        String payloadJson,
                        String errorClass,
                        String errorMessage) {
        jdbc.update("""
                INSERT INTO dead_letters
                    (id, user_id, job_type, payload, error_class, error_message)
                VALUES
                    (:id, :userId, :jobType, :payload::jsonb, :errorClass, :errorMessage)
                """,
                new MapSqlParameterSource()
                        .addValue("id",           id)
                        .addValue("userId",       userId)
                        .addValue("jobType",      jobType)
                        .addValue("payload",      payloadJson)
                        .addValue("errorClass",   errorClass)
                        .addValue("errorMessage", errorMessage));
    }
}
