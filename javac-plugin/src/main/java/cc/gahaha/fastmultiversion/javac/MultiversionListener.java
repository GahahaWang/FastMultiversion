package cc.gahaha.fastmultiversion.javac;

import cc.gahaha.fastmultiversion.apidb.ApiClass;
import cc.gahaha.fastmultiversion.apidb.ApiDatabase;
import cc.gahaha.fastmultiversion.versioning.McVersion;
import cc.gahaha.fastmultiversion.versioning.VersionRange;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.Diagnostic;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/**
 * Hooks the plugin into javac's phases:
 * <ul>
 *   <li>after a file is parsed: prune it ({@link SourcePruner}) and drop imports nothing uses anymore;</li>
 *   <li>before a file is entered, when every source file is parsed: drop wildcard imports of packages
 *       that do not exist in this build;</li>
 *   <li>after a class is analyzed: check what is left ({@link LeftoverChecker}) and, with an API database,
 *       that the Minecraft API it uses exists in every version the code runs in ({@link RangeChecker}).</li>
 * </ul>
 * When annotation processors generate files, javac re-enters the existing trees; they stay pruned.
 */
final class MultiversionListener implements TaskListener {
    private final Context context;
    private final Trees trees;
    private final TreeMaker make;
    private final McVersion target;
    private final ApiDatabase db;
    /** Kept code that runs in fewer versions than its surroundings, and those versions; see SourcePruner. */
    private final Map<JCTree, Long> guards = new IdentityHashMap<>();

    private final Set<JCCompilationUnit> pruned = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<JCCompilationUnit> importsChecked = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> sourcePackages = new HashSet<>();
    private final Set<String> sourceTypes = new HashSet<>();
    private final Map<String, Boolean> packageExists = new HashMap<>();
    private final Map<String, List<String>> removed = new HashMap<>();
    private final Map<String, VersionRange> ranges = new HashMap<>();
    private Set<String> platformPackages;
    private boolean missingTargetReported;

    private MultiversionListener(JavacTask task, McVersion target, ApiDatabase db) {
        this.context = ((BasicJavacTask) task).getContext();
        this.trees = Trees.instance(task);
        this.make = TreeMaker.instance(context);
        this.target = target;
        this.db = db;
    }

    /** @param db the API database for the range check, or {@code null} to only prune */
    static void install(JavacTask task, McVersion target, ApiDatabase db) {
        task.addTaskListener(new MultiversionListener(task, target, db));
    }

    McVersion rawTarget() {
        return target;
    }

    @Override
    public void finished(TaskEvent e) {
        switch (e.getKind()) {
            case PARSE -> {
                if (e.getCompilationUnit() instanceof JCCompilationUnit unit && pruned.add(unit)) {
                    prune(unit);
                }
            }
            case ANALYZE -> {
                if (e.getTypeElement() != null && trees.getTree(e.getTypeElement()) instanceof JCTree tree) {
                    new LeftoverChecker(this, e.getCompilationUnit(), removed).scan(tree);
                    if (db != null && target != null) {
                        new RangeChecker(this, e.getCompilationUnit(), db, db.indexOf(target), guards, context).scan(tree);
                    }
                }
            }
            default -> {
            }
        }
    }

    @Override
    public void started(TaskEvent e) {
        if (e.getKind() == TaskEvent.Kind.ENTER && e.getCompilationUnit() instanceof JCCompilationUnit unit
                && importsChecked.add(unit)) {
            Imports.removeMissingPackages(unit, name -> packageExists(name) || classExists(name));
            if (db != null) {
                Imports.removeOtherVersionClasses(unit, this::classExists, this::isApiClass);
            }
        }
    }

    /** Whether the database knows {@code name} (a canonical name) as a class of some version. */
    private boolean isApiClass(String name) {
        for (String internal = name.replace('.', '/'); ; ) {
            ApiClass c = db.get(internal);
            if (c != null) {
                return !c.platform();
            }
            int slash = internal.lastIndexOf('/');
            if (slash < 0) {
                return false;
            }
            internal = internal.substring(0, slash) + "$" + internal.substring(slash + 1);
        }
    }

