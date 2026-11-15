package cc.gahaha.fastmultiversion.intellij;

import cc.gahaha.fastmultiversion.apidb.ApiClass;
import cc.gahaha.fastmultiversion.apidb.ApiDatabase;
import cc.gahaha.fastmultiversion.versioning.McVersion;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.openapi.util.SimpleModificationTracker;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

/**
 * The project's multiversion setup, read from what the Gradle plugin writes:
 * {@code build/multiversion/project.properties} (versions, active version, loaders, database path) and the
 * API database it points to. Reloaded when those files change; checked at most every couple of seconds, and
 * right away after a Gradle sync ({@link ApiDatabaseUpdater}).
 */
@Service(Service.Level.PROJECT)
public final class MultiversionProject {
    private static final Logger LOG = Logger.getInstance(MultiversionProject.class);
    private static final String INFO = "build/multiversion/project.properties";
    private static final long RECHECK_MILLIS = 2000;

    /**
     * A loaded setup.
     *
     * @param db       the API database
     * @param active   the index of the active version in {@code db.versions()}
     * @param packages every package of a class in the database, with '/' separators
     * @param layout   how the Gradle plugin laid out the version projects
     */
    public record State(ApiDatabase db, int active, Set<String> packages, Layout layout) {
        long activeBit() {
            return 1L << active;
        }

        /** The newest version in {@code versions}, a non-empty mask. */
        static int newest(long versions) {
            return 63 - Long.numberOfLeadingZeros(versions);
        }

        String name(int version) {
            return db.versions().get(version).toString();
        }
    }

    /**
     * The version projects: {@code :<version>} without loaders, otherwise {@code :<version>:<loader>} plus
     * {@code :<version>:common} if there is a {@code common.gradle}.
     *
     * @param base         the root project's directory; {@code null} in tests
     * @param loaders      the loaders, empty for a mod without loaders
     * @param activeLoader the loader whose project owns {@code src/main} in the IDE when there is no common project
     * @param common       whether there are common projects (which then own {@code src/main} in the IDE)
     */
    public record Layout(@Nullable Path base, List<String> loaders, @Nullable String activeLoader, boolean common) {
        public static final Layout SINGLE = new Layout(null, List.of(), null, false);

        /** The kind of version project that compiles {@code file}: a loader, "common", or {@code null} without loaders. */
        @Nullable String kind(@Nullable VirtualFile file) {
            if (loaders.isEmpty()) {
                return null;
            }
            if (base != null && file != null) {
                String path = file.getPath();
                for (String loader : loaders) {
                    String root = base.resolve("src").resolve(loader).toString().replace('\\', '/') + "/";
                    if (path.startsWith(root)) {
                        return loader;
                    }
                }
            }
            return common ? "common" : activeLoader;
        }
    }

    private final Project project;
    private final SimpleModificationTracker changes = new SimpleModificationTracker();
    private State state;
    private State override;
    private long lastCheck;
    private long infoStamp = -1;
    private long dbStamp = -1;
    /** The info file's stamp when the database was found missing and its generation started. */
    private long missingDatabaseFor = -1;

    public MultiversionProject(Project project) {
        this.project = project;
    }

    public static MultiversionProject getInstance(Project project) {
        return project.getService(MultiversionProject.class);
    }

    @TestOnly
    public void setForTests(@Nullable ApiDatabase db, @Nullable McVersion active) {
        override = db == null ? null : create(db, db.indexOf(active), Layout.SINGLE);
        changes.incModificationCount();
    }

    /** Whether {@code projectDir} is the root of a FastMultiversion project, by what its Gradle plugin writes. */
    static boolean isMultiversion(Path projectDir) {
        return Files.isRegularFile(projectDir.resolve(INFO));
    }

    /**
     * Changes whenever the setup does (a new database, another active version, ...) or is {@link #reload()
     * reloaded}: what is made from the setup, such as the {@link VersionViews views}, depends on it.
     */
    public ModificationTracker tracker() {
        return changes;
    }

    /**
     * Reads the setup again now, e.g. after the database was generated or the project was synced, and has the
     * open files checked again, so that code of other versions is resolved with the new database and libraries.
     */
    public void reload() {
        synchronized (this) {
            lastCheck = 0;
        }
        state();
        changes.incModificationCount();
        restartHighlighting();
    }

