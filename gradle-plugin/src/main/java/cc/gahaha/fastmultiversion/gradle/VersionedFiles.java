package cc.gahaha.fastmultiversion.gradle;

import cc.gahaha.fastmultiversion.versioning.McVersion;
import cc.gahaha.fastmultiversion.versioning.VersionedLines;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Transformer;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileCopyDetails;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Provider;
import org.gradle.language.jvm.tasks.ProcessResources;

/**
 * Access wideners, class tweakers and access transformers for one version project. Their lines whose
 * {@code @Mc.Is} comment excludes the version are left out ({@link VersionedLines}), and a mod writes them once, in
 * either format, for every loader: what the project's loader plugin applies to Minecraft is all of them, in its own
 * format ({@link AccessEntries}), and so is what its jar gets.
 * <ul>
 *   <li>With Loom, its {@code accessWidenerPath} is an access widener made from them all, which the jar gets under
 *       the name {@code fabric.mod.json} gives it (else that of the access widener among them, else
 *       {@code <project>.accesswidener}).</li>
 *   <li>With ModDevGradle, its access transformers are an access transformer made from them all, which the jar gets
 *       as {@code META-INF/accesstransformer.cfg}, where NeoForge looks by default.</li>
 *   <li>Otherwise the jar gets the files as they are, without the other versions' lines.</li>
 * </ul>
 * The files are those of the project's resource directories: {@code src/main/resources}, its loader's
 * {@code src/<loader>/resources} and its own {@code versions/<version>/<loader>/src/main/resources}; so with several
 * loaders, one file in {@code src/main/resources} serves the common projects and every loader. A build script that
 * sets Loom's or ModDevGradle's files itself wins; {@link MultiversionVersionExtension#versioned} makes a copy for it.
 *
 * <p>The resource directories are the ones a build uses, also during an IDE sync (when only the active version
 * gets the shared ones as source directories): the other versions' Minecraft in the IDE has them applied too.
 */
final class VersionedFiles {
    private static final List<String> LOOM_PLUGINS = List.of("net.fabricmc.fabric-loom", "net.fabricmc.fabric-loom-remap", "fabric-loom");
    private static final String MOD_DEV_PLUGIN = "net.neoforged.moddev";
    private static final String ACCESS_TRANSFORMER = "META-INF/accesstransformer.cfg";
    private static final Pattern FABRIC_ACCESS_WIDENER = Pattern.compile("\"accessWidener\"\\s*:\\s*\"([^\"]+)\"");

    /** What the project's loader plugin applies to Minecraft, and so what its jar gets. */
    private enum Format { AS_THEY_ARE, ACCESS_WIDENER, ACCESS_TRANSFORMER }

    private final Project project;
    private final McVersion version;
    private final List<File> resourceDirs;
    private Format format = Format.AS_THEY_ARE;
    private Object loom;
    private final Map<Format, Optional<File>> made = new EnumMap<>(Format.class);

    VersionedFiles(Project project, McVersion version, String loader, boolean common) {
        this.project = project;
        this.version = version;
        File root = project.getRootDir();
        List<File> dirs = new ArrayList<>(List.of(project.file("src/main/resources"), new File(root, "src/main/resources")));
        if (loader != null && !common) {
            dirs.add(new File(root, "src/" + loader + "/resources"));
        }
        this.resourceDirs = List.copyOf(dirs);
    }

    void apply() {
        for (String id : LOOM_PLUGINS) {
            project.getPluginManager().withPlugin(id, plugin -> {
                Object extension = project.getExtensions().findByName("loom");
                RegularFileProperty path = call(extension, "getAccessWidenerPath", RegularFileProperty.class);
                if (path != null && format == Format.AS_THEY_ARE) {
                    format = Format.ACCESS_WIDENER;
                    loom = extension;
                    path.convention(accessWidener());
                }
            });
        }
        project.getPluginManager().withPlugin(MOD_DEV_PLUGIN, plugin -> {
            Object accessTransformers = call(project.getExtensions().findByName("neoForge"), "getAccessTransformers", Object.class);
            ConfigurableFileCollection files = call(accessTransformers, "getFiles", ConfigurableFileCollection.class);
            if (files != null && format == Format.AS_THEY_ARE) {
                format = Format.ACCESS_TRANSFORMER;
                files.convention(accessTransformers());
            }
        });
        // Once the build script applied its loader plugin: what the jar gets depends on it.
        project.afterEvaluate(evaluated -> project.getTasks().named("processResources", ProcessResources.class, task -> {
            task.getInputs().property("multiversionVersion", version.toString());
            task.getInputs().property("multiversionAccessFormat", format.name());
            if (format == Format.AS_THEY_ARE) {
                task.eachFile(new FilterVersionedFile(version.toString()));
            } else {
                Format format = this.format;
                File made = madeDir(format);
                task.from(project.provider(() -> made(format).isPresent() ? made : List.of()));
                task.eachFile(new OnlyTheMadeOne(made.getAbsolutePath()));
            }
        }));
    }

