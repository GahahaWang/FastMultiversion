package cc.gahaha.fastmultiversion.intellij;

import cc.gahaha.fastmultiversion.apidb.ApiDatabase;
import cc.gahaha.fastmultiversion.apidb.generator.ApiDbGenerator;
import cc.gahaha.fastmultiversion.apidb.generator.VersionJars;
import cc.gahaha.fastmultiversion.versioning.McVersion;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.ide.highlighter.JavaHighlightingColors;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider;
import com.intellij.lang.java.JavaDocumentationTarget;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.module.ModifiableModuleModel;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.platform.backend.documentation.DocumentationTarget;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.testFramework.IndexingTestUtil;
import com.intellij.codeInspection.TextBlockMigrationInspection;
import com.siyeh.ig.style.UnnecessarySemicolonInspection;
import com.intellij.testFramework.UsefulTestCase;
import com.intellij.testFramework.builders.JavaModuleFixtureBuilder;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture;
import com.intellij.testFramework.fixtures.JavaTestFixtureFactory;
import com.intellij.testFramework.fixtures.TestFixtureBuilder;
import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Code that does not run in the active version, resolved in the version it runs in. The project is laid
 * out like a synced multiversion project: the main module (the active version, 26.2) owns the sources and
 * has 26.2's Minecraft; the module {@code EntityCount.26_1.main} (the project {@code :26.1}) has 26.1's.
 * In 26.1 the screen is {@code mc.screen} / {@code mc.setScreen}; in 26.2 it moved to {@code mc.gui}.
 */
public class VersionViewTest extends UsefulTestCase {
    private static final String COMPAT = """
            import cc.gahaha.fastmultiversion.Mc;
            import mc.*;
            class Compat {
                static Screen screen(Minecraft mc) {
                    return Mc.is("[26.2,)") ? mc.gui.screen() : mc.screen;
                }
                static void setScreen(Minecraft mc, Screen screen) {
                    if (Mc.is("[26.2,)")) {
                        mc.gui.setScreen(screen);
                    } else {
                        mc.setScreen(screen);
                    }
                }
                static boolean hidden(Minecraft mc) {
                    return !Mc.is("[26.2,)") && mc.options.hideGui;
                }
            }
            """;

    private static Path libraries;
    private static ApiDatabase database;
    private JavaCodeInsightTestFixture myFixture;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Path libraries = libraries();
        VfsRootAccess.allowRootAccess(getTestRootDisposable(), libraries.toString());

        TestFixtureBuilder<IdeaProjectTestFixture> project = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder(getName());
        myFixture = JavaTestFixtureFactory.getFixtureFactory().createCodeInsightFixture(project.getFixture());
        JavaModuleFixtureBuilder<?> active = project.addModule(JavaModuleFixtureBuilder.class);
        active.addSourceContentRoot(myFixture.getTempDirPath());
        active.addJdk(System.getProperty("java.home"));
        active.addLibrary("mc-26.2", libraries.resolve("mc-26.2").toString());
        JavaModuleFixtureBuilder<?> old = project.addModule(JavaModuleFixtureBuilder.class);
        old.addJdk(System.getProperty("java.home"));
        old.addLibrary("mc-26.1", libraries.resolve("mc-26.1").toString());
        myFixture.setUp();

        Module oldModule = old.getFixture().getModule();
        WriteAction.runAndWait(() -> {
            ModifiableModuleModel model = ModuleManager.getInstance(myFixture.getProject()).getModifiableModel();
            model.renameModule(oldModule, "EntityCount.26_1.main");
            model.commit();
        });
        IndexingTestUtil.waitUntilIndexesAreReady(myFixture.getProject());

