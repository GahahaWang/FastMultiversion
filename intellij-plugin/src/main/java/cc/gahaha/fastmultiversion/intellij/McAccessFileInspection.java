package cc.gahaha.fastmultiversion.intellij;

import cc.gahaha.fastmultiversion.apidb.ApiClass;
import cc.gahaha.fastmultiversion.apidb.ApiClass.MemberKey;
import cc.gahaha.fastmultiversion.apidb.ApiClass.MemberKind;
import cc.gahaha.fastmultiversion.apidb.ApiClass.MemberShape;
import cc.gahaha.fastmultiversion.apidb.ApiClass.Variant;
import cc.gahaha.fastmultiversion.apidb.ApiDatabase;
import cc.gahaha.fastmultiversion.versioning.McVersion;
import cc.gahaha.fastmultiversion.versioning.VersionRange;
import cc.gahaha.fastmultiversion.versioning.VersionedLines;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Checks access wideners, class tweakers and access transformers against the API database, as the build applies
 * them: each entry's class, field or method must exist in every version that gets the line, which is every
 * version, or those of the line's {@code # @Mc.Is("...")} ({@link VersionedLines}). Loom's {@code check} fails on
 * an access widener or class tweaker entry whose target a version lacks (an error here); ModDevGradle checks
 * access transformers only with {@code validateAccessTransformers} (a warning). Access wideners are checked
 * against the Fabric projects' database, access transformers against the NeoForge projects'.
 */
public final class McAccessFileInspection extends LocalInspectionTool {
    public static final String SHORT_NAME = "McAccessFile";
    private static final McApiRangeInspection.Group GROUP = new McApiRangeInspection.Group(SHORT_NAME);

