package cc.gahaha.fastmultiversion.intellij;

import com.intellij.lang.documentation.DocumentationProvider;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Quick Documentation in code that does not run in the active version: documents the declaration of the
 * version the code runs in. The Java documentation provider renders it.
 */
public final class InactiveDocumentationProvider implements DocumentationProvider {
    @Override
    public @Nullable PsiElement getCustomDocumentationElement(@NotNull Editor editor, @NotNull PsiFile file,
                                                             @Nullable PsiElement contextElement, int targetOffset) {
        List<PsiElement> targets = VersionTargets.of(VersionTargets.identifierAt(file, targetOffset));
        return targets.isEmpty() ? null : targets.getFirst();
    }
}
