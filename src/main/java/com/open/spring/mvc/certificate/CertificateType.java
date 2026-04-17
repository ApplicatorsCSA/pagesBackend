package com.open.spring.mvc.certificate;

/**
 * Enum representing the type of certificate a user can earn.
 * - EXCELLENCE: Awarded when average score >= 88%
 * - COMPLETION: Awarded when average score >= 70% but < 88%
 */
public enum CertificateType {
    EXCELLENCE("Excellence"),
    COMPLETION("Completion");

    private final String displayName;

    CertificateType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