    private void prune(JCCompilationUnit unit) {
        String pkg = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
        if (!pkg.isEmpty()) {
            sourcePackages.add(pkg);
        }
        for (JCTree def : unit.defs) {
            if (def instanceof JCClassDecl c) {
                collectTypes(pkg.isEmpty() ? "" : pkg + ".", c);
            }
        }
        SourcePruner pruner = new SourcePruner(this, unit, make, db == null ? null : db.versions(), guards);
        for (SourcePruner.Removed r : pruner.prune()) {
            removed.computeIfAbsent(r.name(), k -> new ArrayList<>()).add(r.description());
        }
        Imports.removeUnused(unit);
    }

    // ---- packages and classes, for imports

    /** Records the canonical names of a source class and its member classes, e.g. {@code a.b.Outer.Inner}. */
    private void collectTypes(String prefix, JCClassDecl c) {
        String name = prefix + c.name;
        sourceTypes.add(name);
        for (JCTree member : c.defs) {
            if (member instanceof JCClassDecl nested) {
                collectTypes(name + ".", nested);
            }
        }
    }

    /**
     * Whether {@code name} is a class, as in {@code import a.b.Outer.*;}, which imports Outer's member
     * classes: one of the sources, a class on the classpath (trying '$' for each later dot), or a class in
     * a JDK package.
     */
    private boolean classExists(String name) {
        if (sourceTypes.contains(name)) {
            return true;
        }
        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0 && platformPackages().contains(name.substring(0, lastDot))) {
            return true;
        }
        JavaFileManager files = context.get(JavaFileManager.class);
        try {
            for (String binary = name; ; ) {
                if (files.hasLocation(StandardLocation.CLASS_PATH)
                        && files.getJavaFileForInput(StandardLocation.CLASS_PATH, binary, JavaFileObject.Kind.CLASS) != null) {
                    return true;
                }
                int dot = binary.lastIndexOf('.');
                if (dot < 0) {
                    return false;
                }
                binary = binary.substring(0, dot) + "$" + binary.substring(dot + 1);
            }
        } catch (IOException | RuntimeException e) {
            return true; // when in doubt, keep the import and let javac decide
        }
    }

    private boolean packageExists(String name) {
        if (sourcePackages.contains(name)) {
            return true;
        }
        return packageExists.computeIfAbsent(name, this::lookUpPackage);
    }

    private boolean lookUpPackage(String name) {
        if (platformPackages().contains(name)) {
            return true;
        }
        JavaFileManager files = context.get(JavaFileManager.class);
        try {
            for (StandardLocation location : List.of(StandardLocation.CLASS_PATH, StandardLocation.SOURCE_PATH)) {
                if (files.hasLocation(location)
                        && files.list(location, name, EnumSet.of(JavaFileObject.Kind.CLASS, JavaFileObject.Kind.SOURCE), false).iterator().hasNext()) {
                    return true;
                }
            }
            // Modular setups are left to javac rather than guessed at.
            return files.hasLocation(StandardLocation.MODULE_PATH);
        } catch (IOException | RuntimeException e) {
            return true; // when in doubt, keep the import and let javac decide
        }
    }

    private Set<String> platformPackages() {
        if (platformPackages == null) {
            platformPackages = new HashSet<>();
            for (ModuleReference module : ModuleFinder.ofSystem().findAll()) {
                ModuleDescriptor descriptor = module.descriptor();
                platformPackages.addAll(descriptor.packages());
            }
        }
        return platformPackages;
    }

    // ---- shared helpers for SourcePruner and LeftoverChecker

    VersionRange range(String text, JCTree at, CompilationUnitTree unit) {
        VersionRange range = ranges.get(text);
        if (range != null) {
            return range;
        }
        try {
            range = VersionRange.parse(text);
        } catch (IllegalArgumentException e) {
            error(at, unit, e.getMessage());
            return null;
        }
        ranges.put(text, range);
        return range;
    }

    McVersion target(JCTree at, CompilationUnitTree unit) {
        if (target == null && !missingTargetReported) {
            missingTargetReported = true;
            error(at, unit, "Mc.is and @IfMc need the Minecraft version to build for: enable the plugin with "
                    + "-Xplugin:\"" + MultiversionPlugin.NAME + " " + MultiversionPlugin.TARGET_ARG + "<version>\"");
        }
        return target;
    }

    void error(JCTree at, CompilationUnitTree unit, String message) {
        trees.printMessage(Diagnostic.Kind.ERROR, "[multiversion] " + message, at, unit);
    }

    void note(JCTree at, CompilationUnitTree unit, String message) {
        trees.printMessage(Diagnostic.Kind.NOTE, "[multiversion] " + message, at, unit);
    }
}
