package cc.gahaha.fastmultiversion.intellij;

import cc.gahaha.fastmultiversion.apidb.ApiClass;
import cc.gahaha.fastmultiversion.apidb.ApiClass.MemberKey;
import cc.gahaha.fastmultiversion.apidb.ApiClass.MemberKind;
import cc.gahaha.fastmultiversion.apidb.ApiDatabase;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiImportStatementBase;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.PsiPackageStatement;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Names that do not resolve in the version the code is resolved against, but that the API database knows
 * from other versions: a class imported from another version's package, a member that another version has.
 */
final class OtherVersions {
    /** What a name refers to in other versions ({@code 'mc.Options'}, {@code 'Minecraft.setScreen'}) and which versions have it. */
    record Found(String what, long versions) {
    }

    private OtherVersions() {
    }

    /**
     * Whether {@code reference} resolves to nothing and is worth reporting: not in an import, package
     * statement or javadoc, and not behind a qualifier that is unresolved itself (that one is reported).
     */
    static boolean isUnresolved(PsiJavaCodeReferenceElement reference) {
        return reference.multiResolve(false).length == 0 && qualifierResolves(reference)
                && PsiTreeUtil.getParentOfType(reference, PsiDocComment.class, PsiImportStatementBase.class, PsiPackageStatement.class) == null;
    }

    private static boolean qualifierResolves(PsiJavaCodeReferenceElement reference) {
        PsiElement qualifier = reference.getQualifier();
        if (qualifier instanceof PsiJavaCodeReferenceElement q) {
            if (q.multiResolve(false).length == 0) {
                return false;
            }
            PsiElement target = q.resolve();
            if (target instanceof PsiClass || target instanceof PsiPackage || !(qualifier instanceof PsiExpression)) {
                return true;
            }
        }
        if (qualifier instanceof PsiExpression e) {
            PsiType type = e.getType();
            return type != null && !(type instanceof PsiClassType c && c.resolve() == null);
        }
        return true;
    }

    /**
     * What the unresolved {@code reference} names in other versions; {@code null} if the database does not
     * know it, or knows it in {@code version} (where it did not resolve, so it is something else).
     */
    static @Nullable Found find(PsiJavaCodeReferenceElement reference, ApiDatabase db, int version) {
        String name = reference.getReferenceName();
        if (name == null) {
            return null;
        }
        boolean call = reference.getParent() instanceof PsiMethodCallExpression;
        PsiElement qualifier = reference.getQualifier();
        Found found = null;
        if (qualifier == null) {
            if (reference instanceof PsiReferenceExpression) {
                // An inherited member, e.g. a method of a Minecraft superclass.
                for (PsiClass c = PsiTreeUtil.getParentOfType(reference, PsiClass.class); found == null && c != null;
                     c = PsiTreeUtil.getParentOfType(c, PsiClass.class)) {
                    found = member(c, name, call, db);
                }
            }
            if (found == null && !call) {
                found = importedClass(reference, name, db);
            }
        } else {
            PsiElement target = qualifier instanceof PsiJavaCodeReferenceElement q ? q.resolve() : null;
            if (target instanceof PsiPackage p) {
                found = call ? null : apiClassFound(db, p.getQualifiedName() + "." + name);
            } else {
                PsiClass c = target instanceof PsiClass k ? k
                        : qualifier instanceof PsiExpression e && e.getType() instanceof PsiClassType t ? t.resolve() : null;
                if (c != null && reference instanceof PsiReferenceExpression) {
                    found = member(c, name, call, db);
                }
                if (found == null && !call && target instanceof PsiClass outer && outer.getQualifiedName() != null) {
                    found = apiClassFound(db, outer.getQualifiedName() + "." + name);
                }
            }
        }
        return found == null || found.versions() == 0 || (found.versions() & (1L << version)) != 0 ? null : found;
    }

    /**
     * For the name of a class import ({@code import a.b.C;}): the class the database knows by that name and the
     * versions that have it, also where the active version lacks its package (which {@link #find} needs); otherwise
     * {@code null}.
     */
    static @Nullable Found importedClass(PsiJavaCodeReferenceElement reference, ApiDatabase db) {
        String name = reference.getParent() instanceof PsiImportStatement ? reference.getQualifiedName() : null;
        return name == null ? null : apiClassFound(db, name);
    }

    /**
     * What an unresolved {@code reference} in code that runs in the active version names in other versions, as
     * {@link #find} or, for an import, {@link #importedClass}; {@code null} if no other version has it.
     */
    static @Nullable Found inOtherVersions(PsiJavaCodeReferenceElement reference, ApiDatabase db, int active) {
        Found found = find(reference, db, active);
        if (found == null) {
            found = importedClass(reference, db);
        }
        return found == null || (found.versions() & ~(1L << active)) == 0 ? null : new Found(found.what(), found.versions() & ~(1L << active));
    }

