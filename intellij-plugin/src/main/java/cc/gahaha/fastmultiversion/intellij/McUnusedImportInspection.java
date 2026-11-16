package cc.gahaha.fastmultiversion.intellij;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiImportStatementBase;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * "Unused import statement" on the imports that the IDE does not judge, because the active version cannot use
 * them: of a class the active version does not have, and of a class whose simple name an earlier import already
 * takes there ({@link InactiveCodeHighlightFilter} hides the IDE's errors and warnings on both). Such an import
 * is used if a name resolves through it in the {@link VersionViews view} of another version that has the class;
 * if none does, no build uses it.
 */
public final class McUnusedImportInspection extends AbstractBaseJavaLocalInspectionTool {
    public static final String SHORT_NAME = "McUnusedImport";
    private static final McApiRangeInspection.Group GROUP = new McApiRangeInspection.Group(SHORT_NAME);

    /** A check of the whole file (and so not one that {@link McApiRangeInspection} runs on views). */
    @Override
    public boolean runForWholeFile() {
        return true;
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        MultiversionProject.State state = MultiversionProject.getInstance(holder.getProject()).state();
        if (state == null || !(holder.getFile() instanceof PsiJavaFile file)) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }
        return new JavaElementVisitor() {
            @Override
            public void visitImportList(@NotNull PsiImportList list) {
                for (PsiImportStatement statement : unusedImports(state, file)) {
                    ProblemDescriptor descriptor = holder.getManager().createProblemDescriptor(statement, "Unused import statement",
                            isOnTheFly, new LocalQuickFix[]{new RemoveImportFix()}, ProblemHighlightType.LIKE_UNUSED_SYMBOL);
                    descriptor.setProblemGroup(GROUP);
                    holder.registerProblem(descriptor);
                }
            }
        };
    }

    /** The imports of {@code file} that the IDE does not judge and that no other version uses either. */
    static List<PsiImportStatement> unusedImports(MultiversionProject.State state, PsiJavaFile file) {
        PsiImportList imports = file.getImportList();
        if (imports == null) {
            return List.of();
        }
        // Each such import, with the versions (other than the active one) that have its class.
        Map<PsiImportStatement, Long> unused = new LinkedHashMap<>();
        long versions = 0;
        for (PsiImportStatement statement : imports.getImportStatements()) {
            String name = statement.isOnDemand() ? null : statement.getQualifiedName();
            String internal = name == null ? null : OtherVersions.apiClass(state.db(), name);
            if (internal != null && (statement.resolve() == null || InactiveCodeHighlightFilter.isShadowedByAnEarlierImport(statement, state))) {
                long in = state.db().get(internal).versions() & ~state.activeBit();
                unused.put(statement, in);
                versions |= in;
            }
        }
        for (long rest = versions; rest != 0 && !unused.isEmpty(); rest &= rest - 1) {
            int version = Long.numberOfTrailingZeros(rest);
            List<PsiImportStatement> candidates = unused.entrySet().stream()
                    .filter(entry -> (entry.getValue() & (1L << version)) != 0).map(Map.Entry::getKey).toList();
            if (candidates.isEmpty()) {
                continue;
            }
            PsiJavaFile view = VersionViews.of(state, file, version);
            if (view == null) {
                candidates.forEach(unused::remove); // without the version's module there is no telling: not reported
                continue;
            }
            Map<PsiImportStatementBase, PsiImportStatement> inView = new HashMap<>();
            for (PsiImportStatement candidate : candidates) {
                PsiImportStatement counterpart = VersionViews.counterpart(candidate, view);
                if (counterpart != null) {
                    inView.put(counterpart, candidate);
                }
            }
            for (PsiImportStatementBase used : resolvedThrough(view, inView.keySet())) {
                unused.remove(inView.get(used));
            }
        }
        return new ArrayList<>(unused.keySet());
    }

    /** Which of the imports {@code wanted} a name in {@code view} resolves through. */
    private static Set<PsiImportStatementBase> resolvedThrough(PsiJavaFile view, Set<PsiImportStatementBase> wanted) {
        Set<PsiImportStatementBase> found = new HashSet<>();
        if (wanted.isEmpty()) {
            return found;
        }
        view.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (element instanceof PsiImportList) {
                    return;
                }
                // A class import is used by a simple name (a call's name is a method's, never a class's).
                if (element instanceof PsiJavaCodeReferenceElement reference && !reference.isQualified()
                        && !(reference.getParent() instanceof PsiMethodCallExpression)
                        && reference.advancedResolve(false).getCurrentFileResolveScope() instanceof PsiImportStatementBase statement
                        && wanted.contains(statement) && found.add(statement) && found.size() == wanted.size()) {
                    stopWalking();
                    return;
                }
                super.visitElement(element);
            }
        });
        return found;
    }

    private static final class RemoveImportFix implements LocalQuickFix {
        @Override
        public @NotNull String getFamilyName() {
            return "Remove unused import";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            if (descriptor.getPsiElement() instanceof PsiImportStatementBase statement) {
                statement.delete();
            }
        }
    }
}
