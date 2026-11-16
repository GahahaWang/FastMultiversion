package cc.gahaha.fastmultiversion.javac;

import cc.gahaha.fastmultiversion.apidb.ApiDatabase;
import cc.gahaha.fastmultiversion.versioning.McVersion;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;

/**
 * A javac plugin that compiles the sources for one Minecraft version.
 *
 * <p>Enable it with {@code -Xplugin:"FastMultiversion target=<version>"}, with this jar on the processor path.
 * Right after each file is parsed, and before javac resolves any name, the plugin removes:
 * <ul>
 *   <li>the branches of {@code Mc.is("range")} conditions that cannot run on the target;</li>
 *   <li>declarations whose {@code @Mc.Is("range")} excludes the target;</li>
 *   <li>single imports that nothing uses anymore, and wildcard imports of packages the target lacks.</li>
 * </ul>
 * javac then compiles an ordinary program for that version against that version's real jars.
 *
 * <p>With {@code apidb=<file>}, the plugin also checks the kept code against the API database: every
 * Minecraft class or member it uses must be usable, the same way, in every version that code runs in
 * (see {@link RangeChecker}). The database's versions are taken as the versions the mod supports.
 *
 * <p>This has to be a plugin rather than an annotation processor: javac resolves imports and member
 * signatures before processors run, so a processor would be too late for code that names classes
 * missing from the target version.
 *
 * <p>The plugin edits javac's trees, so javac's JVM needs
 * {@code --add-exports jdk.compiler/com.sun.tools.javac.<api|code|tree|util>=ALL-UNNAMED}.
 */
public final class MultiversionPlugin implements Plugin {
    public static final String NAME = "FastMultiversion";
    public static final String TARGET_ARG = "target=";
    /**
     * The API database ({@code .mcapi}); turns on the version range check. javac splits plugin arguments at
     * spaces, so give a {@code file:} URI (spaces encoded as {@code %20}) or a path without spaces.
     */
    public static final String APIDB_ARG = "apidb=";

    static final List<String> REQUIRED_EXPORTS = List.of(
            "com.sun.tools.javac.api",
            "com.sun.tools.javac.code",
            "com.sun.tools.javac.tree",
            "com.sun.tools.javac.util");

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void init(JavacTask task, String... args) {
        McVersion target = null;
        Path apiDbFile = null;
        List<String> problems = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith(TARGET_ARG)) {
                try {
                    target = McVersion.parse(arg.substring(TARGET_ARG.length()));
                } catch (IllegalArgumentException e) {
                    problems.add("invalid " + arg + ": " + e.getMessage());
                }
            } else if (arg.startsWith(APIDB_ARG)) {
                String value = arg.substring(APIDB_ARG.length());
                try {
                    apiDbFile = value.startsWith("file:") ? Path.of(URI.create(value)) : Path.of(value);
                } catch (IllegalArgumentException e) {
                    problems.add("invalid " + arg + ": " + e.getMessage());
                }
            } else {
                problems.add("unknown argument '" + arg + "'; expected " + TARGET_ARG + "<version> or "
                        + APIDB_ARG + "<file>");
            }
        }
        List<String> missing = missingExports();
        if (!missing.isEmpty()) {
            problems.add("javac internals are not accessible. Start the compiler JVM with: " + missing.stream()
                    .map(p -> "--add-exports=jdk.compiler/" + p + "=ALL-UNNAMED")
                    .collect(Collectors.joining(" ")));
        }
        ApiDatabase db = null;
        if (apiDbFile != null) {
            try {
                db = ApiDatabase.read(apiDbFile);
            } catch (IOException | RuntimeException e) {
                problems.add("cannot read the API database " + apiDbFile + ": " + e.getMessage());
            }
            if (db != null && target != null && db.indexOf(target) < 0) {
                problems.add("the API database " + apiDbFile + " covers Minecraft " + db.versions()
                        + " but not the target " + target);
            }
        }
        if (!problems.isEmpty()) {
            reportOnFirstFile(task, String.join("; ", problems));
            return;
        }
        MultiversionListener.install(task, target, db);
    }

    private List<String> missingExports() {
        Module compiler = ModuleLayer.boot().findModule("jdk.compiler").orElse(null);
        if (compiler == null) {
            return REQUIRED_EXPORTS;
        }
        Module self = getClass().getModule();
        return REQUIRED_EXPORTS.stream().filter(p -> !compiler.isExported(p, self)).toList();
    }

    /** Plugins have no error channel of their own, so attach the problem to the first source file. */
    private static void reportOnFirstFile(JavacTask task, String message) {
        Trees trees = Trees.instance(task);
        task.addTaskListener(new TaskListener() {
            private boolean reported;

            @Override
            public void finished(TaskEvent e) {
                if (!reported && e.getKind() == TaskEvent.Kind.PARSE && e.getCompilationUnit() != null) {
                    reported = true;
                    trees.printMessage(Diagnostic.Kind.ERROR, "[multiversion] " + message,
                            e.getCompilationUnit(), e.getCompilationUnit());
                }
            }
        });
    }
}
