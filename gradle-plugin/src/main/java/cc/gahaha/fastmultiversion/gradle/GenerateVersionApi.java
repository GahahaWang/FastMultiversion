package cc.gahaha.fastmultiversion.gradle;

import cc.gahaha.fastmultiversion.apidb.ApiDatabase;
import cc.gahaha.fastmultiversion.apidb.generator.ApiDbGenerator;
import cc.gahaha.fastmultiversion.apidb.generator.VersionJars;
import cc.gahaha.fastmultiversion.versioning.McVersion;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/** Records the API of one version, from the jars on its compile classpath (Minecraft, libraries, loader API). */
@CacheableTask
public abstract class GenerateVersionApi extends DefaultTask {
    @Input
    public abstract Property<String> getMinecraftVersion();

    @Classpath
    public abstract ConfigurableFileCollection getClasspath();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    void generate() throws IOException {
        List<Path> jars = getClasspath().getFiles().stream()
                .filter(f -> f.isFile() && f.getName().endsWith(".jar"))
                .map(File::toPath)
                .toList();
        if (jars.isEmpty()) {
            throw new GradleException("multiversion: the compile classpath of Minecraft " + getMinecraftVersion().get()
                    + " has no jars; add Minecraft in version.gradle");
        }
        ApiDatabase db = ApiDbGenerator.generate(List.of(new VersionJars(McVersion.parse(getMinecraftVersion().get()), jars)));
        db.write(getOutputFile().get().getAsFile().toPath());
    }
}
