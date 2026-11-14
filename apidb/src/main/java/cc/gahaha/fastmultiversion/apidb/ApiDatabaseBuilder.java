package cc.gahaha.fastmultiversion.apidb;

import cc.gahaha.fastmultiversion.apidb.ApiClass.ClassShape;
import cc.gahaha.fastmultiversion.apidb.ApiClass.MemberKey;
import cc.gahaha.fastmultiversion.apidb.ApiClass.MemberKind;
import cc.gahaha.fastmultiversion.apidb.ApiClass.MemberShape;
import cc.gahaha.fastmultiversion.apidb.ApiClass.Variant;
import cc.gahaha.fastmultiversion.versioning.McVersion;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Collects classes and members version by version, merging identical forms into one {@link Variant}.
 * Platform classes are added once and count for every version.
 */
public final class ApiDatabaseBuilder {
    private final List<McVersion> versions;
    private final long allVersions;
    private final Map<String, Entry> classes = new TreeMap<>();

    public ApiDatabaseBuilder(List<McVersion> versions) {
        if (versions.isEmpty() || versions.size() > ApiDatabase.MAX_VERSIONS) {
            throw new IllegalArgumentException("between 1 and " + ApiDatabase.MAX_VERSIONS + " versions are supported");
        }
        for (int i = 1; i < versions.size(); i++) {
            if (versions.get(i - 1).compareTo(versions.get(i)) >= 0) {
                throw new IllegalArgumentException("versions must be strictly ascending: " + versions);
            }
        }
        this.versions = List.copyOf(versions);
        this.allVersions = ApiDatabase.allVersions(versions.size());
    }

    public void addClass(int version, String name, ClassShape shape) {
        Entry entry = entry(name, false);
        add(entry.shapes, 1L << version, shape);
    }

    public void addPlatformClass(String name, ClassShape shape) {
        Entry entry = entry(name, true);
        add(entry.shapes, allVersions, shape);
    }

    /** Adds a member; for a platform class pass any version, it counts for all. */
    public void addMember(int version, MemberKind kind, String owner, String name, String descriptor, MemberShape shape) {
        Entry entry = classes.get(owner);
        if (entry == null) {
            throw new IllegalStateException("add class " + owner + " before its members");
        }
        Map<MemberKey, List<Variant<MemberShape>>> members = kind == MemberKind.FIELD ? entry.fields : entry.methods;
        long mask = entry.platform ? allVersions : 1L << version;
        add(members.computeIfAbsent(new MemberKey(name, descriptor), k -> new ArrayList<>()), mask, shape);
    }

    public boolean contains(String name) {
        return classes.containsKey(name);
    }

    public ApiDatabase build() {
        Map<String, ApiClass> built = new LinkedHashMap<>();
        classes.forEach((name, e) -> built.put(name, new ApiClass(name, e.platform, ordered(e.shapes),
                freeze(e.fields), freeze(e.methods))));
        return new ApiDatabase(versions, built);
    }

    /** Variants ordered by their oldest version, so the result does not depend on the order things were added. */
    private static <T> List<Variant<T>> ordered(List<Variant<T>> variants) {
        return variants.stream().sorted(Comparator.comparingInt(v -> Long.numberOfTrailingZeros(v.versions()))).toList();
    }

    private Entry entry(String name, boolean platform) {
        Entry entry = classes.computeIfAbsent(name, k -> new Entry(platform));
        if (entry.platform != platform) {
            throw new IllegalStateException(name + " is added both as a platform class and as a versioned class");
        }
        return entry;
    }

    private static <T> void add(List<Variant<T>> variants, long mask, T shape) {
        for (int i = 0; i < variants.size(); i++) {
            Variant<T> variant = variants.get(i);
            if (variant.shape().equals(shape)) {
                variants.set(i, new Variant<>(variant.versions() | mask, shape));
                return;
            }
        }
        variants.add(new Variant<>(mask, shape));
    }

    private static Map<MemberKey, List<Variant<MemberShape>>> freeze(Map<MemberKey, List<Variant<MemberShape>>> members) {
        Map<MemberKey, List<Variant<MemberShape>>> frozen = new LinkedHashMap<>();
        members.forEach((key, variants) -> frozen.put(key, ordered(variants)));
        return frozen;
    }

    private static final class Entry {
        final boolean platform;
        final List<Variant<ClassShape>> shapes = new ArrayList<>();
        final Map<MemberKey, List<Variant<MemberShape>>> fields = new LinkedHashMap<>();
        final Map<MemberKey, List<Variant<MemberShape>>> methods = new LinkedHashMap<>();

        Entry(boolean platform) {
            this.platform = platform;
        }
    }
}
