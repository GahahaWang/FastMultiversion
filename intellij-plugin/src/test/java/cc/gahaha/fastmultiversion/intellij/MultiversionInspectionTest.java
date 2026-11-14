package cc.gahaha.fastmultiversion.intellij;

import cc.gahaha.fastmultiversion.apidb.ApiClass.ClassShape;
import cc.gahaha.fastmultiversion.apidb.ApiClass.MemberKind;
import cc.gahaha.fastmultiversion.apidb.ApiClass.MemberShape;
import cc.gahaha.fastmultiversion.apidb.ApiDatabase;
import cc.gahaha.fastmultiversion.apidb.ApiDatabaseBuilder;
import cc.gahaha.fastmultiversion.apidb.ApiFlags;
import cc.gahaha.fastmultiversion.versioning.McVersion;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.TextRange;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * The IDE side, with "26.2" active: the sources added in {@link #setUp()} are what the IDE resolves against
 * (26.2's API), and the database also knows 26.1, which has {@code Registry.id}, {@code OldThing} and
 * {@code render(int)} instead.
 */
public class MultiversionInspectionTest extends LightJavaCodeInsightFixtureTestCase {
    private static final int PUBLIC = ApiFlags.PUBLIC;
    private static final int V1 = 1;
    private static final int V2 = 2;
    private static final int BOTH = 3;

    /** The JDK the tests run on (the IDE's runtime); the bundled mock JDKs only exist in IntelliJ's own sources. */
    private static final LightProjectDescriptor RUNTIME_JDK = new DefaultLightProjectDescriptor() {
        @Override
        public Sdk getSdk() {
            return JavaSdk.getInstance().createJdk("runtime", System.getProperty("java.home"), false);
        }
    };

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return RUNTIME_JDK;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addClass("""
                package cc.gahaha.fastmultiversion;
                public final class Mc { private Mc() {} public static boolean is(String range) { return false; } }
                """);
        myFixture.addClass("""
                package cc.gahaha.fastmultiversion;
                public @interface IfMc { String value(); }
                """);
        myFixture.addClass("package mc; public interface Callback<T> { void call(T value); }");
        myFixture.addClass("package mc; public class Item {}");
        myFixture.addClass("""
                package mc;
                public class Registry {
                    public static String of(String a, String b) { return a + "/" + b; }
                    public void hurt(Callback<Registry> callback) {}
                }
                """);
        myFixture.addClass("package mc; public class NewThing { public String name() { return \"new\"; } }");
        myFixture.addClass("package mc; public class Screen { public void render(long x) {} }");
        MultiversionProject.getInstance(getProject()).setForTests(database(), McVersion.parse("26.2"));
        myFixture.enableInspections(new McApiRangeInspection());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            MultiversionProject.getInstance(getProject()).setForTests(null, null);
        } finally {
            super.tearDown();
        }
    }

    public void testReportsApiThatSomeVersionsLack() {
        myFixture.configureByText("Unguarded.java", """
                import mc.*;
                class Unguarded {
                    String a() { return Registry.of("a", "b"); }
                    String b() { return new NewThing().name(); }
                    void c(Registry r) { r.hurt(x -> {}); }
                    static class MyScreen extends Screen {
                        @Override public void render(long x) {}
                    }
                }
                """);
        List<HighlightInfo> errors = errors();

        assertEquals(describe(errors), 4, errors.size());
        assertError(errors, "of", "'Registry.of(String, String)' only exists in [26.2,), but this code runs in [26.1,). "
                + "Use it inside if (Mc.is(\"[26.2,)\")) or in a declaration with @IfMc(\"[26.2,)\").");
        assertError(errors, "NewThing", "'mc.NewThing' only exists in [26.2,)");
        assertError(errors, "hurt", "'Registry.hurt(Callback<Registry>)' exists in every version this code runs in, "
                + "but only has this form");
        assertError(errors, "render", "'MyScreen.render' overrides 'Screen.render(long)', which only exists like this in [26.2,)");
    }

    public void testAcceptsGuardedCode() {
        myFixture.configureByText("Guarded.java", """
                import cc.gahaha.fastmultiversion.IfMc;
                import cc.gahaha.fastmultiversion.Mc;
                import mc.*;
                class Guarded {
                    String id() {
                        if (Mc.is("[26.2,)")) {
                            return Registry.of("a", "b");
                        } else {
                            return Registry.id("a", "b");
                        }
                    }
                    String thing() { return Mc.is("[26.2,)") ? new NewThing().name() : new OldThing().name(); }
                    String early() {
                        if (!Mc.is("[26.2,)")) {
                            return new OldThing().name();
                        }
                        return new NewThing().name();
                    }
                    boolean both() { return Mc.is("[26.2,)") && new NewThing().name() != null; }
                    @IfMc("[26.2,)") String annotated() { return Registry.of("x", "y"); }
                    @IfMc("[26.1,26.2)") String old() { return Registry.id("x", "y"); }
                    void hurt(Registry r) {
                        if (Mc.is("[26.2,)")) {
                            r.hurt(x -> {});
                        }
                    }
                    static class MyScreen extends Screen {
                        @IfMc("[26.2,)") @Override public void render(long x) {}
                    }
                }
                """);

        // No range errors, and no "cannot resolve" for OldThing or Registry.id in the 26.1-only code (without
        // an IDE module for 26.1 it is not checked here; the javac plugin checks it when building 26.1).
        assertEmpty(describe(errors()), errors());
    }

    public void testShowsSyntaxErrorsInInactiveCode() {
        myFixture.configureByText("Broken.java", """
                import cc.gahaha.fastmultiversion.Mc;
                import mc.*;
                class Broken {
                    String id() {
                        if (Mc.is("[26.2,)")) {
                            return Registry.of("a", "b");
                        } else {
                            return Registry.id("a", "b")
                        }
                    }
                    String thing() { return Mc.is("[26.2,)") ? new NewThing().name() : new OldThing().name() }
                }
                """);
        // Both missing ';' are reported, although the IDE puts them at the end of 26.1-only code.
        List<HighlightInfo> errors = errors();
        assertEquals(describe(errors), 2, errors.size());
        assertTrue(describe(errors), errors.stream().allMatch(info -> "';' expected".equals(info.getDescription())));
    }

    public void testChecksRanges() {
        myFixture.addClass("package other; public class Ranges { public static final String NEW = \"[26.2,)\"; }");
        myFixture.configureByText("Ranges.java", """
                import cc.gahaha.fastmultiversion.IfMc;
                import cc.gahaha.fastmultiversion.Mc;
                class Ranges {
                    static final String OLD = "[26.1," + "26.2)";
                    boolean a() { return Mc.is("[26.2"); }
                    boolean b() { return Mc.is("26.3"); }
                    boolean c() { return Mc.is(other.Ranges.NEW); }
                    boolean d() { return Mc.is(OLD) || Mc.is("26.2"); }
                    @IfMc("[26.1,26.2)") void e() {}
                    @IfMc("2x.1") void f() {}
                }
                """);
        List<HighlightInfo> problems = myFixture.doHighlighting(HighlightSeverity.WARNING);
        assertEquals(describe(problems), 4, problems.size());
        assertProblem(problems, "\"[26.2\"", HighlightSeverity.ERROR, "Version range '[26.2' is missing ']' or ')'.");
        assertProblem(problems, "\"26.3\"", HighlightSeverity.WARNING,
                "'26.3' matches none of the versions this project builds (26.1, 26.2), so it is false in every build.");
        assertProblem(problems, "other.Ranges.NEW", HighlightSeverity.ERROR,
                "The range passed to Mc.is must be a string literal or a String constant declared in this file");
        assertProblem(problems, "\"2x.1\"", HighlightSeverity.ERROR, "In version range '2x.1': invalid Minecraft version '2x.1' (expected e.g. 26.1");
    }

    public void testCompletesRanges() {
        myFixture.configureByText("Complete.java", """
                import cc.gahaha.fastmultiversion.Mc;
                class Complete {
                    boolean a() { return Mc.is("[26.<caret>"); }
                }
                """);
        myFixture.completeBasic();
        List<String> ranges = myFixture.getLookupElementStrings();
        assertNotNull(ranges);
        assertTrue(ranges.toString(), ranges.containsAll(List.of("[26.1,)", "[26.2,)", "[26.1,26.2)")));
        assertFalse(ranges.toString(), ranges.contains("26.1"));
    }

    public void testSurroundsWithMcIs() {
        myFixture.configureByText("Fix.java", """
                import mc.*;
                class Fix {
                    void run() {
                        Registry.o<caret>f("a", "b");
                    }
                }
                """);
        IntentionAction fix = myFixture.findSingleIntention("Surround with if (Mc.is(\"[26.2,)\"))");
        myFixture.launchAction(fix);

        String text = myFixture.getFile().getText();
        assertTrue(text, text.contains("import cc.gahaha.fastmultiversion.Mc;"));
        assertTrue(text, text.replaceAll("\\s+", " ").contains("if (Mc.is(\"[26.2,)\")) { Registry.of(\"a\", \"b\"); }"));
        assertEmpty(describe(errors()), errors());
    }

    public void testAnnotatesWithIfMc() {
        myFixture.configureByText("MyScreen.java", """
                import mc.*;
                class MyScreen extends Screen {
                    @Override public void ren<caret>der(long x) {}
                }
                """);
        IntentionAction fix = myFixture.findSingleIntention("Annotate with @IfMc(\"[26.2,)\")");
        myFixture.launchAction(fix);

        String text = myFixture.getFile().getText();
        assertTrue(text, text.contains("import cc.gahaha.fastmultiversion.IfMc;"));
        assertTrue(text, text.contains("@IfMc(\"[26.2,)\")"));
        assertEmpty(describe(errors()), errors());
    }

    public void testHidesImportsOfOtherVersionsOnly() {
        myFixture.configureByText("Imports.java", """
                import mc.OldThing;
                import mc.gone.*;
                import mc.Typo;
                import mc.NewThing;
                class Imports {}
                """);
        List<HighlightInfo> errors = errors();
        assertEquals(describe(errors), 1, errors.size());
        assertEquals("Typo", text(errors.getFirst()));
    }

    public void testDoesNothingWithoutASetup() {
        MultiversionProject.getInstance(getProject()).setForTests(null, null);
        myFixture.configureByText("Plain.java", """
                import mc.*;
                class Plain { String a() { return Registry.of("a", "b"); } }
                """);
        assertEmpty(describe(errors()), errors());
    }

    // ---- helpers

    private List<HighlightInfo> errors() {
        return myFixture.doHighlighting(HighlightSeverity.ERROR);
    }

    private String text(HighlightInfo info) {
        return myFixture.getEditor().getDocument().getText(TextRange.create(info.getStartOffset(), info.getEndOffset()));
    }

    private String describe(List<HighlightInfo> infos) {
        return infos.stream().map(i -> "'" + text(i) + "': " + i.getDescription()).toList().toString();
    }

    private void assertProblem(List<HighlightInfo> problems, String highlighted, HighlightSeverity severity, String descriptionStart) {
        assertTrue(describe(problems), problems.stream().anyMatch(info -> text(info).equals(highlighted) && info.getSeverity() == severity
                && info.getDescription() != null && info.getDescription().startsWith(descriptionStart)));
    }

    private void assertError(List<HighlightInfo> errors, String highlighted, String descriptionStart) {
        assertTrue(describe(errors), errors.stream().anyMatch(info -> text(info).equals(highlighted)
                && info.getDescription() != null && info.getDescription().startsWith(descriptionStart)));
    }

    private static ApiDatabase database() {
        ApiDatabaseBuilder b = new ApiDatabaseBuilder(List.of(McVersion.parse("26.1"), McVersion.parse("26.2")));
        b.addPlatformClass("java/lang/Object", new ClassShape(PUBLIC, null, List.of(), null));
        cls(b, "mc/Callback", BOTH, PUBLIC | ApiFlags.INTERFACE | ApiFlags.ABSTRACT);
        cls(b, "mc/Item", BOTH, PUBLIC);
        cls(b, "mc/Registry", BOTH, PUBLIC);
        String twoStrings = "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
        method(b, "mc/Registry", V1, "id", twoStrings, PUBLIC | ApiFlags.STATIC, null);
        method(b, "mc/Registry", V2, "of", twoStrings, PUBLIC | ApiFlags.STATIC, null);
        method(b, "mc/Registry", V1, "hurt", "(Lmc/Callback;)V", PUBLIC, "(Lmc/Callback<Lmc/Item;>;)V");
        method(b, "mc/Registry", V2, "hurt", "(Lmc/Callback;)V", PUBLIC, "(Lmc/Callback<Lmc/Registry;>;)V");
        method(b, "mc/Registry", BOTH, "<init>", "()V", PUBLIC, null);
        for (String thing : List.of("mc/OldThing", "mc/NewThing")) {
            int versions = thing.equals("mc/OldThing") ? V1 : V2;
            cls(b, thing, versions, PUBLIC);
            method(b, thing, versions, "<init>", "()V", PUBLIC, null);
            method(b, thing, versions, "name", "()Ljava/lang/String;", PUBLIC, null);
        }
        cls(b, "mc/gone/Gone", V1, PUBLIC);
        cls(b, "mc/Screen", BOTH, PUBLIC);
        method(b, "mc/Screen", BOTH, "<init>", "()V", PUBLIC, null);
        method(b, "mc/Screen", V1, "render", "(I)V", PUBLIC, null);
        method(b, "mc/Screen", V2, "render", "(J)V", PUBLIC, null);
        return b.build();
    }

    private static void cls(ApiDatabaseBuilder b, String name, int versions, int access) {
        for (int v = 0; v < 2; v++) {
            if ((versions & (1 << v)) != 0) {
                b.addClass(v, name, new ClassShape(access, "java/lang/Object", List.of(), null));
            }
        }
    }

    private static void method(ApiDatabaseBuilder b, String owner, int versions, String name, String descriptor, int access, String signature) {
        for (int v = 0; v < 2; v++) {
            if ((versions & (1 << v)) != 0) {
                b.addMember(v, MemberKind.METHOD, owner, name, descriptor, new MemberShape(access, signature));
            }
        }
    }
}
