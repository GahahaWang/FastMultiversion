package cc.gahaha.fastmultiversion.intellij;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightVisitor;
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder;
import com.intellij.codeInsight.daemon.impl.analysis.HighlightVisitorImpl;
import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.InspectionEngine;
import com.intellij.codeInspection.LocalQuickFixOnPsiElement;
import com.intellij.codeInspection.QuickFix;
import com.intellij.codeInspection.ex.InspectionProfileImpl;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper;
import com.intellij.codeInspection.ex.Tools;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.lang.annotation.ProblemGroup;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import cc.gahaha.fastmultiversion.versioning.VersionRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiCompiledElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethodReferenceExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reports Minecraft classes and members used in code that also runs in versions without them (or where
 * they have another form), the same check the javac plugin runs on a build. See {@link RangeCheck}.
 *
 * <p>Code that does not run in the active version is checked in a {@link VersionViews view} of the newest
 * version it runs in: references that do not resolve there are reported (as the IDE would report them in
 * that version, with fixes that import that version's classes), and the range check runs against that
 * version. Without an IDE module for the version (e.g. before a Gradle sync) such code is not checked.
 *
 * <p>A name that does not resolve but exists in other versions (e.g. a class imported from another
 * version's package) is reported with the versions that have it, in place of the IDE's plain error
 * ({@link InactiveCodeHighlightFilter} hides that one).
 */
public final class McApiRangeInspection extends AbstractBaseJavaLocalInspectionTool {
    public static final String SHORT_NAME = "McApiRange";
    private static final Logger LOG = Logger.getInstance(McApiRangeInspection.class);
    private static final int MAX_IMPORT_FIXES = 5;

    /**
     * Marks the problems of the plugin's inspections for {@link InactiveCodeHighlightFilter}, which keeps them
     * where it hides the IDE's own (the IDE sets an info's tool id only after the filters ran). Its name must be
     * the inspection's short name: the IDE looks the inspection up by it.
     */
    record Group(String shortName) implements ProblemGroup {
        static final Group INSTANCE = new Group(SHORT_NAME);

        @Override
        public String getProblemName() {
            return shortName;
        }
    }

    static void register(ProblemsHolder holder, PsiElement at, String message, ProblemHighlightType type, LocalQuickFix... fixes) {
        register(holder, at, null, message, type, fixes);
    }

    static void register(ProblemsHolder holder, PsiElement at, @Nullable TextRange rangeInElement, String message,
                         ProblemHighlightType type, LocalQuickFix... fixes) {
        ProblemDescriptor descriptor = holder.getManager().createProblemDescriptor(at, rangeInElement, message, type, holder.isOnTheFly(), fixes);
        descriptor.setProblemGroup(Group.INSTANCE);
        holder.registerProblem(descriptor);
    }

    private static final Key<FileCheck> FILE_CHECK = Key.create("multiversion.fileCheck");

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly, @NotNull LocalInspectionToolSession session) {
        MultiversionProject.State state = MultiversionProject.getInstance(holder.getProject()).state();
        if (state == null || !(holder.getFile() instanceof PsiJavaFile file)) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }
        FileCheck check = new FileCheck(state, file, holder);
        session.putUserData(FILE_CHECK, check);
        return new JavaElementVisitor() {
            @Override
            public void visitReferenceElement(@NotNull PsiJavaCodeReferenceElement reference) {
                if (!(reference.getParent() instanceof PsiNewExpression)) { // checked with its constructor
                    check.reference(reference);
                }
            }

            @Override
            public void visitReferenceExpression(@NotNull PsiReferenceExpression expression) {
                check.reference(expression);
            }

            @Override
            public void visitMethodReferenceExpression(@NotNull PsiMethodReferenceExpression expression) {
                check.reference(expression);
            }

            @Override
            public void visitNewExpression(@NotNull PsiNewExpression expression) {
                check.newExpression(expression);
            }

            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                check.overrides(method);
            }

            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
                PsiExpression range = Guards.mcIsRange(call);
                if (range != null) {
                    check.range(range, "the range passed to Mc.is", "it is false in every build");
                }
            }

            @Override
            public void visitAnnotation(@NotNull PsiAnnotation annotation) {
                PsiAnnotationMemberValue range = Guards.annotationRange(annotation);
                if (range != null) {
                    check.range(range, "the range in @Mc.Is", "the declaration is in no build");
                }
            }
        };
    }

    /** Once every element was checked: the IDE's own Java checks of the code that does not run in the active version. */
    @Override
    public void inspectionFinished(@NotNull LocalInspectionToolSession session, @NotNull ProblemsHolder problemsHolder) {
        FileCheck check = session.getUserData(FILE_CHECK);
        if (check != null) {
            check.inactiveCode();
        }
    }

    /** The checks of one file: in the file itself for code that runs in the active version, in views for the rest. */
    private static final class FileCheck {
        private final MultiversionProject.State state;
        private final PsiJavaFile file;
        private final ProblemsHolder holder;
        private final Guards guards;
        private final Context active;
        private final Map<Integer, Optional<Context>> views = new HashMap<>();

        FileCheck(MultiversionProject.State state, PsiJavaFile file, ProblemsHolder holder) {
            this.state = state;
            this.file = file;
            this.holder = holder;
            this.guards = new Guards(state.db());
            this.active = new Context(file, state.active(), "", RangeCheck.to(holder), false);
        }

        /**
         * A range of {@code Mc.is} or {@code @Mc.Is}, checked as the build reads it: a string literal or a
         * {@code String} constant of this file, valid, and matching at least one version the project builds.
         */
        void range(PsiElement range, String what, String unused) {
            String text = Guards.rangeText(range);
            if (text == null) {
                register(holder, range, StringUtil.capitalize(what) + " must be a string literal or a String constant "
                        + "declared in this file, so that the build knows it before compiling.", ProblemHighlightType.GENERIC_ERROR);
                return;
            }
            try {
                VersionRange.parse(text);
            } catch (IllegalArgumentException e) {
                register(holder, range, StringUtil.capitalize(e.getMessage()) + ".", ProblemHighlightType.GENERIC_ERROR);
                return;
            }
            Long versions = guards.versions(text);
            if (versions != null && versions == 0) {
                String all = state.db().versions().stream().map(Object::toString).collect(Collectors.joining(", "));
                register(holder, range, "'" + text + "' matches none of the versions this project builds (" + all + "), so "
                        + unused + ".", ProblemHighlightType.WARNING);
            }
        }

        void reference(PsiJavaCodeReferenceElement reference) {
            long guard = guards.of(reference);
            Context context = context(guard);
            PsiJavaCodeReferenceElement inContext = context == null ? null : context.find(reference);
            if (inContext != null && !context.unresolved(reference, inContext, guard)) {
                context.check.reference(inContext, guard);
                if (!context.view) {
                    declarationVersions(reference, reference.resolve(), guard);
                }
            }
        }

        /**
         * A declaration of the mod itself that does not exist in every version the code using it runs in, e.g.
         * an {@code @Mc.Is("[26.1,26.2)")} method called from code that also runs in 26.2. (In views, the
         * declarations a version does not have are dropped, so such uses do not resolve there.)
         */
        private void declarationVersions(PsiJavaCodeReferenceElement reference, @Nullable PsiElement target, long guard) {
            if (!(target instanceof PsiMember member) || member instanceof PsiCompiledElement
                    || !(member.getContainingFile() instanceof PsiJavaFile) || member instanceof PsiTypeParameter) {
                return;
            }
            long present = guards.of(member);
            if ((guard & ~present) != 0) {
                active.check.reportDeclaration(nameOf(reference), RangeCheck.describe(member), present, guard);
            }
        }

        void newExpression(PsiNewExpression expression) {
            long guard = guards.of(expression);
            Context context = context(guard);
            PsiNewExpression inContext = context == null ? null : context.find(expression);
            PsiJavaCodeReferenceElement classReference = expression.getClassReference();
            if (inContext != null && classReference != null && inContext.getClassReference() != null
                    && !context.unresolved(classReference, inContext.getClassReference(), guard)) {
                context.check.newExpression(inContext, guard);
                if (!context.view) {
                    declarationVersions(classReference, classReference.resolve(), guard);
                }
            }
        }

        /**
         * Runs the IDE's Java error checks (types, flow, ...) and the enabled inspections on the code that does
         * not run in the active version, each piece in the view of the newest version it runs in, and reports
         * their problems with that version. The IDE's own checks of that code use the active version, so its
         * problems there are hidden. Unresolved names are left to {@link Context#unresolved}, which says more.
         */
        void inactiveCode() {
            Set<Integer> versions = new TreeSet<>();
            guards.inactiveVersions(file, guards.all(), state.activeBit(), versions);
            for (int version : versions) {
                Context context = context(1L << version);
                if (context != null) {
                    try {
                        List<PsiElement> elements = new ArrayList<>();
                        guards.inactiveElements(context.resolvedIn, guards.all(), version, state.activeBit(), elements);
                        javaErrors(context, elements);
                        inspections(context, elements);
                    } catch (ProcessCanceledException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        // The IDE's Java checks are not made for files outside the editor; keep the other checks.
                        LOG.warn("cannot check " + file.getName() + " in Minecraft " + state.name(version), e);
                    }
                }
            }
        }

        private void javaErrors(Context context, List<PsiElement> elements) {
            PsiJavaFile view = context.resolvedIn;
            HighlightVisitor visitor = javaHighlightVisitor(view);
            if (visitor == null) {
                return;
            }
            HighlightInfoHolder infos = new HighlightInfoHolder(view);
            visitor.analyze(view, false, infos, () -> elements.forEach(visitor::visit));
            for (int i = 0; i < infos.size(); i++) {
                HighlightInfo info = infos.get(i);
                TextRange range = TextRange.create(info.getStartOffset(), info.getEndOffset());
                if (info.getSeverity() != HighlightSeverity.ERROR || info.getDescription() == null
                        || InactiveCodeHighlightFilter.isSyntaxError(info, view) || isUnresolvedName(view, range)) {
                    continue;
                }
                PsiElement at = file.findElementAt(range.getStartOffset());
                while (at != null && !(at instanceof PsiFile) && !at.getTextRange().contains(range)) {
                    at = at.getParent();
                }
                if (at != null && !(at instanceof PsiFile)) {
                    register(holder, at, range.shiftLeft(at.getTextRange().getStartOffset()), context.prefix + info.getDescription(),
                            ProblemHighlightType.GENERIC_ERROR);
                }
            }
        }

        /**
         * Runs the inspections enabled for the file on {@code elements} of a view and reports what they find, with
         * the severity configured for them. Left out: this inspection, inspections that need the whole file, and
         * those below warnings, which the IDE shows for the file itself.
         */
        private void inspections(Context context, List<PsiElement> elements) {
            if (elements.isEmpty()) {
                return;
            }
            InspectionProfileImpl profile = InspectionProjectProfileManager.getInstance(file.getProject()).getCurrentProfile();
            Map<String, HighlightSeverity> severities = new HashMap<>();
            List<LocalInspectionToolWrapper> tools = new ArrayList<>();
            for (Tools enabled : profile.getAllEnabledInspectionTools(file.getProject())) {
                InspectionToolWrapper<?, ?> tool = enabled.getEnabledTool(file);
                if (!(tool instanceof LocalInspectionToolWrapper local) || SHORT_NAME.equals(tool.getShortName())
                        || local.runForWholeFile() || !tool.isApplicable(JavaLanguage.INSTANCE)) {
                    continue;
                }
                HighlightSeverity severity = profile.getErrorLevel(tool.getDisplayKey(), file).getSeverity();
                if (severity.compareTo(HighlightSeverity.WARNING) >= 0) {
                    tools.add(local);
                    severities.put(tool.getShortName(), severity);
                }
            }
            if (tools.isEmpty()) {
                return;
            }
            Map<LocalInspectionToolWrapper, List<ProblemDescriptor>> found = InspectionEngine.inspectElements(
                    tools, context.resolvedIn, context.resolvedIn.getTextRange(), true, holder.isOnTheFly(), elements, (tool, problem) -> true);
            found.forEach((tool, problems) -> {
                for (ProblemDescriptor problem : problems) {
                    report(context, problem, severities.getOrDefault(tool.getShortName(), HighlightSeverity.WARNING));
                }
            });
        }

        /** Reports a problem found in a view on the same element of the file, with the fixes that work on that. */
        private void report(Context context, ProblemDescriptor problem, HighlightSeverity severity) {
            PsiElement inView = problem.getPsiElement();
            if (inView == null || problem.getDescriptionTemplate() == null) {
                return;
            }
            PsiElement at = VersionViews.counterpart(inView, file);
            TextRange rangeInElement = problem.getTextRangeInElement();
            List<LocalQuickFix> fixes = new ArrayList<>();
            if (at != null && problem.getFixes() != null) {
                for (QuickFix<?> fix : problem.getFixes()) {
                    // A fix that keeps the view's elements would change the view, not the file.
                    if (fix instanceof LocalQuickFix local && !(fix instanceof LocalQuickFixOnPsiElement)) {
                        fixes.add(local);
                    }
                }
            } else {
                TextRange range = rangeInElement == null ? inView.getTextRange() : rangeInElement.shiftRight(inView.getTextRange().getStartOffset());
                at = file.findElementAt(range.getStartOffset());
                while (at != null && !(at instanceof PsiFile) && !at.getTextRange().contains(range)) {
                    at = at.getParent();
                }
                if (at == null || at instanceof PsiFile) {
                    return;
                }
                rangeInElement = range.shiftLeft(at.getTextRange().getStartOffset());
            }
            HighlightSeverity shown = shownSeverity(problem.getHighlightType(), severity);
            if (shown == null) {
                return; // not highlighted (only offers a fix) or below warnings: the IDE shows it for the file itself
            }
            ProblemHighlightType type = shown.compareTo(HighlightSeverity.ERROR) >= 0 ? ProblemHighlightType.GENERIC_ERROR : ProblemHighlightType.WARNING;
            register(holder, at, rangeInElement, context.prefix + problem.getDescriptionTemplate(), type, fixes.toArray(LocalQuickFix[]::new));
        }

        /**
         * How the IDE shows a problem of this type from an inspection configured with {@code severity}: as an
         * error or a warning, or {@code null} for less (weak warnings, and problems that only offer a fix).
         */
        private static @Nullable HighlightSeverity shownSeverity(ProblemHighlightType type, HighlightSeverity severity) {
            return switch (type) {
                case GENERIC_ERROR, ERROR, LIKE_UNKNOWN_SYMBOL -> HighlightSeverity.ERROR;
                case WARNING -> HighlightSeverity.WARNING;
                case GENERIC_ERROR_OR_WARNING, LIKE_DEPRECATED, LIKE_MARKED_FOR_REMOVAL, LIKE_UNUSED_SYMBOL ->
                        severity.compareTo(HighlightSeverity.ERROR) >= 0 ? HighlightSeverity.ERROR
                                : severity.compareTo(HighlightSeverity.WARNING) >= 0 ? HighlightSeverity.WARNING : null;
                default -> null;
            };
        }

        /** Whether {@code range} starts at the name of an unresolved reference, which {@link Context#unresolved} reports. */
        private static boolean isUnresolvedName(PsiJavaFile view, TextRange range) {
            PsiElement at = view.findElementAt(range.getStartOffset());
            return at != null && at.getParent() instanceof PsiJavaCodeReferenceElement reference
                    && reference.getReferenceNameElement() == at && reference.multiResolve(false).length == 0;
        }

        private static @Nullable HighlightVisitor javaHighlightVisitor(PsiJavaFile file) {
            for (HighlightVisitor visitor : HighlightVisitor.EP_HIGHLIGHT_VISITOR.getExtensionList(file.getProject())) {
                if (visitor instanceof HighlightVisitorImpl && visitor.suitableForFile(file)) {
                    return visitor.clone();
                }
            }
            return null;
        }

        void overrides(PsiMethod method) {
            long guard = guards.of(method);
            Context context = context(guard);
            PsiMethod inContext = context == null ? null : context.find(method);
            if (inContext != null) {
                context.check.overrides(inContext, guard);
            }
        }

        /**
         * Where code with {@code guard} is resolved: the file itself if it runs in the active version, else
         * the view of the newest version it runs in; {@code null} for dead code or without a module for it.
         */
        private @Nullable Context context(long guard) {
            if ((guard & state.activeBit()) != 0) {
                return active;
            }
            if (guard == 0) {
                return null;
            }
            int version = MultiversionProject.State.newest(guard);
            return views.computeIfAbsent(version, v -> {
                PsiJavaFile view = VersionViews.of(state, file, v);
                if (view == null) {
                    return Optional.empty();
                }
                String prefix = "[" + state.name(v) + "] ";
                RangeCheck.Reporter toOriginal = (at, message, type, fixes) -> {
                    PsiElement original = VersionViews.counterpart(at, file);
                    if (original != null) {
                        register(holder, original, prefix + message, type, fixes);
                    }
                };
                return Optional.of(new Context(view, v, prefix, toOriginal, true));
            }).orElse(null);
        }

        /** The file or a view, with the version it is resolved in. */
        private final class Context {
            final PsiJavaFile resolvedIn;
            final int version;
            final String prefix;
            final RangeCheck check;
            final boolean view;

            Context(PsiJavaFile resolvedIn, int version, String prefix, RangeCheck.Reporter reporter, boolean view) {
                this.resolvedIn = resolvedIn;
                this.version = version;
                this.prefix = prefix;
                this.check = new RangeCheck(state.db(), version, reporter);
                this.view = view;
            }

            <T extends PsiElement> @Nullable T find(T original) {
                return view ? VersionViews.counterpart(original, resolvedIn) : original;
            }

            /** Reports {@code original} if it resolves to nothing here (as {@code inContext}); returns whether it did. */
            boolean unresolved(PsiJavaCodeReferenceElement original, PsiJavaCodeReferenceElement inContext, long guard) {
                if (!OtherVersions.isUnresolved(inContext)) {
                    return false;
                }
                String message = unresolvedMessage(inContext);
                String shadowing = view ? null : OtherVersions.shadowingImport(inContext, state.db()); // views drop such imports
                if (shadowing != null) {
                    register(holder, nameOf(original), message + ": the IDE resolves it through the first import of that name, '"
                                    + shadowing + "', which this version does not have. Import the packages on demand instead.",
                            ProblemHighlightType.LIKE_UNKNOWN_SYMBOL, new OnDemandImportsFix(inContext.getReferenceName()));
                    return true;
                }
                OtherVersions.Found found = OtherVersions.find(inContext, state.db(), version);
                if (found != null) {
                    check.reportMissing(nameOf(inContext), found.what(), found.versions(), guard, message);
                    return true;
                }
                if (!view) {
                    return false; // the IDE's own error, with its fixes
                }
                register(holder, nameOf(original), prefix + message, ProblemHighlightType.LIKE_UNKNOWN_SYMBOL, importFixes(inContext));
                return true;
            }

            /** Fixes that import a class of this version with the reference's name. */
            private LocalQuickFix[] importFixes(PsiJavaCodeReferenceElement reference) {
                String name = reference.getReferenceName();
                GlobalSearchScope scope = resolvedIn.getViewProvider().getVirtualFile().getUserData(VersionViews.SCOPE);
                if (name == null || scope == null || reference.getQualifier() != null || reference.getParent() instanceof PsiMethodCallExpression) {
                    return LocalQuickFix.EMPTY_ARRAY;
                }
                return Arrays.stream(PsiShortNamesCache.getInstance(resolvedIn.getProject()).getClassesByName(name, scope))
                        .filter(c -> c.getQualifiedName() != null && c.getContainingFile() != resolvedIn && isImportable(c))
                        .map(PsiClass::getQualifiedName)
                        .filter(Objects::nonNull)
                        .distinct()
                        .sorted(Comparator.naturalOrder())
                        .limit(MAX_IMPORT_FIXES)
                        .map(qualifiedName -> (LocalQuickFix) new ImportClassFix(qualifiedName, state.name(version)))
                        .toArray(LocalQuickFix[]::new);
            }

            private boolean isImportable(PsiClass c) {
                if (c.hasModifierProperty(PsiModifier.PUBLIC)) {
                    return true;
                }
                String qualifiedName = Objects.requireNonNull(c.getQualifiedName());
                return !c.hasModifierProperty(PsiModifier.PRIVATE)
                        && qualifiedName.equals(resolvedIn.getPackageName() + "." + c.getName());
            }
        }
    }

    private static PsiElement nameOf(PsiJavaCodeReferenceElement reference) {
        return reference.getReferenceNameElement() != null ? reference.getReferenceNameElement() : reference;
    }

    /** As the IDE says it: "Cannot resolve method 'x' in 'Owner'" or "Cannot resolve symbol 'x'". */
    static String unresolvedMessage(PsiJavaCodeReferenceElement reference) {
        String name = reference.getReferenceName();
        if (!(reference.getParent() instanceof PsiMethodCallExpression)) {
            return "Cannot resolve symbol '" + name + "'";
        }
        String owner = null;
        if (reference instanceof PsiReferenceExpression expression && expression.getQualifierExpression() != null) {
            PsiExpression qualifier = expression.getQualifierExpression();
            if (qualifier instanceof PsiReferenceExpression q && q.resolve() instanceof PsiClass c) {
                owner = c.getName();
            } else if (qualifier.getType() instanceof PsiClassType type) {
                owner = type.getClassName();
            }
        } else {
            PsiClass c = PsiTreeUtil.getParentOfType(reference, PsiClass.class);
            owner = c == null ? null : c.getName();
        }
        return "Cannot resolve method '" + name + "'" + (owner == null ? "" : " in '" + owner + "'");
    }
}
