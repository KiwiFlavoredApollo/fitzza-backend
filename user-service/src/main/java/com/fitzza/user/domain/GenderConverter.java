package com.fitzza.user.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

// 여성 코드를 W에서 F로 바꾸기 전에 저장된 행도 읽을 수 있도록 W는 F로 읽는다. 저장은 항상 새 코드로 한다.
@Converter
public class GenderConverter implements AttributeConverter<Gender, String> {

    private static final String LEGACY_FEMALE = "W";

    @Override
    public String convertToDatabaseColumn(Gender gender) {
        return gender == null ? null : gender.name();
    }

    @Override
    public Gender convertToEntityAttribute(String code) {
        if (code == null) {
            return null;
        }
        return LEGACY_FEMALE.equals(code) ? Gender.F : Gender.valueOf(code);
    }
}
