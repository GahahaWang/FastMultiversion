package cc.gahaha.fastmultiversion.intellij;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Annotates the enclosing method or field with {@code @Mc.Is("range")}. */
final class AddMcIsFix implements LocalQuickFix {
    private final String range;

    AddMcIsFix(String range) {
        this.range = range;
    }

    @Override
    public @NotNull String getName() {
        return "Annotate with @Mc.Is(\"" + range + "\")";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Annotate with @Mc.Is(...)";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiMember member = target(descriptor.getPsiElement());
        PsiModifierList modifiers = member == null ? null : member.getModifierList();
        if (modifiers == null) {
            return;
        }
        PsiAnnotation annotation = JavaPsiFacade.getElementFactory(project)
                .createAnnotationFromText("@" + Guards.MC_IS + "(\"" + range + "\")", member);
        PsiElement added = modifiers.addAfter(annotation, null);
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(added);
    }

    /** The method or field that contains {@code element}. */
    static @Nullable PsiMember target(PsiElement element) {
        return PsiTreeUtil.getParentOfType(element, PsiMethod.class, PsiField.class);
    }
}