    /** {@code path} as {@link MultiversionVersionExtension#versioned} takes it: a string relative to the root directory. */
    File resolve(Object path) {
        return path instanceof CharSequence text ? new File(project.getRootDir(), text.toString()) : project.file(path);
    }

    /**
     * This version's copy of {@code source}, written now (Loom reads its access widener while configuring) under
     * {@code build/multiversion/versioned/}.
     */
    File versioned(File source) {
        Path target = project.getLayout().getBuildDirectory().dir("multiversion/versioned/" + place(source)).get().getAsFile().toPath()
                .resolve(source.getName());
        write(target, String.join("\n", lines(source, false)));
        return target.toFile();
    }

    /**
     * The access widener made from all the files, with this version's lines: what Loom's {@code accessWidenerPath} is
     * by default; none without such files.
     */
    Provider<RegularFile> accessWidener() {
        return project.getLayout().file(project.provider(() -> made(Format.ACCESS_WIDENER).orElse(null)));
    }

    /** The access transformer made from all the files, as ModDevGradle's access transformers are by default. */
    FileCollection accessTransformers() {
        return project.files(project.provider(() -> made(Format.ACCESS_TRANSFORMER).map(List::of).orElse(List.of())));
    }

    /** The access widener or access transformer made from all the files; written once. */
    private synchronized Optional<File> made(Format format) {
        return made.computeIfAbsent(format, f -> Optional.ofNullable(make(f)));
    }

    private File make(Format format) {
        File dir = madeDir(format);
        delete(dir.toPath());
        List<File> sources = found(name -> true);
        if (sources.isEmpty() || format == Format.AS_THEY_ARE) {
            return null;
        }
        AccessEntries entries = new AccessEntries();
        for (File source : sources) {
            List<String> lines = lines(source, true);
            if (isAccessTransformer(source.getName())) {
                entries.addAccessTransformer(lines, display(source));
            } else {
                entries.addAccessWidener(lines, display(source));
            }
        }
        String comment = "Made by FastMultiversion for Minecraft " + version + " from "
                + sources.stream().map(this::display).collect(Collectors.joining(", ")) + "; changes there, not here.";
        String name;
        String text;
        if (format == Format.ACCESS_WIDENER) {
            name = accessWidenerName(sources);
            text = entries.toAccessWidener(new MinecraftClasses(this::minecraftJars), comment);
        } else {
            name = ACCESS_TRANSFORMER;
            text = entries.toAccessTransformer(comment);
        }
        for (String warning : entries.warnings()) {
            project.getLogger().warn("multiversion: " + warning + " (" + project.getPath() + ", Minecraft " + version + ")");
        }
        Path target = dir.toPath().resolve(name);
        write(target, text);
        return target.toFile();
    }

