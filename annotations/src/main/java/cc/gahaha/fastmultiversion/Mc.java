package cc.gahaha.fastmultiversion;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Minecraft version conditions that are resolved at compile time.
 *
 * <p>The same sources are compiled once per Minecraft version, each time against that version's real
 * jars. Right after parsing, the multiversion javac plugin replaces each {@code Mc.is(...)} call with
 * {@code true} or {@code false} and removes the code that cannot run on that version, so javac never
 * sees it. Every branch stays ordinary Java, and the finished bytecode contains no reference to this
 * class.
 *
 * <pre>{@code
 * if (Mc.is("[26.1, 26.2)")) {
 *     oldApi();
 * } else if (Mc.is("[26.2,)")) {
 *     newApi();
 * }
 *
 * int size = Mc.is("[26.2,)") ? newSize() : oldSize();
 * }</pre>
 *
 * <p>For declarations, {@link Is @Mc.Is} keeps a class, method or field only in some versions.
 *
 * <h2>Range syntax</h2>
 * <ul>
 *   <li>{@code [26.1, 26.2)}: from 26.1 (inclusive) to 26.2 (exclusive)</li>
 *   <li>{@code [26.2,)}: 26.2 and later; {@code (,26.2]}: up to and including 26.2</li>
 *   <li>{@code [26.1]} or {@code 26.1}: exactly 26.1</li>
 *   <li>{@code [26.1,26.2),[26.3,)}: several ranges separated by commas</li>
 * </ul>
 * A missing patch number counts as zero, so {@code 26.1} equals {@code 26.1.0}. Snapshots, pre-releases
 * and release candidates sort before the release they lead to:
 * {@code 26.2-snapshot-1 < 26.2-pre-1 < 26.2-rc-1 < 26.2}.
 */
public final class Mc {
    private Mc() {
    }

    /**
     * Returns whether the Minecraft version being compiled for lies within {@code range}.
     *
     * <p>{@code range} must be a string literal, or a {@code String} constant declared in the same file
     * (the plugin runs before names are resolved, so it cannot follow constants into other files).
     * Always call this method directly; it is replaced while compiling, and calling it at runtime throws.
     *
     * @param range a version range, see {@link Mc} for the syntax
     * @return the compile-time result for the target version
     * @throws IllegalStateException always, if this code was compiled without the multiversion plugin
     */
    public static boolean is(String range) {
        throw new IllegalStateException("Mc.is(\"" + range + "\") was compiled without the multiversion "
                + "javac plugin (-Xplugin:FastMultiversion), so it was not replaced with a constant");
    }

    /**
     * Keeps a declaration only in builds for Minecraft versions inside {@link #value()}.
     *
     * <p>In a build for any other version, the multiversion javac plugin removes the declaration from the
     * source before javac resolves anything, exactly as if it had not been written. Its signature may
     * therefore use classes that only exist in the versions it is kept for.
     *
     * <pre>{@code
     * @Mc.Is("[26.1, 26.2)")
     * @Override
     * public void render(OldContext ctx) { ... }
     *
     * @Mc.Is("[26.2,)")
     * @Override
     * public void render(NewContext ctx) { ... }
     * }</pre>
     *
     * <p>Works on classes (top-level, nested and local), methods, constructors, fields, enum constants and
     * record components. Code that is kept in a build cannot refer to a declaration that the build removed;
     * javac reports "cannot find symbol" and the plugin adds a note explaining why. Use such a declaration
     * only inside a matching {@link Mc#is(String)} branch or another declaration with a matching
     * {@code @Mc.Is}.
     *
     * <p>The range must be a string literal or a {@code String} constant declared in the same file. Java still
     * has to accept every declaration together, so two methods with the same signature cannot be told apart
     * by {@code @Mc.Is}; use {@link Mc#is(String)} inside a single method instead.
     */
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
    public @interface Is {
        /**
         * The Minecraft versions that keep this declaration, using the range syntax described in {@link Mc}.
         *
         * @return the version range
         */
        String value();
    }
}
