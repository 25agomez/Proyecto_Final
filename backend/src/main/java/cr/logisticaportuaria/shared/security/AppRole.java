package cr.logisticaportuaria.shared.security;

/**
 * Roles of the application. The name of the role is used as the Spring Security
 * authority ({@code ROLE_<name>}).
 */
public enum AppRole {

    /** Operator of a gate: registers readings and resolves the manual review queue. */
    OPERADOR_PUERTA,

    /** Supervisor: approves the operations that change the master data. */
    SUPERVISOR,

    /** Administrator: full access. */
    ADMIN,

    /** Auditor: read only access to every report. */
    AUDITOR;

    /**
     * @return the Spring Security authority of the role
     */
    public String authority() {
        return "ROLE_" + name();
    }
}
