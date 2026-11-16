package cc.gahaha.fastmultiversion.intellij;

import cc.gahaha.fastmultiversion.apidb.ApiClass;
import cc.gahaha.fastmultiversion.apidb.ApiClass.MemberKind;
import cc.gahaha.fastmultiversion.apidb.ApiDatabase;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiImportStatementBase;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiPackageStatement;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The range check on PSI: for each Minecraft class or member that the code uses, the versions the code
 * runs in (its guard, see {@link Guards}) must all be versions in which it can be used exactly as the IDE
 * resolved it in the reference version ({@link ApiDatabase#compatible}): the active version for code that
 * runs in it, or the version of the {@link VersionViews view} the code was resolved in.
 */
final class RangeCheck {
    /** Where problems go. */
    interface Reporter {
        void report(PsiElement at, String message, ProblemHighlightType type, LocalQuickFix... fixes);
    }

    private final ApiDatabase db;
    private final int referenceVersion;
    private final Reporter reporter;

    RangeCheck(ApiDatabase db, int referenceVersion, Reporter reporter) {
        this.db = db;
        this.referenceVersion = referenceVersion;
        this.reporter = reporter;
    }

    static Reporter to(ProblemsHolder holder) {
        return (at, message, type, fixes) -> McApiRangeInspection.register(holder, at, message, type, fixes);
    }

    void reference(PsiJavaCodeReferenceElement reference, long guard) {
        if (PsiTreeUtil.getParentOfType(reference, PsiImportStatementBase.class, PsiPackageStatement.class) != null) {
            return;
        }
        PsiElement target = reference.resolve();
        PsiElement at = reference.getReferenceNameElement() != null ? reference.getReferenceNameElement() : reference;
        switch (target) {
            case PsiTypeParameter ignored -> {
            }
            case PsiClass c -> {
                if (!isQualifierOfAnotherReference(reference)) {
                    checkClass(c, at, guard);
                }
            }
            case PsiMethod method -> {
                if (!qualifierIsReported(reference, guard)) {
                    checkMember(MemberKind.METHOD, method, JvmNames.descriptor(method), start(reference, method), at, guard, chain(reference));
                }
            }
            case PsiField field -> {
                if (!qualifierIsReported(reference, guard)) {
                    checkMember(MemberKind.FIELD, field, JvmNames.descriptor(field.getType()), start(reference, field), at, guard,
                            chain(reference));
                }
            }
            case null, default -> {
            }
        }
    }

    void newExpression(PsiNewExpression expression, long guard) {
        PsiJavaCodeReferenceElement classReference = expression.getClassReference();
        if (classReference == null || expression.getAnonymousClass() != null) {
            return; // an anonymous class's supertype is checked as a plain reference
        }
        if (!(classReference.resolve() instanceof PsiClass c)) {
            return;
        }
        PsiElement at = classReference.getReferenceNameElement() != null ? classReference.getReferenceNameElement() : classReference;
        if (checkClass(c, at, guard)) {
            PsiMethod constructor = expression.resolveConstructor();
            if (constructor != null) {
                checkMember(MemberKind.METHOD, constructor, JvmNames.descriptor(constructor), c, at, guard, 0);
            }
        }
    }

    /** A method that overrides a Minecraft method must only exist where that method does. */
    void overrides(PsiMethod method, long guard) {
        if (method.isConstructor() || method.hasModifierProperty(PsiModifier.STATIC) || method.hasModifierProperty(PsiModifier.PRIVATE)
                || method.getNameIdentifier() == null) {
            return;
        }
        for (PsiMethod overridden : method.findSuperMethods()) {
            PsiClass owner = overridden.getContainingClass();
            String name = owner == null ? null : JvmNames.internalName(owner);
            String descriptor = JvmNames.descriptor(overridden);
            ApiClass api = name == null ? null : db.get(name);
            if (api == null || api.platform() || descriptor == null) {
                continue;
            }
            long compatible = db.compatible(MemberKind.METHOD, name, overridden.getName(), descriptor, referenceVersion);
            if ((guard & ~compatible) != 0) {
                String usable = db.range(compatible);
                PsiClass self = method.getContainingClass();
                reporter.report(method.getNameIdentifier(), "'" + (self == null ? "" : self.getName() + ".") + method.getName()
                                + "' overrides " + describe(overridden) + ", which only exists like this in " + usable
                                + ", but this method exists in " + db.range(guard) + ". Elsewhere it would override nothing "
                                + "and never be called. Give it @Mc.Is(\"" + usable + "\").",
                        ProblemHighlightType.GENERIC_ERROR, new AddMcIsFix(usable));
                return;
            }
        }
    }

    // ---- checks

    /** Returns false if it reported a problem. */
    private boolean checkClass(PsiClass c, PsiElement at, long guard) {
        String name = JvmNames.internalName(c);
        ApiClass api = name == null ? null : db.get(name);
        if (api == null || api.platform()) {
            return true;
        }
        return report(db.classCompatible(name, referenceVersion), api.versions(), guard, at, "'" + c.getQualifiedName() + "'");
    }

    /** {@code chain}: versions in which the member works anyway, as part of an {@code m().n} chain ({@link #chain}). */
    private void checkMember(MemberKind kind, PsiMember member, String descriptor, PsiClass start, PsiElement at, long guard,
                             long chain) {
        if (descriptor == null) {
            return;
        }
        String memberName = member instanceof PsiMethod m && m.isConstructor() ? "<init>" : member.getName();
        String owner = owner(kind, member, memberName, descriptor, start);
        if (owner == null) {
            return;
        }
        long compatible = db.compatible(kind, owner, memberName, descriptor, referenceVersion) | chain;
        report(compatible, db.resolvable(kind, owner, memberName, descriptor) | chain, guard, at, describe(member));
    }

    /**
     * For {@code reference} as {@code m} or {@code n} of {@code m().n}: the versions in which this source works
     * although {@code m} returns another class there ({@link ApiDatabase#compatibleChain}), as the javac plugin
     * finds them; 0 for any other reference.
     */
    private long chain(PsiJavaCodeReferenceElement reference) {
        if (!(reference instanceof PsiReferenceExpression expression)) {
            return 0;
        }
        PsiMethodCallExpression call;
        PsiReferenceExpression next;
        if (PsiUtil.skipParenthesizedExprDown(expression.getQualifierExpression()) instanceof PsiMethodCallExpression qualifier) {
            call = qualifier; // reference is n
            next = expression;
        } else if (expression.getParent() instanceof PsiMethodCallExpression c && c.getMethodExpression() == expression
                && PsiUtil.skipParenthesizedExprUp(c.getParent()) instanceof PsiReferenceExpression n
                && PsiUtil.skipParenthesizedExprDown(n.getQualifierExpression()) == c) {
            call = c; // reference is m
            next = n;
        } else {
            return 0;
        }
        PsiMethod method = call.resolveMethod();
        String descriptor = method == null ? null : JvmNames.descriptor(method);
        String owner = descriptor == null ? null
                : owner(MemberKind.METHOD, method, method.getName(), descriptor, start(call.getMethodExpression(), method));
        String nextName = next.getReferenceName();
        if (owner == null || nextName == null) {
            return 0;
        }
        return switch (next.resolve()) {
            case PsiMethod m when JvmNames.descriptor(m) != null -> db.compatibleChain(owner, method.getName(), descriptor,
                    MemberKind.METHOD, nextName, JvmNames.descriptor(m), referenceVersion);
            case PsiField f when JvmNames.descriptor(f.getType()) != null -> db.compatibleChain(owner, method.getName(), descriptor,
                    MemberKind.FIELD, nextName, JvmNames.descriptor(f.getType()), referenceVersion);
            case null, default -> 0;
        };
    }

    /** The class the member is used through, as the javac plugin determines it. */
    private PsiClass start(PsiJavaCodeReferenceElement reference, PsiMember member) {
        if (reference instanceof PsiReferenceExpression expression && expression.getQualifierExpression() != null) {
            PsiExpression qualifier = expression.getQualifierExpression();
            if (qualifier instanceof PsiReferenceExpression q && q.resolve() instanceof PsiClass c) {
                return c; // Type.member
            }
            return qualifier.getType() instanceof PsiClassType type ? type.resolve() : null;
        }
        PsiClass declaring = member.getContainingClass();
        for (PsiClass c = PsiTreeUtil.getParentOfType(reference, PsiClass.class); c != null; c = PsiTreeUtil.getParentOfType(c, PsiClass.class)) {
            if (declaring != null && (c == declaring || c.isInheritor(declaring, true))) {
                return c; // an unqualified name: the innermost class that inherits it
            }
        }
        return null;
    }

    /**
     * Looks the member up through the first class in the database on the way up from {@code start}, as the
     * JVM would; falls back to the class that declares it in the active version.
     */
    private String owner(MemberKind kind, PsiMember member, String memberName, String descriptor, PsiClass start) {
        for (PsiClass c = start; c != null; c = c.getSuperClass()) {
            String name = JvmNames.internalName(c);
            ApiClass api = name == null ? null : db.get(name);
            if (api != null) {
                if (!api.platform() && db.resolve(kind, name, memberName, descriptor, referenceVersion).status()
                        == ApiDatabase.Resolution.Status.FOUND) {
                    return name;
                }
                break;
            }
        }
        PsiClass declaring = member.getContainingClass();
        String name = declaring == null ? null : JvmNames.internalName(declaring);
        ApiClass api = name == null ? null : db.get(name);
        return api == null || api.platform() ? null : name;
    }

    /** {@code Registry} in {@code Registry.of(...)}: the member reference reports any problem with it. */
    private static boolean isQualifierOfAnotherReference(PsiJavaCodeReferenceElement reference) {
        return reference.getParent() instanceof PsiJavaCodeReferenceElement parent && parent.getQualifier() == reference
                && !(parent.resolve() instanceof PsiClass);
    }

    /** Whether the qualifier is a class or {@code new} expression that is reported already. */
    private boolean qualifierIsReported(PsiJavaCodeReferenceElement reference, long guard) {
        if (!(reference instanceof PsiReferenceExpression expression)) {
            return false;
        }
        PsiElement qualifier = expression.getQualifierExpression();
        PsiClass c = null;
        if (qualifier instanceof PsiNewExpression created && created.getClassReference() != null
                && created.getClassReference().resolve() instanceof PsiClass resolved) {
            c = resolved;
        }
        String name = c == null ? null : JvmNames.internalName(c);
        ApiClass api = name == null ? null : db.get(name);
        return api != null && !api.platform() && (guard & ~db.classCompatible(name, referenceVersion)) != 0;
    }

    /**
     * Reports a name that does not resolve in the reference version but exists in {@code present}: like any
     * range problem, with fixes, if some versions the code runs in have it; otherwise as unresolved
     * ({@code unresolved}), saying where it exists.
     */
    void reportMissing(PsiElement at, String what, long present, long guard, String unresolved) {
        if ((guard & present) != 0) {
            report(present, present, guard, at, what);
        } else {
            reporter.report(at, unresolved + " (" + what + " only exists in " + db.range(present) + ")",
                    ProblemHighlightType.LIKE_UNKNOWN_SYMBOL);
        }
    }

    /**
     * Reports a use of a declaration of the mod that only the versions {@code present} keep ({@code @Mc.Is}),
     * from code that runs in {@code guard}.
     */
    void reportDeclaration(PsiElement at, String what, long present, long guard) {
        if ((guard & present) != 0) {
            report(present, present, guard, at, what);
        } else {
            reporter.report(at, what + " only exists in " + db.range(present) + ", not in " + db.range(guard)
                    + ", where this code runs.", ProblemHighlightType.GENERIC_ERROR);
        }
    }

    // ---- reporting

    private boolean report(long compatible, long present, long guard, PsiElement at, String what) {
        if ((guard & ~compatible) == 0) {
            return true;
        }
        String usable = db.range(compatible);
        String problem = (guard & ~present) != 0
                ? what + " only exists in " + usable
                : what + " exists in every version this code runs in, but only has this form (signature, "
                        + "static-ness or visibility) in " + usable;
        List<LocalQuickFix> fixes = new ArrayList<>();
        if (SurroundWithMcIsFix.statementToWrap(at) != null) {
            fixes.add(new SurroundWithMcIsFix(usable));
        }
        if (AddMcIsFix.target(at) != null) {
            fixes.add(new AddMcIsFix(usable));
        }
        reporter.report(at, problem + ", but this code runs in " + db.range(guard) + ". Use it inside "
                        + "if (Mc.is(\"" + usable + "\")) or in a declaration with @Mc.Is(\"" + usable + "\").",
                ProblemHighlightType.GENERIC_ERROR, fixes.toArray(LocalQuickFix[]::new));
        return false;
    }

    static String describe(PsiMember member) {
        PsiClass owner = member.getContainingClass();
        String ownerName = owner == null ? "" : owner.getName();
        if (member instanceof PsiMethod method) {
            String parameters = java.util.Arrays.stream(method.getParameterList().getParameters())
                    .map(PsiParameter::getType).map(t -> t.getPresentableText()).collect(Collectors.joining(", ", "(", ")"));
            return "'" + (method.isConstructor() ? ownerName : ownerName + "." + method.getName()) + parameters + "'";
        }
        return "'" + ownerName + "." + member.getName() + "'";
    }
}
