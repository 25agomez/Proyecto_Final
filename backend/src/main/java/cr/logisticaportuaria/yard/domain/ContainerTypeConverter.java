package cr.logisticaportuaria.yard.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Converts the {@link ContainerType} enum to the lower case text stored in the
 * database.
 */
@Converter(autoApply = false)
public class ContainerTypeConverter implements AttributeConverter<ContainerType, String> {

    @Override
    public String convertToDatabaseColumn(ContainerType attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public ContainerType convertToEntityAttribute(String dbData) {
        return dbData == null ? null : ContainerType.valueOf(dbData);
    }
}
