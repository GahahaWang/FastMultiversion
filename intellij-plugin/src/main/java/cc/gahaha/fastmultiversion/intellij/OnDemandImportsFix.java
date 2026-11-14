package cc.gahaha.fastmultiversion.intellij;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiJavaFile;
import java.util.HashSet;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * Replaces the single imports of a class name (one per version, from the packages the class moved between)
 * with on-demand imports of their packages. Unlike single imports, on-demand imports do not hide each other
 * in the IDE, whichever version is active; javac drops the ones whose package a version lacks.
 */
final class OnDemandImportsFix implements LocalQuickFix {
    private final String name;

    OnDemandImportsFix(String name) {
        this.name = name;
    }

    @Override
    public @NotNull String getName() {
        return "Import the packages of '" + name + "' on demand";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Import the packages of a class from several versions on demand";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        if (!(descriptor.getPsiElement().getContainingFile() instanceof PsiJavaFile file) || file.getImportList() == null) {
            return;
        }
        PsiImportList imports = file.getImportList();
        Set<String> onDemand = new HashSet<>();
        for (PsiImportStatement statement : imports.getImportStatements()) {
            if (statement.isOnDemand() && statement.getQualifiedName() != null) {
                onDemand.add(statement.getQualifiedName());
            }
        }
        for (PsiImportStatement statement : imports.getImportStatements()) {
            String imported = statement.getQualifiedName();
            if (statement.isOnDemand() || imported == null || !imported.endsWith("." + name)) {
                continue;
            }
            String pkg = imported.substring(0, imported.length() - name.length() - 1);
            if (onDemand.add(pkg)) {
                statement.replace(ImportClassFix.parseImport(file, pkg + ".*"));
            } else {
                statement.delete();
            }
        }
    }
}