        myFixture.addClass("""
                package cc.gahaha.fastmultiversion;
                public final class Mc { private Mc() {} public static boolean is(String range) { return false; } public @interface Is { String value(); } }
                """);
        MultiversionProject.getInstance(myFixture.getProject()).setForTests(database, McVersion.parse("26.2"));
        myFixture.enableInspections(new McApiRangeInspection(), new McUnusedImportInspection());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            MultiversionProject.getInstance(myFixture.getProject()).setForTests(null, null);
            myFixture.tearDown();
        } catch (Throwable e) {
            addSuppressedException(e);
        } finally {
            myFixture = null;
            super.tearDown();
        }
    }

    public void testChecksInactiveCodeAgainstItsVersion() {
        myFixture.configureByText("Compat.java", COMPAT);
        assertEmpty(describe(errors()), errors());

        myFixture.configureByText("Typos.java", """
                import cc.gahaha.fastmultiversion.Mc;
                import mc.*;
                class Typos {
                    static Object screen(Minecraft mc) {
                        return Mc.is("[26.2,)") ? mc.gui.screen() : mc.scren;
                    }
                    static void setScreen(Minecraft mc, Screen screen) {
                        if (Mc.is("[26.2,)")) {
                            mc.gui.setScren(screen);
                        } else {
                            mc.setScren(screen);
                            mc.gui.setScreen(screen);
                        }
                    }
                }
                """);
        List<HighlightInfo> errors = errors();
        assertError(errors, "scren", "[26.1] Cannot resolve symbol 'scren'");
        assertError(errors, "setScren", "[26.1] Cannot resolve method 'setScren' in 'Minecraft'");
        assertError(errors, "gui", "[26.1] Cannot resolve symbol 'gui'"); // 26.2's API in the 26.1 branch
        assertError(errors, "setScren", "Cannot resolve method 'setScren' in 'Gui'"); // the active branch, as usual
        assertEquals(describe(errors), 4, errors.size());
    }

    public void testImportsAClassThatOnlyAnotherVersionHas() {
        myFixture.configureByText("Imports.java", """
                import cc.gahaha.fastmultiversion.Mc;
                import mc.Minecraft;
                import mc.Options;
                class Imports {
                    static boolean hidden(Minecraft mc) {
                        if (!Mc.is("[26.2,)")) {
                            Options options = mc.options;
                            return options.hideGui;
                        }
                        return false;
                    }
                    static Options unguarded;
                }
                """);
        List<HighlightInfo> errors = errors();
        // The import and the 26.1 branch are fine; the code that also runs in 26.2 is told where Options exists.
        assertEquals(describe(errors), 1, errors.size());
        assertError(errors, "Options", "'mc.Options' only exists in [26.1,26.2), but this code runs in [26.1,).");
        assertTrue(describe(errors), errors.getFirst().getStartOffset() > myFixture.getFile().getText().indexOf("static Options unguarded"));
    }

    public void testExplainsApiOfOtherVersionsInActiveCode() {
        myFixture.configureByText("Active.java", """
                import cc.gahaha.fastmultiversion.Mc;
                import mc.*;
                class Active {
                    static void run(Minecraft mc, Screen screen) {
                        mc.setScr<caret>een(screen);
                        if (Mc.is("[26.2,)")) {
                            Object old = mc.screen;
                        }
                    }
                }
                """);
        List<HighlightInfo> errors = errors();
        assertError(errors, "setScreen", "'Minecraft.setScreen' only exists in [26.1,26.2), but this code runs in [26.1,).");
        assertError(errors, "screen", "Cannot resolve symbol 'screen' ('Minecraft.screen' only exists in [26.1,26.2))");
        assertEquals(describe(errors), 2, errors.size()); // in place of the IDE's own errors

        myFixture.launchAction(myFixture.findSingleIntention("Surround with if (Mc.is(\"[26.1,26.2)\"))"));
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().replaceAll("\\s+", " ")
                .contains("if (Mc.is(\"[26.1,26.2)\")) { mc.setScreen(screen); }"));
        assertEquals(describe(errors()), 1, errors().size()); // now 26.1 code, which has setScreen
    }

    public void testImportsAClassOfTheBranchVersion() {
        myFixture.configureByText("Fix.java", """
                import cc.gahaha.fastmultiversion.Mc;
                import mc.Minecraft;
                class Fix {
                    static boolean hidden(Minecraft mc) {
                        if (!Mc.is("[26.2,)")) {
                            Opt<caret>ions options = mc.options;
                            return options.hideGui;
                        }
                        return false;
                    }
                }
                """);
        assertError(errors(), "Options", "[26.1] Cannot resolve symbol 'Options'");
        myFixture.launchAction(myFixture.findSingleIntention("Import 'mc.Options' (26.1)"));

        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("import mc.Minecraft;\nimport mc.Options;\n"));
        assertEmpty(describe(errors()), errors());
    }

    public void testCompletesClassesOfTheBranchVersion() {
        myFixture.configureByText("Complete.java", """
                import cc.gahaha.fastmultiversion.Mc;
                import mc.Minecraft;
                class Complete {
                    static boolean hidden(Minecraft mc) {
                        if (!Mc.is("[26.2,)")) {
                            Optio<caret>
                        }
                        return false;
                    }
                }
                """);
        LookupElement[] elements = myFixture.completeBasic();
        if (elements != null) {
            LookupElement options = Arrays.stream(elements).filter(e -> e.getLookupString().equals("Options")).findFirst()
                    .orElseThrow(() -> new AssertionError(myFixture.getLookupElementStrings()));
            String tail = LookupElementPresentation.renderElement(options).getTailText();
            assertTrue(tail, tail != null && tail.contains("26.1"));
            myFixture.getLookup().setCurrentItem(options);
            myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
        }
        String text = myFixture.getFile().getText();
        assertTrue(text, text.contains("import mc.Options;"));
        assertTrue(text, text.contains("            Options\n"));
    }

    public void testImportsAMovedClassFromEachVersion() {
        myFixture.configureByText("Poses.java", """
                import cc.gahaha.fastmultiversion.Mc;
                import mc.newpkg.Pose;
                import mc.oldpkg.Pose;
                class Poses {
                    static String name() {
                        return Mc.is("[26.2,)") ? new Pose().name() : new Po<caret>se().name();
                    }
                }
                """);
        assertEmpty(describe(errors()), errors());
        PsiElement[] targets = gotoTargets();
        assertEquals(1, targets.length);
        assertTrue(path(targets[0]), path(targets[0]).endsWith("/mc-26.1/mc/oldpkg/Pose.class"));
    }

    public void testExplainsAnImportThatHidesTheActiveVersionsClass() {
        // mc.oldpkg.Pose (26.1) comes first, so the IDE does not see 26.2's mc.newpkg.Pose through the second import.
        myFixture.configureByText("Poses.java", """
                import cc.gahaha.fastmultiversion.Mc;
                import mc.oldpkg.Pose;
                import mc.newpkg.Pose;
                class Poses {
                    static String name() {
                        return Mc.is("[26.2,)") ? new Po<caret>se().name() : new Pose().name();
                    }
                }
                """);
        // Explained on the active branch's Pose (with follow-on errors until it is fixed); the 26.1 branch is fine,
        // its view drops the 26.2 import.
        List<HighlightInfo> errors = errors();
        assertError(errors, "Pose", "Cannot resolve symbol 'Pose': the IDE resolves it through the first import of that name, "
                + "'mc.oldpkg.Pose', which this version does not have.");
        assertEquals(describe(errors), 1, errors.stream().filter(info -> text(info).equals("Pose")).count());

        myFixture.launchAction(myFixture.findSingleIntention("Import the packages of 'Pose' on demand"));
        String text = myFixture.getFile().getText();
        assertTrue(text, text.contains("import mc.oldpkg.*;\nimport mc.newpkg.*;\n"));
        assertEmpty(describe(errors()), errors());
    }

    public void testReportsJavaErrorsOfInactiveCodeWithItsVersion() {
        myFixture.configureByText("Errors.java", """
                import cc.gahaha.fastmultiversion.Mc;
                import mc.*;
                class Errors {
                    static void run(Minecraft mc) {
                        if (Mc.is("[26.2,)")) {
                            Screen screen = mc.gui.screen();
                        } else {
                            String hidden = mc.options.hideGui;
                            int count = "many";
                            Screen screen = mc.screen;
                        }
                    }
                    @Mc.Is("[26.1,26.2)")
                    static Screen old(Minecraft mc) {
                        if (mc.screen != null) {
                            return mc.screen;
                        }
                    }
                }
                """);
        List<HighlightInfo> errors = errors();
        assertError(errors, "hideGui", "[26.1] Incompatible types. Found: 'boolean', required: 'java.lang.String'");
        assertError(errors, "\"many\";", "[26.1] Incompatible types. Found: 'java.lang.String', required: 'int'");
        assertTrue(describe(errors), errors.stream().anyMatch(info -> "[26.1] Missing return statement".equals(info.getDescription())));
        assertEquals(describe(errors), 3, errors.size());
    }

    public void testDeclaresOncePerVersion() {
        myFixture.configureByText("Twice.java", """
                import cc.gahaha.fastmultiversion.Mc;
                import mc.*;
                class Twice {
                    @Mc.Is("[26.1,26.2)") static Screen screen(Minecraft mc) { return mc.screen; }
                    @Mc.Is("[26.2,)") static Screen screen(Minecraft mc) { return mc.gui.screen(); }
                    static Screen current(Minecraft mc) { return scr<caret>een(mc); }
                }
                """);
        assertEmpty(describe(errors()), errors());

        // The call goes to the active version's declaration, not to a choice of both.
        PsiElement[] targets = gotoTargets();
        assertEquals(1, targets.length);
        assertTrue(targets[0].getText(), targets[0].getText().contains("mc.gui.screen()"));
    }

    public void testRunsInspectionsOnInactiveCodeInItsVersion() {
        // Text blocks: offers its fix on every string, without highlighting it; that stays so.
        myFixture.enableInspections(new UnnecessarySemicolonInspection(), new TextBlockMigrationInspection());
        myFixture.configureByText("Semicolons.java", """
                import cc.gahaha.fastmultiversion.Mc;
                import mc.*;
                class Semicolons {
                    static void setScreen(Minecraft mc, Screen screen) {
                        if (Mc.is("[26.2,)")) {
                            mc.gui.setScreen(screen);;
                        } else {
                            mc.setScreen(screen);<caret>;
                            System.out.println("26.1");
                        }
                    }
                }
                """);
        List<HighlightInfo> warnings = myFixture.doHighlighting(HighlightSeverity.WARNING).stream()
                .filter(info -> info.getSeverity() == HighlightSeverity.WARNING).toList();
        assertEquals(describe(warnings), 2, warnings.size());
        int inactive = myFixture.getFile().getText().indexOf("mc.setScreen(screen);;") + "mc.setScreen(screen);".length();
        assertTrue(describe(warnings), warnings.stream().anyMatch(info -> info.getStartOffset() == inactive
                && info.getDescription().startsWith("[26.1] Unnecessary semicolon")));
        assertTrue(describe(warnings), warnings.stream().anyMatch(info -> info.getStartOffset() != inactive
                && info.getDescription().startsWith("Unnecessary semicolon")));

        // Its fix works on the file.
        myFixture.launchAction(myFixture.getAvailableIntentions().stream()
                .filter(action -> action.getText().startsWith("Remove unnecessary semicolon")).findFirst()
                .orElseThrow(() -> new AssertionError(myFixture.getAvailableIntentions().stream().map(a -> a.getText()).toList())));
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("mc.setScreen(screen);\n"));
    }

    public void testReportsUsesOfDeclarationsForOtherVersions() {
        myFixture.configureByText("Calls.java", """
                import cc.gahaha.fastmultiversion.Mc;
                class Calls {
                    static int run() {
                        if (Mc.is("[26.2,)")) {
                            return old(1);
                        }
                        return old(2); // fine: only 26.1 gets past the return
                    }
                    static int unguarded() { return old(3); }
                    @Mc.Is("[26.1,26.2)") static int old(int a) { return a; }
                }
                """);
        List<HighlightInfo> errors = errors();
        assertEquals(describe(errors), 2, errors.size());
        assertTrue(describe(errors), errors.stream().anyMatch(info -> info.getDescription().equals(
                "'Calls.old(int)' only exists in [26.1,26.2), not in [26.2,), where this code runs.")));
        assertTrue(describe(errors), errors.stream().anyMatch(info -> info.getDescription().startsWith(
                "'Calls.old(int)' only exists in [26.1,26.2), but this code runs in [26.1,). Use it inside if (Mc.is(\"[26.1,26.2)\"))")));
    }

    public void testNavigatesToTheDeclarationOfTheBranchVersion() {
        myFixture.configureByText("Compat.java", COMPAT.replace("mc.setScreen(screen)", "mc.setScr<caret>een(screen)"));
        PsiElement[] targets = gotoTargets();
        assertEquals(1, targets.length);
        assertTrue(targets[0] instanceof PsiMethod);
        assertTrue(path(targets[0]), path(targets[0]).contains("/mc-26.1/"));

        myFixture.configureByText("Compat.java", COMPAT.replace("mc.options.hideGui", "mc.options.hide<caret>Gui"));
        targets = gotoTargets();
        assertEquals(1, targets.length);
        assertTrue(path(targets[0]), path(targets[0]).contains("/mc-26.1/"));

        // A declaration in the file itself is the one in the editor.
        myFixture.configureByText("Compat.java", COMPAT.replace("mc.setScreen(screen)", "mc.setScreen(scr<caret>een)"));
        targets = gotoTargets();
        assertEquals(1, targets.length);
        assertTrue(targets[0] instanceof PsiParameter);
        assertSame(myFixture.getFile(), targets[0].getContainingFile());

        // Code that runs in the active version is left to the IDE.
        myFixture.configureByText("Compat.java", COMPAT.replace("mc.gui.setScreen(screen)", "mc.gui.setScr<caret>een(screen)"));
        int offset = myFixture.getCaretOffset();
        assertNull(new InactiveGotoDeclarationHandler().getGotoDeclarationTargets(
                myFixture.getFile().findElementAt(offset), offset, myFixture.getEditor()));
    }

    public void testNavigatesToClassesOfOtherVersionsFromActiveCode() {
        // mc.Options only exists in 26.1; the active version is 26.2, where the IDE finds no declaration.
        String imports = """
                import cc.gahaha.fastmultiversion.Mc;
                import mc.Opt<caret>ions;
                import mc.Minecraft;
                class Imports {
                    static boolean hidden(Minecraft mc) {
                        if (!Mc.is("[26.2,)")) {
                            return mc.options.hideGui;
                        }
                        return false;
                    }
                }
                """;
        myFixture.configureByText("Imports.java", imports);
        PsiElement[] targets = gotoTargets();
        assertEquals(1, targets.length);
        assertTrue(path(targets[0]), path(targets[0]).endsWith("/mc-26.1/mc/Options.class"));

        List<? extends DocumentationTarget> documented = IdeDocumentationTargetProvider.getInstance(myFixture.getProject())
                .documentationTargets(myFixture.getEditor(), myFixture.getFile(), myFixture.getCaretOffset());
        assertFalse(documented.isEmpty());
        assertTrue(path(((JavaDocumentationTarget) documented.getFirst()).getElement()).contains("/mc-26.1/"));

        // A name that no version has stays unresolved.
        myFixture.configureByText("Typo.java", imports.replace("mc.Opt<caret>ions", "mc.Opt<caret>ionz"));
        assertEquals(0, gotoTargets().length);
    }

    public void testNavigatesFromImportsOfClassesOnlyOtherVersionsHave() {
        // mc.text.Line only exists in 26.1, which also has mc.text.pos.Line, imported first; mc.gone, its whole
        // package, too. Their imports still go to 26.1's classes, and get the color of a class.
        myFixture.configureByText("Lines.java", """
                import mc.text.pos.Line;
                import mc.text.Li<caret>ne;
                import mc.gone.Thing;
                class Lines { Line line; }
                """);
        PsiElement[] targets = gotoTargets();
        assertEquals(1, targets.length);
        assertTrue(path(targets[0]), path(targets[0]).endsWith("/mc-26.1/mc/text/Line.class"));

        myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf("Thing") + 2);
        targets = gotoTargets();
        assertEquals(1, targets.length);
        assertTrue(path(targets[0]), path(targets[0]).endsWith("/mc-26.1/mc/gone/Thing.class"));

        List<HighlightInfo> infos = myFixture.doHighlighting();
        TextAttributesKey aClass = key(info(infos, "import mc.text.pos.Line", "Line"));
        assertNotNull(aClass);
        assertEquals(aClass, key(info(infos, "import mc.text.Line", "Line")));
        assertEquals(aClass, key(info(infos, "import mc.gone.Thing", "Thing")));
    }

    public void testDocumentsTheDeclarationOfTheBranchVersion() {
        myFixture.configureByText("Compat.java", COMPAT.replace("mc.setScreen(screen)", "mc.setScr<caret>een(screen)"));
        List<? extends DocumentationTarget> targets = IdeDocumentationTargetProvider.getInstance(myFixture.getProject())
                .documentationTargets(myFixture.getEditor(), myFixture.getFile(), myFixture.getCaretOffset());
        assertFalse(targets.isEmpty());
        PsiElement documented = ((JavaDocumentationTarget) targets.getFirst()).getElement();
        assertTrue(documented instanceof PsiMethod);
        assertTrue(path(documented), path(documented).contains("/mc-26.1/"));
    }

    public void testColorsNamesOfInactiveCodeWithTheColorScheme() {
        myFixture.configureByText("Compat.java", COMPAT);
        List<HighlightInfo> infos = myFixture.doHighlighting(); // names have a severity below INFORMATION
        // 26.1's setScreen is a method call, as the IDE has 26.2's in the active branch; 26.1's fields are instance fields.
        assertEquals(JavaHighlightingColors.METHOD_CALL_ATTRIBUTES, key(info(infos, "mc.gui.setScreen(screen)", "setScreen")));
        HighlightInfo setScreen = info(infos, "mc.setScreen(screen)", "setScreen");
        assertEquals(JavaHighlightingColors.METHOD_CALL_ATTRIBUTES, key(setScreen));
        assertEquals(JavaHighlightingColors.INSTANCE_FIELD_ATTRIBUTES, key(info(infos, ": mc.screen", "screen")));
        assertEquals(JavaHighlightingColors.INSTANCE_FIELD_ATTRIBUTES, key(info(infos, "mc.options.hideGui", "options")));
        assertEquals(JavaHighlightingColors.INSTANCE_FIELD_ATTRIBUTES, key(info(infos, "mc.options.hideGui", "hideGui")));

        // Keys, not colors: the editor's color scheme (e.g. a theme's) decides the colors.
        assertNull(setScreen.forcedTextAttributes);
        EditorColorsScheme theme = (EditorColorsScheme) EditorColorsManager.getInstance().getGlobalScheme().clone();
        theme.setAttributes(JavaHighlightingColors.METHOD_CALL_ATTRIBUTES, new TextAttributes(Color.RED, null, null, null, Font.PLAIN));
        assertEquals(Color.RED, setScreen.getTextAttributes(null, theme).getForegroundColor());
    }

    public void testColorsNamesOfOtherVersionsInActiveCode() {
        // mc.Options only exists in 26.1 (the active version is 26.2), mc.Minecraft in both: both imports get the
        // color the IDE gives a class.
        myFixture.configureByText("Imports.java", """
                import mc.Minecraft;
                import mc.Options;
                class Imports {
                    Minecraft mc;
                }
                """);
        List<HighlightInfo> infos = myFixture.doHighlighting();
        TextAttributesKey minecraft = key(info(infos, "import mc.Minecraft", "Minecraft"));
        assertNotNull(minecraft);
        assertEquals(minecraft, key(info(infos, "import mc.Options", "Options")));
    }

    public void testAcceptsAChainThroughAMethodThatReturnsAnotherClass() {
        // Data.range() returns mc.old.Range in 26.1 and mc.pos.Range in 26.2; both have within(int).
        myFixture.configureByText("Chain.java", """
                class Chain {
                    static boolean within() { return mc.Data.range().within(1); }
                }
                """);
        assertEmpty(describe(errors()), errors());

        // While the rest is being written (within has two overloads, so without its arguments it matches neither),
        // only that is reported, not range().
        for (String typing : List.of("mc.Data.range().within()", "mc.Data.range().")) {
            myFixture.configureByText("Typing.java", "class Typing {\n    static boolean within() { return " + typing + "; }\n}\n");
            List<HighlightInfo> errors = errors();
            assertFalse(typing + ": " + describe(errors), errors.isEmpty());
            assertFalse(typing + ": " + describe(errors), errors.stream().anyMatch(info -> text(info).equals("range")));
        }

        // Written down, the returned class is another one in each version: still reported.
        myFixture.configureByText("Typed.java", """
                class Typed {
                    static boolean within() { mc.pos.Range range = mc.Data.range(); return range.within(1); }
                }
                """);
        assertError(errors(), "Range", "'mc.pos.Range' only exists in [26.2,)");
    }

    public void testTheFirstImportWinsWhereAVersionHasBothClasses() {
        // 26.1 has mc.util.Layer and mc.util.pos.Layer, 26.2 only the latter: Layer is mc.util.pos.Layer in both, as
        // in the build, and so is what Layers.current() returns.
        myFixture.configureByText("Current.java", """
                import cc.gahaha.fastmultiversion.Mc;
                import mc.util.pos.Layer;
                import mc.util.Layer;
                class Current {
                    @Mc.Is("[26.1,26.2)") static Layer current() { return mc.Layers.current(); }
                    @Mc.Is("[26.2,)") static Layer current() { return mc.Layers.current(); }
                }
                """);
        assertEmpty(describe(errors()), errors());
    }

    public void testReportsImportsThatNoVersionUses() {
        // The IDE (26.2) cannot use the later imports of Line and Layer, nor the one of Thing (only 26.1 has
        // mc.text.Line and mc.gone.Thing), and 26.1 does not either: there Line and Layer are those of the first
        // imports, and nothing uses Thing. Options only exists in 26.1, whose code uses it.
        myFixture.configureByText("Imports.java", """
                import cc.gahaha.fastmultiversion.Mc;
                import mc.text.pos.Line;
                import mc.text.Line;
                import mc.util.pos.Layer;
                import mc.util.Layer;
                import mc.gone.Thing;
                import mc.Minecraft;
                import mc.Options;
                class Imports {
                    Line line;
                    Layer layer;
                    static boolean hidden(Minecraft mc) {
                        if (!Mc.is("[26.2,)")) {
                            Options options = mc.options;
                            return options.hideGui;
                        }
                        return false;
                    }
                }
                """);
        assertEquals(List.of("import mc.text.Line;", "import mc.util.Layer;", "import mc.gone.Thing;"), unusedImports());

        myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf("mc.gone.Thing"));
        myFixture.launchAction(myFixture.findSingleIntention("Remove unused import"));
        assertFalse(myFixture.getFile().getText(), myFixture.getFile().getText().contains("mc.gone.Thing"));
        assertEquals(List.of("import mc.text.Line;", "import mc.util.Layer;"), unusedImports());

        // Where the earlier import's class is missing in a version, the later import is that version's.
        myFixture.configureByText("Poses.java", """
                import cc.gahaha.fastmultiversion.Mc;
                import mc.newpkg.Pose;
                import mc.oldpkg.Pose;
                class Poses {
                    static String name() {
                        return Mc.is("[26.2,)") ? new Pose().name() : new Pose().name();
                    }
                }
                """);
        assertEmpty(unusedImports());
    }

    public void testReloadingTheSetupRebuildsTheViews() {
        myFixture.configureByText("Compat.java", COMPAT);
        MultiversionProject setup = MultiversionProject.getInstance(myFixture.getProject());
        PsiJavaFile file = (PsiJavaFile) myFixture.getFile();
        PsiJavaFile view = VersionViews.of(setup.state(), file, 0);
        assertNotNull(view);
        assertSame(view, VersionViews.of(setup.state(), file, 0));

        long before = setup.tracker().getModificationCount();
        setup.reload(); // as after a Gradle sync and generateApiDatabase
        assertTrue(setup.tracker().getModificationCount() > before);
        assertNotSame(view, VersionViews.of(setup.state(), file, 0));
        assertEmpty(describe(errors()), errors()); // and checked with the new views
    }

    public void testCompletesTheMembersOfTheBranchVersion() {
        myFixture.configureByText("Compat.java", """
                import cc.gahaha.fastmultiversion.Mc;
                import mc.*;
                class Compat {
                    static void run(Minecraft mc) {
                        if (Mc.is("[26.2,)")) {
                            mc.gui.screen();
                        } else {
                            mc.<caret>
                        }
                    }
                }
                """);
        LookupElement[] elements = myFixture.completeBasic();
        List<String> names = myFixture.getLookupElementStrings();
        assertNotNull(names);
        assertTrue(names.toString(), names.containsAll(List.of("screen", "setScreen", "options")));
        assertFalse(names.toString(), names.contains("gui"));
        LookupElement setScreen = Arrays.stream(elements).filter(e -> e.getLookupString().equals("setScreen")).findFirst().orElseThrow();
        String tail = LookupElementPresentation.renderElement(setScreen).getTailText();
        assertTrue(tail, tail != null && tail.contains("26.1"));

        // Choosing one inserts it as usual.
        myFixture.configureByText("Compat.java", """
                import cc.gahaha.fastmultiversion.Mc;
                import mc.*;
                class Compat {
                    static void run(Minecraft mc, Screen screen) {
                        if (!Mc.is("[26.2,)")) {
                            mc.setSc<caret>
                        }
                    }
                }
                """);
        assertNull(myFixture.completeBasic());
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("mc.setScreen()"));
        assertEquals(myFixture.getFile().getText().indexOf("mc.setScreen(") + "mc.setScreen(".length(), myFixture.getCaretOffset());
    }

    public void testLeavesCompletionInActiveCodeToTheIde() {
        myFixture.configureByText("Compat.java", """
                import cc.gahaha.fastmultiversion.Mc;
                import mc.*;
                class Compat {
                    static void run(Minecraft mc) {
                        if (Mc.is("[26.2,)")) {
                            mc.<caret>
                        }
                    }
                }
                """);
        myFixture.completeBasic();
        List<String> names = myFixture.getLookupElementStrings();
        assertNotNull(names);
        assertTrue(names.toString(), names.contains("gui"));
        assertFalse(names.toString(), names.contains("setScreen"));
    }

    // ---- helpers

    /** What Go to Declaration would use: the first handler with targets. */
    private PsiElement[] gotoTargets() {
        int offset = myFixture.getCaretOffset();
        PsiElement leaf = myFixture.getFile().findElementAt(offset);
        for (GotoDeclarationHandler handler : GotoDeclarationHandler.EP_NAME.getExtensionList()) {
            PsiElement[] targets = handler.getGotoDeclarationTargets(leaf, offset, myFixture.getEditor());
            if (targets != null && targets.length > 0) {
                return targets;
            }
        }
        return PsiElement.EMPTY_ARRAY;
    }

    private static String path(PsiElement element) {
        return element.getContainingFile().getVirtualFile().getPath();
    }

    private List<HighlightInfo> errors() {
        return myFixture.doHighlighting(HighlightSeverity.ERROR);
    }

    /** The imports highlighted as unused, in the order of the file. */
    private List<String> unusedImports() {
        return myFixture.doHighlighting(HighlightSeverity.WARNING).stream()
                .filter(info -> "Unused import statement".equals(info.getDescription()))
                .sorted(java.util.Comparator.comparingInt(HighlightInfo::getStartOffset)).map(this::text).toList();
    }

    private String text(HighlightInfo info) {
        return myFixture.getEditor().getDocument().getText(TextRange.create(info.getStartOffset(), info.getEndOffset()));
    }

    private String describe(List<HighlightInfo> infos) {
        return infos.stream().map(i -> "'" + text(i) + "': " + i.getDescription()).toList().toString();
    }

    /** The highlight (below warnings) of {@code name} in the first {@code code} of the file. */
    private HighlightInfo info(List<HighlightInfo> infos, String code, String name) {
        int start = myFixture.getFile().getText().indexOf(code);
        assertTrue(code, start >= 0);
        start += code.indexOf(name);
        for (HighlightInfo info : infos) {
            if (info.getStartOffset() == start && info.getEndOffset() == start + name.length()
                    && info.getSeverity().compareTo(HighlightSeverity.WARNING) < 0) {
                return info;
            }
        }
        throw new AssertionError("no highlight of " + name + " in " + code + ": " + describe(infos));
    }

    /** The color scheme key of a highlight. */
    private static TextAttributesKey key(HighlightInfo info) {
        return info.forcedTextAttributesKey != null ? info.forcedTextAttributesKey : info.type.getAttributesKey();
    }

    private void assertError(List<HighlightInfo> errors, String highlighted, String descriptionStart) {
        assertTrue(describe(errors), errors.stream().anyMatch(info -> text(info).equals(highlighted)
                && info.getDescription() != null && info.getDescription().startsWith(descriptionStart)));
    }

    /**
     * Two tiny Minecraft versions, compiled once, and their API database. In 26.1 the screen is
     * {@code mc.screen} / {@code mc.setScreen}, in 26.2 it moved to {@code mc.gui}; {@code mc.Options} only
     * exists in 26.1; {@code Pose} moved from {@code mc.oldpkg} to {@code mc.newpkg}.
     */
    private static synchronized Path libraries() throws IOException {
        if (libraries == null) {
            Path root = Files.createTempDirectory("multiversion-mc");
            Path old = compile(root, "mc-26.1", merged(Map.of(
                    "mc/Screen.java", "package mc; public class Screen {}",
                    "mc/Options.java", "package mc; public class Options { public boolean hideGui; }",
                    "mc/Minecraft.java", "package mc; public class Minecraft { public Screen screen; public Options options; "
                            + "public void setScreen(Screen screen) {} }",
                    "mc/oldpkg/Pose.java", "package mc.oldpkg; public class Pose { public String name() { return \"old\"; } }",
                    "mc/old/Range.java", "package mc.old; public class Range { public boolean within(int y) { return y > 0; } "
                            + "public boolean within(int x, int z) { return true; } }",
                    "mc/Data.java", "package mc; public class Data { public static mc.old.Range range() { return null; } }",
                    // 26.1 kept mc.util.Layer next to the moved mc.util.pos.Layer, as libraries do.
                    "mc/util/Layer.java", "package mc.util; public class Layer {}",
                    "mc/util/pos/Layer.java", "package mc.util.pos; public class Layer {}",
                    "mc/Layers.java", "package mc; public class Layers { public static mc.util.pos.Layer current() { return null; } }"), Map.of(
                    // 26.1 also has mc.text.Line next to mc.text.pos.Line, 26.2 only the latter; mc.gone is only in 26.1.
                    "mc/text/Line.java", "package mc.text; public class Line {}",
                    "mc/text/pos/Line.java", "package mc.text.pos; public class Line {}",
                    "mc/gone/Thing.java", "package mc.gone; public class Thing {}")));
            Path current = compile(root, "mc-26.2", Map.of(
                    "mc/Screen.java", "package mc; public class Screen {}",
                    "mc/Gui.java", "package mc; public class Gui { public Screen screen() { return null; } "
                            + "public void setScreen(Screen screen) {} }",
                    "mc/Minecraft.java", "package mc; public class Minecraft { public final Gui gui = new Gui(); }",
                    "mc/newpkg/Pose.java", "package mc.newpkg; public class Pose { public String name() { return \"new\"; } }",
                    "mc/pos/Range.java", "package mc.pos; public class Range { public boolean within(int y) { return y > 0; } "
                            + "public boolean within(int x, int z) { return true; } }",
                    "mc/Data.java", "package mc; public class Data { public static mc.pos.Range range() { return null; } }",
                    "mc/util/Layer.java", "package mc.util; public class Layer {}",
                    "mc/util/pos/Layer.java", "package mc.util.pos; public class Layer {}",
                    "mc/Layers.java", "package mc; public class Layers { public static mc.util.pos.Layer current() { return null; } }",
                    "mc/text/pos/Line.java", "package mc.text.pos; public class Line {}"));
            database = ApiDbGenerator.generate(List.of(
                    new VersionJars(McVersion.parse("26.1"), List.of(old)),
                    new VersionJars(McVersion.parse("26.2"), List.of(current))));
            libraries = root;
        }
        return libraries;
    }

    private static Map<String, String> merged(Map<String, String> some, Map<String, String> more) {
        Map<String, String> all = new java.util.HashMap<>(some);
        all.putAll(more);
        return all;
    }

    /** Compiles into {@code root/name} (the IDE's library root) and returns a jar of it (for the database). */
    private static Path compile(Path root, String name, Map<String, String> sources) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertNotNull("the tests need a JDK with javac", javac);
        Path classes = root.resolve(name);
        List<String> args = new ArrayList<>(List.of("-d", classes.toString()));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = root.resolve(name + "-src").resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        assertEquals(0, javac.run(null, null, null, args.toArray(String[]::new)));
        Path jar = root.resolve(name + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
             Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
        }
        return jar;
    }
}
