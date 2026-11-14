package cc.gahaha.fastmultiversion.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IfMcTest {
    @TempDir
    Path temp;

    private static final String WIDGET = """
            package t;
            import cc.gahaha.fastmultiversion.IfMc;
            public class Widget {
                public static class OldContext {}
                public static class NewContext {}

                @IfMc("[26.1,26.2)") public static final String OLD_FIELD = "OLD_FIELD_VALUE";
                @IfMc("[26.2,)") public static final String NEW_FIELD = "NEW_FIELD_VALUE";

                public Widget() {}
                @IfMc("[26.1,26.2)") public Widget(OldContext context) {}

                @IfMc("[26.1,26.2)") public String render(OldContext context) { return "old"; }
                @IfMc("[26.2,)") public String render(NewContext context) { return "new"; }

                @IfMc("[26.1,26.2)") public static class OldHelper {}
            }
            """;

    @Test
    void removesMembersOutsideTheTarget() throws Exception {
        var result = TestCompiler.target(temp, "26.2").source(WIDGET).compile().assertSuccess();
        Class<?> widget = result.load("t.Widget");

        assertEquals(List.of("NewContext"), renderParameters(widget));
        assertEquals(List.of("NEW_FIELD"), fieldNames(widget));
        assertEquals(1, widget.getDeclaredConstructors().length);
        assertFalse(result.hasClass("t.Widget$OldHelper"));
        assertFalse(result.mentions("t.Widget", "OldHelper"), "removed nested class is still listed");
        assertFalse(result.mentions("t.Widget", "OLD_FIELD"));
    }

    @Test
    void keepsMembersInsideTheTarget() throws Exception {
        var result = TestCompiler.target(temp, "26.1").source(WIDGET).compile().assertSuccess();
        Class<?> widget = result.load("t.Widget");

        assertEquals(List.of("OldContext"), renderParameters(widget));
        assertEquals(List.of("OLD_FIELD"), fieldNames(widget));
        assertEquals(2, widget.getDeclaredConstructors().length);
        assertTrue(result.hasClass("t.Widget$OldHelper"));
    }

    private static List<String> renderParameters(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.getName().equals("render"))
                .map(m -> m.getParameterTypes()[0].getSimpleName())
                .toList();
    }

    private static List<String> fieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields()).map(f -> f.getName()).sorted().toList();
    }

    private static final String BRIDGE = """
            package t;
            import cc.gahaha.fastmultiversion.IfMc;
            public class Bridge {
                public static class Base<T> {
                    public String take(T value) { return "base"; }
                }
                public static class Impl extends Base<String> {
                    @IfMc("[26.1,26.2)") @Override public String take(String value) { return "impl"; }
                }
                public static String call() {
                    Base<String> base = new Impl();
                    return base.take("x");
                }
            }
            """;

    @Test
    void removedOverrideLeavesNoBridgeMethod() throws Exception {
        var newer = TestCompiler.target(temp, "26.2").source(BRIDGE).compile().assertSuccess();
        assertEquals("base", newer.call("t.Bridge", "call"));
        Method[] declared = newer.load("t.Bridge$Impl").getDeclaredMethods();
        assertEquals(0, declared.length, () -> "left over: " + Arrays.toString(declared));

        var older = TestCompiler.target(temp, "26.1").source(BRIDGE).compile().assertSuccess();
        assertEquals("impl", older.call("t.Bridge", "call"));
    }

    private static final String LEGACY = """
            package t;
            import cc.gahaha.fastmultiversion.IfMc;
            @IfMc("[26.1,26.2)")
            public class Legacy {
                public static class Inner {}
                public static Runnable lambda = () -> {};
                public Object anonymous = new Object() {};
                public static String hello() { return "hello"; }
            }
            """;

    @Test
    void removesWholeTopLevelClass() throws Exception {
        var newer = TestCompiler.target(temp, "26.2").source(LEGACY).compile().assertSuccess();
        assertFalse(newer.hasClass("t.Legacy"));
        assertFalse(newer.hasClass("t.Legacy$Inner"));
        assertFalse(newer.hasClass("t.Legacy$1"));

        var older = TestCompiler.target(temp, "26.1").source(LEGACY).compile().assertSuccess();
        assertTrue(older.hasClass("t.Legacy"));
        assertTrue(older.hasClass("t.Legacy$Inner"));
        assertTrue(older.hasClass("t.Legacy$1"));
    }

    private static final String REFS = """
            package t;
            import cc.gahaha.fastmultiversion.IfMc;
            import cc.gahaha.fastmultiversion.Mc;
            public class Refs {
                @IfMc("[26.1,26.2)") static String oldOnly() { return "old"; }
                static String guarded() {
                    if (Mc.is("[26.1,26.2)")) return oldOnly();
                    return "new";
                }
                @IfMc("[26.1,26.2)") static String alsoOld() { return oldOnly(); }
                static String broken() { return oldOnly(); }
            }
            """;

    @Test
    void reportsReferencesToRemovedMembersFromKeptCode() throws Exception {
        var result = TestCompiler.target(temp, "26.2").source(REFS).compile().assertFailure();

        // Only broken() on line 11; guarded() and alsoOld() are pruned with oldOnly().
        // javac reports the missing symbol itself; the plugin explains why it is missing.
        assertEquals(1, result.errors().lines().filter(l -> l.startsWith("line ")).count(), result.errors());
        assertTrue(result.errors().startsWith("line 11: cannot find symbol"), result.errors());
        assertTrue(result.notes().contains("line 11: [multiversion] method Refs.oldOnly(...) is declared with "
                + "@IfMc(\"[26.1,26.2)\"), so it is not part of the Minecraft 26.2 build"), result.notes());
    }

    @Test
    void acceptsTheSameReferencesWhereTheMemberExists() throws Exception {
        TestCompiler.target(temp, "26.1").source(REFS).compile().assertSuccess();
    }

    @Test
    void reportsReferencesToRemovedTopLevelClass() throws Exception {
        var result = TestCompiler.target(temp, "26.2").source(LEGACY).source("""
                package t;
                public class User {
                    public static String use() { return Legacy.hello(); }
                }
                """).compile().assertFailure();

        assertTrue(result.errors().contains("line 3: cannot find symbol"), result.errors());
        assertTrue(result.notes().contains("class Legacy is declared with @IfMc(\"[26.1,26.2)\")"), result.notes());
    }

    @Test
    void reportsInvalidRange() throws Exception {
        var result = TestCompiler.target(temp, "26.2").source("""
                package t;
                import cc.gahaha.fastmultiversion.IfMc;
                public class BadRange {
                    @IfMc("[26.1,26.2") void broken() {}
                }
                """).compile().assertFailure();

        assertTrue(result.errors().contains("line 4: [multiversion] version range '[26.1,26.2' is missing"),
                result.errors());
    }

    private static final String POINT = """
            package t;
            import cc.gahaha.fastmultiversion.IfMc;
            public record Point(int x, @IfMc("[26.1,26.2)") int y) {}
            """;

    @Test
    void removesRecordComponents() throws Exception {
        var newer = TestCompiler.target(temp, "26.2").source(POINT).compile().assertSuccess();
        assertEquals(1, newer.load("t.Point").getRecordComponents().length);

        var older = TestCompiler.target(temp, "26.1").source(POINT).compile().assertSuccess();
        assertEquals(2, older.load("t.Point").getRecordComponents().length);
    }

    private static final String LOCAL = """
            package t;
            import cc.gahaha.fastmultiversion.IfMc;
            import cc.gahaha.fastmultiversion.Mc;
            public class Local {
                public static String run() {
                    @IfMc("[26.1,26.2)")
                    class OldHelper { String name() { return "LOCAL_OLD"; } }
                    if (Mc.is("[26.1,26.2)")) {
                        return new OldHelper().name();
                    }
                    return "new";
                }
            }
            """;

    @Test
    void removesLocalClasses() throws Exception {
        var newer = TestCompiler.target(temp, "26.2").source(LOCAL).compile().assertSuccess();
        assertEquals("new", newer.call("t.Local", "run"));
        assertFalse(newer.hasClass("t.Local$1OldHelper"));

        var older = TestCompiler.target(temp, "26.1").source(LOCAL).compile().assertSuccess();
        assertEquals("LOCAL_OLD", older.call("t.Local", "run"));
    }
}