    /** What an entry is about: a class ({@code kind == null}), or a field or method of it (no descriptor: any with the name). */
    private record Target(String owner, @Nullable MemberKind kind, @Nullable String name, @Nullable String descriptor) {
        String describe() {
            String simple = owner.substring(owner.lastIndexOf('/') + 1).replace('$', '.');
            return kind == null ? "'" + owner.replace('/', '.').replace('$', '.') + "'"
                    : "'" + simple + "." + name + (kind == MemberKind.METHOD && descriptor != null ? descriptor : "") + "'";
        }
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        String name = holder.getFile().getName();
        MultiversionProject setup = MultiversionProject.getInstance(holder.getProject());
        MultiversionProject.State state = VersionedLines.isVersionedFile(name) ? setup.state() : null;
        if (state == null) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }
        boolean transformer = name.toLowerCase(Locale.ROOT).endsWith(".cfg");
        ApiDatabase db = setup.database(state, transformer ? "neoforge" : "fabric");
        return new PsiElementVisitor() {
            @Override
            public void visitFile(@NotNull PsiFile file) {
                new FileCheck(holder, file, db, transformer).run();
            }
        };
    }

    private record FileCheck(ProblemsHolder holder, PsiFile file, ApiDatabase db, boolean transformer) {
        /** Every line's range is checked, as the build reads them all; the entries also against their versions. */
        void run() {
            String text = file.getText();
            boolean header = !transformer; // the first entry of an access widener or class tweaker is its header
            for (int start = 0; start <= text.length(); ) {
                int end = text.indexOf('\n', start);
                end = end < 0 ? text.length() : end;
                String line = StringUtil.trimEnd(text.substring(start, end), "\r");
                String entry = (line.indexOf('#') < 0 ? line : line.substring(0, line.indexOf('#'))).strip();
                VersionedLines.Marker marker = VersionedLines.marker(line);
                if (!entry.isEmpty() && header) {
                    header = false;
                    if (marker != null) {
                        register(TextRange.create(start + marker.start(), start + marker.end()), "Every version needs the header: "
                                + "a build without it could not read the file.", ProblemHighlightType.GENERIC_ERROR);
                    }
                } else {
                    Long applies = marker == null ? Long.valueOf(db.allVersions()) : applies(marker, start);
                    if (applies != null && !entry.isEmpty()) {
                        check(line, entry, start, applies, marker != null);
                    }
                }
                start = end + 1;
            }
        }

        /** The versions in the range of {@code marker}; {@code null} (and reported) if it is not valid or matches none. */
        private @Nullable Long applies(VersionedLines.Marker marker, int offset) {
            TextRange rangeText = TextRange.create(offset + marker.rangeStart(), offset + marker.rangeEnd());
            long applies = 0;
            try {
                VersionRange range = VersionRange.parse(marker.range());
                List<McVersion> versions = db.versions();
                for (int i = 0; i < versions.size(); i++) {
                    if (range.contains(versions.get(i))) {
                        applies |= 1L << i;
                    }
                }
            } catch (IllegalArgumentException e) {
                register(rangeText, StringUtil.capitalize(e.getMessage()) + ".", ProblemHighlightType.GENERIC_ERROR);
                return null;
            }
            if (applies == 0) {
                String names = db.versions().stream().map(Object::toString).collect(Collectors.joining(", "));
                register(rangeText, "'" + marker.range() + "' matches none of the versions this project builds (" + names
                        + "), so no build gets this line.", ProblemHighlightType.WARNING);
                return null;
            }
            return applies;
        }

        private void check(String line, String entry, int offset, long applies, boolean ranged) {
            Target target = transformer ? accessTransformer(entry) : accessWidener(entry);
            Long present = target == null ? null : present(target);
            if (present == null || (applies & ~present) == 0) {
                return;
            }
            int start = offset + line.indexOf(entry);
            TextRange at = TextRange.create(start, start + entry.length());
            ProblemHighlightType type = transformer ? ProblemHighlightType.WARNING : ProblemHighlightType.GENERIC_ERROR;
            if (present == 0) {
                register(at, target.describe() + " does not exist in any version this project builds.", type);
                return;
            }
            String fixed = db.range((applies & present) != 0 ? applies & present : present);
            register(at, target.describe() + " only exists in " + db.range(present) + ", but this line applies to " + db.range(applies)
                    + ". Give it # @Mc.Is(\"" + fixed + "\").", type, new SetRangeFix(fixed, ranged));
        }

        /**
         * {@code <access> class <class>}, {@code <access> method <class> <name> <descriptor>}, {@code <access> field <class>
         * <name> <descriptor>}, {@code inject-interface <class> <interface>}, {@code extend-enum <class> ...}; each may
         * start with {@code transitive-}. {@code null} for anything else (Loom reports what it cannot read).
         */
        private static @Nullable Target accessWidener(String entry) {
            String[] tokens = entry.split("\\s+");
            String kind = tokens[0].startsWith("transitive-") ? tokens[0].substring("transitive-".length()) : tokens[0];
            if ((kind.equals("inject-interface") || kind.equals("extend-enum")) && tokens.length >= 2) {
                return new Target(tokens[1], null, null, null);
            }
            if (tokens.length < 3) {
                return null;
            }
            return switch (tokens[1]) {
                case "class" -> new Target(tokens[2], null, null, null);
                case "method" -> tokens.length < 5 ? null : new Target(tokens[2], MemberKind.METHOD, tokens[3], tokens[4]);
                case "field" -> tokens.length < 5 ? null : new Target(tokens[2], MemberKind.FIELD, tokens[3], tokens[4]);
                default -> null;
            };
        }

        /**
         * {@code <access> <class>}, {@code <access> <class> <field>}, {@code <access> <class> <method>(<descriptor>)} with
         * the class written with dots ({@code net.minecraft.Outer$Inner}); {@code *} and {@code *()} (every field or
         * method) only need the class.
         */
        private static @Nullable Target accessTransformer(String entry) {
            String[] tokens = entry.split("\\s+");
            if (tokens.length < 2) {
                return null;
            }
            String owner = tokens[1].replace('.', '/');
            if (tokens.length == 2 || tokens[2].startsWith("*")) {
                return new Target(owner, null, null, null);
            }
            String member = tokens[2];
            int parenthesis = member.indexOf('(');
            return parenthesis < 0 ? new Target(owner, MemberKind.FIELD, member, null)
                    : new Target(owner, MemberKind.METHOD, member.substring(0, parenthesis), member.substring(parenthesis));
        }

        /** The versions that have the target; {@code null} for a JDK class, which every version has. */
        private @Nullable Long present(Target target) {
            ApiClass c = db.get(target.owner());
            if (c == null) {
                return 0L;
            }
            if (c.platform()) {
                return null;
            }
            if (target.kind() == null) {
                return c.versions();
            }
            long versions = 0;
            Map<MemberKey, List<Variant<MemberShape>>> members = target.kind() == MemberKind.FIELD ? c.fields() : c.methods();
            for (Map.Entry<MemberKey, List<Variant<MemberShape>>> member : members.entrySet()) {
                if (member.getKey().name().equals(target.name())
                        && (target.descriptor() == null || member.getKey().descriptor().equals(target.descriptor()))) {
                    for (Variant<MemberShape> variant : member.getValue()) {
                        versions |= variant.versions();
                    }
                }
            }
            return versions;
        }

        private void register(TextRange range, String message, ProblemHighlightType type, LocalQuickFix... fixes) {
            PsiElement at = file.findElementAt(range.getStartOffset());
            while (at != null && !(at instanceof PsiFile) && !at.getTextRange().contains(range)) {
                at = at.getParent();
            }
            if (at == null) {
                return;
            }
            ProblemDescriptor descriptor = holder.getManager().createProblemDescriptor(at, range.shiftLeft(at.getTextRange().getStartOffset()),
                    message, type, holder.isOnTheFly(), fixes);
            descriptor.setProblemGroup(GROUP);
            holder.registerProblem(descriptor);
        }
    }

    /** Gives the line the versions that have its target: adds {@code # @Mc.Is("...")}, or changes its range. */
    private static final class SetRangeFix implements LocalQuickFix {
        private final String range;
        private final boolean change;

        SetRangeFix(String range, boolean change) {
            this.range = range;
            this.change = change;
        }

        @Override
        public @NotNull String getName() {
            return (change ? "Change to" : "Add") + " # @Mc.Is(\"" + range + "\")";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Set the versions of the line";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            Document document = element == null ? null : element.getContainingFile().getViewProvider().getDocument();
            if (document == null) {
                return;
            }
            TextRange inElement = descriptor.getTextRangeInElement();
            int offset = element.getTextRange().getStartOffset() + (inElement == null ? 0 : inElement.getStartOffset());
            int line = document.getLineNumber(offset);
            int lineStart = document.getLineStartOffset(line);
            String text = document.getText(TextRange.create(lineStart, document.getLineEndOffset(line)));
            VersionedLines.Marker marker = VersionedLines.marker(text);
            if (marker != null) {
                document.replaceString(lineStart + marker.rangeStart(), lineStart + marker.rangeEnd(), range);
            } else {
                int end = lineStart + text.stripTrailing().length();
                document.replaceString(end, lineStart + text.length(), (text.indexOf('#') >= 0 ? " " : "  # ") + "@Mc.Is(\"" + range + "\")");
            }
            PsiDocumentManager.getInstance(project).commitDocument(document);
        }
    }
}
