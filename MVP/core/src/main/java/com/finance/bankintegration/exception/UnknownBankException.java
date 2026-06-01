package com.finance.bankintegration.exception;

import java.util.Set;

// Thrown when the user supplies a bankId that no registered CsvBankParser
// claims. Mapped to 422. The set of supported banks is included in the
// message so the client can correct without trial-and-error.
public class UnknownBankException extends RuntimeException {

    private final String      attempted;
    private final Set<String> supported;

    public UnknownBankException(String attempted, Set<String> supported) {
        super("Unknown bank id '" + attempted + "'. Supported: " + supported);
        this.attempted = attempted;
        this.supported = Set.copyOf(supported);
    }

    public String      getAttempted() { return attempted; }
    public Set<String> getSupported() { return supported; }
}
