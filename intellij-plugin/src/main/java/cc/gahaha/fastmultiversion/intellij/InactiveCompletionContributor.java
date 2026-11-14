package cc.gahaha.fastmultiversion.intellij;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.completion.JavaLookupElementBuilder;
import com.intellij.codeInsight.completion.util.ParenthesesInsertHandler;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.psi.HierarchicalMethodSignature;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiImportStatementBase;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiPackageStatement;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiResolveHelper;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.TypeConversionUtil;
import java.util.HashSet;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * Completion in code that does not run in the active version, from the version it runs in (resolved in its
 * {@link VersionViews view}), each item marked with that version:
 * <ul>
 *   <li>member completion ({@code expression.}): the members the qualifier has in that version, instead of
 *       the active version's;</li>
 *   <li>class names: that version's classes that the active version lacks, added to the IDE's; choosing one
 *       imports it.</li>
 * </ul>
 */
public final class InactiveCompletionContributor extends CompletionContributor {
    @Override
    public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
        if (parameters.getCompletionType() != CompletionType.BASIC) {
            return;
        }
        PsiElement position = parameters.getPosition();
        if (!(position.getParent() instanceof PsiJavaCodeReferenceElement reference)
                || PsiTreeUtil.getParentOfType(reference, PsiImportStatementBase.class, PsiPackageStatement.class) != null
                || !(parameters.getOriginalFile() instanceof PsiJavaFile original)
                || !(position.getContainingFile() instanceof PsiJavaFile copy)) {
            return;
        }
        MultiversionProject.State state = MultiversionProject.getInstance(original.getProject()).state();
        if (state == null) {
            return;
        }
        long guard = new Guards(state.db()).of(reference);
        if (guard == 0 || (guard & state.activeBit()) != 0) {
            return;
        }
        int version = MultiversionProject.State.newest(guard);
        String tail = "  " + state.name(version);
        if (reference.getQualifier() == null) {
            addClasses(state, original, version, tail, result);
            return;
        }
        if (!(reference instanceof PsiReferenceExpression expression) || expression.getQualifierExpression() == null) {
            return;
        }
        PsiJavaFile view = VersionViews.create(state, original, copy.getText(), version);
        PsiReferenceExpression inView = view == null ? null : VersionViews.counterpart(expression, view);
        if (inView != null && addMembers(inView, tail, result)) {
            result.stopHere(); // the IDE's own Java completion would add the active version's members
        }
    }

    /** Adds the classes of {@code version} that the active version does not have; the IDE adds the others. */
    private static void addClasses(MultiversionProject.State state, PsiJavaFile original, int version, String tail,
                                   CompletionResultSet result) {
        if (result.getPrefixMatcher().getPrefix().isEmpty()) {
            return;
        }
        GlobalSearchScope scope = MultiversionProject.getInstance(original.getProject()).versionScope(state, original, version);
        if (scope == null) {
            return;
        }
        GlobalSearchScope activeScope = original.getResolveScope();
        JavaPsiFacade facade = JavaPsiFacade.getInstance(original.getProject());
        PsiShortNamesCache cache = PsiShortNamesCache.getInstance(original.getProject());
        Set<String> names = new HashSet<>();
        cache.processAllClassNames(name -> {
            if (result.getPrefixMatcher().prefixMatches(name)) {
                names.add(name);
            }
            return true;
        }, scope, null);
        for (String name : names) {
            for (PsiClass c : cache.getClassesByName(name, scope)) {
                String qualifiedName = c.getQualifiedName();
                if (qualifiedName == null || facade.findClass(qualifiedName, activeScope) != null
                        || !c.hasModifierProperty(PsiModifier.PUBLIC)) {
                    continue;
                }
                result.addElement(JavaLookupElementBuilder.forClass(c, name, true)
                        .appendTailText(tail, true)
                        .withInsertHandler((context, item) -> {
                            PsiDocumentManager documents = PsiDocumentManager.getInstance(context.getProject());
                            documents.commitDocument(context.getDocument());
                            if (context.getFile() instanceof PsiJavaFile file) {
                                ImportClassFix.addImport(file, qualifiedName);
                                documents.doPostponedOperationsAndUnblockDocument(context.getDocument());
                            }
                        }));
            }
        }
    }

    /** Adds the members that can follow the qualifier of {@code reference}; false if its type is unknown here. */
    private static boolean addMembers(PsiReferenceExpression reference, String tail, CompletionResultSet result) {
        PsiExpression qualifier = reference.getQualifierExpression();
        PsiClass c;
        PsiSubstitutor substitutor;
        boolean statics;
        if (qualifier instanceof PsiReferenceExpression q && q.resolve() instanceof PsiClass type) {
            c = type;
            substitutor = PsiSubstitutor.EMPTY;
            statics = true;
        } else if (qualifier != null && qualifier.getType() instanceof PsiClassType type) {
            PsiClassType.ClassResolveResult resolved = type.resolveGenerics();
            c = resolved.getElement();
            substitutor = resolved.getSubstitutor();
            statics = false;
        } else {
            return false;
        }
        if (c == null) {
            return false;
        }
        PsiResolveHelper helper = JavaPsiFacade.getInstance(reference.getProject()).getResolveHelper();
        PsiClass accessClass = statics ? null : c;
        for (HierarchicalMethodSignature signature : c.getVisibleSignatures()) {
            PsiMethod method = signature.getMethod();
            if (method.isConstructor() || !fits(method, statics) || !helper.isAccessible(method, reference, accessClass)) {
                continue;
            }
            PsiClass owner = method.getContainingClass();
            PsiSubstitutor s = owner != null && (owner == c || c.isInheritor(owner, true))
                    ? TypeConversionUtil.getSuperClassSubstitutor(owner, c, substitutor) : PsiSubstitutor.EMPTY;
            result.addElement(JavaLookupElementBuilder.forMethod(method, method.getName(), s, c)
                    .withInsertHandler(ParenthesesInsertHandler.getInstance(method.hasParameters()))
                    .appendTailText(tail, true));
        }
        Set<String> fields = new HashSet<>();
        for (PsiField field : c.getAllFields()) {
            if (fits(field, statics) && fields.add(field.getName()) && helper.isAccessible(field, reference, accessClass)) {
                result.addElement(JavaLookupElementBuilder.forField(field, field.getName(), c)
                        .withTypeText(substitutor.substitute(field.getType()).getPresentableText())
                        .appendTailText(tail, true));
            }
        }
        if (statics) {
            for (PsiClass inner : c.getAllInnerClasses()) {
                if (inner.getName() != null && fits(inner, true) && helper.isAccessible(inner, reference, null)) {
                    result.addElement(JavaLookupElementBuilder.forClass(inner, inner.getName()).appendTailText(tail, true));
                }
            }
            result.addElement(LookupElementBuilder.create("class").bold());
        }
        return true;
    }

    /** Static members after a class name, instance members after an expression. */
    private static boolean fits(PsiMember member, boolean statics) {
        return member.hasModifierProperty(PsiModifier.STATIC) == statics
                || member instanceof PsiClass c && (c.isInterface() || c.isEnum() || c.isRecord()); // implicitly static
    }
}
