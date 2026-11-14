package cc.gahaha.fastmultiversion.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class VersionRangeTest {
    @Test
    void ordersVersions() {
        String[] ascending = {
                "26.1-snapshot-1", "26.1-snapshot-10", "26.1-pre-1", "26.1-rc-1", "26.1",
                "26.1.1", "26.1.10", "26.2-snapshot-1", "26.2", "27.1"};
        for (int i = 1; i < ascending.length; i++) {
            McVersion lower = McVersion.parse(ascending[i - 1]);
            McVersion higher = McVersion.parse(ascending[i]);
            assertTrue(lower.compareTo(higher) < 0, lower + " < " + higher);
        }
        assertEquals(McVersion.parse("26.1"), McVersion.parse("26.1.0"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "[26.1,26.2)       | 26.1     | true",
            "[26.1,26.2)       | 26.1.9   | true",
            "[26.1,26.2)       | 26.2     | false",
            "(26.1,26.2]       | 26.1     | false",
            "(26.1,26.2]       | 26.2     | true",
            "[26.2,)           | 30.1     | true",
            "[26.2,)           | 26.2-rc-1 | false",
            "(,26.2]           | 26.1     | true",
            "[26.1]            | 26.1.0   | true",
            "26.1              | 26.1.1   | false",
            "[26.1,26.2),[26.3,) | 26.2.5 | false",
            "[26.1,26.2),[26.3,) | 26.4   | true",
            "' [ 26.1 , 26.2 ) ' | 26.1   | true"})
    void containsVersions(String range, String version, boolean expected) {
        assertEquals(expected, VersionRange.parse(range).contains(McVersion.parse(version)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "[26.1,26.2", "[26.2,26.1)", "[26.1,26.1)", "(26.1)", "[26.1,26.2,26.3]",
            "[26.1,26.2) x", "[26.1,26.2),", "26.x", "1..2"})
    void rejectsInvalidRanges(String range) {
        assertThrows(IllegalArgumentException.class, () -> VersionRange.parse(range));
    }
}
