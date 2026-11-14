package cc.gahaha.fastmultiversion.gradle;

import java.util.List;
import org.gradle.api.tasks.Input;
import org.gradle.process.CommandLineArgumentProvider;

/** JVM arguments for the compiler process: the javac plugin edits javac's trees, which jdk.compiler does not export. */
public class JavacExports implements CommandLineArgumentProvider {
    static final List<String> ARGUMENTS = List.of("api", "code", "tree", "util").stream()
            .map(p -> "--add-exports=jdk.compiler/com.sun.tools.javac." + p + "=ALL-UNNAMED")
            .toList();

    @Input
    public List<String> getArguments() {
        return ARGUMENTS;
    }

    @Override
    public Iterable<String> asArguments() {
        return ARGUMENTS;
    }
}
