package cc.gahaha.fastmultiversion.intellij;

import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.ClassUtil;
import com.intellij.psi.util.TypeConversionUtil;
import org.jetbrains.annotations.Nullable;

/** Class-file names and descriptors of PSI classes, types and methods, as the API database stores them. */
final class JvmNames {
    private JvmNames() {
    }

    /** E.g. {@code net/minecraft/world/item/ItemStack}; {@code null} for local and anonymous classes. */
    static @Nullable String internalName(PsiClass c) {
        String name = ClassUtil.getJVMClassName(c);
        return name == null ? null : name.replace('.', '/');
    }

    static @Nullable String descriptor(PsiType type) {
        PsiType t = TypeConversionUtil.erasure(type);
        if (t instanceof PsiPrimitiveType primitive) {
            return switch (primitive.getName()) {
                case "int" -> "I";
                case "long" -> "J";
                case "boolean" -> "Z";
                case "byte" -> "B";
                case "char" -> "C";
                case "short" -> "S";
                case "float" -> "F";
                case "double" -> "D";
                case "void" -> "V";
                default -> null;
            };
        }
        if (t instanceof PsiArrayType array) {
            String component = descriptor(array.getComponentType());
            return component == null ? null : "[" + component;
        }
        if (t instanceof PsiClassType classType && classType.resolve() instanceof PsiClass c) {
            String name = internalName(c);
            return name == null ? null : "L" + name + ";";
        }
        return null;
    }

    static @Nullable String descriptor(PsiMethod method) {
        StringBuilder s = new StringBuilder("(");
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            String d = descriptor(parameter.getType());
            if (d == null) {
                return null;
            }
            s.append(d);
        }
        String result = method.isConstructor() ? "V" : method.getReturnType() == null ? null : descriptor(method.getReturnType());
        return result == null ? null : s.append(')').append(result).toString();
    }
}
