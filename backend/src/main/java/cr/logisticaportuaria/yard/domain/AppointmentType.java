package cr.logisticaportuaria.yard.domain;

/**
 * Type of an appointment ({@code appointment.appointment_type}).
 */
public enum AppointmentType {

    /** The truck comes to load a container (ingreso). */
    INGRESO,

    /** The truck comes to pick up a container (retiro). */
    RETIRO
}
