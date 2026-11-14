package cc.gahaha.fastmultiversion.apidb.generator;

import cc.gahaha.fastmultiversion.apidb.ApiDatabase;
import cc.gahaha.fastmultiversion.versioning.McVersion;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Command line entry point for {@link ApiDbGenerator}. */
public final class ApiDbMain {
    private static final String USAGE = """
            Usage: ApiDbMain --output <api.mcapi> [--diff <diff.txt>] --version <mc-version>=<jar>[%s<jar>...] ...

              --output   where to write the API database
              --diff     where to write a readable list of the differences between versions (optional)
              --version  one Minecraft version and the jars that make up its API; repeat for every version
            """.formatted(File.pathSeparator);

    private ApiDbMain() {
    }

    public static void main(String[] args) throws IOException {
        Path output = null;
        Path diff = null;
        List<VersionJars> versions = new ArrayList<>();
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--output" -> output = Path.of(value(args, ++i, "--output"));
                    case "--diff" -> diff = Path.of(value(args, ++i, "--diff"));
                    case "--version" -> versions.add(parseVersion(value(args, ++i, "--version")));
                    case "--help", "-h" -> {
                        System.out.print(USAGE);
                        return;
                    }
                    default -> throw new IllegalArgumentException("unknown argument '" + args[i] + "'");
                }
            }
            if (output == null || versions.isEmpty()) {
                throw new IllegalArgumentException("--output and at least one --version are required");
            }
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.print(USAGE);
            System.exit(2);
            return;
        }

        long start = System.nanoTime();
        ApiDatabase db = ApiDbGenerator.generate(versions);
        Path parent = output.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        db.write(output);
        System.out.printf("Wrote %s (%,d bytes, %d classes) in %d ms%n", output, Files.size(output),
                db.classes().size(), (System.nanoTime() - start) / 1_000_000);
        if (diff != null) {
            String text = ApiDiff.format(db);
            Files.writeString(diff, text, StandardCharsets.UTF_8);
            System.out.print(text.lines().limit(4).map(l -> l + "\n").reduce("", String::concat));
            System.out.println("Wrote " + diff);
        }
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }

    private static VersionJars parseVersion(String spec) {
        int eq = spec.indexOf('=');
        if (eq <= 0 || eq == spec.length() - 1) {
            throw new IllegalArgumentException("--version expects <mc-version>=<jar>, got '" + spec + "'");
        }
        McVersion version = McVersion.parse(spec.substring(0, eq));
        List<Path> jars = Arrays.stream(spec.substring(eq + 1).split(File.pathSeparator))
                .filter(s -> !s.isBlank())
                .map(Path::of)
                .toList();
        for (Path jar : jars) {
            if (!Files.isRegularFile(jar)) {
                throw new IllegalArgumentException("jar not found: " + jar);
            }
        }
        return new VersionJars(version, jars);
    }
}
