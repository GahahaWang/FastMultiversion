package cc.gahaha.fastmultiversion.intellij;

import cc.gahaha.fastmultiversion.versioning.VersionedLines;
import com.intellij.codeInsight.daemon.impl.HighlightInfoType;
import com.intellij.ide.highlighter.JavaHighlightingColors;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiPlainText;
import org.jetbrains.annotations.NotNull;

/**
 * Colors the {@code # @Mc.Is("...")} of access wideners, class tweakers and access transformers as in Java code:
 * {@code @Mc.Is}, its parentheses and the range get the editor's colors for a Java annotation's name, parentheses
 * and string, so that a theme colors them as it colors Java. In plain text (no other plugin colors the file), the
 * rest of a comment gets the color of a Java line comment; the Minecraft Development plugin colors comments itself.
 * Keys only: the color scheme decides the colors.
 */
public final class AccessFileAnnotator implements Annotator {
    private static final String NAME = "@Mc.Is";

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        PsiFile file = element.getContainingFile();
        if (element.getFirstChild() != null || file == null || !VersionedLines.isVersionedFile(file.getName())) {
            return; // leaves only: comments, or plain text's single leaf
        }
        String text = element.getText();
        if (text.indexOf('#') < 0) {
            return;
        }
        boolean plain = element instanceof PsiPlainText;
        int base = element.getTextRange().getStartOffset();
        for (int start = 0; start < text.length(); ) {
            int end = text.indexOf('\n', start);
            end = end < 0 ? text.length() : end;
            color(StringUtil.trimEnd(text.substring(start, end), "\r"), base + start, plain, holder);
            start = end + 1;
        }
    }

    private static void color(String line, int offset, boolean plain, AnnotationHolder holder) {
        int comment = line.indexOf('#');
        if (comment < 0) {
            return;
        }
        VersionedLines.Marker marker = VersionedLines.marker(line);
        if (plain) {
            int markerStart = marker == null ? line.length() : marker.start();
            add(holder, offset, comment, markerStart, JavaHighlightingColors.LINE_COMMENT);
            if (marker != null) {
                add(holder, offset, marker.end(), line.length(), JavaHighlightingColors.LINE_COMMENT);
            }
        }
        if (marker == null) {
            return;
        }
        add(holder, offset, marker.start(), marker.start() + NAME.length(), JavaHighlightingColors.ANNOTATION_NAME_ATTRIBUTES);
        int open = line.indexOf('(', marker.start() + NAME.length());
        add(holder, offset, open, open + 1, JavaHighlightingColors.PARENTHESES);
        add(holder, offset, marker.rangeStart() - 1, marker.rangeEnd() + 1, JavaHighlightingColors.STRING); // with the quotes
        add(holder, offset, marker.end() - 1, marker.end(), JavaHighlightingColors.PARENTHESES);
    }

    private static void add(AnnotationHolder holder, int offset, int start, int end, TextAttributesKey key) {
        if (start < end) {
            holder.newSilentAnnotation(HighlightInfoType.SYMBOL_TYPE_SEVERITY).range(TextRange.create(offset + start, offset + end))
                    .textAttributes(key).create();
        }
    }
}
