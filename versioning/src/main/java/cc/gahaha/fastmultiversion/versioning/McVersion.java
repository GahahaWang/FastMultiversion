package cc.gahaha.fastmultiversion.versioning;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Minecraft version such as {@code 26.1}, {@code 26.1.2}, {@code 26.2-snapshot-3}, {@code 26.2-pre-1}
 * or {@code 26.2-rc-1}.
 *
 * <p>Numeric parts compare as numbers, and missing trailing parts count as zero. A snapshot, pre-release
 * or release candidate sorts before the release with the same number.
 */
public final class McVersion implements Comparable<McVersion> {
    private static final Pattern PATTERN =
            Pattern.compile("(\\d+(?:\\.\\d+)*)(?:-(snapshot|pre|rc)-?(\\d+))?");

    private enum Stage { SNAPSHOT, PRE, RC, RELEASE }

    private final String text;
    private final int[] numbers;
    private final Stage stage;
    private final int stageNumber;

    private McVersion(String text, int[] numbers, Stage stage, int stageNumber) {
        this.text = text;
        this.numbers = numbers;
        this.stage = stage;
        this.stageNumber = stageNumber;
    }

    /**
     * Parses a version.
     *
     * @throws IllegalArgumentException if {@code text} is not a valid version
     */
    public static McVersion parse(String text) {
        String trimmed = text.strip();
        Matcher m = PATTERN.matcher(trimmed.toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            throw new IllegalArgumentException("invalid Minecraft version '" + text
                    + "' (expected e.g. 26.1, 26.1.2, 26.2-snapshot-1, 26.2-pre-1, 26.2-rc-1)");
        }
        int[] numbers;
        try {
            numbers = Arrays.stream(m.group(1).split("\\.")).mapToInt(Integer::parseInt).toArray();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid Minecraft version '" + text + "': number too large");
        }
        int end = numbers.length;
        while (end > 1 && numbers[end - 1] == 0) {
            end--;
        }
        numbers = Arrays.copyOf(numbers, end);
        if (m.group(2) == null) {
            return new McVersion(trimmed, numbers, Stage.RELEASE, 0);
        }
        Stage stage = Stage.valueOf(m.group(2).toUpperCase(Locale.ROOT));
        return new McVersion(trimmed, numbers, stage, Integer.parseInt(m.group(3)));
    }

    @Override
    public int compareTo(McVersion other) {
        int length = Math.max(numbers.length, other.numbers.length);
        for (int i = 0; i < length; i++) {
            int a = i < numbers.length ? numbers[i] : 0;
            int b = i < other.numbers.length ? other.numbers[i] : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        int byStage = stage.compareTo(other.stage);
        return byStage != 0 ? byStage : Integer.compare(stageNumber, other.stageNumber);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof McVersion other && compareTo(other) == 0;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Arrays.hashCode(numbers) + stage.hashCode()) + stageNumber;
    }

    @Override
    public String toString() {
        return text;
    }
}
