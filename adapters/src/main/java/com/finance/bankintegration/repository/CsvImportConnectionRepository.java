package com.finance.bankintegration.repository;

import com.finance.bankintegration.entity.CsvImportConnectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

// JpaRepository over csv_import_connections. RLS scopes reads/writes to
// the authenticated user automatically — no user_id filter needed in
// finder method names.
public interface CsvImportConnectionRepository extends JpaRepository<CsvImportConnectionEntity, UUID> {
}
