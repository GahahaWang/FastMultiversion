package cc.gahaha.fastmultiversion.versioning;

import java.util.ArrayList;
import java.util.List;

/**
 * A set of Minecraft versions written as one or more intervals: {@code [26.1,26.2)}, {@code [26.2,)},
 * {@code (,26.2]}, {@code [26.1]}, {@code 26.1}, or a comma-separated list such as
 * {@code [26.1,26.2),[26.3,)}.
 */
public final class VersionRange {
    private record Interval(McVersion lower, boolean lowerInclusive, McVersion upper, boolean upperInclusive) {
        boolean contains(McVersion v) {
            if (lower != null) {
                int c = v.compareTo(lower);
                if (c < 0 || (c == 0 && !lowerInclusive)) {
                    return false;
                }
            }
            if (upper != null) {
                int c = v.compareTo(upper);
                return c < 0 || (c == 0 && upperInclusive);
            }
            return true;
        }
    }

    private final String text;
    private final List<Interval> intervals;

    private VersionRange(String text, List<Interval> intervals) {
        this.text = text;
        this.intervals = intervals;
    }

    /**
     * Parses a range.
     *
     * @throws IllegalArgumentException if {@code text} is not a valid range
     */
    public static VersionRange parse(String text) {
        String s = text.strip();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("empty version range");
        }
        List<Interval> intervals = new ArrayList<>();
        int i = 0;
        while (true) {
            i = skipSpaces(s, i);
            if (i >= s.length()) {
                throw new IllegalArgumentException("version range '" + text + "' ends with a comma");
            }
            char open = s.charAt(i);
            if (open == '[' || open == '(') {
                int close = indexOfClosing(s, i + 1);
                if (close < 0) {
                    throw new IllegalArgumentException("version range '" + text + "' is missing ']' or ')'");
                }
                intervals.add(parseInterval(text, open, s.substring(i + 1, close), s.charAt(close)));
                i = close + 1;
            } else {
                int end = s.indexOf(',', i);
                if (end < 0) {
                    end = s.length();
                }
                McVersion exact = version(text, s.substring(i, end));
                intervals.add(new Interval(exact, true, exact, true));
                i = end;
            }
            i = skipSpaces(s, i);
            if (i >= s.length()) {
                break;
            }
            if (s.charAt(i) != ',') {
                throw new IllegalArgumentException("unexpected '" + s.charAt(i) + "' in version range '" + text + "'");
            }
            i++;
        }
        return new VersionRange(s, List.copyOf(intervals));
    }

    private static Interval parseInterval(String text, char open, String body, char close) {
        int comma = body.indexOf(',');
        if (comma < 0) {
            if (open != '[' || close != ']') {
                throw new IllegalArgumentException("single version in '" + text + "' must be written as [version]");
            }
            McVersion exact = version(text, body);
            return new Interval(exact, true, exact, true);
        }
        if (body.indexOf(',', comma + 1) >= 0) {
            throw new IllegalArgumentException("interval in '" + text + "' has more than two bounds");
        }
        String lowerText = body.substring(0, comma).strip();
        String upperText = body.substring(comma + 1).strip();
        McVersion lower = lowerText.isEmpty() ? null : version(text, lowerText);
        McVersion upper = upperText.isEmpty() ? null : version(text, upperText);
        boolean lowerInclusive = open == '[';
        boolean upperInclusive = close == ']';
        if (lower != null && upper != null) {
            int c = lower.compareTo(upper);
            if (c > 0 || (c == 0 && !(lowerInclusive && upperInclusive))) {
                throw new IllegalArgumentException("interval in '" + text + "' contains no versions");
            }
        }
        return new Interval(lower, lowerInclusive, upper, upperInclusive);
    }

    private static McVersion version(String rangeText, String versionText) {
        try {
            return McVersion.parse(versionText);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("in version range '" + rangeText + "': " + e.getMessage(), e);
        }
    }

    private static int indexOfClosing(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ']' || c == ')') {
                return i;
            }
            if (c == '[' || c == '(') {
                return -1;
            }
        }
        return -1;
    }

    private static int skipSpaces(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    public boolean contains(McVersion version) {
        for (Interval interval : intervals) {
            if (interval.contains(version)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return text;
    }
}
