package cc.gahaha.fastmultiversion;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Keeps a declaration only in builds for Minecraft versions inside {@link #value()}.
 *
 * <p>In a build for any other version, the multiversion javac plugin removes the declaration from the
 * source before javac resolves anything, exactly as if it had not been written. Its signature may
 * therefore use classes that only exist in the versions it is kept for.
 *
 * <pre>{@code
 * @IfMc("[26.1, 26.2)")
 * @Override
 * public void render(OldContext ctx) { ... }
 *
 * @IfMc("[26.2,)")
 * @Override
 * public void render(NewContext ctx) { ... }
 * }</pre>
 *
 * <p>Works on classes (top-level, nested and local), methods, constructors, fields, enum constants and
 * record components. Code that is kept in a build cannot refer to a declaration that the build removed;
 * javac reports "cannot find symbol" and the plugin adds a note explaining why. Use such a declaration
 * only inside a matching {@link Mc#is(String)} branch or another declaration with a matching
 * {@code @IfMc}.
 *
 * <p>The range must be a string literal or a {@code String} constant declared in the same file. Java still
 * has to accept every declaration together, so two methods with the same signature cannot be told apart
 * by {@code @IfMc}; use {@link Mc#is(String)} inside a single method instead.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface IfMc {
    /**
     * The Minecraft versions that keep this declaration, using the range syntax described in {@link Mc}.
     *
     * @return the version range
     */
    String value();
}
