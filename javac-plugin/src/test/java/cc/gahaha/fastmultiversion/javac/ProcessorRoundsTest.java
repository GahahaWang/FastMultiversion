package cc.gahaha.fastmultiversion.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When an annotation processor generates a file, javac re-enters every existing tree for the next round.
 * The pruning must survive that, and generated files must be pruned as well.
 */
class ProcessorRoundsTest {
    @TempDir
    Path temp;

    @SupportedAnnotationTypes("*")
    static final class GeneratingProcessor extends AbstractProcessor {
        private boolean generated;

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (!generated) {
                generated = true;
                try (Writer out = processingEnv.getFiler().createSourceFile("gen.Generated").openWriter()) {
                    out.write("""
                            package gen;
                            import cc.gahaha.fastmultiversion.Mc;
                            public class Generated {
                                public static String which() { return Mc.is("[26.2,)") ? "gen-new" : "gen-old"; }
                            }
                            """);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return false;
        }
    }

    @Test
    void pruningSurvivesProcessorRounds() throws Exception {
        Path api = TestCompiler.jar(temp, "api-26.2", """
                package mc;
                public class NewThing { public String name() { return "new-thing"; } }
                """);
        var result = TestCompiler.target(temp, "26.2").classpath(api).processor(new GeneratingProcessor()).source("""
                package t;
                import cc.gahaha.fastmultiversion.Mc;
                public class UsesGenerated {
                    public static String run() {
                        if (Mc.is("[26.1,26.2)")) {
                            return new mc.OldThing().name();
                        }
                        return new mc.NewThing().name() + " " + gen.Generated.which();
                    }
                }
                """).compile().assertSuccess();

        assertEquals("new-thing gen-new", result.call("t.UsesGenerated", "run", api));
    }
}
