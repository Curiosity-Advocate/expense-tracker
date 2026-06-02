package com.finance.bankintegration.repository;

import com.finance.bankintegration.entity.CsvImportEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// JpaRepository over csv_imports. Reads through this repo go via the app
// pool with RLS scoping to the current user. The async processor and
// startup recovery bypass RLS via the setup pool (see V29 GRANT) and use
// JdbcTemplate directly — they don't go through this repository.
public interface CsvImportRepository extends JpaRepository<CsvImportEntity, UUID> {

    // Rate-limit check: returns true if the user has any csv_imports row for
    // this bank account that is currently RUNNING, or completed successfully
    // (imported > 0) within the last 7 days. Empty completed imports don't
    // count (per the empty-CSV decision).
    @Query("""
        SELECT COUNT(c) > 0 FROM CsvImportEntity c
         WHERE c.bankAccountId = :bankAccountId
           AND (
                 c.status = 'RUNNING'
              OR (c.status = 'COMPLETED' AND c.importedCount > 0 AND c.completedAt >= :sevenDaysAgo)
           )
        """)
    boolean hasRecentOrInFlightImport(@Param("bankAccountId") UUID bankAccountId,
                                       @Param("sevenDaysAgo") Instant sevenDaysAgo);

    // Status endpoint lookup. RLS handles "not yours" → empty.
    // Returning Optional via JpaRepository.findById is enough.

    // Most-recent imports for an account (status history). Used by potential
    // future "show me my last N imports" UI; not strictly required for B1
    // but trivial to add now.
    List<CsvImportEntity> findTop10ByBankAccountIdOrderBySubmittedAtDesc(UUID bankAccountId);
}
