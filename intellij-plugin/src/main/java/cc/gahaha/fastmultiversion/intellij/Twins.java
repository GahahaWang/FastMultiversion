package cc.gahaha.fastmultiversion.intellij;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.JavaResolveResult;
import com.intellij.psi.PsiCall;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiType;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Declarations made once per version, e.g. {@code @Mc.Is("[26.1,26.2)") Screen screen(...)} and
 * {@code @Mc.Is("[26.2,)") Screen screen(...)}. Each build keeps one; the IDE sees all of them.
 */
final class Twins {
    private Twins() {
    }

    /** Whether {@code member}, which runs in the active version, has a twin that does not. */
    static boolean inOtherVersions(PsiMember member, Guards guards, long active) {
        PsiClass owner = member.getContainingClass();
        if (owner == null || (guards.of(member) & active) == 0) {
            return false;
        }
        List<PsiMember> twins = new ArrayList<>();
        switch (member) {
            case PsiMethod method -> twins.addAll(List.of(owner.findMethodsBySignature(method, false)));
            case PsiField field -> {
                for (PsiField other : owner.getFields()) {
                    if (field.getName().equals(other.getName())) {
                        twins.add(other);
                    }
                }
            }
            case PsiClass c -> {
                for (PsiClass other : owner.getInnerClasses()) {
                    if (c.getName() != null && c.getName().equals(other.getName())) {
                        twins.add(other);
                    }
                }
            }
            default -> {
            }
        }
        return twins.stream().anyMatch(twin -> twin != member && (guards.of(twin) & active) == 0);
    }

    /**
     * The one method or constructor that {@code call} can mean in the active version, if the IDE finds the
     * call ambiguous only because of candidates for other versions; else {@code null}.
     */
    static @Nullable PsiMethod activeCandidate(PsiCall call, Guards guards, long active) {
        JavaResolveResult[] candidates = switch (call) {
            case PsiMethodCallExpression methodCall -> methodCall.getMethodExpression().multiResolve(false);
            case PsiNewExpression creation when creation.getClassReference() != null && creation.getArgumentList() != null
                    && creation.getClassReference().resolve() instanceof PsiClass c -> {
                PsiType type = JavaPsiFacade.getElementFactory(call.getProject()).createType(c);
                yield JavaPsiFacade.getInstance(call.getProject()).getResolveHelper()
                        .multiResolveConstructor((PsiClassType) type, creation.getArgumentList(), creation);
            }
            default -> JavaResolveResult.EMPTY_ARRAY;
        };
        if (candidates.length < 2) {
            return null;
        }
        PsiMethod found = null;
        for (JavaResolveResult candidate : candidates) {
            PsiElement element = candidate.getElement();
            if (!(element instanceof PsiMethod method)) {
                return null;
            }
            if ((guards.of(method) & active) != 0) {
                if (found != null) {
                    return null;
                }
                found = method;
            }
        }
        return found;
    }
}
