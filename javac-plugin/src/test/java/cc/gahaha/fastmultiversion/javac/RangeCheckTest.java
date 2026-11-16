package cc.gahaha.fastmultiversion.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.gahaha.fastmultiversion.apidb.generator.ApiDbGenerator;
import cc.gahaha.fastmultiversion.apidb.generator.VersionJars;
import cc.gahaha.fastmultiversion.versioning.McVersion;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The range check, against a made-up API that changes between "26.1" and "26.2". */
class RangeCheckTest {
    @TempDir
    static Path temp;

    private static Path api261;
    private static Path api262;
    private static Path database;

    @BeforeAll
    static void buildApis() throws Exception {
        String item = "package mc; public class Item {}";
        api261 = TestCompiler.jar(temp, "mc-26.1", item, """
                package mc;
                public class Registry {
                    public static String id(String ns, String path) { return ns + ":" + path; }
                    public void hurt(java.util.function.Consumer<Item> consumer) {}
                    public static class Entry { public String key() { return "entry"; } }
                }
                """, """
                package mc;
                public class OldThing { public String name() { return "old-thing"; } }
                """, """
                package mc;
                public class Base { public String tick() { return "base"; } }
                """, """
                package mc;
                public class Sub extends Base {}
                """, """
                package mc;
                public class Screen { public void render(int x) {} }
                """, """
                package mc.old;
                public class Range { public boolean within(int y) { return y > 0; } }
                """, """
                package mc;
                public class Data { public static mc.old.Range range() { return new mc.old.Range(); } }
                """);
        api262 = TestCompiler.jar(temp, "mc-26.2", item, """
                package mc;
                public class Registry {
                    public static String of(String ns, String path) { return ns + "/" + path; }
                    public void hurt(java.util.function.Consumer<Registry> consumer) {}
                    public static class Entry { public String key() { return "entry"; } }
                }
                """, """
                package mc;
                public class NewThing { public String name() { return "new-thing"; } }
                """, """
                package mc;
                public class Base {}
                """, """
                package mc;
                public class Sub extends Base { public String tick() { return "sub"; } }
                """, """
                package mc;
                public class Screen { public void render(long x) {} }
                """, """
                package mc.pos;
                public class Range { public boolean within(int y) { return y > 0; } }
                """, """
                package mc;
                public class Data { public static mc.pos.Range range() { return new mc.pos.Range(); } }
                """);
        database = temp.resolve("api.mcapi");
        ApiDbGenerator.generate(List.of(
                new VersionJars(McVersion.parse("26.1"), List.of(api261)),
                new VersionJars(McVersion.parse("26.2"), List.of(api262)))).write(database);
    }

    @Test
    void acceptsAChainThroughAMethodThatReturnsAnotherClass() throws Exception {
        // Data.range() returns mc.old.Range in 26.1 and mc.pos.Range in 26.2; both have within(int).
        String chain = """
                package mod;
                public class Chain {
                    public static boolean within() { return mc.Data.range().within(1); }
                }
                """;
        compiler("26.1").source(chain).compile().assertSuccess();
        compiler("26.2").source(chain).compile().assertSuccess();

        // Written down, the returned class is another one in each version: still reported.
        var typed = compiler("26.2").source("""
                package mod;
                public class Typed {
                    public static boolean within() { mc.pos.Range range = mc.Data.range(); return range.within(1); }
                }
                """).compile().assertFailure();
        assertTrue(typed.errors().contains("'mc.pos.Range' only exists in [26.2,)"), typed.errors());
    }

    private static TestCompiler compiler(String target) {
        return TestCompiler.target(temp, target).classpath(target.equals("26.1") ? api261 : api262).apiDatabase(database);
    }

