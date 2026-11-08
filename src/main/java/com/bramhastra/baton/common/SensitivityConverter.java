package com.bramhastra.baton.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class SensitivityConverter implements AttributeConverter<Sensitivity, Short> {

    @Override
    public Short convertToDatabaseColumn(Sensitivity attribute) {
        return attribute == null ? null : attribute.level();
    }

    @Override
    public Sensitivity convertToEntityAttribute(Short dbData) {
        return dbData == null ? null : Sensitivity.ofLevel(dbData);
    }
}
