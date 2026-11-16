package cc.gahaha.fastmultiversion.intellij;

import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiCall;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiImportStatementBase;
import com.intellij.psi.PsiImportStaticStatement;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.SyntaxTraverser;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Hides errors and warnings that the IDE reports because it resolves everything against the active version
 * (syntax errors are always shown):
 * <ul>
 *   <li>in code that does not run in the active version: {@link McApiRangeInspection} checks it against the
 *       versions it runs in instead (its problems are kept), and the javac plugin does when it builds them;</li>
 *   <li>on imports of classes and packages that only other supported versions have;</li>
 *   <li>"already defined in a single-type import" on a later import of a class whose simple name an earlier
 *       import of another class already took, where the active version has both: the build drops the later
 *       one, as the first import wins;</li>
 *   <li>(on both kinds of imports, "unused" too: {@link McUnusedImportInspection} reports them if no version
 *       uses them);</li>
 *   <li>"cannot resolve" on a name that other versions have: the inspection reports it with those versions;</li>
 *   <li>a declaration "already defined" by its twin for other versions ({@code @Mc.Is} on both), and a call
 *       "ambiguous" between them.</li>
 * </ul>
 */
public final class InactiveCodeHighlightFilter implements HighlightInfoFilter {
    @Override
    public boolean accept(@NotNull HighlightInfo info, @Nullable PsiFile file) {
        if (!(file instanceof PsiJavaFile) || info.getSeverity().compareTo(HighlightSeverity.WARNING) < 0
                || info.getProblemGroup() instanceof McApiRangeInspection.Group) {
            return true;
        }
        MultiversionProject.State state = MultiversionProject.getInstance(file.getProject()).state();
        if (state == null || isSyntaxError(info, file)) {
            return true;
        }
        PsiElement at = file.findElementAt(info.getStartOffset());
        if (at == null) {
            return true;
        }
        PsiImportStatementBase importStatement = PsiTreeUtil.getParentOfType(at, PsiImportStatementBase.class);
        if (importStatement != null) {
            return !isFromOtherVersions(importStatement, state) && !isShadowedByAnEarlierImport(importStatement, state);
        }
        Guards guards = new Guards(state.db());
        if ((guards.of(at) & state.activeBit()) == 0) {
            return false;
        }
        return info.getSeverity().compareTo(HighlightSeverity.ERROR) < 0
                || !(isReportedByInspection(at, state, file) || isAboutTwins(info, at, guards, state.activeBit()));
    }

    /**
     * An error that only exists because the file also declares what other versions have: "already defined" on
     * a declaration whose twin (same name and signature) does not exist in the active version, or "ambiguous"
     * on a call that only one of the candidates in the active version matches.
     */
    private static boolean isAboutTwins(HighlightInfo info, PsiElement at, Guards guards, long active) {
        TextRange range = TextRange.create(info.getStartOffset(), info.getEndOffset());
        PsiMember member = PsiTreeUtil.getParentOfType(at, PsiMember.class, false);
        if (member != null && header(member).contains(range) && !(at.getParent() instanceof PsiJavaCodeReferenceElement)
                && Twins.inOtherVersions(member, guards, active)) {
            return true;
        }
        PsiCall call = PsiTreeUtil.getParentOfType(at, PsiCall.class, false);
        return call != null && call.getArgumentList() != null && call.getArgumentList().getTextRange().equals(range)
                && Twins.activeCandidate(call, guards, active) != null;
    }

    /** Where the IDE reports a duplicate declaration: from the modifiers to the parameters, or the name. */
    private static TextRange header(PsiMember member) {
        int start = member.getTextRange().getStartOffset();
        PsiElement end = member instanceof PsiMethod method ? method.getParameterList()
                : member instanceof PsiNameIdentifierOwner owner ? owner.getNameIdentifier() : null;
        return end == null ? TextRange.EMPTY_RANGE : TextRange.create(start, end.getTextRange().getEndOffset());
    }

