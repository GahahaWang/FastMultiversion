package cc.gahaha.fastmultiversion.versioning;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lines of an access widener, class tweaker or access transformer that only some Minecraft versions get: an
 * entry ends with a comment that holds {@code @Mc.Is} with a version range, as in code:
 *
 * <pre>
 * accessible method net/minecraft/world/entity/Entity calculateViewVector (FF)Lnet/minecraft/world/phys/Vec3;  # @Mc.Is("[26.1,26.3)")
 * </pre>
 *
 * Each version's build keeps the lines without such a comment and those whose range contains the version. All
 * three formats end an entry at {@code #}, so the files stay valid as they are.
 */
public final class VersionedLines {
    private static final Pattern MARKER = Pattern.compile("@Mc\\.Is\\(\\s*\"([^\"]*)\"\\s*\\)");

    private VersionedLines() {
    }

    /**
     * Where a line's {@code @Mc.Is} is.
     *
     * @param start      the offset of {@code @Mc.Is} in the line
     * @param end        the offset just after its closing parenthesis
     * @param rangeStart the offset of the range text (inside the quotes)
     * @param rangeEnd   the offset just after the range text
     * @param range      the range text
     */
    public record Marker(int start, int end, int rangeStart, int rangeEnd, String range) {
    }

    /**
     * Whether files with this name (no directories) are filtered per version: access wideners, class tweakers and
     * access transformers ({@code accesstransformer.cfg}, or named like {@code mymod_at.cfg}).
     */
    public static boolean isVersionedFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".accesswidener") || lower.endsWith(".classtweaker") || lower.endsWith("_at.cfg")
                || (lower.startsWith("accesstransformer") && lower.endsWith(".cfg"));
    }

    /** The {@code @Mc.Is} in the comment at the end of {@code line}, or {@code null} if there is none. */
    public static Marker marker(String line) {
        int comment = line.indexOf('#');
        if (comment < 0) {
            return null;
        }
        Matcher matcher = MARKER.matcher(line);
        if (!matcher.find(comment)) {
            return null;
        }
        return new Marker(matcher.start(), matcher.end(), matcher.start(1), matcher.end(1), matcher.group(1));
    }

    /**
     * Whether {@code version} gets {@code line}: it has no {@code @Mc.Is}, or its range contains the version.
     *
     * @throws IllegalArgumentException if the range is not valid
     */
    public static boolean appliesTo(String line, McVersion version) {
        Marker marker = marker(line);
        return marker == null || VersionRange.parse(marker.range()).contains(version);
    }
}
