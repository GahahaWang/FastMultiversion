package cc.gahaha.fastmultiversion.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VersionedLinesTest {
    private static final McVersion V26_1 = McVersion.parse("26.1");
    private static final McVersion V26_2 = McVersion.parse("26.2");

    @Test
    void keepsLinesWithoutARangeAndThoseWhoseRangeHasTheVersion() {
        String plain = "accessible field mc/Minecraft gui Lmc/Gui;";
        String newer = "accessible field mc/Minecraft gui Lmc/Gui;  # @Mc.Is(\"[26.2,)\")";
        String noted = "public mc.Minecraft gui # moved in 26.2 @Mc.Is( \"26.2\" )";
        assertTrue(VersionedLines.appliesTo(plain, V26_1));
        assertFalse(VersionedLines.appliesTo(newer, V26_1));
        assertTrue(VersionedLines.appliesTo(newer, V26_2));
        assertFalse(VersionedLines.appliesTo(noted, V26_1));
        assertTrue(VersionedLines.appliesTo(noted, V26_2));
    }

    @Test
    void findsTheRangeOnlyInTheComment() {
        String line = "accessible field mc/Minecraft gui Lmc/Gui;  # @Mc.Is(\"[26.2,)\")";
        VersionedLines.Marker marker = VersionedLines.marker(line);
        assertEquals("[26.2,)", marker.range());
        assertEquals(line.indexOf("@Mc.Is"), marker.start());
        assertEquals(line.length(), marker.end());
        assertEquals("[26.2,)", line.substring(marker.rangeStart(), marker.rangeEnd()));
        assertNull(VersionedLines.marker("@Mc.Is(\"26.2\") is not in a comment"));
        assertNull(VersionedLines.marker("# a comment without a range"));
    }

    @Test
    void rejectsAnInvalidRange() {
        assertThrows(IllegalArgumentException.class, () -> VersionedLines.appliesTo("x # @Mc.Is(\"[26.2\")", V26_2));
    }

    @Test
    void knowsTheFilesItApplies() {
        assertTrue(VersionedLines.isVersionedFile("mymod.accesswidener"));
        assertTrue(VersionedLines.isVersionedFile("mymod.classtweaker"));
        assertTrue(VersionedLines.isVersionedFile("accesstransformer.cfg"));
        assertTrue(VersionedLines.isVersionedFile("mymod_at.cfg"));
        assertFalse(VersionedLines.isVersionedFile("mymod.mixins.json"));
        assertFalse(VersionedLines.isVersionedFile("other.cfg"));
    }
}
