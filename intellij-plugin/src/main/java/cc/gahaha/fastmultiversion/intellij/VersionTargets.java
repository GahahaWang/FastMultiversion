package cc.gahaha.fastmultiversion.intellij;

import com.intellij.psi.JavaResolveResult;
import com.intellij.psi.PsiCall;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.ResolveResult;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * What a name refers to, for navigation and documentation, where the IDE's own answer is wrong: in code that
 * does not run in the active version, resolved in the {@link VersionViews view} of the newest version it runs
 * in; in active code, a call that the IDE finds ambiguous between {@link Twins twins} for several versions.
 */
final class VersionTargets {
    private VersionTargets() {
    }

    /**
     * The targets of the reference whose name is {@code leaf}, in the version the code runs in; empty if the
     * code runs in the active version (the IDE's own resolution is right there) or the version has no view.
     */
    static List<PsiElement> of(@Nullable PsiElement leaf) {
        if (!(leaf instanceof PsiIdentifier) || !(leaf.getContainingFile() instanceof PsiJavaFile file)
                || !(leaf.getParent() instanceof PsiJavaCodeReferenceElement reference) || reference.getReferenceNameElement() != leaf) {
            return List.of();
        }
        MultiversionProject.State state = MultiversionProject.getInstance(file.getProject()).state();
        if (state == null) {
            return List.of();
        }
        Guards guards = new Guards(state.db());
        long guard = guards.of(reference);
        if (guard != 0 && (guard & state.activeBit()) != 0) {
            // Active code: only a call that the IDE finds ambiguous between twins for several versions.
            PsiMethod method = reference.getParent() instanceof PsiCall call ? Twins.activeCandidate(call, guards, state.activeBit()) : null;
            return method == null ? List.of() : List.of(method);
        }
        if (guard == 0) {
            return List.of();
        }
        PsiJavaFile view = VersionViews.of(state, file.getOriginalFile() instanceof PsiJavaFile original ? original : file,
                MultiversionProject.State.newest(guard));
        PsiJavaCodeReferenceElement inView = view == null ? null : VersionViews.counterpart(reference, view);
        if (inView == null) {
            return List.of();
        }
        JavaResolveResult resolved = inView.advancedResolve(false);
        List<PsiElement> targets = new ArrayList<>();
        if (resolved.getElement() != null) {
            add(targets, resolved.getElement(), view, file);
        } else {
            for (ResolveResult candidate : inView.multiResolve(false)) {
                add(targets, candidate.getElement(), view, file);
            }
        }
        return targets;
    }

    /** Adds {@code target}, with declarations in the view itself replaced by those in the file. */
    private static void add(List<PsiElement> targets, @Nullable PsiElement target, PsiJavaFile view, PsiJavaFile file) {
        if (target != null && target.getContainingFile() == view) {
            target = VersionViews.counterpart(target, file);
        }
        if (target != null) {
            targets.add(target);
        }
    }

    /** The identifier at {@code offset}, or the one just before it (a caret at the end of a name). */
    static @Nullable PsiElement identifierAt(PsiElement file, int offset) {
        PsiElement at = file.findElementAt(offset);
        if (!(at instanceof PsiIdentifier) && offset > 0) {
            PsiElement before = file.findElementAt(offset - 1);
            if (before instanceof PsiIdentifier) {
                return before;
            }
        }
        return at;
    }
}
