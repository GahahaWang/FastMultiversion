package cc.gahaha.fastmultiversion.gradle;

import java.util.List;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.process.CommandLineArgumentProvider;

/** The {@code -Xplugin} argument that turns on the javac plugin for one version, tracked as task input. */
public abstract class MultiversionCompilerArguments implements CommandLineArgumentProvider {
    @Input
    public abstract Property<String> getTarget();

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getApiDatabase();

    @Override
    public Iterable<String> asArguments() {
        String plugin = "-Xplugin:FastMultiversion target=" + getTarget().get();
        if (getApiDatabase().isPresent()) {
            // A URI, because javac splits plugin arguments at spaces.
            plugin += " apidb=" + getApiDatabase().get().getAsFile().toURI();
        }
        return List.of(plugin);
    }
}