    /** The name Fabric looks for: the one {@code fabric.mod.json} gives, else the access widener's among the sources. */
    private String accessWidenerName(List<File> sources) {
        Optional<File> modJson = resourceDirs.stream().map(dir -> new File(dir, "fabric.mod.json")).filter(File::isFile).findFirst();
        if (modJson.isPresent()) {
            String json = project.getProviders().fileContents(project.getObjects().fileProperty().fileValue(modJson.get())).getAsText().getOrElse("");
            Matcher name = FABRIC_ACCESS_WIDENER.matcher(json);
            if (name.find()) {
                return name.group(1);
            }
        }
        String name = sources.stream().filter(source -> !isAccessTransformer(source.getName())).map(this::relativePath).findFirst()
                .orElse(project.getRootProject().getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "") + ".accesswidener");
        if (modJson.isPresent()) {
            project.getLogger().warn("multiversion: fabric.mod.json has no \"accessWidener\": add \"accessWidener\": \"" + name
                    + "\", or Fabric does not apply the access changes when the game runs (" + project.getPath() + ")");
        }
        return name;
    }

    /** Minecraft's jars as Loom has them (it asks for the access widener after providing them). */
    private List<Path> minecraftJars() {
        Object provider = call(loom, "getMinecraftProvider", Object.class);
        List<?> jars = call(provider, "getMinecraftJars", List.class);
        if (jars == null) {
            project.getLogger().warn("multiversion: cannot find Minecraft's jars in Loom (" + project.getPath() + ")");
            return List.of();
        }
        return jars.stream().filter(Path.class::isInstance).map(Path.class::cast).toList();
    }

    /** The lines of {@code source} this version gets; with {@code blanks}, the others blank (so that line numbers stay). */
    private List<String> lines(File source, boolean blanks) {
        String text = project.getProviders().fileContents(project.getObjects().fileProperty().fileValue(source)).getAsText().getOrNull();
        if (text == null) {
            throw new GradleException("multiversion: " + source + " does not exist");
        }
        String[] lines = text.split("\r?\n", -1);
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (applies(lines[i], source + ":" + (i + 1))) {
                kept.add(lines[i]);
            } else if (blanks) {
                kept.add("");
            }
        }
        return kept;
    }

    private File madeDir(Format format) {
        return project.getLayout().getBuildDirectory().dir("multiversion/access/" + format.name().toLowerCase(Locale.ROOT)).get().getAsFile();
    }

    /** Where under {@code build/multiversion/versioned/} a copy goes: by resource directory and the path in it, so that names never clash. */
    private String place(File source) {
        Path file = source.toPath().toAbsolutePath().normalize();
        for (int i = 0; i < resourceDirs.size(); i++) {
            Path dir = resourceDirs.get(i).toPath().toAbsolutePath().normalize();
            if (file.startsWith(dir)) {
                Path parent = dir.relativize(file).getParent();
                return i + (parent == null ? "" : "/" + parent.toString().replace('\\', '/'));
            }
        }
        return "other";
    }

    /** {@code source}'s path in its resource directory, as the jar has it. */
    private String relativePath(File source) {
        Path file = source.toPath().toAbsolutePath().normalize();
        for (File dir : resourceDirs) {
            Path root = dir.toPath().toAbsolutePath().normalize();
            if (file.startsWith(root)) {
                return root.relativize(file).toString().replace('\\', '/');
            }
        }
        return source.getName();
    }

    /** {@code source} relative to the root directory, for messages. */
    private String display(File source) {
        Path root = project.getRootDir().toPath().toAbsolutePath().normalize();
        Path file = source.toPath().toAbsolutePath().normalize();
        return file.startsWith(root) ? root.relativize(file).toString().replace('\\', '/') : file.toString();
    }

    /** The versioned files in the resource directories that {@code name} accepts, the project's own first. */
    private List<File> found(Predicate<String> name) {
        List<File> found = new ArrayList<>();
        for (File dir : resourceDirs) {
            if (!dir.isDirectory()) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dir.toPath())) {
                files.filter(Files::isRegularFile)
                        .filter(f -> VersionedLines.isVersionedFile(f.getFileName().toString()) && name.test(f.getFileName().toString()))
                        .sorted(Comparator.comparing(Path::toString))
                        .forEach(f -> found.add(f.toFile()));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot list " + dir, e);
            }
        }
        return found;
    }

    private boolean applies(String line, String where) {
        try {
            return VersionedLines.appliesTo(line, version);
        } catch (IllegalArgumentException e) {
            throw new GradleException(where + ": " + e.getMessage(), e);
        }
    }

    static boolean isAccessTransformer(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".cfg");
    }

    private static void write(Path target, String content) {
        try {
            if (!Files.isRegularFile(target) || !Files.readString(target, StandardCharsets.UTF_8).equals(content)) {
                Files.createDirectories(target.getParent());
                Files.writeString(target, content, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + target, e);
        }
    }

    private static void delete(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete " + dir, e);
        }
    }

    /** {@code target.method()} if there is such a method returning a {@code type}, else {@code null}: the loaders' plugins are not on the classpath. */
    private static <T> T call(Object target, String method, Class<T> type) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(method);
            m.setAccessible(true);
            Object result = m.invoke(target);
            return type.isInstance(result) ? type.cast(result) : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Leaves out the lines that are not for the version, in the access wideners, class tweakers and access transformers {@code processResources} copies. */
    private record FilterVersionedFile(String version) implements Action<FileCopyDetails>, Serializable {
        @Override
        public void execute(FileCopyDetails details) {
            if (VersionedLines.isVersionedFile(details.getName())) {
                details.filter(new KeepLinesOf(version, details.getPath()));
            }
        }
    }

    private record KeepLinesOf(String version, String path) implements Transformer<String, String>, Serializable {
        @Override
        public String transform(String line) {
            try {
                return VersionedLines.appliesTo(line, McVersion.parse(version)) ? line : null;
            } catch (IllegalArgumentException e) {
                throw new GradleException(path + ": " + e.getMessage(), e);
            }
        }
    }

    /** Leaves the files the made one replaces out of the jar. */
    private record OnlyTheMadeOne(String madeDir) implements Action<FileCopyDetails>, Serializable {
        @Override
        public void execute(FileCopyDetails details) {
            if (VersionedLines.isVersionedFile(details.getName()) && !details.getFile().toPath().toAbsolutePath().startsWith(Path.of(madeDir))) {
                details.exclude();
            }
        }
    }
}
