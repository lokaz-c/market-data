package com.lokaz.marketdata.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.lokaz.marketdata.demo.SyntheticDataProperties.SeedMode;

class SeedModeTest {

    @ParameterizedTest(name = "{0}, keys={1}, data={2} -> {3}")
    @CsvSource({
            "NEVER, false, false, false",
            "WHEN_NO_KEYS, false, false, true",
            "WHEN_NO_KEYS, true, false, false",
            "ALWAYS, true, false, true",
            "ALWAYS, false, true, false",
            "WHEN_NO_KEYS, false, true, false",
    })
    void seedsOnlyWhenTheModeAllowsAndNothingIsThereYet(SeedMode mode, boolean keys, boolean data, boolean expected) {
        assertThat(mode.shouldSeed(keys, data)).isEqualTo(expected);
    }
}
