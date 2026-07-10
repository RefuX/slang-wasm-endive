package io.github.refux.slangwasm;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The Slang build tag of the bundled {@code slang-wasm-wasi.wasm}, available as a static value so
 * callers can identify the compiler <em>without</em> instantiating the runtime.
 *
 * <p>{@link SlangCompiler#version()} returns the same string, but only after loading the WASM module
 * (multi-second, ~230&nbsp;MB of live heap). A consumer that keys persistent artifacts on the
 * compiler build — for example a shader-variant disk cache that wants to look up an entry, and skip
 * loading the compiler entirely, when nothing has changed since the last run — reads {@link
 * #BUILD_TAG} instead and never pays that cost on a cache hit.
 *
 * <p>The value is <b>generated at build time</b>: the {@code generateSlangVersion} Gradle task runs
 * the bundled compiler once and writes the tag into {@code /slang-version.properties}, which this
 * class loads. It is therefore always in step with the embedded wasm with no hand-maintenance;
 * {@code SlangCompilerSmokeTest} additionally asserts it matches {@link SlangCompiler#version()}.
 */
public final class SlangVersion {
    /** The Slang build tag of the bundled wasm, e.g. {@code "2026.12.2-80-gbc7729ab0"}. */
    public static final String BUILD_TAG = readBuildTag();

    private static final String RESOURCE = "/slang-version.properties";
    private static final String KEY = "slang.build.tag";

    private SlangVersion() {
    }

    private static String readBuildTag() {
        try (InputStream in = SlangVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        RESOURCE + " is missing from the jar — the generateSlangVersion build task did not run.");
            }
            Properties properties = new Properties();
            properties.load(in);
            String tag = properties.getProperty(KEY);
            if (tag == null || tag.isBlank()) {
                throw new IllegalStateException(KEY + " is absent or empty in " + RESOURCE);
            }
            return tag;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + RESOURCE, e);
        }
    }
}
