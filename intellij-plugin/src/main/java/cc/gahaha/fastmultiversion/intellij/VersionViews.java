package cc.gahaha.fastmultiversion.intellij;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiAnonymousClass;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassInitializer;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiImportStatementBase;
import com.intellij.psi.PsiImportStaticStatement;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiRecordComponent;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.impl.source.PsiFileImpl;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * Views of a source file as one Minecraft version sees it. The IDE resolves a file against the active
 * version only; a view is a copy of the file (same text, so the same offsets) whose references resolve
 * against another version's libraries ({@link VersionViewResolveScopeProvider}). Code that only runs in
 * that version is resolved, checked and completed in the view, and the results are mapped back.
 */
final class VersionViews {
    /** On a view's virtual file: what it resolves against. */
    static final Key<GlobalSearchScope> SCOPE = Key.create("multiversion.versionView.scope");
    private static final Key<CachedValue<Map<Integer, Optional<PsiJavaFile>>>> VIEWS = Key.create("multiversion.versionViews");

    private VersionViews() {
    }

    /** The view of {@code original} in {@code version}, kept until the file, the project structure or the setup changes. */
    static @Nullable PsiJavaFile of(MultiversionProject.State state, PsiJavaFile original, int version) {
        Map<Integer, Optional<PsiJavaFile>> views = CachedValuesManager.getCachedValue(original, VIEWS, () ->
                CachedValueProvider.Result.create(new ConcurrentHashMap<>(), original,
                        ProjectRootModificationTracker.getInstance(original.getProject()),
                        MultiversionProject.getInstance(original.getProject()).tracker()));
        return views.computeIfAbsent(version, v -> Optional.ofNullable(create(state, original, original.getText(), v))).orElse(null);
    }

    /**
     * A new view with {@code text} (e.g. a completion copy's) of {@code original} in {@code version};
     * {@code null} if the IDE has no module for that version.
     */
    static @Nullable PsiJavaFile create(MultiversionProject.State state, PsiJavaFile original, CharSequence text, int version) {
        GlobalSearchScope scope = MultiversionProject.getInstance(original.getProject()).versionScope(state, original, version);
        if (scope == null) {
            return null;
        }
        PsiJavaFile view = parse(original, text, scope);
        // As javac does for the version, drop what the version does not have, blanked out so that offsets stay
        // the same: imports of classes that only other versions have (the IDE resolves a name through the first
        // import of it, so they would hide this version's import of the same name, e.g. of a class that moved
        // to another package), and declarations whose @IfMc excludes the version (else e.g. a method declared
        // once per version would be defined twice).
        List<TextRange> otherVersions = new ArrayList<>(otherVersionImports(view, state));
        if (view != null) {
            otherVersionDeclarations(view, new Guards(state.db()), version, otherVersions);
        }
        if (view == null || otherVersions.isEmpty()) {
            return view;
        }
        StringBuilder blanked = new StringBuilder(text);
        for (TextRange range : otherVersions) {
            for (int i = range.getStartOffset(); i < range.getEndOffset(); i++) {
                if (!Character.isWhitespace(blanked.charAt(i))) {
                    blanked.setCharAt(i, ' ');
                }
            }
        }
        return parse(original, blanked, scope);
    }

    private static @Nullable PsiJavaFile parse(PsiJavaFile original, CharSequence text, GlobalSearchScope scope) {
        PsiFile file = PsiFileFactory.getInstance(original.getProject())
                .createFileFromText(original.getName(), JavaLanguage.INSTANCE, text, true, false);
        if (!(file instanceof PsiJavaFile view) || !(file instanceof PsiFileImpl impl)) {
            return null;
        }
        // Physical (events enabled; it is never changed), as inspections expect of the files they check. The
        // original makes the view count as project code (its module, language level), so that the IDE asks the
        // resolve scope providers about the view's own virtual file, which carries the scope.
        impl.setOriginalFile(original);
        file.getViewProvider().getVirtualFile().putUserData(SCOPE, scope);
        return view;
    }

    /** Single imports that do not resolve in {@code view} but name a class the database knows from other versions. */
    private static List<TextRange> otherVersionImports(@Nullable PsiJavaFile view, MultiversionProject.State state) {
        PsiImportList imports = view == null ? null : view.getImportList();
        if (imports == null) {
            return List.of();
        }
        List<TextRange> ranges = new ArrayList<>();
        for (PsiImportStatementBase statement : imports.getAllImportStatements()) {
            PsiJavaCodeReferenceElement reference = statement.getImportReference();
            String name = reference == null ? null : reference.getQualifiedName();
            if (name == null || statement.isOnDemand() || statement.resolve() != null) {
                continue;
            }
            if (statement instanceof PsiImportStaticStatement && name.lastIndexOf('.') > 0) {
                name = name.substring(0, name.lastIndexOf('.'));
            }
            if (OtherVersions.apiClass(state.db(), name) != null) {
                ranges.add(statement.getTextRange());
            }
        }
        return ranges;
    }

    /** Adds the declarations in {@code element} (not nested in each other) that {@code version} does not have. */
    private static void otherVersionDeclarations(PsiElement element, Guards guards, int version, List<TextRange> ranges) {
        for (PsiElement child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (isRemovable(child) && (guards.of(child) & (1L << version)) == 0) {
                ranges.add(child.getTextRange());
            } else if (!(child instanceof PsiCodeBlock) && !(child instanceof PsiExpression)) {
                otherVersionDeclarations(child, guards, version, ranges);
            }
        }
    }

    /** A declaration that can be blanked out alone: not an enum constant, a record component, or a field sharing its type. */
    private static boolean isRemovable(PsiElement element) {
        return switch (element) {
            case PsiEnumConstant ignored -> false;
            case PsiRecordComponent ignored -> false;
            case PsiTypeParameter ignored -> false;
            case PsiField field -> field.getTypeElement() != null && PsiTreeUtil.isAncestor(field, field.getTypeElement(), true)
                    && field.getText().stripTrailing().endsWith(";");
            case PsiMethod ignored -> true;
            case PsiClass c -> !(c instanceof PsiAnonymousClass);
            case PsiClassInitializer ignored -> true;
            default -> false;
        };
    }

    /** The element of the same class and range in {@code in}, a view of {@code element}'s file or the file of a view. */
    @SuppressWarnings("unchecked")
    static <T extends PsiElement> @Nullable T counterpart(T element, PsiFile in) {
        TextRange range = element.getTextRange();
        if (range == null || range.getEndOffset() > in.getTextLength()) {
            return null;
        }
        for (PsiElement e = in.findElementAt(range.getStartOffset()); e != null && !(e instanceof PsiFile); e = e.getParent()) {
            TextRange r = e.getTextRange();
            if (r.getStartOffset() != range.getStartOffset() || r.getEndOffset() > range.getEndOffset()) {
                return null;
            }
            if (r.getEndOffset() == range.getEndOffset() && e.getClass() == element.getClass()) {
                return (T) e;
            }
        }
        return null;
    }
}
