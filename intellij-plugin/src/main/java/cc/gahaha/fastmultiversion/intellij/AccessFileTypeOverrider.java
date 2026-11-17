package cc.gahaha.fastmultiversion.intellij;

import cc.gahaha.fastmultiversion.versioning.VersionedLines;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.fileTypes.UnknownFileType;
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Opens access wideners, class tweakers and access transformers as text where no file type claims them (e.g.
 * without the Minecraft Development plugin), so that {@link McAccessFileInspection} can check them. A file type
 * that another plugin or the user gave them stays.
 */
public final class AccessFileTypeOverrider implements FileTypeOverrider {
    @Override
    public @Nullable FileType getOverriddenFileType(@NotNull VirtualFile file) {
        if (file.isDirectory() || !VersionedLines.isVersionedFile(file.getName())) {
            return null;
        }
        return FileTypeManager.getInstance().getFileTypeByFileName(file.getNameSequence()) == UnknownFileType.INSTANCE
                ? PlainTextFileType.INSTANCE : null;
    }
}
