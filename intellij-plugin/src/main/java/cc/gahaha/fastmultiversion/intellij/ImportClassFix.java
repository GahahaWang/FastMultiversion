package cc.gahaha.fastmultiversion.intellij;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiJavaFile;
import org.jetbrains.annotations.NotNull;

/**
 * Imports a class of the version that code runs in, which the IDE's own import fix cannot offer: it only
 * knows the active version's classes. The import does not resolve in the active version; the IDE hides
 * that, and each build keeps it only if its version has the class.
 */
final class ImportClassFix implements LocalQuickFix {
    private final String qualifiedName;
    private final String version;

    ImportClassFix(String qualifiedName, String version) {
        this.qualifiedName = qualifiedName;
        this.version = version;
    }

    @Override
    public @NotNull String getName() {
        return "Import '" + qualifiedName + "' (" + version + ")";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Import a class of another Minecraft version";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        if (descriptor.getPsiElement().getContainingFile() instanceof PsiJavaFile file) {
            addImport(file, qualifiedName);
        }
    }

    /** Adds {@code import qualifiedName;} in order, unless the file has it or does not need it. */
    static void addImport(PsiJavaFile file, String qualifiedName) {
        PsiImportList imports = file.getImportList();
        String pkg = StringUtil.getPackageName(qualifiedName);
        if (imports == null || imports.findSingleClassImportStatement(qualifiedName) != null
                || pkg.equals(file.getPackageName()) || pkg.equals("java.lang")) {
            return;
        }
        PsiImportStatement statement = parseImport(file, qualifiedName);
        PsiImportStatement[] existing = imports.getImportStatements();
        for (PsiImportStatement s : existing) {
            String name = s.getQualifiedName();
            if (name != null && name.compareTo(qualifiedName) > 0) {
                imports.addBefore(statement, s);
                return;
            }
        }
        if (existing.length > 0) {
            imports.addAfter(statement, existing[existing.length - 1]);
        } else {
            imports.add(statement);
        }
    }

    /**
     * {@code import name;} from text: a class of another version does not resolve in the active one, so it
     * cannot be imported as a PsiClass.
     */
    static PsiImportStatement parseImport(PsiJavaFile context, String name) {
        PsiJavaFile parsed = (PsiJavaFile) PsiFileFactory.getInstance(context.getProject())
                .createFileFromText("Import.java", JavaLanguage.INSTANCE, "import " + name + ";");
        return parsed.getImportList().getImportStatements()[0];
    }
}
