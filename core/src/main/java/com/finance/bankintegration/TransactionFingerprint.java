package com.finance.bankintegration;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

// Computes the deterministic dedupe key used as raw_bank_transactions.external_transaction_id
// for sources that don't carry a stable upstream ID (e.g. CSV exports).
//
// Hash inputs in fixed order:
//   date.toString() || '|' || amount.toPlainString() || '|' || description || '|' || bankAccountId
//
// Why a deterministic hash: the UNIQUE(user_id, external_transaction_id) constraint
// on raw_bank_transactions turns "the same transaction appearing in a re-uploaded CSV"
// into an ON CONFLICT DO NOTHING — idempotent re-imports without application-layer
// scanning. The hash includes bank_account_id so the same (date,amount,description)
// across two different accounts is two distinct transactions.
//
// Why not a random UUID per insert: that would defeat dedup. Why not include
// the rawLine: rawLine may differ across re-exports (CBA reformats; whitespace
// shifts) while the underlying transaction is identical. Hashing on the parsed
// fields gives the most stable identity available without a real upstream ID.
//
// Why SHA-256: same reasoning as refresh_tokens / sudo_tokens hashing — 256
// bits of output makes preimage attacks computationally infeasible, and the
// algorithm is stable across JREs.
public final class TransactionFingerprint {

    private static final HexFormat HEX = HexFormat.of();
    private static final char SEP = '|';

    private TransactionFingerprint() {}

    public static String compute(LocalDate date, BigDecimal amount, String description, UUID bankAccountId) {
        if (date == null || amount == null || description == null || bankAccountId == null) {
            throw new IllegalArgumentException("All fingerprint inputs are required");
        }

        StringBuilder sb = new StringBuilder(80)
                .append(date.toString())
                .append(SEP)
                .append(amount.toPlainString())
                .append(SEP)
                .append(description)
                .append(SEP)
                .append(bankAccountId.toString());

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by every JRE; unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
