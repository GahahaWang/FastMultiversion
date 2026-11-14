package cc.gahaha.fastmultiversion.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.ResolveScopeProvider;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Makes a {@link VersionViews version view} resolve against its version's libraries. */
public final class VersionViewResolveScopeProvider extends ResolveScopeProvider {
    @Override
    public @Nullable GlobalSearchScope getResolveScope(@NotNull VirtualFile file, @NotNull Project project) {
        return file.getUserData(VersionViews.SCOPE);
    }
}
