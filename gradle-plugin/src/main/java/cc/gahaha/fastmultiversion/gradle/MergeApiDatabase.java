package cc.gahaha.fastmultiversion.gradle;

import cc.gahaha.fastmultiversion.apidb.ApiDatabase;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Combines the per-version API files into the database that the range check uses. */
@CacheableTask
public abstract class MergeApiDatabase extends DefaultTask {
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getParts();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    void merge() throws IOException {
        List<ApiDatabase> parts = new ArrayList<>();
        for (File part : getParts().getFiles()) {
            parts.add(ApiDatabase.read(part.toPath()));
        }
        ApiDatabase db = ApiDatabase.merge(parts);
        db.write(getOutputFile().get().getAsFile().toPath());
        getLogger().lifecycle("multiversion: API database for Minecraft {} ({} classes)", db.versions(), db.classes().size());
    }
}