    /** The setup, or {@code null} if this is no multiversion project or its database was not generated yet. */
    public synchronized @Nullable State state() {
        if (override != null) {
            return override;
        }
        long now = System.currentTimeMillis();
        if (now - lastCheck < RECHECK_MILLIS) {
            return state;
        }
        lastCheck = now;
        String base = project.getBasePath();
        if (base == null) {
            return update(null);
        }
        try {
            Path info = Path.of(base).resolve(INFO);
            if (!Files.isRegularFile(info)) {
                return update(null);
            }
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(info, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            long newInfoStamp = Files.getLastModifiedTime(info).toMillis();
            Path db = Path.of(base).resolve(properties.getProperty("apidb", "build/multiversion/api.mcapi"));
            if (!Files.isRegularFile(db)) {
                // E.g. after the build directory was deleted: a sync writes the info but runs no tasks.
                if (missingDatabaseFor != newInfoStamp) {
                    missingDatabaseFor = newInfoStamp; // once per sync, also if generating it fails
                    ApiDatabaseUpdater.getInstance(project).update(base);
                }
                return update(null);
            }
            long newDbStamp = Files.getLastModifiedTime(db).toMillis();
            if (state != null && newInfoStamp == infoStamp && newDbStamp == dbStamp) {
                return state;
            }
            ApiDatabase database = ApiDatabase.read(db);
            int active = database.indexOf(McVersion.parse(properties.getProperty("active", "")));
            String loaders = properties.getProperty("loaders", "").strip();
            Layout layout = new Layout(Path.of(base),
                    loaders.isEmpty() ? List.of() : Arrays.stream(loaders.split(",")).map(String::strip).toList(),
                    properties.getProperty("activeLoader"),
                    Boolean.parseBoolean(properties.getProperty("common", "false")));
            infoStamp = newInfoStamp;
            dbStamp = newDbStamp;
            return update(active < 0 ? null : create(database, active, layout));
        } catch (IOException | RuntimeException e) {
            LOG.warn("cannot load the multiversion setup of " + base, e);
            return update(null);
        }
    }

    /** Sets the loaded setup; if it changed, the open files are checked again with it. */
    private @Nullable State update(@Nullable State loaded) {
        if (loaded != state) {
            state = loaded;
            changes.incModificationCount();
            restartHighlighting();
        }
        return state;
    }

    private void restartHighlighting() {
        ApplicationManager.getApplication().invokeLater(() -> DaemonCodeAnalyzer.getInstance(project)
                .restart("FastMultiversion setup changed"), project.getDisposed());
    }

    /**
     * What code in {@code file} sees when it runs in version {@code version}: the project's own sources that
     * the file's module sees, plus the libraries (Minecraft, the loader, ...) of the IDE module of that
     * version's project. {@code null} if the IDE has no such module, e.g. before a Gradle sync.
     */
    @Nullable GlobalSearchScope versionScope(State state, PsiFile file, int version) {
        VirtualFile virtualFile = file.getOriginalFile().getVirtualFile();
        Module own = virtualFile == null ? null : ModuleUtilCore.findModuleForFile(virtualFile, project);
        Module other = versionModule(state, virtualFile, version);
        if (own == null || other == null || own == other) {
            return null;
        }
        return GlobalSearchScope.moduleWithDependenciesScope(own)
                .union(GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(other, false));
    }

    /**
     * The main source set module of the version project that would compile {@code file} in {@code version}:
     * IntelliJ names it after the Gradle project path, with the dots of a project name replaced, e.g.
     * {@code EntityCount.26_1_2.common.main} for {@code :26.1.2:common}.
     */
    private @Nullable Module versionModule(State state, @Nullable VirtualFile file, int version) {
        String kind = state.layout().kind(file);
        String rest = (kind == null ? "" : "." + kind) + ".main";
        String name = state.name(version);
        List<String> suffixes = List.of(moduleName("." + name.replace('.', '_') + rest), moduleName("." + name + rest));
        for (Module module : ModuleManager.getInstance(project).getModules()) {
            String moduleName = moduleName(module.getName());
            if (suffixes.stream().anyMatch(moduleName::endsWith)) {
                return module;
            }
        }
        return null;
    }

    /** A module name with the characters IntelliJ may replace in project names normalized. */
    private static String moduleName(String name) {
        return name.replaceAll("[^A-Za-z0-9._]", "_");
    }

    private static State create(ApiDatabase db, int active, Layout layout) {
        Set<String> packages = new HashSet<>();
        for (ApiClass c : db.classes()) {
            int slash = c.name().lastIndexOf('/');
            if (slash > 0) {
                packages.add(c.name().substring(0, slash));
            }
        }
        return new State(db, active, Set.copyOf(packages), layout);
    }
}
