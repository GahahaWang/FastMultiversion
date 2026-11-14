package cc.gahaha.fastmultiversion.apidb.generator;

import static cc.gahaha.fastmultiversion.apidb.ApiClass.MemberKind.METHOD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.gahaha.fastmultiversion.apidb.ApiClass;
import cc.gahaha.fastmultiversion.apidb.ApiDatabase;
import cc.gahaha.fastmultiversion.apidb.ApiFlags;
import cc.gahaha.fastmultiversion.versioning.McVersion;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiDbGeneratorTest {
    private static final long V1 = 0b01;
    private static final long V2 = 0b10;
    private static final long BOTH = 0b11;

    @TempDir
    Path temp;

    private Path v261;
    private Path v262;
    private ApiDatabase db;

    @BeforeEach
    void generate() throws IOException {
        v261 = ApiJars.build(temp, "mc-26.1", List.of("""
                package mc;
                public class Registry {
                    public int size() { return 1; }
                    protected void hook() {}
                    public static Runnable task() { return new Runnable() { public void run() {} }; }
                    public static class Entry {}
                    private static class Secret {}
                }
                """, """
                package mc;
                public class Thing implements Runnable { public void run() {} }
                """));
        v262 = ApiJars.build(temp, "mc-26.2", List.of("""
                package mc;
                public class Registry {
                    public long size() { return 2; }
                    public void hook() {}
                    public static class Entry {}
                }
                """, """
                package mc;
                public class Thing {}
                """, """
                package mc;
                public class Box<T> implements java.util.function.Supplier<T> { public T get() { return null; } }
                """));
        // Deliberately out of order: the generator sorts by version.
        db = ApiDbGenerator.generate(List.of(
                new VersionJars(McVersion.parse("26.2"), List.of(v262)),
                new VersionJars(McVersion.parse("26.1"), List.of(v261))));
    }

    @Test
    void recordsWhereEachClassAndMemberExists() {
        assertEquals(List.of(McVersion.parse("26.1"), McVersion.parse("26.2")), db.versions());
        assertEquals(BOTH, db.get("mc/Registry").versions());
        assertEquals(V2, db.get("mc/Box").versions());
        assertEquals(V1, db.resolvable(METHOD, "mc/Registry", "size", "()I"));
        assertEquals(V2, db.resolvable(METHOD, "mc/Registry", "size", "()J"));
        assertEquals(V2, db.compatible(METHOD, "mc/Registry", "hook", "()V", 1), "public only from 26.2");
        assertEquals(BOTH, db.resolvable(METHOD, "mc/Registry", "<init>", "()V"));
    }

    @Test
    void keepsNestedClassModifiersAndDropsAnonymousClasses() {
        assertNull(db.get("mc/Registry$1"), "anonymous classes are not API");
        ApiClass secret = db.get("mc/Registry$Secret");
        assertNotNull(secret);
        int access = secret.shapeIn(0).access();
        assertEquals(0, ApiFlags.visibility(access), "private, as declared, not package-private as in the class file");
        assertTrue(ApiFlags.isStatic(access));
    }

    @Test
    void followsInheritanceIntoTheJdk() {
        ApiClass runnable = db.get("java/lang/Runnable");
        assertNotNull(runnable);
        assertTrue(runnable.platform());
        assertEquals(V1, db.subtype("mc/Thing", "java/lang/Runnable"));
        assertEquals(V1, db.resolvable(METHOD, "mc/Thing", "run", "()V"));
        assertEquals(BOTH, db.resolvable(METHOD, "mc/Thing", "hashCode", "()I"), "inherited from Object");
        assertEquals(V2, db.resolvable(METHOD, "mc/Box", "get", "()Ljava/lang/Object;"));
        assertTrue(db.get("mc/Box").shapeIn(1).signature().contains("Ljava/util/function/Supplier<TT;>;"));
    }

    @Test
    void roundTripsThroughAFile() throws IOException {
        Path file = temp.resolve("api.mcapi");
        db.write(file);
        ApiDatabase read = ApiDatabase.read(file);
        assertEquals(new ArrayList<>(db.classes()), new ArrayList<>(read.classes()));
    }

    @Test
    void diffListsTheDifferences() {
        String diff = ApiDiff.format(db);
        assertTrue(diff.contains("[26.2,)                  mc/Box"), diff);
        assertTrue(diff.contains("size()I"), diff);
        assertTrue(diff.contains("hook()V"), diff);
        assertTrue(diff.contains("[26.1,26.2)          protected"), diff);
    }

    @Test
    void rejectsTheSameVersionTwice() {
        assertThrows(IllegalArgumentException.class, () -> ApiDbGenerator.generate(List.of(
                new VersionJars(McVersion.parse("26.1"), List.of(v261)),
                new VersionJars(McVersion.parse("26.1.0"), List.of(v262)))));
    }
}