    /**
     * The import that hides the class an unresolved {@code reference} names: the IDE resolves a name through the
     * first single import of it only, so an import of another version's class (one that moved to another
     * package) hides a later import of the same name that does resolve. {@code null} if there is none.
     */
    static @Nullable String shadowingImport(PsiJavaCodeReferenceElement reference, ApiDatabase db) {
        String name = reference.getReferenceName();
        if (name == null || reference.getQualifier() != null || reference.getParent() instanceof PsiMethodCallExpression
                || !(reference.getContainingFile() instanceof PsiJavaFile file)) {
            return null;
        }
        return shadowingImport(name, file, db);
    }

    /** The import that hides the class {@code name} in {@code file}, as above. */
    static @Nullable String shadowingImport(String name, PsiJavaFile file, ApiDatabase db) {
        if (file.getImportList() == null) {
            return null;
        }
        String first = null;
        for (PsiImportStatement statement : file.getImportList().getImportStatements()) {
            String imported = statement.getQualifiedName();
            if (statement.isOnDemand() || imported == null || !(imported.equals(name) || imported.endsWith("." + name))) {
                continue;
            }
            if (first == null) {
                if (statement.resolve() != null || apiClass(db, imported) == null) {
                    return null;
                }
                first = imported;
            } else if (statement.resolve() instanceof PsiClass) {
                return first;
            }
        }
        return null;
    }

    /** A class named {@code name} as the file's imports and package would find it. */
    private static @Nullable Found importedClass(PsiElement at, String name, ApiDatabase db) {
        PsiFile file = at.getContainingFile();
        if (!(file instanceof PsiJavaFile javaFile)) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        PsiImportList imports = javaFile.getImportList();
        if (imports != null) {
            for (PsiImportStatement statement : imports.getImportStatements()) {
                String imported = statement.getQualifiedName();
                if (imported != null && !statement.isOnDemand() && (imported.equals(name) || imported.endsWith("." + name))) {
                    candidates.add(imported);
                }
            }
            for (PsiImportStatement statement : imports.getImportStatements()) {
                if (statement.isOnDemand() && statement.getQualifiedName() != null) {
                    candidates.add(statement.getQualifiedName() + "." + name);
                }
            }
        }
        candidates.add(javaFile.getPackageName().isEmpty() ? name : javaFile.getPackageName() + "." + name);
        for (String candidate : candidates) {
            Found found = apiClassFound(db, candidate);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The versions in which {@code name} resolves as a method or field when used through {@code c}. */
    private static @Nullable Found member(PsiClass c, String name, boolean method, ApiDatabase db) {
        String start = null;
        for (PsiClass k = c; k != null && start == null; k = k.getSuperClass()) {
            String internal = JvmNames.internalName(k);
            ApiClass api = internal == null ? null : db.get(internal);
            if (api != null && !api.platform()) {
                start = internal;
            }
        }
        if (start == null) {
            return null;
        }
        // Every descriptor the name has in the class or its supertypes in any version, then JVM resolution per version.
        MemberKind kind = method ? MemberKind.METHOD : MemberKind.FIELD;
        Set<String> descriptors = new HashSet<>();
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(start));
        while (!queue.isEmpty()) {
            String owner = queue.pop();
            ApiClass api = seen.add(owner) ? db.get(owner) : null;
            if (api == null) {
                continue;
            }
            for (MemberKey key : (method ? api.methods() : api.fields()).keySet()) {
                if (key.name().equals(name)) {
                    descriptors.add(key.descriptor());
                }
            }
            for (ApiClass.Variant<ApiClass.ClassShape> shape : api.shapes()) {
                if (shape.shape().superName() != null) {
                    queue.add(shape.shape().superName());
                }
                queue.addAll(shape.shape().interfaces());
            }
        }
        long versions = 0;
        for (String descriptor : descriptors) {
            versions |= db.resolvable(kind, start, name, descriptor);
        }
        return versions == 0 ? null : new Found("'" + simpleName(start) + "." + name + "'", versions);
    }

    private static @Nullable Found apiClassFound(ApiDatabase db, String canonicalName) {
        String internal = apiClass(db, canonicalName);
        return internal == null ? null : new Found("'" + canonicalName + "'", db.get(internal).versions());
    }

    /**
     * The internal name of the database class with this canonical name ({@code a.b.Outer.Inner} is
     * {@code a/b/Outer$Inner}: each later dot is tried as '$'), or {@code null} if there is none.
     */
    static @Nullable String apiClass(ApiDatabase db, String canonicalName) {
        for (String internal = canonicalName.replace('.', '/'); ; ) {
            ApiClass c = db.get(internal);
            if (c != null) {
                return c.platform() ? null : internal;
            }
            int slash = internal.lastIndexOf('/');
            if (slash < 0) {
                return null;
            }
            internal = internal.substring(0, slash) + "$" + internal.substring(slash + 1);
        }
    }

    private static String simpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1).replace('$', '.');
    }
}
