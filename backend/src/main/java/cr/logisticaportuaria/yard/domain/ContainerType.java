package cr.logisticaportuaria.yard.domain;

/**
 * Type of container ({@code container.container_type}). The values are stored in
 * lower case in the database.
 */
public enum ContainerType {

    /** General cargo. */
    dry,

    /** Container with a plugged reefer. */
    reefer,

    /** Hazardous cargo (IMO). */
    hazmat
}
