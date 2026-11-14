package cc.gahaha.fastmultiversion.javac;

import cc.gahaha.fastmultiversion.apidb.ApiClass;
import cc.gahaha.fastmultiversion.apidb.ApiClass.MemberKind;
import cc.gahaha.fastmultiversion.apidb.ApiDatabase;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ArrayType;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCMemberReference;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCNewClass;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.Context;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Checks, after attribution, that the kept code only uses Minecraft API where it is available.
 *
 * <p>For every reference to a class or member in the API database, the versions the code runs in (its
 * guard, from {@link SourcePruner}) must all be versions in which that class or member can be used exactly
 * as javac resolved it for the target: present, reachable through the same class, and with the same
 * static-ness, visibility and generic signature ({@link ApiDatabase#compatible}). A method that overrides
 * a Minecraft method must likewise only exist where that method does; elsewhere it would silently stop
 * being called.
 *
 * <p>Everything outside the database (the mod's own classes, the JDK, libraries the database lacks) is
 * not checked.
 */
final class RangeChecker extends TreeScanner {
    private final MultiversionListener host;
    private final CompilationUnitTree unit;
    private final ApiDatabase db;
    private final int target;
    private final Map<JCTree, Long> guards;
    private final Types types;
    private final Deque<ClassSymbol> classes = new ArrayDeque<>();
    private long guard;

    RangeChecker(MultiversionListener host, CompilationUnitTree unit, ApiDatabase db, int target,
                 Map<JCTree, Long> guards, Context context) {
        this.host = host;
        this.unit = unit;
        this.db = db;
        this.target = target;
        this.guards = guards;
        this.types = Types.instance(context);
        this.guard = db.allVersions();
    }

    @Override
    public void scan(JCTree tree) {
        if (tree == null) {
            return;
        }
        Long narrowed = guards.get(tree);
        if (narrowed == null) {
            tree.accept(this);
        } else if (narrowed != 0) { // 0: a copy of code checked elsewhere
            long saved = guard;
            guard = narrowed;
            tree.accept(this);
            guard = saved;
        }
    }

    @Override
    public void visitClassDef(JCClassDecl tree) {
        classes.push(tree.sym);
        super.visitClassDef(tree);
        classes.pop();
    }

    @Override
    public void visitIdent(JCIdent tree) {
        check(tree.sym, tree, null);
    }

    @Override
    public void visitSelect(JCFieldAccess tree) {
        if (check(tree.sym, tree, tree.selected.type)) {
            super.visitSelect(tree);
        }
    }

    @Override
    public void visitReference(JCMemberReference tree) {
        if (check(tree.sym, tree, tree.expr.type)) {
            super.visitReference(tree);
        }
    }

    @Override
    public void visitNewClass(JCNewClass tree) {
        scan(tree.encl);
        scan(tree.typeargs);
        boolean anonymous = tree.def != null;
        // An anonymous class's constructor is synthetic; its class's availability is what matters.
        if (anonymous || check(tree.constructor, tree, tree.clazz.type)) {
            scan(tree.clazz);
        }
        scan(tree.args);
        scan(tree.def);
    }

    @Override
    public void visitMethodDef(JCMethodDecl tree) {
        checkOverrides(tree);
        super.visitMethodDef(tree);
    }

    // ---- references

    /** Returns false after reporting, so the enclosing parts of the same reference are not reported again. */
    private boolean check(Symbol sym, JCTree at, Type site) {
        if (sym == null || sym.kind == Kind.ERR || sym.kind.isResolutionError()) {
            return true;
        }
        switch (sym.kind) {
            case TYP -> {
                if (!(sym instanceof ClassSymbol c)) {
                    return true; // a type variable
                }
                String name = internalName(c);
                ApiClass api = db.get(name);
                if (api == null || api.platform()) {
                    return true;
                }
                return report(db.classCompatible(name, target), api.versions(), at, "'" + c.getQualifiedName() + "'");
            }
            case VAR -> {
                if (sym.owner == null || sym.owner.kind != Kind.TYP || sym.name.contentEquals("this")
                        || sym.name.contentEquals("super") || sym.name.contentEquals("class")) {
                    return true; // locals, parameters, this, super, Foo.class
                }
                return checkMember(MemberKind.FIELD, sym, descriptor(sym.type), at, site);
            }
            case MTH -> {
                return checkMember(MemberKind.METHOD, sym, methodDescriptor((MethodSymbol) sym), at, site);
            }
            default -> {
                return true;
            }
        }
    }

    private boolean checkMember(MemberKind kind, Symbol sym, String descriptor, JCTree at, Type site) {
        String owner = qualifier(kind, sym, descriptor, site);
        if (owner == null) {
            return true;
        }
        String name = sym.name.toString();
        long compatible = db.compatible(kind, owner, name, descriptor, target);
        long present = db.resolvable(kind, owner, name, descriptor);
        return report(compatible, present, at, describe(sym));
    }

    /**
     * The class to look the member up through, as the JVM would: the type it is used through (or, if
     * that is the mod's own class, its nearest superclass in the database). Falls back to the class that
     * declares it in the target version.
     */
    private String qualifier(MemberKind kind, Symbol sym, String descriptor, Type site) {
        Symbol start = null;
        if (site != null && (site.hasTag(TypeTag.CLASS) || site.hasTag(TypeTag.TYPEVAR))) {
            start = types.erasure(site).tsym;
        } else if (site == null) {
            for (ClassSymbol c : classes) { // an unqualified name: the innermost class that inherits it
                if (c.isSubClass(sym.owner, types)) {
                    start = c;
                    break;
                }
            }
        }
        for (Type t = start == null ? null : start.type; t != null && t.hasTag(TypeTag.CLASS); t = types.supertype(t)) {
            String name = internalName((ClassSymbol) t.tsym);
            ApiClass api = db.get(name);
            if (api != null) {
                if (!api.platform()
                        && db.resolve(kind, name, sym.name.toString(), descriptor, target).status() == ApiDatabase.Resolution.Status.FOUND) {
                    return name;
                }
                break;
            }
        }
        if (!(sym.owner instanceof ClassSymbol declaring)) {
            return null;
        }
        String name = internalName(declaring);
        ApiClass api = db.get(name);
        return api == null || api.platform() ? null : name;
    }

    // ---- overrides

    private void checkOverrides(JCMethodDecl tree) {
        MethodSymbol method = tree.sym;
        if (method == null || method.isConstructor() || method.isStatic() || (method.flags() & Flags.PRIVATE) != 0
                || !(method.owner instanceof ClassSymbol owner)) {
            return;
        }
        for (Type supertype : types.closure(owner.type)) {
            if (supertype.tsym == owner || !(supertype.tsym instanceof ClassSymbol superclass)) {
                continue;
            }
            String name = internalName(superclass);
            ApiClass api = db.get(name);
            if (api == null || api.platform()) {
                continue;
            }
            for (Symbol member : superclass.members().getSymbolsByName(method.name)) {
                if (member.kind == Kind.MTH && method.overrides(member, owner, types, false)) {
                    String descriptor = methodDescriptor((MethodSymbol) member);
                    long compatible = db.compatible(MemberKind.METHOD, name, member.name.toString(), descriptor, target);
                    if ((guard & ~compatible) != 0) {
                        String usable = db.range(compatible);
                        host.error(tree, unit, "'" + owner.getSimpleName() + "." + method.name + "' overrides "
                                + describe(member) + ", which only exists like this in " + usable
                                + ", but this method exists in " + db.range(guard) + ". Elsewhere it would override "
                                + "nothing and never be called. Give it @IfMc(\"" + usable + "\").");
                        return;
                    }
                }
            }
        }
    }

    // ---- reporting

    /**
     * Reports if the guard reaches versions outside {@code compatible}.
     *
     * @param present the versions that have the class or member at all, to tell "missing" from "changed"
     */
    private boolean report(long compatible, long present, JCTree at, String what) {
        if ((guard & ~compatible) == 0) {
            return true;
        }
        String usable = db.range(compatible);
        String problem = (guard & ~present) != 0
                ? what + " only exists in " + usable
                : what + " exists in every version this code runs in, but only has this form (signature, "
                        + "static-ness or visibility) in " + usable;
        host.error(at, unit, problem + ", but this code runs in " + db.range(guard) + ". Use it inside "
                + "if (Mc.is(\"" + usable + "\")) or in a declaration with @IfMc(\"" + usable + "\").");
        return false;
    }

    private String describe(Symbol sym) {
        String owner = sym.owner.getSimpleName().toString();
        if (sym instanceof MethodSymbol method) {
            String name = method.isConstructor() ? owner : owner + "." + method.name;
            return "'" + name + method.type.getParameterTypes().stream().map(RangeChecker::simple)
                    .collect(Collectors.joining(", ", "(", ")")) + "'";
        }
        return "'" + owner + "." + sym.name + "'";
    }

    private static String simple(Type type) {
        if (type instanceof ClassType c) {
            String base = c.tsym.getSimpleName().toString();
            return c.getTypeArguments().isEmpty() ? base
                    : base + c.getTypeArguments().stream().map(RangeChecker::simple).collect(Collectors.joining(",", "<", ">"));
        }
        if (type instanceof ArrayType a) {
            return simple(a.elemtype) + "[]";
        }
        return type.toString();
    }

    // ---- descriptors

    private String methodDescriptor(MethodSymbol method) {
        Type erased = types.erasure(method.type);
        StringBuilder s = new StringBuilder("(");
        for (Type parameter : erased.getParameterTypes()) {
            s.append(descriptor(parameter));
        }
        return s.append(')').append(descriptor(erased.getReturnType())).toString();
    }

    private String descriptor(Type type) {
        Type t = types.erasure(type);
        return switch (t.getTag()) {
            case BYTE -> "B";
            case CHAR -> "C";
            case DOUBLE -> "D";
            case FLOAT -> "F";
            case INT -> "I";
            case LONG -> "J";
            case SHORT -> "S";
            case BOOLEAN -> "Z";
            case VOID -> "V";
            case ARRAY -> "[" + descriptor(((ArrayType) t).elemtype);
            case CLASS -> "L" + internalName((ClassSymbol) t.tsym) + ";";
            default -> "Ljava/lang/Object;";
        };
    }

    private static String internalName(ClassSymbol c) {
        return c.flatname.toString().replace('.', '/');
    }
}
