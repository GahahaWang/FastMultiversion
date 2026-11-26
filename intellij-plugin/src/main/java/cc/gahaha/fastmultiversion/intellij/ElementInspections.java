package cc.gahaha.fastmultiversion.intellij;

import com.intellij.codeInsight.daemon.impl.ProblemDescriptorWithReporterName;
import com.intellij.codeInspection.InspectionEngine;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.SuppressionUtil;
import com.intellij.codeInspection.ex.DynamicGroupTool;
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Runs local inspections on some elements of a file as the IDE runs them on a file: the tools that are available for
 * the file and apply to the elements' languages, all in one {@link LocalInspectionToolSession} of the file, each from
 * {@code inspectionStarted} to {@code inspectionFinished}, without the problems that are suppressed where they are.
 *
 * <p>Only the IDE makes sessions, in its own runs, so the elements are inspected from within one: {@link InSession}
 * is run on the file and gets the session the IDE makes for it.
 */
final class ElementInspections {
    private ElementInspections() {
    }

    /** The problems that {@code tools} find in {@code elements} of {@code file}, by the tool they are for. */
    static Map<LocalInspectionToolWrapper, List<ProblemDescriptor>> run(List<LocalInspectionToolWrapper> tools, PsiFile file,
                                                                       List<? extends PsiElement> elements, boolean isOnTheFly) {
        Set<String> dialects = InspectionEngine.calcElementDialectIds(elements, List.of());
        List<LocalInspectionToolWrapper> applicable = InspectionEngine.filterToolsApplicableByLanguage(tools, dialects, dialects);
        InspectionManager manager = InspectionManager.getInstance(file.getProject());
        Map<LocalInspectionToolWrapper, List<ProblemDescriptor>> found = new LinkedHashMap<>();
        InSession inSession = new InSession(session -> {
            for (LocalInspectionToolWrapper tool : applicable) {
                inspect(tool, applicable, session, manager, elements, isOnTheFly, found);
            }
        });
        inSession.processFile(file, manager);
        inSession.rethrow();
        return found;
    }

    private static void inspect(LocalInspectionToolWrapper wrapper, List<LocalInspectionToolWrapper> tools, LocalInspectionToolSession session,
                                InspectionManager manager, List<? extends PsiElement> elements, boolean isOnTheFly,
                                Map<LocalInspectionToolWrapper, List<ProblemDescriptor>> found) {
        LocalInspectionTool tool = wrapper.getTool();
        ProblemsHolder holder = new ProblemsHolder(manager, session.getFile(), isOnTheFly);
        PsiElementVisitor visitor = InspectionEngine.createVisitor(tool, holder, isOnTheFly, session);
        if (visitor == PsiElementVisitor.EMPTY_VISITOR) {
            return; // not available for the file
        }
        tool.inspectionStarted(session, isOnTheFly);
        for (PsiElement element : elements) {
            ProgressManager.checkCanceled();
            element.accept(visitor);
        }
        tool.inspectionFinished(session, holder);
        for (ProblemDescriptor problem : holder.getResults()) {
            LocalInspectionToolWrapper reporter = reporter(wrapper, problem, tools);
            PsiElement at = problem.getPsiElement();
            if (reporter != null && (at == null || !SuppressionUtil.inspectionResultSuppressed(at, reporter.getTool()))) {
                found.computeIfAbsent(reporter, t -> new ArrayList<>()).add(problem);
            }
        }
    }

    /**
     * The tool a problem is for: the one that found it, unless it reports for another (one of its group, or else of
     * {@code tools}); {@code null} if that one is not there.
     */
    private static @Nullable LocalInspectionToolWrapper reporter(LocalInspectionToolWrapper wrapper, ProblemDescriptor problem,
                                                                 List<LocalInspectionToolWrapper> tools) {
        if (!(problem instanceof ProblemDescriptorWithReporterName named)) {
            return wrapper;
        }
        List<LocalInspectionToolWrapper> candidates = wrapper instanceof DynamicGroupTool group ? group.getChildren() : tools;
        for (LocalInspectionToolWrapper candidate : candidates) {
            if (candidate.getShortName().equals(named.getReportingToolShortName())) {
                return candidate;
            }
        }
        return null;
    }

    /** A tool that inspects nothing: run on a file, it hands the session the IDE made for it to {@code inSession}. */
    private static final class InSession extends LocalInspectionTool {
        private final Consumer<LocalInspectionToolSession> inSession;
        private boolean ran;
        private @Nullable RuntimeException failure;

        InSession(Consumer<LocalInspectionToolSession> inSession) {
            this.inSession = inSession;
        }

        @Override
        public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly, @NotNull LocalInspectionToolSession session) {
            ran = true;
            try {
                inSession.accept(session);
            } catch (ProcessCanceledException e) {
                throw e;
            } catch (RuntimeException e) {
                failure = e; // thrown by rethrow: the IDE would report it as this plugin's error
            }
            return PsiElementVisitor.EMPTY_VISITOR;
        }

        void rethrow() {
            if (failure != null) {
                throw failure;
            }
            if (!ran) {
                throw new IllegalStateException("the IDE did not start a session for the inspections");
            }
        }

        @Override
        public @NotNull String getShortName() {
            return "FastMultiversionElementInspections";
        }

        @Override
        public @NotNull String getDisplayName() {
            return "FastMultiversion: inspections of inactive code";
        }

        @Override
        public @NotNull String getGroupDisplayName() {
            return "FastMultiversion";
        }
    }
}
