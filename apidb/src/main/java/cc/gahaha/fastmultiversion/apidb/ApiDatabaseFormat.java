package cc.gahaha.fastmultiversion.apidb;

import cc.gahaha.fastmultiversion.apidb.ApiClass.ClassShape;
import cc.gahaha.fastmultiversion.apidb.ApiClass.MemberKey;
import cc.gahaha.fastmultiversion.apidb.ApiClass.MemberShape;
import cc.gahaha.fastmultiversion.apidb.ApiClass.Variant;
import cc.gahaha.fastmultiversion.versioning.McVersion;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The gzip-compressed binary file format: a header, the versions, a table of every string (names,
 * descriptors, signatures), then the classes, which refer to strings by index.
 *
 * <pre>
 * int magic "MCAP", int format
 * int n, n x string                  versions
 * int n, n x string                  string table
 * int n, n x class:
 *   int name, boolean platform
 *   int n, n x (long versions, int access, int superName, int n, n x int interface, int signature)
 *   2 x (int n, n x member)          fields, then methods
 *     member: int name, int descriptor, int n, n x (long versions, int access, int signature)
 * </pre>
 * Index -1 stands for {@code null}; a string is an int byte length followed by UTF-8 bytes.
 */
final class ApiDatabaseFormat {
    private static final int MAGIC = 0x4D434150; // "MCAP"
    private static final int FORMAT = 1;

    private ApiDatabaseFormat() {
    }

    static void write(ApiDatabase db, OutputStream target) throws IOException {
        Map<String, Integer> strings = new LinkedHashMap<>();
        for (ApiClass c : db.classes()) {
            intern(strings, c.name());
            for (Variant<ClassShape> shape : c.shapes()) {
                intern(strings, shape.shape().superName());
                shape.shape().interfaces().forEach(i -> intern(strings, i));
                intern(strings, shape.shape().signature());
            }
            for (Map<MemberKey, List<Variant<MemberShape>>> members : List.of(c.fields(), c.methods())) {
                members.forEach((key, variants) -> {
                    intern(strings, key.name());
                    intern(strings, key.descriptor());
                    variants.forEach(v -> intern(strings, v.shape().signature()));
                });
            }
        }

        GZIPOutputStream gzip = new GZIPOutputStream(target);
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(gzip, 1 << 16));
        out.writeInt(MAGIC);
        out.writeInt(FORMAT);
        out.writeInt(db.versions().size());
        for (McVersion version : db.versions()) {
            writeString(out, version.toString());
        }
        out.writeInt(strings.size());
        for (String s : strings.keySet()) {
            writeString(out, s);
        }
        out.writeInt(db.classes().size());
        for (ApiClass c : db.classes()) {
            out.writeInt(strings.get(c.name()));
            out.writeBoolean(c.platform());
            out.writeInt(c.shapes().size());
            for (Variant<ClassShape> variant : c.shapes()) {
                ClassShape shape = variant.shape();
                out.writeLong(variant.versions());
                out.writeInt(shape.access());
                out.writeInt(index(strings, shape.superName()));
                out.writeInt(shape.interfaces().size());
                for (String i : shape.interfaces()) {
                    out.writeInt(strings.get(i));
                }
                out.writeInt(index(strings, shape.signature()));
            }
            writeMembers(out, strings, c.fields());
            writeMembers(out, strings, c.methods());
        }
        out.flush();
        gzip.finish(); // completes the gzip stream without closing the caller's stream
    }

    private static void writeMembers(DataOutputStream out, Map<String, Integer> strings,
                                     Map<MemberKey, List<Variant<MemberShape>>> members) throws IOException {
        out.writeInt(members.size());
        for (var entry : members.entrySet()) {
            out.writeInt(strings.get(entry.getKey().name()));
            out.writeInt(strings.get(entry.getKey().descriptor()));
            out.writeInt(entry.getValue().size());
            for (Variant<MemberShape> variant : entry.getValue()) {
                out.writeLong(variant.versions());
                out.writeInt(variant.shape().access());
                out.writeInt(index(strings, variant.shape().signature()));
            }
        }
    }

    static ApiDatabase read(InputStream source) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(source), 1 << 16));
        if (in.readInt() != MAGIC) {
            throw new IOException("not a Minecraft API database");
        }
        int format = in.readInt();
        if (format != FORMAT) {
            throw new IOException("unsupported API database format " + format + " (expected " + FORMAT + ")");
        }
        List<McVersion> versions = new ArrayList<>();
        for (int i = in.readInt(); i > 0; i--) {
            versions.add(McVersion.parse(readString(in)));
        }
        String[] strings = new String[in.readInt()];
        for (int i = 0; i < strings.length; i++) {
            strings[i] = readString(in);
        }
        Map<String, ApiClass> classes = new LinkedHashMap<>();
        for (int i = in.readInt(); i > 0; i--) {
            String name = strings[in.readInt()];
            boolean platform = in.readBoolean();
            List<Variant<ClassShape>> shapes = new ArrayList<>();
            for (int s = in.readInt(); s > 0; s--) {
                long mask = in.readLong();
                int access = in.readInt();
                String superName = string(strings, in.readInt());
                List<String> interfaces = new ArrayList<>();
                for (int n = in.readInt(); n > 0; n--) {
                    interfaces.add(strings[in.readInt()]);
                }
                shapes.add(new Variant<>(mask, new ClassShape(access, superName, interfaces, string(strings, in.readInt()))));
            }
            classes.put(name, new ApiClass(name, platform, List.copyOf(shapes), readMembers(in, strings), readMembers(in, strings)));
        }
        return new ApiDatabase(versions, classes);
    }

    private static Map<MemberKey, List<Variant<MemberShape>>> readMembers(DataInputStream in, String[] strings) throws IOException {
        Map<MemberKey, List<Variant<MemberShape>>> members = new LinkedHashMap<>();
        for (int i = in.readInt(); i > 0; i--) {
            MemberKey key = new MemberKey(strings[in.readInt()], strings[in.readInt()]);
            List<Variant<MemberShape>> variants = new ArrayList<>();
            for (int v = in.readInt(); v > 0; v--) {
                long mask = in.readLong();
                int access = in.readInt();
                variants.add(new Variant<>(mask, new MemberShape(access, string(strings, in.readInt()))));
            }
            members.put(key, List.copyOf(variants));
        }
        return members;
    }

    private static void intern(Map<String, Integer> strings, String s) {
        if (s != null) {
            strings.putIfAbsent(s, strings.size());
        }
    }

    private static int index(Map<String, Integer> strings, String s) {
        return s == null ? -1 : strings.get(s);
    }

    private static String string(String[] strings, int index) {
        return index < 0 ? null : strings[index];
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        return new String(in.readNBytes(in.readInt()), StandardCharsets.UTF_8);
    }
}
