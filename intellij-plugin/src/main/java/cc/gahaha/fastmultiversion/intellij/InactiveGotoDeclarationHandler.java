package cc.gahaha.fastmultiversion.intellij;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Go to Declaration in code that does not run in the active version: to the declaration in the version it
 * runs in (e.g. the decompiled {@code Minecraft.setScreen} of 26.1.2 in an {@code Mc.is("[26.1,26.2)")}
 * branch), not to the active version's.
 */
public final class InactiveGotoDeclarationHandler implements GotoDeclarationHandler {
    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement sourceElement, int offset, Editor editor) {
        PsiElement leaf = sourceElement == null || sourceElement instanceof PsiIdentifier ? sourceElement
                : VersionTargets.identifierAt(sourceElement.getContainingFile(), offset);
        List<PsiElement> targets = VersionTargets.of(leaf);
        return targets.isEmpty() ? null : targets.toArray(PsiElement.EMPTY_ARRAY);
    }
}
