package cc.gahaha.fastmultiversion.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The fields and methods of Minecraft's classes, read from its jars when first asked for (only the classes asked
 * for): what an access widener needs that an access transformer leaves out.
 */
final class MinecraftClasses implements AccessEntries.Members {
    private record Shape(List<AccessEntries.Member> fields, List<AccessEntries.Member> methods) {
    }

    private static final Shape MISSING = new Shape(null, null);

    private final Supplier<List<Path>> jars;
    private final Map<String, Shape> shapes = new ConcurrentHashMap<>();

    /** {@code jars} is asked for when a class is first needed, e.g. Minecraft's jars once Loom has them. */
    MinecraftClasses(Supplier<List<Path>> jars) {
        this.jars = jars;
    }

    @Override
    public List<AccessEntries.Member> fields(String owner) {
        return shape(owner).fields();
    }

    @Override
    public List<AccessEntries.Member> methods(String owner) {
        return shape(owner).methods();
    }

    private Shape shape(String owner) {
        return shapes.computeIfAbsent(owner, this::read);
    }

    private Shape read(String owner) {
        for (Path jar : jars.get()) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ZipEntry entry = zip.getEntry(owner + ".class");
                if (entry == null) {
                    continue;
                }
                List<AccessEntries.Member> fields = new ArrayList<>();
                List<AccessEntries.Member> methods = new ArrayList<>();
                try (InputStream in = zip.getInputStream(entry)) {
                    new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                            fields.add(new AccessEntries.Member(name, descriptor));
                            return null;
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                            methods.add(new AccessEntries.Member(name, descriptor));
                            return null;
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
                return new Shape(List.copyOf(fields), List.copyOf(methods));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + jar, e);
            }
        }
        return MISSING;
    }
}
