package cc.gahaha.fastmultiversion.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class McIsTest {
    @TempDir
    Path temp;

    private static final String BRANCHES = """
            package t;
            import cc.gahaha.fastmultiversion.Mc;
            public class Branches {
                public static String which() {
                    if (Mc.is("[26.1,26.2)")) {
                        return "MARK_261";
                    } else if (Mc.is("[26.2,26.3)")) {
                        return "MARK_262";
                    } else {
                        return "MARK_NEWER";
                    }
                }
            }
            """;

    @ParameterizedTest
    @CsvSource({
            "26.1, MARK_261",
            "26.1.3, MARK_261",
            "26.2-snapshot-4, MARK_261",
            "26.2, MARK_262",
            "26.2.1, MARK_262",
            "26.3, MARK_NEWER"})
    void keepsOnlyTheBranchForTheTarget(String target, String expected) throws Exception {
        var result = TestCompiler.target(temp, target).source(BRANCHES).compile().assertSuccess();

        assertEquals(expected, result.call("t.Branches", "which"));
        for (String marker : List.of("MARK_261", "MARK_262", "MARK_NEWER")) {
            assertEquals(marker.equals(expected), result.mentions("t.Branches", marker), marker);
        }
        assertFalse(result.mentions("t.Branches", "cc/gahaha/fastmultiversion/Mc"), "Mc is still referenced");
    }

    private static final String ONLY_OLD = """
            package t;
            public class OnlyOld {
                public static String name() { return "old"; }
            }
            """;

    private static final String USES_OLD = """
            package t;
            import cc.gahaha.fastmultiversion.Mc;
            public class UsesOld {
                public static String run() {
                    if (Mc.is("[26.1,26.2)")) {
                        Runnable lambda = () -> OnlyOld.name();
                        Object anonymous = new Object() {
                            @Override public String toString() { return OnlyOld.name(); }
                        };
                        return OnlyOld.name() + lambda + anonymous;
                    }
                    return "new";
                }
            }
            """;

    @Test
    void deadBranchLeavesNoTraceSoItsClassesMayBeMissingAtRuntime() throws Exception {
        var result = TestCompiler.target(temp, "26.2").source(ONLY_OLD).source(USES_OLD).compile().assertSuccess();

        assertFalse(result.mentions("t.UsesOld", "OnlyOld"));
        assertFalse(result.mentions("t.UsesOld", "lambda$"), "lambda from the dead branch was generated");
        assertFalse(result.hasClass("t.UsesOld$1"), "anonymous class from the dead branch was generated");

        // As if OnlyOld belonged to a Minecraft version this build does not run on.
        result.delete("t.OnlyOld");
        assertEquals("new", result.call("t.UsesOld", "run"));
    }

    @Test
    void liveBranchKeepsLambdasAndAnonymousClasses() throws Exception {
        var result = TestCompiler.target(temp, "26.1").source(ONLY_OLD).source(USES_OLD).compile().assertSuccess();

        assertTrue(result.hasClass("t.UsesOld$1"));
        assertTrue(((String) result.call("t.UsesOld", "run")).startsWith("old"));
    }

    private static final String TERNARY = """
            package t;
            import cc.gahaha.fastmultiversion.Mc;
            public class Ternary {
                public static long widened() { return Mc.is("[26.2,)") ? 1 : 2L; }
                public static Integer boxed() { return Mc.is("[26.2,)") ? null : 5; }
                public static int unboxed() { Integer seven = 7; return Mc.is("[26.1,26.2)") ? seven : 3; }
                public static String chained() { return Mc.is("[26.1,26.2)") ? "a" : Mc.is("[26.2,26.3)") ? "b" : "c"; }
                public static String asArgument() { return String.valueOf(Mc.is("[26.2,)") ? 'n' : 'o'); }
            }
            """;

    @Test
    void ternaryKeepsTheLiveArmWithItsConversions() throws Exception {
        var newer = TestCompiler.target(temp, "26.2").source(TERNARY).compile().assertSuccess();
        assertEquals(1L, newer.call("t.Ternary", "widened"));
        assertNull(newer.call("t.Ternary", "boxed"));
        assertEquals(3, newer.call("t.Ternary", "unboxed"));
        assertEquals("b", newer.call("t.Ternary", "chained"));
        assertEquals("n", newer.call("t.Ternary", "asArgument"));

        var older = TestCompiler.target(temp, "26.1").source(TERNARY).compile().assertSuccess();
        assertEquals(2L, older.call("t.Ternary", "widened"));
        assertEquals(5, older.call("t.Ternary", "boxed"));
        assertEquals(7, older.call("t.Ternary", "unboxed"));
        assertEquals("a", older.call("t.Ternary", "chained"));
        assertEquals("o", older.call("t.Ternary", "asArgument"));
    }

    private static final String MIXED = """
            package t;
            import cc.gahaha.fastmultiversion.Mc;
            public class Mixed {
                public static int calls;
                static boolean touch() { calls++; return true; }
                public static String andWithDeadRange() {
                    if (touch() && Mc.is("[26.1,26.2)")) { return "DEAD_AND"; }
                    return "alive";
                }
                public static String orWithLiveRange() {
                    if (touch() || Mc.is("[26.2,)")) { return "taken"; }
                    return "not taken";
                }
                public static String rightSideNeverRuns() {
                    return Mc.is("[26.1,26.2)") && touch() ? "DEAD_RHS" : "skipped";
                }
                public static boolean negated() { return !Mc.is("[26.1,26.2)"); }
                public static boolean combined() { return Mc.is("[26.1,)") && !Mc.is("[26.3,)"); }
            }
            """;

    @Test
    void conditionsKeepTheirSideEffects() throws Exception {
        var result = TestCompiler.target(temp, "26.2").source(MIXED).compile().assertSuccess();
        Class<?> mixed = result.load("t.Mixed");
        Field calls = mixed.getField("calls");

        assertEquals("alive", invoke(mixed, "andWithDeadRange"));
        assertEquals(1, calls.getInt(null), "touch() must still run");
        assertEquals("taken", invoke(mixed, "orWithLiveRange"));
        assertEquals(2, calls.getInt(null), "touch() must still run");
        assertEquals("skipped", invoke(mixed, "rightSideNeverRuns"));
        assertEquals(2, calls.getInt(null), "touch() is short-circuited away");
        assertEquals(true, invoke(mixed, "negated"));
        assertEquals(true, invoke(mixed, "combined"));

        assertFalse(result.mentions("t.Mixed", "DEAD_AND"));
        assertFalse(result.mentions("t.Mixed", "DEAD_RHS"));
    }

    private static Object invoke(Class<?> type, String method) throws Exception {
        Method m = type.getDeclaredMethod(method);
        m.setAccessible(true);
        return m.invoke(null);
    }

    @Test
    void acceptsConstantRangesAndStaticImports() throws Exception {
        var result = TestCompiler.target(temp, "26.2").source("""
                package t;
                import static cc.gahaha.fastmultiversion.Mc.is;
                public class Constants {
                    static final String OLD = "[26.1,26.2)";
                    static final String NEW = "[26." + "2,)";
                    public static String pick() {
                        if (is(OLD)) return "old";
                        if (is(NEW)) return "new";
                        return "none";
                    }
                }
                """).compile().assertSuccess();

        assertEquals("new", result.call("t.Constants", "pick"));
    }

    @Test
    void sourcesWithoutMcNeedNoTarget() throws Exception {
        TestCompiler.noTarget(temp).source("""
                package t;
                public class Plain { public static int one() { return 1; } }
                """).compile().assertSuccess();
    }

    @Test
    void reportsNonConstantRange() throws Exception {
        var result = TestCompiler.target(temp, "26.2").source("""
                package t;
                import cc.gahaha.fastmultiversion.Mc;
                public class Dynamic {
                    static boolean check(String range) { return Mc.is(range); }
                }
                """).compile().assertFailure();

        assertTrue(result.errors().contains("must be a string literal or a String constant declared in this file"), result.errors());
    }

    @Test
    void reportsInvalidRanges() throws Exception {
        var result = TestCompiler.target(temp, "26.2").source("""
                package t;
                import cc.gahaha.fastmultiversion.Mc;
                public class Invalid {
                    static boolean a() { return Mc.is("[26.2,26.1)"); }
                    static boolean b() { return Mc.is("26.x"); }
                }
                """).compile().assertFailure();

        assertTrue(result.errors().contains("line 4: [multiversion]"), result.errors());
        assertTrue(result.errors().contains("contains no versions"), result.errors());
        assertTrue(result.errors().contains("line 5: [multiversion]"), result.errors());
        assertTrue(result.errors().contains("invalid Minecraft version '26.x'"), result.errors());
    }

    @Test
    void reportsMissingTarget() throws Exception {
        var result = TestCompiler.noTarget(temp).source(BRANCHES).compile().assertFailure();

        assertTrue(result.errors().contains("target=<version>"), result.errors());
    }

    @Test
    void reportsIndirectUseOfMc() throws Exception {
        var result = TestCompiler.target(temp, "26.2").source("""
                package t;
                import cc.gahaha.fastmultiversion.Mc;
                import java.util.function.Predicate;
                public class Indirect {
                    static Predicate<String> check = Mc::is;
                }
                """).compile().assertFailure();

        assertTrue(result.errors().contains("direct call Mc.is"), result.errors());
    }
}
