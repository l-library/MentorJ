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
 * 采集 staged 改动：在项目根目录执行 {@code git diff --cached}，作为 {@code AgentRequest.stagedDiff}。
 *
 * <p>直接用命令行而不是 Git4Idea，避免给插件加一个 Git 插件的硬依赖；IDE 里的 Git 本来就来自同一个
 * 可执行文件，能提交代码的环境一定能跑这条命令。输出重定向到临时文件再读，避免管道缓冲区写满时死锁，
 * 并有超时兜底。
 */
final class StagedDiff {

    private static final long TIMEOUT_SECONDS = 30;

    private StagedDiff() {
    }

    /** 采集结果：{@code failure} 非空表示失败，否则 {@code diff} 为改动正文（可能为空）。 */
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
            // 临时文件删不掉不影响本次结果。
        }
    }
}
