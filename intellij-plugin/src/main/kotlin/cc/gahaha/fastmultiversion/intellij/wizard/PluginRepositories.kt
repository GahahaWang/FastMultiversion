package cc.gahaha.fastmultiversion.intellij.wizard

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

/** Puts the FastMultiversion Gradle plugin where a new project finds it. */
object PluginRepositories {
    private const val RELEASES = "https://github.com/GahahaWang/FastMultiversion/releases/download"

    /** Downloads a file; `null` if it cannot. */
    fun interface Downloader {
        fun download(url: String): ByteArray?
    }

    /**
     * The release of [version] (this IntelliJ plugin's own version), unpacked into the project; for a
     * development build (a snapshot) or when the release cannot be downloaded, the local Maven repository.
     */
    fun prepare(dir: Path, version: String, downloader: Downloader): PluginRepository {
        if (version.endsWith("-SNAPSHOT")) {
            return PluginRepository.MavenLocal(version)
        }
        val zip = downloader.download("$RELEASES/v$version/fastmultiversion-$version-maven-repository.zip")
            ?: return PluginRepository.MavenLocal(version)
        return try {
            unzip(zip, dir.resolve(ProjectGenerator.REPOSITORY_DIR))
            PluginRepository.InProject(version)
        } catch (e: IOException) {
            PluginRepository.MavenLocal(version)
        }
    }

    private fun unzip(zip: ByteArray, target: Path) {
        val root = target.toAbsolutePath().normalize()
        ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val file = root.resolve(entry.name).normalize()
                if (!file.startsWith(root)) {
                    throw IOException("unexpected entry ${entry.name}")
                }
                if (entry.isDirectory) {
                    Files.createDirectories(file)
                } else {
                    Files.createDirectories(file.parent)
                    Files.copy(input, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