    private static final String GUARDED = """
            package mod;
            import cc.gahaha.fastmultiversion.Mc;
            import mc.*;
            public class Guarded {
                public static String run() {
                    String id;
                    if (Mc.is("[26.2,)")) {
                        id = Registry.of("a", "b");
                    } else {
                        id = Registry.id("a", "b");
                    }
                    String thing = Mc.is("[26.2,)") ? new NewThing().name() : new OldThing().name();
                    boolean fresh = Mc.is("[26.2,)") && new NewThing().name() != null;
                    return String.join(" ", id, thing, String.valueOf(fresh), early(), annotated(),
                            new Sub().tick(), new Registry.Entry().key());
                }
                static String early() {
                    if (!Mc.is("[26.2,)")) {
                        return "old-early";
                    }
                    return new NewThing().name();
                }
                static String annotated() {
                    return Mc.is("[26.2,)") ? newOnly() : "old-annotated";
                }
                @Mc.Is("[26.2,)")
                static String newOnly() { return Registry.of("x", "y"); }
                static void damage(Registry registry) {
                    if (Mc.is("[26.2,)")) {
                        registry.hurt(r -> {});
                    } else {
                        registry.hurt(item -> {});
                    }
                }
                static class MyScreen extends Screen {
                    @Mc.Is("[26.1,26.2)") @Override public void render(int x) {}
                    @Mc.Is("[26.2,)") @Override public void render(long x) {}
                }
            }
            """;

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "26.1 | a:b old-thing false old-early old-annotated base entry",
            "26.2 | a/b new-thing true new-thing x/y sub entry"})
    void acceptsCodeGuardedForEveryVersion(String target, String expected) throws Exception {
        var result = compiler(target).source(GUARDED).compile().assertSuccess();
        assertEquals(expected, result.call("mod.Guarded", "run", target.equals("26.1") ? api261 : api262));
    }

    private static final String UNGUARDED = """
            package mod;
            import mc.*;
            public class Unguarded {
                static String a() { return Registry.of("a", "b"); }
                static String b() { return new NewThing().name(); }
                static void c(Registry r) { r.hurt(x -> {}); }
                static class MyScreen extends Screen {
                    @Override public void render(long x) {}
                }
                static String fine() { return new Sub().tick() + new Registry.Entry().key(); }
            }
            """;

    @Test
    void reportsApiUsedWhereSomeVersionsLackIt() throws Exception {
        String errors = compiler("26.2").source(UNGUARDED).compile().assertFailure().errors();

        assertEquals(4, errors.lines().filter(l -> l.startsWith("line ")).count(), errors);
        assertTrue(errors.contains("line 4: [multiversion] 'Registry.of(String, String)' only exists in [26.2,), "
                + "but this code runs in [26.1,). Use it inside if (Mc.is(\"[26.2,)\")) or in a declaration with "
                + "@Mc.Is(\"[26.2,)\")."), errors);
        assertTrue(errors.contains("line 5: [multiversion] 'NewThing.name()' only exists in [26.2,)"), errors);
        assertTrue(errors.contains("line 6: [multiversion] 'Registry.hurt(Consumer<Registry>)' exists in every "
                + "version this code runs in, but only has this form"), errors);
        assertTrue(errors.contains("line 8: [multiversion] 'MyScreen.render' overrides 'Screen.render(long)', "
                + "which only exists like this in [26.2,)"), errors);
    }

    @Test
    void reportsTheSameProblemsWhenBuildingTheOtherVersion() throws Exception {
        String errors = compiler("26.1").source("""
                package mod;
                import mc.*;
                public class Old {
                    static String a() { return Registry.id("a", "b"); }
                }
                """).compile().assertFailure().errors();

        assertTrue(errors.contains("line 4: [multiversion] 'Registry.id(String, String)' only exists in [26.1,26.2), "
                + "but this code runs in [26.1,)"), errors);
    }

    @Test
    void withoutADatabaseOnlyPrunes() throws Exception {
        TestCompiler.target(temp, "26.2").classpath(api262).source(UNGUARDED).compile().assertSuccess();
    }

    @Test
    void rejectsATargetTheDatabaseDoesNotCover() throws Exception {
        String errors = TestCompiler.target(temp, "26.3").classpath(api262).apiDatabase(database)
                .source("package mod; public class Any {}").compile().assertFailure().errors();
        assertTrue(errors.contains("covers Minecraft [26.1, 26.2] but not the target 26.3"), errors);
    }
}
