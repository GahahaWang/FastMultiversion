package cc.gahaha.fastmultiversion.apidb;

import static cc.gahaha.fastmultiversion.apidb.ApiClass.MemberKind.FIELD;
import static cc.gahaha.fastmultiversion.apidb.ApiClass.MemberKind.METHOD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cc.gahaha.fastmultiversion.apidb.ApiClass.ClassShape;
import cc.gahaha.fastmultiversion.apidb.ApiClass.MemberKind;
import cc.gahaha.fastmultiversion.apidb.ApiClass.MemberShape;
import cc.gahaha.fastmultiversion.apidb.ApiDatabase.Resolution.Status;
import cc.gahaha.fastmultiversion.versioning.McVersion;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiDatabaseTest {
    private static final int PUBLIC = ApiFlags.PUBLIC;
    private static final int V1 = 0b001; // 26.1
    private static final int V2 = 0b010; // 26.2
    private static final int V3 = 0b100; // 26.3
    private static final int ALL = 0b111;

    private ApiDatabaseBuilder builder;
    private ApiDatabase db;

    @BeforeEach
    void build() {
        builder = new ApiDatabaseBuilder(List.of(McVersion.parse("26.1"), McVersion.parse("26.2"), McVersion.parse("26.3")));
        builder.addPlatformClass("java/lang/Object", new ClassShape(PUBLIC, null, List.of(), null));
        builder.addMember(0, METHOD, "java/lang/Object", "toString", "()Ljava/lang/String;", member(PUBLIC));

        // tick() moves from Sub up to Base in 26.3; old() is removed in 26.2; make() changes its return type.
        cls("mc/Base", ALL, PUBLIC, "java/lang/Object");
        method("mc/Base", V3, "tick", "()V", PUBLIC, null);
        cls("mc/Sub", ALL, PUBLIC, "mc/Base");
        method("mc/Sub", V1 | V2, "tick", "()V", PUBLIC, null);
        method("mc/Sub", V1, "old", "()V", PUBLIC, null);
        method("mc/Sub", V1 | V2, "make", "()Lmc/A;", PUBLIC, null);
        method("mc/Sub", V3, "make", "()Lmc/B;", PUBLIC, null);

        // Same descriptor, but the generic signature, static-ness or visibility changes.
        cls("mc/Stack", ALL, PUBLIC, "java/lang/Object");
        method("mc/Stack", V1 | V2, "hurt", "(Ljava/util/function/Consumer;)V", PUBLIC, "(Ljava/util/function/Consumer<Lmc/Item;>;)V");
        method("mc/Stack", V3, "hurt", "(Ljava/util/function/Consumer;)V", PUBLIC, "(Ljava/util/function/Consumer<Lmc/Stack;>;)V");
        method("mc/Stack", V1, "count", "()I", PUBLIC, null);
        method("mc/Stack", V2 | V3, "count", "()I", PUBLIC | ApiFlags.STATIC, null);
        method("mc/Stack", V1, "hook", "()V", ApiFlags.PROTECTED, null);
        method("mc/Stack", V2 | V3, "hook", "()V", PUBLIC, null);

        // Thing implements Named only from 26.2; Named has an inherited default method and a static one.
        cls("mc/Named", ALL, PUBLIC | ApiFlags.INTERFACE | ApiFlags.ABSTRACT, "java/lang/Object");
        method("mc/Named", ALL, "name", "()Ljava/lang/String;", PUBLIC, null);
        method("mc/Named", ALL, "create", "()Lmc/Named;", PUBLIC | ApiFlags.STATIC, null);
        builder.addClass(0, "mc/Thing", new ClassShape(PUBLIC, "java/lang/Object", List.of(), null));
        for (int v : new int[] {1, 2}) {
            builder.addClass(v, "mc/Thing", new ClassShape(PUBLIC, "java/lang/Object", List.of("mc/Named"), null));
        }

        // A constant inherited from an interface, a supertype outside the database, and a class turned interface.
        cls("mc/Constants", ALL, PUBLIC | ApiFlags.INTERFACE | ApiFlags.ABSTRACT, "java/lang/Object");
        field("mc/Constants", ALL, "ID", "I", PUBLIC | ApiFlags.STATIC | ApiFlags.FINAL);
        cls("mc/Impl", ALL, PUBLIC, "java/lang/Object", "mc/Constants");
        cls("mc/Wrapper", ALL, PUBLIC, "lib/External");
        cls("mc/Shape", V1, PUBLIC, "java/lang/Object");
        cls("mc/Shape", V2 | V3, PUBLIC | ApiFlags.INTERFACE | ApiFlags.ABSTRACT, "java/lang/Object");

        db = builder.build();
    }

    @Test
    void methodsAreFoundWhereverTheJvmWouldResolveThem() {
        assertEquals(ALL, db.resolvable(METHOD, "mc/Sub", "tick", "()V"), "tick() moved to the superclass");
        assertEquals(ALL, db.compatible(METHOD, "mc/Sub", "tick", "()V", 0));
        assertEquals("mc/Base", db.resolve(METHOD, "mc/Sub", "tick", "()V", 2).declaringClass());
        assertEquals(V1, db.resolvable(METHOD, "mc/Sub", "old", "()V"));
        assertEquals(V1 | V2, db.resolvable(METHOD, "mc/Sub", "make", "()Lmc/A;"));
        assertEquals(ALL, db.resolvable(METHOD, "mc/Sub", "toString", "()Ljava/lang/String;"), "inherited from Object");
    }

    @Test
    void compatibilityAlsoComparesSignatureStaticnessAndVisibility() {
        String hurt = "(Ljava/util/function/Consumer;)V";
        assertEquals(ALL, db.resolvable(METHOD, "mc/Stack", "hurt", hurt));
        assertEquals(V1 | V2, db.compatible(METHOD, "mc/Stack", "hurt", hurt, 0), "Consumer<Item> only up to 26.2");
        assertEquals(V3, db.compatible(METHOD, "mc/Stack", "hurt", hurt, 2), "Consumer<Stack> only from 26.3");

        assertEquals(V1, db.compatible(METHOD, "mc/Stack", "count", "()I", 0), "static from 26.2");
        assertEquals(ALL, db.compatible(METHOD, "mc/Stack", "hook", "()V", 0), "protected use works where it is public");
        assertEquals(V2 | V3, db.compatible(METHOD, "mc/Stack", "hook", "()V", 1), "public use fails where it is protected");
    }

    @Test
    void interfacesContributeInheritedMembersAndSubtypes() {
        assertEquals(V2 | V3, db.resolvable(METHOD, "mc/Thing", "name", "()Ljava/lang/String;"));
        assertEquals(0, db.resolvable(METHOD, "mc/Thing", "create", "()Lmc/Named;"), "static interface methods are not inherited");
        assertEquals(V2 | V3, db.subtype("mc/Thing", "mc/Named"));
        assertEquals(ALL, db.subtype("mc/Sub", "mc/Base"));
        assertEquals(ALL, db.resolvable(FIELD, "mc/Impl", "ID", "I"), "fields are found in superinterfaces");
    }

    @Test
    void unknownSupertypesCountAsPresent() {
        assertEquals(Status.UNKNOWN, db.resolve(METHOD, "mc/Wrapper", "fromLibrary", "()V", 0).status());
        assertEquals(ALL, db.resolvable(METHOD, "mc/Wrapper", "fromLibrary", "()V"));
        assertEquals(ALL, db.subtype("mc/Wrapper", "lib/SomethingElse"));
        assertEquals(Status.MISSING, db.resolve(METHOD, "mc/Sub", "nothing", "()V", 0).status());
        assertEquals(ALL, db.classVersions("lib/External"));
    }

    @Test
    void classesComparesKindAndPresence() {
        assertEquals(ALL, db.classVersions("mc/Shape"));
        assertEquals(V2 | V3, db.classCompatible("mc/Shape", 2), "a class until 26.2, an interface after");
        assertEquals(V1, db.classCompatible("mc/Shape", 0));
    }

    @Test
    void rangesUseTheDatabaseVersions() {
        assertEquals("[26.1,26.2)", db.range(V1));
        assertEquals("[26.2,)", db.range(V2 | V3));
        assertEquals("[26.1,26.2),[26.3,)", db.range(V1 | V3));
    }

    @Test
    void fileFormatRoundTrips() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        db.write(bytes);
        ApiDatabase read = ApiDatabase.read(new ByteArrayInputStream(bytes.toByteArray()));

        assertEquals(db.versions(), read.versions());
        assertEquals(new ArrayList<>(db.classes()), new ArrayList<>(read.classes()));
        assertEquals(V1 | V2, read.compatible(METHOD, "mc/Stack", "hurt", "(Ljava/util/function/Consumer;)V", 0));
    }

    @Test
    void mergingSingleVersionDatabasesGivesTheSameAnswers() {
        List<ApiDatabase> parts = new ArrayList<>();
        for (int v = 0; v < 3; v++) {
            ApiDatabaseBuilder single = new ApiDatabaseBuilder(List.of(db.versions().get(v)));
            for (ApiClass c : db.classes()) {
                ClassShape shape = c.shapeIn(v);
                if (shape == null) {
                    continue;
                }
                if (c.platform()) {
                    single.addPlatformClass(c.name(), shape);
                } else {
                    single.addClass(0, c.name(), shape);
                }
                for (MemberKind kind : MemberKind.values()) {
                    int version = v;
                    (kind == FIELD ? c.fields() : c.methods()).forEach((key, variants) -> {
                        MemberShape member = c.member(kind, key, version);
                        if (member != null) {
                            single.addMember(0, kind, c.name(), key.name(), key.descriptor(), member);
                        }
                    });
                }
            }
            parts.add(single.build());
        }
        ApiDatabase merged = ApiDatabase.merge(List.of(parts.get(2), parts.get(0), parts.get(1)));

        assertEquals(db.versions(), merged.versions());
        assertEquals(new ArrayList<>(db.classes()), new ArrayList<>(merged.classes()));
        assertThrows(IllegalArgumentException.class, () -> ApiDatabase.merge(List.of(parts.get(0), parts.get(0))));
    }

    @Test
    void rejectsUnorderedVersions() {
        assertThrows(IllegalArgumentException.class,
                () -> new ApiDatabaseBuilder(List.of(McVersion.parse("26.2"), McVersion.parse("26.1"))));
    }

    // ---- helpers

    private void cls(String name, int versions, int access, String superName, String... interfaces) {
        for (int v = 0; v < 3; v++) {
            if ((versions & (1 << v)) != 0) {
                builder.addClass(v, name, new ClassShape(access, superName, List.of(interfaces), null));
            }
        }
    }

    private void method(String owner, int versions, String name, String descriptor, int access, String signature) {
        add(METHOD, owner, versions, name, descriptor, access, signature);
    }

    private void field(String owner, int versions, String name, String descriptor, int access) {
        add(FIELD, owner, versions, name, descriptor, access, null);
    }

    private void add(MemberKind kind, String owner, int versions, String name, String descriptor, int access, String signature) {
        for (int v = 0; v < 3; v++) {
            if ((versions & (1 << v)) != 0) {
                builder.addMember(v, kind, owner, name, descriptor, new MemberShape(access, signature));
            }
        }
    }

    private static MemberShape member(int access) {
        return new MemberShape(access, null);
    }
}
