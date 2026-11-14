package cc.gahaha.fastmultiversion.apidb.generator;

import cc.gahaha.fastmultiversion.apidb.ApiClass.ClassShape;
import cc.gahaha.fastmultiversion.apidb.ApiClass.MemberKind;
import cc.gahaha.fastmultiversion.apidb.ApiClass.MemberShape;
import cc.gahaha.fastmultiversion.apidb.ApiDatabase;
import cc.gahaha.fastmultiversion.apidb.ApiDatabaseBuilder;
import cc.gahaha.fastmultiversion.apidb.ApiFlags;
import cc.gahaha.fastmultiversion.versioning.McVersion;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Reads every version's jars and records each named class and each non-synthetic member with its
 * access flags, supertypes and generic signature. Local and anonymous classes are left out; no mod can
 * refer to them.
 *
 * <p>JDK classes that the input classes extend or implement are added as platform classes, read from the
 * running JDK, so that member lookups can follow inheritance into {@code Object}, {@code Iterable} and so
 * on. Supertypes from libraries that were not given stay unknown.
 */
public final class ApiDbGenerator {
    private ApiDbGenerator() {
    }

    public static ApiDatabase generate(List<VersionJars> inputs) throws IOException {
        List<VersionJars> sorted = inputs.stream().sorted(Comparator.comparing(VersionJars::version)).toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).version().equals(sorted.get(i - 1).version())) {
                throw new IllegalArgumentException("Minecraft " + sorted.get(i).version() + " is given twice");
            }
        }
        List<McVersion> versions = sorted.stream().map(VersionJars::version).toList();
        ApiDatabaseBuilder builder = new ApiDatabaseBuilder(versions);

        Set<String> supertypes = new TreeSet<>();
        for (int v = 0; v < sorted.size(); v++) {
            try (VersionClasses source = new VersionClasses(sorted.get(v).jars())) {
                for (String name : new TreeSet<>(source.names())) {
                    ClassNode node = source.read(name);
                    if (!isApiClass(name, node)) {
                        continue;
                    }
                    builder.addClass(v, name, shape(node));
                    addMembers(builder, v, node, false);
                    if (node.superName != null) {
                        supertypes.add(node.superName);
                    }
                    supertypes.addAll(node.interfaces);
                }
            }
        }
        addPlatformClasses(builder, supertypes);
        return builder.build();
    }

    private static void addPlatformClasses(ApiDatabaseBuilder builder, Set<String> supertypes) throws IOException {
        Deque<String> pending = new ArrayDeque<>(supertypes);
        while (!pending.isEmpty()) {
            String name = pending.poll();
            if (builder.contains(name)) {
                continue;
            }
            // The platform loader sees JDK modules only, never this tool's own classpath.
            try (InputStream in = ClassLoader.getPlatformClassLoader().getResourceAsStream(name + ".class")) {
                if (in == null) {
                    continue; // from a library that was not given
                }
                ClassNode node = read(new ClassReader(in));
                builder.addPlatformClass(name, shape(node));
                addMembers(builder, 0, node, true);
                if (node.superName != null) {
                    pending.add(node.superName);
                }
                pending.addAll(node.interfaces);
            }
        }
    }

    private static ClassShape shape(ClassNode node) {
        int access = node.access;
        for (InnerClassNode entry : node.innerClasses) {
            if (entry.name.equals(node.name)) {
                access = entry.access; // a nested class's real modifiers (private, protected, static)
                break;
            }
        }
        return new ClassShape(access & ApiFlags.CLASS_MASK, node.superName, node.interfaces, node.signature);
    }

    private static void addMembers(ApiDatabaseBuilder builder, int version, ClassNode node, boolean visibleOnly) {
        for (FieldNode field : node.fields) {
            if (keep(field.access, visibleOnly)) {
                builder.addMember(version, MemberKind.FIELD, node.name, field.name, field.desc,
                        new MemberShape(field.access & ApiFlags.MEMBER_MASK, field.signature));
            }
        }
        for (MethodNode method : node.methods) {
            if (keep(method.access, visibleOnly) && !method.name.equals("<clinit>")) {
                builder.addMember(version, MemberKind.METHOD, node.name, method.name, method.desc,
                        new MemberShape(method.access & ApiFlags.MEMBER_MASK, method.signature));
            }
        }
    }

    private static boolean keep(int access, boolean visibleOnly) {
        return (access & Opcodes.ACC_SYNTHETIC) == 0 && (!visibleOnly || ApiFlags.visibility(access) >= 2);
    }

    private static boolean isApiClass(String name, ClassNode node) {
        if (name.endsWith("package-info") || name.endsWith("module-info") || (node.access & Opcodes.ACC_SYNTHETIC) != 0) {
            return false;
        }
        // A class's InnerClasses attribute lists every class enclosing it, so its own entries are enough to
        // tell whether it is, or sits inside, a local or anonymous class.
        Map<String, InnerClassNode> entries = new HashMap<>();
        node.innerClasses.forEach(entry -> entries.putIfAbsent(entry.name, entry));
        String current = name;
        for (int depth = 0; depth < 64; depth++) {
            InnerClassNode entry = entries.get(current);
            if (entry == null) {
                return true;
            }
            if (entry.outerName == null || entry.innerName == null) {
                return false;
            }
            current = entry.outerName;
        }
        return true;
    }

    private static ClassNode read(ClassReader reader) {
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    /** The classes of one version, looked up by internal name across its jars. */
    private static final class VersionClasses implements Closeable {
        private final List<ZipFile> zips = new ArrayList<>();
        private final Map<String, ZipFile> owners = new HashMap<>();

        VersionClasses(List<Path> jars) throws IOException {
            try {
                for (Path jar : jars) {
                    ZipFile zip = new ZipFile(jar.toFile());
                    zips.add(zip);
                    zip.stream().forEach(entry -> {
                        String path = entry.getName();
                        // Multi-release variants under META-INF/versions are not part of the base API.
                        if (!entry.isDirectory() && path.endsWith(".class") && !path.startsWith("META-INF/")) {
                            owners.putIfAbsent(path.substring(0, path.length() - ".class".length()), zip);
                        }
                    });
                }
            } catch (IOException | RuntimeException e) {
                close();
                throw e;
            }
        }

        Set<String> names() {
            return owners.keySet();
        }

        ClassNode read(String name) throws IOException {
            ZipFile zip = owners.get(name);
            try (InputStream in = zip.getInputStream(zip.getEntry(name + ".class"))) {
                return ApiDbGenerator.read(new ClassReader(in));
            } catch (RuntimeException e) {
                throw new IOException("cannot read " + name + " from " + zip.getName() + ": " + e, e);
            }
        }

        @Override
        public void close() throws IOException {
            for (ZipFile zip : zips) {
                zip.close();
            }
        }
    }
}
