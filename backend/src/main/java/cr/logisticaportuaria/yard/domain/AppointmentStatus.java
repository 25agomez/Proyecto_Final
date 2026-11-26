package cr.logisticaportuaria.yard.domain;

/**
 * Status of an appointment ({@code appointment.status}).
 */
public enum AppointmentStatus {

    /** The appointment is booked. */
    PENDIENTE,

    /** The truck arrived and the operation is running. */
    EN_CURSO,

    /** The operation finished. */
    COMPLETADA,

    /** The appointment was cancelled. */
    CANCELADA,

    /** The truck did not show up. */
    NO_SHOW
}
