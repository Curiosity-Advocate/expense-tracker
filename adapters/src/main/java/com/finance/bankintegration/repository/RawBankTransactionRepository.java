package com.finance.bankintegration.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

// JdbcTemplate-backed repo for raw_bank_transactions. JPA is awkward for
// the JSONB raw_payload column — JdbcTemplate with an explicit ?::jsonb
// cast in the SQL is cleaner than custom Hibernate UserTypes for this
// single write path.
//
// Runs on the app pool (RLS-enforced). The caller must have set
// app.current_user_id before invoking; otherwise INSERTs will violate
// the RLS policy and fail.
//
// The hash chain (prev_hash / current_hash) is computed by the
// BEFORE INSERT trigger on the table — application code does not
// supply those columns.
@Repository
public class RawBankTransactionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public RawBankTransactionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Inserts one raw transaction. Returns true if the row was inserted,
    // false if it collided on (user_id, external_transaction_id) — i.e.
    // it was already imported. The caller tracks imported vs deduped counts.
    public boolean insertIfNew(UUID id,
                                UUID userId,
                                String sourceFormat,
                                String externalTransactionId,
                                String rawPayloadJson) {
        int rowsAffected = jdbc.update("""
                INSERT INTO raw_bank_transactions
                    (id, user_id, source_format, external_transaction_id, raw_payload)
                VALUES
                    (:id, :userId, :sourceFormat, :externalTransactionId, :rawPayload::jsonb)
                ON CONFLICT (user_id, external_transaction_id) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("id",                    id)
                        .addValue("userId",                userId)
                        .addValue("sourceFormat",          sourceFormat)
                        .addValue("externalTransactionId", externalTransactionId)
                        .addValue("rawPayload",            rawPayloadJson));
        return rowsAffected == 1;
    }
}
