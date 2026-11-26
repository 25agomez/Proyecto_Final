package cr.logisticaportuaria.yard.domain;

/**
 * Type of cargo accepted by a slot or stored in a container. The values are
 * stored in lower case in the database ({@code dry}, {@code reefer},
 * {@code hazmat}).
 */
public enum SlotType {

    /** General cargo. */
    dry,

    /** Container with a plugged reefer. */
    reefer,

    /** Hazardous cargo (IMO). */
    hazmat
}
