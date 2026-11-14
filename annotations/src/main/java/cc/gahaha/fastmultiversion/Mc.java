package cc.gahaha.fastmultiversion;

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
}
