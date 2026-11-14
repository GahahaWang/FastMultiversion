package cc.gahaha.fastmultiversion.javac;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import javax.annotation.processing.Processor;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Compiles sources in-process with {@code -Xplugin:FastMultiversion} and inspects the output. The plugin is
 * found the way javac finds it in a real build: through its service file on the processor path.
 */
public final class TestCompiler {
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern TYPE = Pattern.compile("\\b(?:class|interface|enum|record)\\s+(\\w+)");

    private final Path temp;
    private final String target;
    private final List<String> sources = new ArrayList<>();
    private final List<Path> classpath = new ArrayList<>();
    private final List<Processor> processors = new ArrayList<>();
    private Path apiDatabase;

    private TestCompiler(Path temp, String target) {
        this.temp = temp;
        this.target = target;
    }

    public static TestCompiler target(Path temp, String target) {
        return new TestCompiler(temp, target);
    }

    public static TestCompiler noTarget(Path temp) {
        return new TestCompiler(temp, null);
    }

    public TestCompiler source(String text) {
        sources.add(text);
        return this;
    }

    /** Adds jars or directories to compile against, in front of the test classpath. */
    public TestCompiler classpath(Path... entries) {
        classpath.addAll(List.of(entries));
        return this;
    }

    /** Runs an annotation processor too; without one, annotation processing is off. */
    public TestCompiler processor(Processor processor) {
        processors.add(processor);
        return this;
    }

    /** Turns on the range check with this API database. */
    public TestCompiler apiDatabase(Path file) {
        this.apiDatabase = file;
        return this;
    }

    public Result compile() throws IOException {
        Path out = Files.createTempDirectory(temp, "classes");
        List<String> path = new ArrayList<>();
        classpath.forEach(p -> path.add(p.toString()));
        path.add(System.getProperty("java.class.path"));
        List<String> options = new ArrayList<>(List.of(
                "-d", out.toString(),
                "-classpath", String.join(File.pathSeparator, path),
                "-Xplugin:" + MultiversionPlugin.NAME
                        + (target == null ? "" : " " + MultiversionPlugin.TARGET_ARG + target)
                        + (apiDatabase == null ? "" : " " + MultiversionPlugin.APIDB_ARG + apiDatabase)));
        if (processors.isEmpty()) {
            options.add("-proc:none");
        }
        return run(sources, options, out, processors);
    }

    /** Compiles sources without the plugin into a jar, e.g. to stand in for one Minecraft version's API. */
    public static Path jar(Path temp, String name, String... sources) throws IOException {
        Path classes = Files.createTempDirectory(temp, name + "-classes");
        Result result = run(List.of(sources), List.of("-proc:none", "-d", classes.toString()), classes, List.of());
        assertTrue(result.success(), () -> "sources for " + name + " do not compile:\n" + result.messages(null));
        Path jar = temp.resolve(name + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar)); Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new ZipEntry(classes.relativize(file).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
        }
        return jar;
    }

    private static Result run(List<String> sources, List<String> options, Path out, List<Processor> processors) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<JavaFileObject> units = sources.stream().map(TestCompiler::unit).toList();
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null, units);
            if (!processors.isEmpty()) {
                task.setProcessors(processors);
            }
            boolean success = task.call();
            return new Result(success, diagnostics.getDiagnostics(), out);
        }
    }

    private static JavaFileObject unit(String text) {
        Matcher pkg = PACKAGE.matcher(text);
        Matcher type = TYPE.matcher(text);
        if (!type.find()) {
            throw new IllegalArgumentException("no type declaration in test source:\n" + text);
        }
        String path = (pkg.find() ? pkg.group(1).replace('.', '/') + "/" : "") + type.group(1) + ".java";
        return new SimpleJavaFileObject(URI.create("string:///" + path), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        };
    }

    public record Result(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics, Path classes) {
        public Result assertSuccess() {
            if (!success) {
                fail("compilation failed:\n" + messages(null));
            }
            return this;
        }

        public Result assertFailure() {
            if (success) {
                fail("compilation succeeded, but an error was expected:\n" + messages(null));
            }
            return this;
        }

        /** Error messages, one per line, each prefixed with its line number. */
        public String errors() {
            return messages(Diagnostic.Kind.ERROR);
        }

        /** Notes, one per line, each prefixed with its line number. */
        public String notes() {
            return messages(Diagnostic.Kind.NOTE);
        }

        String messages(Diagnostic.Kind kind) {
            return diagnostics.stream()
                    .filter(d -> kind == null || d.getKind() == kind)
                    .map(d -> "line " + d.getLineNumber() + ": " + d.getMessage(Locale.ROOT))
                    .collect(Collectors.joining("\n"));
        }

        public Path classFile(String binaryName) {
            return classes.resolve(binaryName.replace('.', '/') + ".class");
        }

        public boolean hasClass(String binaryName) {
            return Files.exists(classFile(binaryName));
        }

        /** Whether the class file contains {@code text}, e.g. in a string constant or a referenced name. */
        public boolean mentions(String binaryName, String text) throws IOException {
            String raw = new String(Files.readAllBytes(classFile(binaryName)), StandardCharsets.ISO_8859_1);
            return raw.contains(text);
        }

        public void delete(String binaryName) throws IOException {
            Files.delete(classFile(binaryName));
        }

        /**
         * Loads the output without the test classpath, so a leftover reference to {@code Mc} or to a
         * deleted class fails just as it would in a real game. {@code runtime} plays the role of the game.
         */
        public Class<?> load(String binaryName, Path... runtime) throws Exception {
            return Class.forName(binaryName, true, loader(runtime));
        }

        /** Calls a static no-argument method, then closes the class loader so jars are not left locked. */
        public Object call(String binaryName, String method, Path... runtime) throws Exception {
            try (URLClassLoader loader = loader(runtime)) {
                Method m = Class.forName(binaryName, true, loader).getDeclaredMethod(method);
                m.setAccessible(true);
                return m.invoke(null);
            }
        }

        private URLClassLoader loader(Path... runtime) throws IOException {
            List<URL> urls = new ArrayList<>();
            urls.add(classes.toUri().toURL());
            for (Path entry : runtime) {
                urls.add(entry.toUri().toURL());
            }
            return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        }
    }
}
