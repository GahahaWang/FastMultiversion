package cc.gahaha.fastmultiversion.gradle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Access changes to Minecraft read from access wideners and class tweakers (Fabric) and from access transformers
 * (NeoForge) into one list, and written in either format: a mod writes them once, and each loader gets them in its
 * own. The lines are a version's (already without those whose {@code @Mc.Is} excludes it).
 *
 * <p>The formats do not say quite the same, so a conversion keeps what the target format can say and adds a warning
 * for the rest:
 * <ul>
 *   <li>access widener to access transformer: {@code accessible} is {@code public}; {@code extendable} is
 *       {@code public-f} for a class and {@code protected-f} for a method; {@code mutable} is {@code private-f} (an
 *       access transformer only widens access, so {@code private} keeps it). {@code inject-interface} and
 *       {@code extend-enum} have no access transformer form.</li>
 *   <li>access transformer to access widener: {@code public} and {@code protected} are {@code accessible} (an access
 *       widener has no protected), {@code -f} is {@code extendable} or {@code mutable}; {@code default},
 *       {@code private} and {@code +f} have no access widener form. Fields and wildcards ({@code *}, {@code *()}) need
 *       Minecraft's classes ({@link Members}): an access widener names each member with its descriptor.</li>
 * </ul>
 */
final class AccessEntries {
    private static final Pattern MODIFIER = Pattern.compile("(public|protected|default|private)([-+]f)?");
    private static final String DEFAULT_HEADER = "accessWidener v2 official";

    enum Kind { CLASS, METHOD, FIELD }

    /** Ordered from narrowest: a merge keeps the wider. */
    enum Access { NONE, PROTECTED, PUBLIC }

    /**
     * What an entry is about. {@code name} is {@code null} for a class; {@code descriptor} is a method's or field's
     * descriptor, {@code null} for a class and for a field from an access transformer (which names fields without).
     */
    record Target(Kind kind, String owner, String name, String descriptor) {
    }

    /** What an entry does to its target. */
    record Change(Access access, boolean definalize, boolean makeFinal, boolean transitive) {
        Change merge(Change other) {
            return new Change(access.compareTo(other.access) >= 0 ? access : other.access, definalize || other.definalize,
                    makeFinal || other.makeFinal, transitive || other.transitive);
        }
    }

    /** A member of a Minecraft class. */
    record Member(String name, String descriptor) {
    }

    /** Minecraft's classes, for what an access transformer names without descriptors. */
    interface Members {
        /** The fields of {@code owner} (an internal name), or {@code null} if there is no such class. */
        List<Member> fields(String owner);

        /** Its methods and constructors, or {@code null} if there is no such class. */
        List<Member> methods(String owner);
    }

    private record Wildcard(Kind kind, String owner, Change change, String where) {
    }

    private final Map<Target, Change> changes = new LinkedHashMap<>();
    private final Map<Target, String> sources = new LinkedHashMap<>();
    private final List<Wildcard> wildcards = new ArrayList<>();
    /** Class tweaker lines without an access transformer form ({@code inject-interface}, {@code extend-enum}), by where they are. */
    private final Map<String, String> classTweakerOnly = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private String header;

    boolean isEmpty() {
        return changes.isEmpty() && wildcards.isEmpty() && classTweakerOnly.isEmpty();
    }

    /** What could not be converted, each with where it is. */
    List<String> warnings() {
        return List.copyOf(warnings);
    }

