package cc.gahaha.fastmultiversion.intellij;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoType;
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder;
import com.intellij.codeInsight.daemon.impl.analysis.HighlightNamesUtil;
import com.intellij.lang.annotation.AnnotationBuilder;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.util.PsiModificationTracker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Colors the names in code that does not run in the active version as the color scheme colors them: a method
 * call, a static field, an interface, ... The IDE colors a name by what it resolves to in the active version, so
 * a name that only other versions have, such as {@code Minecraft.setScreen} of 26.1.2, would stay plain. For
 * such names, the IDE's own name highlighting runs on the {@link VersionViews view} of the newest version the
 * code runs in, where they resolve.
 */
public final class InactiveNamesAnnotator implements Annotator {
    private static final Logger LOG = Logger.getInstance(InactiveNamesAnnotator.class);
    private static final Key<Names> NAMES = Key.create("multiversion.inactiveNames");

    /** How a name is colored: a key of the color scheme, or the attributes the IDE made for it (e.g. merged keys). */
    private record Color(@Nullable TextAttributesKey key, @Nullable TextAttributes attributes) {
    }

    /** The colored names of a file, with the PSI modification count and setup they were found with. */
    private record Names(long stamp, MultiversionProject.State state, Map<TextRange, Color> colors) {
    }

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof PsiIdentifier) || !(element.getContainingFile() instanceof PsiJavaFile file)) {
            return;
        }
        Color color = colors(file).get(element.getTextRange());
        if (color == null) {
            return;
        }
        // The severity of the IDE's own name colors.
        AnnotationBuilder builder = holder.newSilentAnnotation(HighlightInfoType.SYMBOL_TYPE_SEVERITY).range(element);
        (color.attributes() != null ? builder.enforcedTextAttributes(color.attributes()) : builder.textAttributes(color.key())).create();
    }

    /** The names of {@code file} to color, by their range; found once per change of the code or the setup. */
    private static Map<TextRange, Color> colors(PsiJavaFile file) {
        MultiversionProject.State state = MultiversionProject.getInstance(file.getProject()).state();
        if (state == null) {
            return Map.of();
        }
        long stamp = PsiModificationTracker.getInstance(file.getProject()).getModificationCount();
        Names names = file.getUserData(NAMES);
        if (names == null || names.stamp() != stamp || names.state() != state) {
            names = new Names(stamp, state, find(file, state));
            file.putUserData(NAMES, names);
        }
        return names.colors();
    }

    private static Map<TextRange, Color> find(PsiJavaFile file, MultiversionProject.State state) {
        Guards guards = new Guards(state.db());
        Set<Integer> versions = new TreeSet<>();
        guards.inactiveVersions(file, guards.all(), state.activeBit(), versions);
        Map<TextRange, Color> colors = new HashMap<>();
        PsiJavaFile original = file.getOriginalFile() instanceof PsiJavaFile o ? o : file;
        for (int version : versions) {
            PsiJavaFile view = VersionViews.of(state, original, version);
            if (view == null) {
                continue;
            }
            List<PsiElement> elements = new ArrayList<>();
            guards.inactiveElements(view, guards.all(), version, state.activeBit(), elements);
            HighlightInfoHolder infos = new HighlightInfoHolder(view);
            try {
                HighlightNamesUtil.highlight(view, infos, visitor -> elements.forEach(element -> element.accept(visitor)));
            } catch (ProcessCanceledException e) {
                throw e;
            } catch (RuntimeException e) {
                // The IDE's name highlighting is not made for files outside the editor; the names stay plain.
                LOG.warn("cannot color the names of " + file.getName() + " in Minecraft " + state.name(version), e);
                continue;
            }
            for (int i = 0; i < infos.size(); i++) {
                HighlightInfo info = infos.get(i);
                TextRange range = TextRange.create(info.getStartOffset(), info.getEndOffset());
                Color color = color(info);
                // A view has the file's text, so the same offsets; names that resolve in the file are colored by the IDE.
                if (color != null && !colors.containsKey(range) && isUnresolvedName(file, range)) {
                    colors.put(range, color);
                }
            }
        }
        return colors;
    }

    private static @Nullable Color color(HighlightInfo info) {
        if (info.getSeverity().compareTo(HighlightSeverity.WARNING) >= 0) {
            return null;
        }
        if (info.forcedTextAttributes != null) {
            return new Color(null, info.forcedTextAttributes);
        }
        TextAttributesKey key = info.forcedTextAttributesKey != null ? info.forcedTextAttributesKey : info.type.getAttributesKey();
        return key == null ? null : new Color(key, null);
    }

    /** Whether {@code range} is exactly the name of a reference that resolves to nothing in {@code file}. */
    private static boolean isUnresolvedName(PsiJavaFile file, TextRange range) {
        PsiElement at = file.findElementAt(range.getStartOffset());
        return at instanceof PsiIdentifier && at.getTextRange().equals(range)
                && at.getParent() instanceof PsiJavaCodeReferenceElement reference && reference.getReferenceNameElement() == at
                && reference.multiResolve(false).length == 0;
    }
}