    /**
     * A syntax error, which is the same in every version and so always shown, even where it is highlighted
     * in inactive code (a missing ';' is reported at the end of the token before it). The IDE reports each
     * parse error at the start of its error element, with the element's description.
     */
    static boolean isSyntaxError(HighlightInfo info, PsiFile file) {
        Map<Integer, Set<String>> errors = CachedValuesManager.getCachedValue(file, () -> {
            Map<Integer, Set<String>> byOffset = new HashMap<>();
            SyntaxTraverser.psiTraverser(file).filter(PsiErrorElement.class).forEach(error ->
                    byOffset.computeIfAbsent(error.getTextRange().getStartOffset(), offset -> new HashSet<>()).add(error.getErrorDescription()));
            return CachedValueProvider.Result.create(byOffset, file);
        });
        Set<String> descriptions = errors.get(info.getStartOffset());
        return descriptions != null && descriptions.contains(info.getDescription());
    }

    /**
     * An unresolved name that {@link McApiRangeInspection} reports better: one that other versions have
     * (reported with those versions) or one hidden by another version's import (reported with that import).
     */
    private static boolean isReportedByInspection(PsiElement at, MultiversionProject.State state, PsiFile file) {
        if (!(at.getParent() instanceof PsiJavaCodeReferenceElement reference) || reference.getReferenceNameElement() != at) {
            return false;
        }
        HighlightDisplayKey key = HighlightDisplayKey.find(McApiRangeInspection.SHORT_NAME);
        return key != null && InspectionProjectProfileManager.getInstance(file.getProject()).getCurrentProfile().isToolEnabled(key, file)
                && OtherVersions.isUnresolved(reference) && (OtherVersions.shadowingImport(reference, state.db()) != null
                        || OtherVersions.find(reference, state.db(), state.active()) != null);
    }

    /** An import that does not resolve here, of a class or package that another supported version has. */
    private static boolean isFromOtherVersions(PsiImportStatementBase statement, MultiversionProject.State state) {
        PsiJavaCodeReferenceElement reference = statement.getImportReference();
        String name = reference == null ? null : reference.getQualifiedName();
        if (name == null) {
            return false;
        }
        PsiElement resolved = statement.resolve();
        if (statement.isOnDemand() && state.packages().contains(name.replace('.', '/'))) {
            // Packages exist project-wide: another version's module may have it, but not this file's version.
            return !(resolved instanceof PsiPackage pkg) || pkg.getClasses(statement.getResolveScope()).length == 0;
        }
        if (resolved != null) {
            return false;
        }
        if (statement instanceof PsiImportStaticStatement && !statement.isOnDemand() && name.lastIndexOf('.') > 0) {
            name = name.substring(0, name.lastIndexOf('.')); // import static a.b.C.member;
        }
        return OtherVersions.apiClass(state.db(), name) != null;
    }

    /**
     * A class import of a class the database knows whose simple name an earlier such import, which resolves here,
     * already took ("already defined in a single-type import"): this version has both classes, e.g. a library kept
     * the old class next to the moved one, and the build drops the later import, as the first one wins.
     */
    static boolean isShadowedByAnEarlierImport(PsiImportStatementBase statement, MultiversionProject.State state) {
        if (!(statement instanceof PsiImportStatement single) || single.isOnDemand() || single.getQualifiedName() == null
                || OtherVersions.apiClass(state.db(), single.getQualifiedName()) == null
                || !(statement.getParent() instanceof PsiImportList imports)) {
            return false;
        }
        String simpleName = single.getImportReference() == null ? null : single.getImportReference().getReferenceName();
        for (PsiImportStatement earlier : imports.getImportStatements()) {
            if (earlier == single) {
                return false;
            }
            String name = earlier.getQualifiedName();
            if (!earlier.isOnDemand() && name != null && earlier.getImportReference() != null
                    && earlier.getImportReference().getReferenceName() != null
                    && earlier.getImportReference().getReferenceName().equals(simpleName)
                    && earlier.resolve() != null && OtherVersions.apiClass(state.db(), name) != null) {
                return true;
            }
        }
        return false;
    }
}