    /** Adds an access widener's or class tweaker's lines; {@code file} names it in warnings. */
    void addAccessWidener(List<String> lines, String file) {
        boolean headerRead = false;
        for (int i = 0; i < lines.size(); i++) {
            String entry = entry(lines.get(i));
            String where = file + ":" + (i + 1);
            if (entry.isEmpty()) {
                continue;
            }
            if (!headerRead) {
                headerRead = true;
                if (header == null || (entry.startsWith("classTweaker") && !header.startsWith("classTweaker"))) {
                    header = entry; // a class tweaker can say everything an access widener can
                }
                continue;
            }
            String[] tokens = entry.split("\\s+");
            boolean transitive = tokens[0].startsWith("transitive-");
            String keyword = transitive ? tokens[0].substring("transitive-".length()) : tokens[0];
            if (keyword.equals("inject-interface") || keyword.equals("extend-enum")) {
                classTweakerOnly.put(where, entry);
                continue;
            }
            Target target = tokens.length < 3 ? null : switch (tokens[1]) {
                case "class" -> new Target(Kind.CLASS, tokens[2], null, null);
                case "method" -> tokens.length < 5 ? null : new Target(Kind.METHOD, tokens[2], tokens[3], tokens[4]);
                case "field" -> tokens.length < 5 ? null : new Target(Kind.FIELD, tokens[2], tokens[3], tokens[4]);
                default -> null;
            };
            Change change = target == null ? null : switch (keyword) {
                case "accessible" -> new Change(Access.PUBLIC, false, false, transitive);
                case "extendable" -> target.kind() == Kind.FIELD ? null
                        : new Change(target.kind() == Kind.CLASS ? Access.PUBLIC : Access.PROTECTED, true, false, transitive);
                case "mutable" -> target.kind() == Kind.FIELD ? new Change(Access.NONE, true, false, transitive) : null;
                default -> null;
            };
            if (change == null) {
                warnings.add(where + ": cannot read '" + entry + "'");
            } else {
                add(target, change, where);
            }
        }
    }

    /** Adds an access transformer's lines; {@code file} names it in warnings. */
    void addAccessTransformer(List<String> lines, String file) {
        for (int i = 0; i < lines.size(); i++) {
            String entry = entry(lines.get(i));
            String where = file + ":" + (i + 1);
            if (entry.isEmpty()) {
                continue;
            }
            String[] tokens = entry.split("\\s+");
            Matcher modifier = MODIFIER.matcher(tokens[0].toLowerCase(Locale.ROOT));
            if (tokens.length < 2 || !modifier.matches()) {
                warnings.add(where + ": cannot read '" + entry + "'");
                continue;
            }
            Access access = switch (modifier.group(1)) {
                case "public" -> Access.PUBLIC;
                case "protected" -> Access.PROTECTED;
                default -> Access.NONE;
            };
            String finality = modifier.group(2);
            Change change = new Change(access, "-f".equals(finality), "+f".equals(finality), false);
            String owner = tokens[1].replace('.', '/');
            String member = tokens.length > 2 ? tokens[2] : null;
            if (member == null) {
                add(new Target(Kind.CLASS, owner, null, null), change, where);
            } else if (member.equals("*") || member.equals("*()")) {
                wildcards.add(new Wildcard(member.equals("*") ? Kind.FIELD : Kind.METHOD, owner, change, where));
            } else if (member.indexOf('(') > 0) {
                int parenthesis = member.indexOf('(');
                add(new Target(Kind.METHOD, owner, member.substring(0, parenthesis), member.substring(parenthesis)), change, where);
            } else {
                add(new Target(Kind.FIELD, owner, member, null), change, where);
            }
        }
    }

    private void add(Target target, Change change, String where) {
        changes.merge(target, change, Change::merge);
        sources.putIfAbsent(target, where);
    }

