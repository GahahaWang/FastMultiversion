package cc.gahaha.fastmultiversion.apidb.generator;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Compiles sources into a jar that stands in for one Minecraft version. */
final class ApiJars {
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern TYPE = Pattern.compile("\\b(?:class|interface|enum|record)\\s+(\\w+)");

    private ApiJars() {
    }

    static Path build(Path temp, String name, List<String> sources) throws IOException {
        Path classes = Files.createTempDirectory(temp, name + "-classes");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<JavaFileObject> units = sources.stream().map(ApiJars::unit).toList();
            boolean ok = compiler.getTask(null, fm, diagnostics, List.of("-proc:none", "-d", classes.toString()), null, units).call();
            assertTrue(ok, () -> "API sources for " + name + " do not compile: " + diagnostics.getDiagnostics());
        }
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

    private static JavaFileObject unit(String text) {
        Matcher pkg = PACKAGE.matcher(text);
        Matcher type = TYPE.matcher(text);
        if (!type.find()) {
            throw new IllegalArgumentException("no type in:\n" + text);
        }
        String path = (pkg.find() ? pkg.group(1).replace('.', '/') + "/" : "") + type.group(1) + ".java";
        return new SimpleJavaFileObject(URI.create("string:///" + path), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        };
    }
}
