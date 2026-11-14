package cc.gahaha.fastmultiversion.apidb;

import static cc.gahaha.fastmultiversion.apidb.ApiFlags.isStatic;
import static cc.gahaha.fastmultiversion.apidb.ApiFlags.visibility;

import cc.gahaha.fastmultiversion.apidb.ApiClass.ClassShape;
import cc.gahaha.fastmultiversion.apidb.ApiClass.MemberKey;
import cc.gahaha.fastmultiversion.apidb.ApiClass.MemberKind;
import cc.gahaha.fastmultiversion.apidb.ApiClass.MemberShape;
import cc.gahaha.fastmultiversion.versioning.McVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Which classes and members exist in which Minecraft versions.
 *
 * <p>Version sets are bit masks over {@link #versions()} (bit {@code i} is the {@code i}-th version, in
 * ascending order); {@link #range(long)} turns one into the {@code Mc.is} range syntax.
 *
 * <p>Queries follow JVM resolution (JVMS 5.4.3), so a member counts as present wherever it can be reached
 * from the class it is used through, even if it moved between a class and its superclass. Classes outside
 * the database (a library that was not given to the generator, say) cannot be looked into; a search that
 * needs one is {@linkplain Resolution.Status#UNKNOWN unknown}, and the masks count unknown versions as
 * present, so that checks built on them do not report false errors.
 */
public final class ApiDatabase {
    public static final int MAX_VERSIONS = 64;

    private final List<McVersion> versions;
    private final Map<String, ApiClass> classes;
    private final long all;

    ApiDatabase(List<McVersion> versions, Map<String, ApiClass> classes) {
        this.versions = List.copyOf(versions);
        this.classes = Collections.unmodifiableMap(classes);
        this.all = allVersions(versions.size());
    }

    public static ApiDatabase read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return ApiDatabaseFormat.read(in);
        }
    }

    public static ApiDatabase read(InputStream in) throws IOException {
        return ApiDatabaseFormat.read(in);
    }

    public void write(Path file) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            ApiDatabaseFormat.write(this, out);
        }
    }

    public void write(OutputStream out) throws IOException {
        ApiDatabaseFormat.write(this, out);
    }

    /**
     * Combines databases of different versions into one, e.g. one database per version built separately.
     *
     * @throws IllegalArgumentException if two parts cover the same version
     */
    public static ApiDatabase merge(List<ApiDatabase> parts) {
        List<McVersion> versions = parts.stream().flatMap(p -> p.versions().stream()).sorted().toList();
        for (int i = 1; i < versions.size(); i++) {
            if (versions.get(i).equals(versions.get(i - 1))) {
                throw new IllegalArgumentException("Minecraft " + versions.get(i) + " is in more than one database");
            }
        }
        ApiDatabaseBuilder builder = new ApiDatabaseBuilder(versions);
        for (ApiDatabase part : parts) {
            int[] index = part.versions().stream().mapToInt(versions::indexOf).toArray();
            for (ApiClass c : part.classes()) {
                for (ApiClass.Variant<ApiClass.ClassShape> shape : c.shapes()) {
                    if (c.platform()) {
                        builder.addPlatformClass(c.name(), shape.shape());
                    } else {
                        forEachVersion(shape.versions(), index, v -> builder.addClass(v, c.name(), shape.shape()));
                    }
                }
                for (MemberKind kind : MemberKind.values()) {
                    (kind == MemberKind.FIELD ? c.fields() : c.methods()).forEach((key, variants) -> {
                        for (ApiClass.Variant<MemberShape> variant : variants) {
                            forEachVersion(c.platform() ? 1 : variant.versions(), index,
                                    v -> builder.addMember(v, kind, c.name(), key.name(), key.descriptor(), variant.shape()));
                        }
                    });
                }
            }
        }
        return builder.build();
    }

    private static void forEachVersion(long mask, int[] index, java.util.function.IntConsumer action) {
        for (int i = 0; i < index.length; i++) {
            if ((mask & (1L << i)) != 0) {
                action.accept(index[i]);
            }
        }
    }

    // ---- versions

    public List<McVersion> versions() {
        return versions;
    }

    public long allVersions() {
        return all;
    }

    static long allVersions(int count) {
        return count == MAX_VERSIONS ? -1L : (1L << count) - 1;
    }

    /** The index of {@code version}, or -1 if the database does not cover it. */
    public int indexOf(McVersion version) {
        return versions.indexOf(version);
    }

    /**
     * Writes a mask as a version range: each run of consecutive versions becomes one interval that ends
     * just before the next known version, or is open if it reaches the newest. With versions 26.1, 26.1.2,
     * 26.2, 26.3, the set {26.1, 26.1.2} is {@code [26.1,26.2)} and {26.2, 26.3} is {@code [26.2,)}.
     */
    public String range(long mask) {
        StringJoiner ranges = new StringJoiner(",");
        int i = 0;
        while (i < versions.size()) {
            if ((mask & (1L << i)) == 0) {
                i++;
                continue;
            }
            int start = i;
            while (i < versions.size() && (mask & (1L << i)) != 0) {
                i++;
            }
            ranges.add("[" + versions.get(start) + "," + (i < versions.size() ? versions.get(i) : "") + ")");
        }
        return ranges.toString();
    }

    // ---- classes

    /** The class, or {@code null} if the database does not know it. */
    public ApiClass get(String internalName) {
        return classes.get(internalName);
    }

    public Collection<ApiClass> classes() {
        return classes.values();
    }

    /** The versions that have the class; every version if the database does not know it. */
    public long classVersions(String name) {
        ApiClass c = classes.get(name);
        return c == null ? all : c.versions();
    }

    /**
     * The versions in which the class is usable the way it is in {@code reference}: it exists, has the same
     * kind (class, interface, enum, annotation) and is at least as visible.
     */
    public long classCompatible(String name, int reference) {
        ApiClass c = classes.get(name);
        if (c == null || c.platform()) {
            return all;
        }
        ClassShape ref = c.shapeIn(reference);
        if (ref == null) {
            return c.versions();
        }
        int kind = ApiFlags.INTERFACE | ApiFlags.ANNOTATION | ApiFlags.ENUM;
        long mask = 0;
        for (int v = 0; v < versions.size(); v++) {
            ClassShape shape = c.shapeIn(v);
            if (shape != null && (shape.access() & kind) == (ref.access() & kind)
                    && visibility(shape.access()) >= visibility(ref.access())) {
                mask |= 1L << v;
            }
        }
        return mask;
    }

    // ---- members

    /** The outcome of looking up a member through a class in one version. */
    public record Resolution(Status status, String declaringClass, MemberShape shape) {
        public enum Status { FOUND, MISSING, UNKNOWN }

        static final Resolution MISSING = new Resolution(Status.MISSING, null, null);
        static final Resolution UNKNOWN = new Resolution(Status.UNKNOWN, null, null);
    }

    /** Looks up a member used through {@code owner} in one version, as the JVM would. */
    public Resolution resolve(MemberKind kind, String owner, String name, String descriptor, int version) {
        ApiClass start = classes.get(owner);
        if (start == null) {
            return Resolution.UNKNOWN;
        }
        if (start.shapeIn(version) == null) {
            return Resolution.MISSING;
        }
        Search search = new Search(new MemberKey(name, descriptor), version);
        Resolution found = kind == MemberKind.FIELD ? search.field(owner) : search.method(owner);
        if (found != null) {
            return found;
        }
        return search.sawUnknown ? Resolution.UNKNOWN : Resolution.MISSING;
    }

    /** The versions in which the member can be reached through {@code owner} (or that is unknown). */
    public long resolvable(MemberKind kind, String owner, String name, String descriptor) {
        long mask = 0;
        for (int v = 0; v < versions.size(); v++) {
            if (resolve(kind, owner, name, descriptor, v).status() != Resolution.Status.MISSING) {
                mask |= 1L << v;
            }
        }
        return mask;
    }

    /**
     * The versions in which the member can be used exactly as in {@code reference}: it is reachable through
     * {@code owner}, has the same static-ness and generic signature, and is at least as visible. A generic
     * signature change matters even when the erased descriptor is the same: javac inserts casts based on it,
     * e.g. for {@code Consumer<Item>} that became {@code Consumer<ItemStack>}.
     *
     * <p>Returns every version if the member cannot be found in {@code reference}.
     */
    public long compatible(MemberKind kind, String owner, String name, String descriptor, int reference) {
        Resolution ref = resolve(kind, owner, name, descriptor, reference);
        if (ref.status() != Resolution.Status.FOUND) {
            return all;
        }
        long mask = 0;
        for (int v = 0; v < versions.size(); v++) {
            Resolution r = v == reference ? ref : resolve(kind, owner, name, descriptor, v);
            if (r.status() == Resolution.Status.UNKNOWN
                    || (r.status() == Resolution.Status.FOUND && usableAs(r.shape(), ref.shape()))) {
                mask |= 1L << v;
            }
        }
        return mask;
    }

    private static boolean usableAs(MemberShape shape, MemberShape reference) {
        return isStatic(shape.access()) == isStatic(reference.access())
                && visibility(shape.access()) >= visibility(reference.access())
                && Objects.equals(shape.signature(), reference.signature());
    }

    // ---- subtypes

    /** The versions in which {@code sub} is {@code sup} or a subtype of it (or that is unknown). */
    public long subtype(String sub, String sup) {
        long mask = 0;
        for (int v = 0; v < versions.size(); v++) {
            if (isSubtype(sub, sup, v)) {
                mask |= 1L << v;
            }
        }
        return mask;
    }

    private boolean isSubtype(String sub, String sup, int version) {
        if (classes.containsKey(sub) && classes.get(sub).shapeIn(version) == null) {
            return false;
        }
        Deque<String> pending = new ArrayDeque<>(List.of(sub));
        Set<String> seen = new HashSet<>();
        boolean sawUnknown = false;
        while (!pending.isEmpty()) {
            String name = pending.poll();
            if (name.equals(sup)) {
                return true;
            }
            if (!seen.add(name)) {
                continue;
            }
            ApiClass c = classes.get(name);
            if (c == null) {
                sawUnknown = true;
                continue;
            }
            ClassShape shape = c.shapeIn(version);
            if (shape != null) {
                if (shape.superName() != null) {
                    pending.add(shape.superName());
                }
                pending.addAll(shape.interfaces());
            }
        }
        return sawUnknown;
    }

    /** One member lookup in one version. */
    private final class Search {
        private final MemberKey key;
        private final int version;
        private final Set<String> visited = new HashSet<>();
        boolean sawUnknown;

        Search(MemberKey key, int version) {
            this.key = key;
            this.version = version;
        }

        /** JVMS 5.4.3.2: the class, then its superinterfaces, then its superclass, each recursively. */
        Resolution field(String name) {
            if (name == null || !visited.add(name)) {
                return null;
            }
            ApiClass c = visit(name);
            ClassShape shape = c == null ? null : c.shapeIn(version);
            if (shape == null) {
                return null;
            }
            MemberShape member = c.member(MemberKind.FIELD, key, version);
            if (member != null) {
                return new Resolution(Resolution.Status.FOUND, name, member);
            }
            for (String superinterface : shape.interfaces()) {
                Resolution found = field(superinterface);
                if (found != null) {
                    return found;
                }
            }
            return field(shape.superName());
        }

        /**
         * JVMS 5.4.3.3 and 5.4.3.4: the class and its superclasses (for an interface: itself, then
         * {@code java/lang/Object}, which is an interface's superclass in the class file), then the
         * superinterfaces, whose private and static methods are not inherited.
         */
        Resolution method(String owner) {
            Deque<String> superinterfaces = new ArrayDeque<>();
            for (String name = owner; name != null && visited.add(name); ) {
                ApiClass c = visit(name);
                ClassShape shape = c == null ? null : c.shapeIn(version);
                if (shape == null) {
                    break;
                }
                MemberShape member = c.member(MemberKind.METHOD, key, version);
                if (member != null) {
                    return new Resolution(Resolution.Status.FOUND, name, member);
                }
                superinterfaces.addAll(shape.interfaces());
                name = shape.superName();
            }
            while (!superinterfaces.isEmpty()) {
                String name = superinterfaces.poll();
                if (!visited.add(name)) {
                    continue;
                }
                ApiClass c = visit(name);
                ClassShape shape = c == null ? null : c.shapeIn(version);
                if (shape == null) {
                    continue;
                }
                MemberShape member = c.member(MemberKind.METHOD, key, version);
                if (member != null && (member.access() & (ApiFlags.PRIVATE | ApiFlags.STATIC)) == 0) {
                    return new Resolution(Resolution.Status.FOUND, name, member);
                }
                superinterfaces.addAll(shape.interfaces());
            }
            return null;
        }

        private ApiClass visit(String name) {
            ApiClass c = classes.get(name);
            if (c == null) {
                sawUnknown = true;
            }
            return c;
        }
    }

    @Override
    public String toString() {
        return "ApiDatabase" + versions + " with " + classes.size() + " classes";
    }
}
