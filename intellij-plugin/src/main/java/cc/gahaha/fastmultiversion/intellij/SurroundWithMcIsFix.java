package cc.gahaha.fastmultiversion.intellij;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiDeclarationStatement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Wraps the statement with the problem in {@code if (Mc.is("range")) { ... }}. */
final class SurroundWithMcIsFix implements LocalQuickFix {
    private final String range;

    SurroundWithMcIsFix(String range) {
        this.range = range;
    }

    @Override
    public @NotNull String getName() {
        return "Surround with if (Mc.is(\"" + range + "\"))";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Surround with if (Mc.is(...))";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiStatement statement = statementToWrap(descriptor.getPsiElement());
        if (statement == null) {
            return;
        }
        PsiStatement wrapped = JavaPsiFacade.getElementFactory(project).createStatementFromText(
                "if (" + Guards.MC + ".is(\"" + range + "\")) {\n" + statement.getText() + "\n}", statement);
        PsiElement result = statement.replace(wrapped);
        result = JavaCodeStyleManager.getInstance(project).shortenClassReferences(result);
        CodeStyleManager.getInstance(project).reformat(result);
    }

    /** The statement of a code block that contains {@code element}; not a declaration, whose scope would shrink. */
    static @Nullable PsiStatement statementToWrap(PsiElement element) {
        PsiStatement statement = PsiTreeUtil.getParentOfType(element, PsiStatement.class, false, PsiClass.class, PsiLambdaExpression.class);
        while (statement != null && !(statement.getParent() instanceof PsiCodeBlock)) {
            statement = PsiTreeUtil.getParentOfType(statement, PsiStatement.class, true, PsiClass.class, PsiLambdaExpression.class);
        }
        return statement instanceof PsiDeclarationStatement ? null : statement;
    }
}
