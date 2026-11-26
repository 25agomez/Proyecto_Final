package cr.logisticaportuaria.yard.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Converts the {@link SlotType} enum to the lower case text stored in the
 * database.
 */
@Converter(autoApply = false)
public class SlotTypeConverter implements AttributeConverter<SlotType, String> {

    @Override
    public String convertToDatabaseColumn(SlotType attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public SlotType convertToEntityAttribute(String dbData) {
        return dbData == null ? null : SlotType.valueOf(dbData);
    }
}
