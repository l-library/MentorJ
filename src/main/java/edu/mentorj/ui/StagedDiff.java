package edu.mentorj.ui;

import edu.mentorj.MentorJBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Collect staged changes: run {@code git diff --cached} in the project root and use the output as
 * {@code AgentRequest.stagedDiff}.
 *
 * <p>The command line is used directly instead of Git4Idea, so the plugin takes no hard dependency on a Git
 * plugin; the IDE's own Git comes from the same executable, so any environment that can commit code can run
 * this command. The output is redirected to a temporary file and read afterwards, which avoids a deadlock when
 * the pipe buffer fills up, and a timeout is a backstop.
 */
final class StagedDiff {

    private static final long TIMEOUT_SECONDS = 30;

    private StagedDiff() {
    }

    /** Collection result: a non-null {@code failure} means it failed, otherwise {@code diff} holds the body
     * (possibly empty). */
    record Outcome(@Nullable String diff, @Nullable String failure) {

        static Outcome of(String diff) {
            return new Outcome(diff, null);
        }

        static Outcome failed(String failure) {
            return new Outcome(null, failure);
        }
    }

    static @NotNull Outcome collect(@Nullable String basePath) {
        if (basePath == null) {
            return Outcome.failed(MentorJBundle.message("git.noProjectDir"));
        }
        Path output = null;
        try {
            output = Files.createTempFile("mentorj-staged-", ".diff");
            ProcessBuilder builder = new ProcessBuilder(
                    "git", "-C", basePath, "diff", "--cached", "--no-color", "--no-ext-diff");
            builder.redirectErrorStream(true);
            builder.redirectOutput(output.toFile());
            Process process = builder.start();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Outcome.failed(MentorJBundle.message("git.timeout"));
            }
            String text = Files.readString(output, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                return Outcome.failed(MentorJBundle.message("git.diffFailed", text.strip()));
            }
            return Outcome.of(text);
        } catch (IOException e) {
            return Outcome.failed(MentorJBundle.message("git.commandNotFound"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.failed(MentorJBundle.message("git.timeout"));
        } finally {
            delete(output);
        }
    }

    private static void delete(@Nullable Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A temporary file that will not delete does not affect this result.
        }
    }
}
