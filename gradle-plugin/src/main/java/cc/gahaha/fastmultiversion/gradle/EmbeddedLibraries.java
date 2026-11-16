package cc.gahaha.fastmultiversion.gradle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The jars packed into this plugin: the javac plugin with its dependencies (for the processor path) and the
 * annotations ({@code Mc}, {@code @Mc.Is}) for the mod's compile classpath. They are unpacked once into the
 * Gradle user home, in a directory named after their content, so a file is never overwritten while a
 * compiler daemon may have it open.
 */
final class EmbeddedLibraries {
    private static final String RESOURCES = "/cc/gahaha/fastmultiversion/gradle/lib/";

    record Libraries(List<File> processorPath, List<File> api) {
    }

    private static Libraries cached;

    private EmbeddedLibraries() {
    }

    static synchronized Libraries extract(File gradleUserHome) {
        if (cached != null && cached.processorPath().stream().allMatch(File::isFile) && cached.api().stream().allMatch(File::isFile)) {
            return cached;
        }
        try {
            Map<String, byte[]> jars = new LinkedHashMap<>();
            Map<String, String> roles = new LinkedHashMap<>();
            for (String line : new String(resource("index.txt"), StandardCharsets.UTF_8).split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String name = line.substring(eq + 1).trim();
                    roles.put(name, line.substring(0, eq).trim());
                    jars.put(name, resource(name));
                }
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            jars.forEach((name, bytes) -> {
                digest.update(name.getBytes(StandardCharsets.UTF_8));
                digest.update(bytes);
            });
            String hash = HexFormat.of().formatHex(digest.digest()).substring(0, 16);
            Path dir = gradleUserHome.toPath().resolve(Path.of("caches", "cc.gahaha.fastmultiversion", hash));
            Files.createDirectories(dir);

            List<File> processorPath = new ArrayList<>();
            List<File> api = new ArrayList<>();
            for (var entry : jars.entrySet()) {
                Path file = dir.resolve(entry.getKey());
                if (!Files.isRegularFile(file) || Files.size(file) != entry.getValue().length) {
                    Path temp = Files.createTempFile(dir, entry.getKey(), ".tmp");
                    Files.write(temp, entry.getValue());
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                ("api".equals(roles.get(entry.getKey())) ? api : processorPath).add(file.toFile());
            }
            cached = new Libraries(List.copyOf(processorPath), List.copyOf(api));
            return cached;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot unpack the multiversion libraries", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = EmbeddedLibraries.class.getResourceAsStream(RESOURCES + name)) {
            if (in == null) {
                throw new IOException("missing resource " + RESOURCES + name + " in the multiversion plugin");
            }
            return in.readAllBytes();
        }
    }
}
