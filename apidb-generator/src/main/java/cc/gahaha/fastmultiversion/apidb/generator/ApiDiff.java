package cc.gahaha.fastmultiversion.apidb.generator;

import cc.gahaha.fastmultiversion.apidb.ApiClass;
import cc.gahaha.fastmultiversion.apidb.ApiClass.MemberKey;
import cc.gahaha.fastmultiversion.apidb.ApiClass.MemberShape;
import cc.gahaha.fastmultiversion.apidb.ApiClass.Variant;
import cc.gahaha.fastmultiversion.apidb.ApiDatabase;
import cc.gahaha.fastmultiversion.apidb.ApiFlags;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A readable list of what differs between the versions in a database: classes that some versions lack,
 * and protected or public members that some versions of their class lack or declare differently. It
 * lists declarations; a member that moved to a superclass shows up as removed in one class and added in
 * the other, while the database's queries still find it through either.
 */
public final class ApiDiff {
    private ApiDiff() {
    }

    public static String format(ApiDatabase db) {
        List<String> classLines = new ArrayList<>();
        List<String> memberLines = new ArrayList<>();
        List<String> changeLines = new ArrayList<>();
        int classes = 0;
        int platform = 0;
        int partialClasses = 0;
        int partialMembers = 0;
        int changedMembers = 0;
        for (ApiClass c : db.classes()) {
            if (c.platform()) {
                platform++;
                continue;
            }
            classes++;
            long classVersions = c.versions();
            if (classVersions != db.allVersions()) {
                partialClasses++;
                classLines.add(String.format("%-24s %s", db.range(classVersions), c.name()));
            }
            List<String> partial = new ArrayList<>();
            List<String> changed = new ArrayList<>();
            for (Map<MemberKey, List<Variant<MemberShape>>> members : List.of(c.fields(), c.methods())) {
                members.forEach((key, variants) -> {
                    if (variants.stream().noneMatch(v -> ApiFlags.visibility(v.shape().access()) >= 2)) {
                        return; // mods cannot use it
                    }
                    long mask = variants.stream().mapToLong(Variant::versions).reduce(0, (a, b) -> a | b);
                    if (mask != classVersions) {
                        partial.add(String.format("    %-20s %s", db.range(mask), label(key)));
                    }
                    if (variants.size() > 1) {
                        changed.add("    " + label(key));
                        variants.forEach(v -> changed.add(String.format("        %-20s %s", db.range(v.versions()), describe(v.shape()))));
                    }
                });
            }
            if (!partial.isEmpty()) {
                partialMembers += partial.size();
                memberLines.add(c.name());
                memberLines.addAll(partial);
            }
            if (!changed.isEmpty()) {
                changedMembers += changed.stream().filter(l -> !l.startsWith("        ")).count();
                changeLines.add(c.name());
                changeLines.addAll(changed);
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("API of Minecraft ").append(db.versions().stream().map(Object::toString).collect(Collectors.joining(", ")))
                .append(": ").append(classes).append(" classes (plus ").append(platform).append(" JDK supertypes)\n");
        out.append(String.format("  %-44s %d%n", "classes not in every version", partialClasses));
        out.append(String.format("  %-44s %d%n", "protected/public members not in every version", partialMembers));
        out.append(String.format("  %-44s %d%n", "protected/public members declared differently", changedMembers));
        section(out, "Classes not in every version", classLines);
        section(out, "Members missing from some versions of their class", memberLines);
        section(out, "Members whose access or generic signature differs", changeLines);
        return out.toString();
    }

    private static void section(StringBuilder out, String title, List<String> lines) {
        if (!lines.isEmpty()) {
            out.append("\n== ").append(title).append("\n");
            lines.forEach(line -> out.append(line).append('\n'));
        }
    }

    /** {@code name(params)return} for methods, {@code name:type} for fields. */
    private static String label(MemberKey key) {
        return key.name() + (key.descriptor().startsWith("(") ? "" : ":") + key.descriptor();
    }

    private static String describe(MemberShape shape) {
        String[] visibility = {"private", "package-private", "protected", "public"};
        StringBuilder s = new StringBuilder(visibility[ApiFlags.visibility(shape.access())]);
        if (ApiFlags.isStatic(shape.access())) {
            s.append(" static");
        }
        if ((shape.access() & ApiFlags.ABSTRACT) != 0) {
            s.append(" abstract");
        }
        if ((shape.access() & ApiFlags.FINAL) != 0) {
            s.append(" final");
        }
        if (shape.signature() != null) {
            s.append("  ").append(shape.signature());
        }
        return s.toString();
    }
}
