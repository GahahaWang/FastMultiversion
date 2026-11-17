package cc.gahaha.fastmultiversion.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AccessEntriesTest {
    /** Minecraft's classes as the conversions see them. */
    private static final AccessEntries.Members MINECRAFT = new AccessEntries.Members() {
        private final Map<String, List<AccessEntries.Member>> fields = Map.of(
                "mc/Holder", List.of(new AccessEntries.Member("count", "I"), new AccessEntries.Member("name", "Ljava/lang/String;")));
        private final Map<String, List<AccessEntries.Member>> methods = Map.of(
                "mc/Holder", List.of(new AccessEntries.Member("<init>", "()V"), new AccessEntries.Member("<clinit>", "()V"),
                        new AccessEntries.Member("reset", "()V")));

        @Override
        public List<AccessEntries.Member> fields(String owner) {
            return fields.get(owner);
        }

        @Override
        public List<AccessEntries.Member> methods(String owner) {
            return methods.get(owner);
        }
    };

    @Test
    void turnsAnAccessWidenerIntoAnAccessTransformer() {
        AccessEntries entries = new AccessEntries();
        entries.addAccessWidener(List.of(
                "classTweaker v1 official",
                "accessible class mc/Holder$Inner  # @Mc.Is(\"[26.2,)\")",
                "extendable class mc/Block",
                "accessible method mc/Holder reset ()V",
                "extendable method mc/Holder tick (I)Z",
                "accessible field mc/Holder count I",
                "mutable field mc/Holder count I",
                "mutable field mc/Holder name Ljava/lang/String;",
                "transitive-accessible method mc/Holder <init> ()V",
                "inject-interface mc/Holder mod/Tagged"), "src/main/resources/mod.classtweaker");

        assertEquals("""
                # made
                public mc.Holder$Inner
                public-f mc.Block
                public mc.Holder reset()V
                protected-f mc.Holder tick(I)Z
                public-f mc.Holder count
                private-f mc.Holder name
                public mc.Holder <init>()V
                """, entries.toAccessTransformer("made"));
        assertEquals(List.of("src/main/resources/mod.classtweaker:10: an access transformer has no form of "
                + "'inject-interface mc/Holder mod/Tagged', so it is left out"), entries.warnings());
    }

    @Test
    void turnsAnAccessTransformerIntoAnAccessWidener() {
        AccessEntries entries = new AccessEntries();
        entries.addAccessTransformer(List.of(
                "# fields need their descriptors from Minecraft",
                "public mc.Holder count",
                "public-f mc.Holder name # @Mc.Is(\"26.2\")",
                "protected mc.Holder reset()V",
                "public-f mc.Block",
                "public mc.Holder *()",
                "default mc.Holder secret()V",
                "public mc.Holder missing"), "src/main/resources/META-INF/accesstransformer.cfg");

        assertEquals("""
                accessWidener v2 official
                # made
                accessible field mc/Holder count I
                accessible field mc/Holder name Ljava/lang/String;
                mutable field mc/Holder name Ljava/lang/String;
                accessible method mc/Holder reset ()V
                accessible class mc/Block
                extendable class mc/Block
                accessible method mc/Holder <init> ()V
                """, entries.toAccessWidener(MINECRAFT, "made"));
        List<String> warnings = entries.warnings();
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.contains("src/main/resources/META-INF/accesstransformer.cfg:8: no field mc.Holder.missing in Minecraft's "
                + "classes, so it is left out of the access widener"), warnings.toString());
        assertTrue(warnings.contains("src/main/resources/META-INF/accesstransformer.cfg:7: an access widener has no default or private "
                + "access, so mc.Holder.secret is left out"), warnings.toString());
    }

    @Test
    void mergesFilesOfBothFormats() {
        AccessEntries entries = new AccessEntries();
        entries.addAccessWidener(List.of("accessWidener v2 official", "accessible field mc/Holder count I"), "mod.accesswidener");
        entries.addAccessTransformer(List.of("public-f mc.Holder count"), "accesstransformer.cfg");

        assertEquals("""
                accessWidener v2 official
                accessible field mc/Holder count I
                mutable field mc/Holder count I
                """, entries.toAccessWidener(MINECRAFT, ""));
        assertEquals("public mc.Holder count\npublic-f mc.Holder count\n", entries.toAccessTransformer(""));
    }
}