    /** As an access widener (or class tweaker, if a source was one); {@code comment} goes below the header. */
    String toAccessWidener(Members members, String comment) {
        Set<String> lines = new LinkedHashSet<>();
        Map<Target, Change> all = new LinkedHashMap<>();
        changes.forEach((target, change) -> {
            if (target.kind() == Kind.FIELD && target.descriptor() == null) {
                List<Member> fields = members.fields(target.owner());
                List<Member> named = fields == null ? List.of() : fields.stream().filter(f -> f.name().equals(target.name())).toList();
                if (named.isEmpty()) {
                    warnings.add(sources.get(target) + ": no field " + display(target) + " in Minecraft's classes, so it is left out of the access widener");
                }
                named.forEach(field -> all.merge(new Target(Kind.FIELD, target.owner(), field.name(), field.descriptor()), change, Change::merge));
            } else {
                all.merge(target, change, Change::merge);
            }
        });
        for (Wildcard wildcard : wildcards) {
            List<Member> found = wildcard.kind() == Kind.FIELD ? members.fields(wildcard.owner()) : members.methods(wildcard.owner());
            if (found == null) {
                warnings.add(wildcard.where() + ": no class " + wildcard.owner().replace('/', '.') + " in Minecraft's classes, so it is left out of the access widener");
                continue;
            }
            for (Member member : found) {
                if (!member.name().equals("<clinit>")) {
                    all.merge(new Target(wildcard.kind(), wildcard.owner(), member.name(), member.descriptor()), wildcard.change(), Change::merge);
                }
            }
        }
        all.forEach((target, change) -> {
            String prefix = change.transitive() ? "transitive-" : "";
            String what = switch (target.kind()) {
                case CLASS -> "class " + target.owner();
                case METHOD -> "method " + target.owner() + " " + target.name() + " " + target.descriptor();
                case FIELD -> "field " + target.owner() + " " + target.name() + " " + target.descriptor();
            };
            if (change.access() != Access.NONE && !(change.definalize() && change.access() == Access.PROTECTED && target.kind() == Kind.METHOD)) {
                lines.add(prefix + "accessible " + what);
            }
            if (change.definalize()) {
                lines.add(prefix + (target.kind() == Kind.FIELD ? "mutable " : "extendable ") + what);
            }
            if (change.makeFinal()) {
                warnings.add(where(target) + ": an access widener cannot make " + display(target) + " final (+f)");
            }
            if (change.access() == Access.NONE && !change.definalize() && !change.makeFinal()) {
                warnings.add(where(target) + ": an access widener has no default or private access, so " + display(target) + " is left out");
            }
        });
        lines.addAll(classTweakerOnly.values());
        return (header == null ? DEFAULT_HEADER : header) + "\n" + comment(comment) + String.join("\n", lines) + (lines.isEmpty() ? "" : "\n");
    }

    /** As an access transformer; {@code comment} goes on top. */
    String toAccessTransformer(String comment) {
        Set<String> lines = new LinkedHashSet<>();
        changes.forEach((target, change) -> {
            String modifier = modifier(change);
            if (modifier != null) {
                String owner = target.owner().replace('/', '.');
                lines.add(switch (target.kind()) {
                    case CLASS -> modifier + " " + owner;
                    case METHOD -> modifier + " " + owner + " " + target.name() + target.descriptor();
                    case FIELD -> modifier + " " + owner + " " + target.name();
                });
            }
        });
        for (Wildcard wildcard : wildcards) {
            String modifier = modifier(wildcard.change());
            if (modifier != null) {
                lines.add(modifier + " " + wildcard.owner().replace('/', '.') + " " + (wildcard.kind() == Kind.FIELD ? "*" : "*()"));
            }
        }
        classTweakerOnly.forEach((where, entry) -> warnings.add(where + ": an access transformer has no form of '" + entry + "', so it is left out"));
        return comment(comment) + String.join("\n", lines) + (lines.isEmpty() ? "" : "\n");
    }

    /** {@code public}, {@code protected-f}, {@code private-f} (keeps the access, only drops final), ...; {@code null} for no change. */
    private static String modifier(Change change) {
        String access = switch (change.access()) {
            case PUBLIC -> "public";
            case PROTECTED -> "protected";
            case NONE -> change.definalize() || change.makeFinal() ? "private" : null;
        };
        return access == null ? null : access + (change.definalize() ? "-f" : change.makeFinal() ? "+f" : "");
    }

    private String where(Target target) {
        String where = sources.get(target);
        return where != null ? where : sources.entrySet().stream()
                .filter(e -> e.getKey().kind() == target.kind() && e.getKey().owner().equals(target.owner()) && e.getKey().name().equals(target.name()))
                .map(Map.Entry::getValue).findFirst().orElse(target.owner());
    }

    private static String display(Target target) {
        String owner = target.owner().replace('/', '.').replace('$', '.');
        return target.name() == null ? owner : owner + "." + target.name();
    }

    private static String comment(String comment) {
        return comment == null || comment.isEmpty() ? "" : "# " + comment + "\n";
    }

    /** A line without its comment and surrounding blanks. */
    private static String entry(String line) {
        int comment = line.indexOf('#');
        return (comment < 0 ? line : line.substring(0, comment)).strip();
    }
}
