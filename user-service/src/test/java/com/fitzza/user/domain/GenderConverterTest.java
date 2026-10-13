package com.fitzza.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GenderConverterTest {

    private final GenderConverter converter = new GenderConverter();

    @Test
    void storesTheCurrentCodes() {
        assertThat(converter.convertToDatabaseColumn(Gender.F)).isEqualTo("F");
        assertThat(converter.convertToDatabaseColumn(Gender.M)).isEqualTo("M");
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void readsRowsSavedWithTheOldFemaleCode() {
        assertThat(converter.convertToEntityAttribute("W")).isEqualTo(Gender.F);
        assertThat(converter.convertToEntityAttribute("F")).isEqualTo(Gender.F);
        assertThat(converter.convertToEntityAttribute("M")).isEqualTo(Gender.M);
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
