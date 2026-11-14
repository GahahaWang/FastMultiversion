package cc.gahaha.fastmultiversion.apidb;

import java.util.List;
import java.util.Map;

/**
 * One class across all versions. Each part is a list of {@link Variant}s: the distinct forms the class
 * or member takes, each with the versions that have that form.
 *
 * @param name     internal name, e.g. {@code net/minecraft/world/item/ItemStack}
 * @param platform whether this is a JDK class, recorded so that resolution can walk through it;
 *                 platform classes count as present in every version
 * @param shapes   the class header in each version that has the class
 * @param fields   fields by name and descriptor
 * @param methods  methods (including constructors, as {@code <init>}) by name and descriptor
 */
public record ApiClass(String name, boolean platform, List<Variant<ClassShape>> shapes,
                       Map<MemberKey, List<Variant<MemberShape>>> fields,
                       Map<MemberKey, List<Variant<MemberShape>>> methods) {

    /** The versions that have this class. */
    public long versions() {
        long mask = 0;
        for (Variant<ClassShape> shape : shapes) {
            mask |= shape.versions();
        }
        return mask;
    }

    /** The class header in {@code version}, or {@code null} if that version lacks the class. */
    public ClassShape shapeIn(int version) {
        return Variant.in(shapes, version);
    }

    /** A member in {@code version}, or {@code null} if this class does not declare it there. */
    public MemberShape member(MemberKind kind, MemberKey key, int version) {
        List<Variant<MemberShape>> variants = (kind == MemberKind.FIELD ? fields : methods).get(key);
        return variants == null ? null : Variant.in(variants, version);
    }

    /** A class header: access flags, supertypes and generic signature. */
    public record ClassShape(int access, String superName, List<String> interfaces, String signature) {
        public ClassShape {
            interfaces = List.copyOf(interfaces);
        }
    }

    /** A field or method: access flags and generic signature ({@code null} if not generic). */
    public record MemberShape(int access, String signature) {
    }

    /** A member's identity in the class file. */
    public record MemberKey(String name, String descriptor) {
    }

    public enum MemberKind { FIELD, METHOD }

    /** One form of a class or member, and the versions (a bit mask over the input versions) that have it. */
    public record Variant<T>(long versions, T shape) {
        static <T> T in(List<Variant<T>> variants, int version) {
            long bit = 1L << version;
            for (Variant<T> variant : variants) {
                if ((variant.versions & bit) != 0) {
                    return variant.shape;
                }
            }
            return null;
        }
    }
}
