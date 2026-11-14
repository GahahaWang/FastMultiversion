package cc.gahaha.fastmultiversion.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pruning runs before javac's flow analysis, so each case below would fail to compile for one of the two
 * versions if a dead branch were simply deleted: missing return, unreachable code, or an unassigned final.
 */
class FlowTest {
    @TempDir
    Path temp;

    private static final String FLOW = """
            package t;
            import cc.gahaha.fastmultiversion.Mc;
            public class Flow {
                public static int bothReturn() {
                    if (Mc.is("[26.1,26.2)")) { return 1; } else { return 2; }
                }
                public static int earlyReturn() {
                    if (Mc.is("[26.2,)")) { return 2; }
                    return 1;
                }
                public static int breakOuter() {
                    int i = 0;
                    outer:
                    while (true) {
                        if (Mc.is("[26.1,26.2)")) {
                            break outer;
                        } else {
                            i++;
                            if (i > 3) break;
                        }
                    }
                    return i;
                }
                public static int onlyExitIsDead() {
                    int i = 0;
                    while (true) {
                        i++;
                        if (Mc.is("[26.1,26.2)")) {
                            if (i > 2) break;
                        } else {
                            if (i > 5) return i;
                        }
                    }
                    return -i;
                }
                public static int continueLoop() {
                    int sum = 0;
                    for (int i = 0; i < 5; i++) {
                        if (Mc.is("[26.1,26.2)")) { continue; }
                        sum += i;
                    }
                    return sum;
                }
                public static int definiteAssignment() {
                    final int v;
                    if (Mc.is("[26.1,26.2)")) v = 1; else v = 2;
                    return v;
                }
                public static int switchInBranch() {
                    int k = 1;
                    if (Mc.is("[26.1,26.2)")) {
                        switch (k) { case 1: return 10; default: return 11; }
                    } else {
                        return 20;
                    }
                }
                public static int tryInBranch() {
                    if (Mc.is("[26.1,26.2)")) {
                        try { return 1; } finally { }
                    } else {
                        return 2;
                    }
                }
                public static int labeledBlock() {
                    int r = 0;
                    block: {
                        if (Mc.is("[26.2,)")) { r = 2; break block; }
                        r = 1;
                    }
                    return r;
                }
                public static int switchExpression() {
                    int k = 1;
                    return switch (k) {
                        case 1 -> {
                            if (Mc.is("[26.1,26.2)")) { yield 1; } else { yield 2; }
                        }
                        default -> 0;
                    };
                }
            }
            """;

    private static final List<String> METHODS = List.of("bothReturn", "earlyReturn", "breakOuter", "onlyExitIsDead",
            "continueLoop", "definiteAssignment", "switchInBranch", "tryInBranch", "labeledBlock", "switchExpression");

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "26.1 | 1, 1, 0, -3, 0, 1, 10, 1, 1, 1",
            "26.2 | 2, 2, 4, 6, 10, 2, 20, 2, 2, 2"})
    void prunedCodeFlowsLikeTheOriginal(String target, String expected) throws Exception {
        var result = TestCompiler.target(temp, target).source(FLOW).compile().assertSuccess();
        StringBuilder actual = new StringBuilder();
        for (String method : METHODS) {
            actual.append(actual.isEmpty() ? "" : ", ").append(result.call("t.Flow", method));
        }
        assertEquals(expected, actual.toString());
    }
}
