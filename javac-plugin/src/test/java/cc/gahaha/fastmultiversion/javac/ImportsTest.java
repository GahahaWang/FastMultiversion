package cc.gahaha.fastmultiversion.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.gahaha.fastmultiversion.apidb.generator.ApiDbGenerator;
import cc.gahaha.fastmultiversion.apidb.generator.VersionJars;
import cc.gahaha.fastmultiversion.versioning.McVersion;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Each version compiles against its own API jar, which lacks the other version's classes and packages. */
class ImportsTest {
    @TempDir
    Path temp;

    private Path api261;
    private Path api262;

    @BeforeEach
    void buildApis() throws Exception {
        String shared = """
                package mc;
                public class Shared { public static String id() { return "shared"; } }
                """;
        api261 = TestCompiler.jar(temp, "api-26.1", shared, """
                package mc;
                public class OldThing { public String name() { return "old-thing"; } }
                """, """
                package mc.oldpkg;
                public class Gone { public static String id() { return "+gone"; } }
                """);
        api262 = TestCompiler.jar(temp, "api-26.2", shared, """
                package mc;
                public class NewThing { public String name() { return "new-thing"; } }
                """, """
                package mc.newpkg;
                public class Fresh { public static String id() { return "+fresh"; } }
                """);
    }

    private static final String USES = """
            package mod;
            import cc.gahaha.fastmultiversion.IfMc;
            import cc.gahaha.fastmultiversion.Mc;
            import mc.NewThing;
            import mc.OldThing;
            import mc.newpkg.*;
            import mc.oldpkg.*;
            import static mc.Shared.id;
            public class Uses {
                public static String run() {
                    if (Mc.is("[26.1,26.2)")) {
                        return new OldThing().name() + Gone.id() + " " + id();
                    } else {
                        return new NewThing().name() + Fresh.id() + " " + id();
                    }
                }
                @IfMc("[26.1,26.2)")
                static OldThing keep(OldThing thing) { return thing; }
            }
            """;

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "26.1 | old-thing+gone shared",
            "26.2 | new-thing+fresh shared"})
    void importsOfTheOtherVersionAreDropped(String target, String expected) throws Exception {
        Path api = target.equals("26.1") ? api261 : api262;
        var result = TestCompiler.target(temp, target).classpath(api).source(USES).compile().assertSuccess();
        assertEquals(expected, result.call("mod.Uses", "run", api));
    }

    private static final String EARLY = """
            package mod;
            import cc.gahaha.fastmultiversion.Mc;
            import mc.NewThing;
            import mc.OldThing;
            public class Early {
                public static String run() {
                    if (Mc.is("[26.1,26.2)")) {
                        return new OldThing().name();
                    }
                    return new NewThing().name();
                }
            }
            """;

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"26.1 | old-thing", "26.2 | new-thing"})
    void codeAfterAnEarlyReturnIsPrunedWhereItCannotRun(String target, String expected) throws Exception {
        Path api = target.equals("26.1") ? api261 : api262;
        var result = TestCompiler.target(temp, target).classpath(api).source(EARLY).compile().assertSuccess();
        assertEquals(expected, result.call("mod.Early", "run", api));
    }

    @Test
    void keepsWildcardImportsOfMemberClasses() throws Exception {
        Path api = TestCompiler.jar(temp, "api-enums", """
                package mc;
                public class Enums { public enum Mode { A } }
                """);
        var result = TestCompiler.target(temp, "26.2").classpath(api).source("""
                package mod;
                public class Kinds { public enum Kind { X } }
                """).source("""
                package mod;
                import mc.Enums.*;
                import mod.Kinds.*;
                public class Wild {
                    public static String run() { return Mode.A + " " + Kind.X; }
                }
                """).compile().assertSuccess();

        assertEquals("A X", result.call("mod.Wild", "run", api));
    }

    @Test
    void keptCodeStillNeedsItsImports() throws Exception {
        var result = TestCompiler.target(temp, "26.2").classpath(api262).source("""
                package mod;
                import mc.OldThing;
                public class Broken {
                    static String name() { return new OldThing().name(); }
                }
                """).compile().assertFailure();

        assertTrue(result.errors().contains("cannot find symbol"), result.errors());
    }

    // ---- a class that moved to another package

    private Path movedOld;
    private Path movedNew;
    private Path movedDatabase;

    private void buildMovedApis() throws Exception {
        movedOld = TestCompiler.jar(temp, "moved-26.1", """
                package mc.oldpkg;
                public class Pose { public String name() { return "old-pose"; } }
                """);
        movedNew = TestCompiler.jar(temp, "moved-26.2", """
                package mc.newpkg;
                public class Pose { public String name() { return "new-pose"; } }
                """);
        movedDatabase = temp.resolve("moved.mcapi");
        ApiDbGenerator.generate(List.of(
                new VersionJars(McVersion.parse("26.1"), List.of(movedOld)),
                new VersionJars(McVersion.parse("26.2"), List.of(movedNew)))).write(movedDatabase);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"26.1 | old-pose old-pose", "26.2 | new-pose new-pose"})
    void importsAMovedClassFromEachVersionsPackage(String target, String expected) throws Exception {
        buildMovedApis();
        Path api = target.equals("26.1") ? movedOld : movedNew;
        var result = TestCompiler.target(temp, target).classpath(api).apiDatabase(movedDatabase).source("""
                package mod;
                import cc.gahaha.fastmultiversion.IfMc;
                import cc.gahaha.fastmultiversion.Mc;
                import mc.newpkg.Pose;
                import mc.oldpkg.Pose;
                public class Poses {
                    public static String run() {
                        String name = Mc.is("[26.2,)") ? new Pose().name() : new Pose().name();
                        return name + " " + (Mc.is("[26.2,)") ? describe(new Pose()) : describe(new Pose()));
                    }
                    @IfMc("[26.1,26.2)") static String describe(Pose pose) { return pose.name(); }
                    @IfMc("[26.2,)") static String describe(Pose pose) { return pose.name(); }
                }
                """).compile().assertSuccess();

        assertEquals(expected, result.call("mod.Poses", "run", api));
    }

    @Test
    void stillRejectsImportsOfClassesNoVersionHas() throws Exception {
        buildMovedApis();
        var result = TestCompiler.target(temp, "26.2").classpath(movedNew).apiDatabase(movedDatabase).source("""
                package mod;
                import mc.newpkg.Typo;
                public class Typos {
                    static Typo typo;
                }
                """).compile().assertFailure();

        assertTrue(result.errors().contains("Typo"), result.errors());
    }
}
