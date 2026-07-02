package org.shaderslang.wasm;

import run.endive.compiler.Cache;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.experimental.dircache.DirectoryCache;
import run.endive.runtime.Instance;
import run.endive.runtime.Machine;
import run.endive.runtime.Store;
import run.endive.wasi.WasiOptions;
import run.endive.wasi.WasiPreview1;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

import org.shaderslang.wasm.enums.Target;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * One loaded {@code slang-wasm-lib.wasm} instance, shared across any number of
 * {@link SlangCompiler} sessions.
 *
 * <p>Loading the module is the expensive part. {@code SlangCompiler}'s
 * {@code fromWasm}/{@code builder} factories create one {@code SlangRuntime} per
 * session, which is fine for one-shot use. To compile many shaders, load the
 * runtime once and open a session per configuration:
 *
 * <pre>{@code
 * try (var runtime = SlangRuntime.load()) {
 *     try (var spirv = runtime.forSpirv()) {
 *         CompileResult r = spirv.compile("hello", source, "main");
 *     }
 *     // ... more sessions on the same instance, no re-load ...
 * }
 * }</pre>
 *
 * <p><b>Build-time compiled module (default).</b> The no-arg {@link #load()} /
 * {@link #builder()} factories use the module that was compiled to JVM bytecode
 * at <em>build</em> time by endive's build-time compiler (the
 * {@code compileWasmToJvmBytecode} Gradle task): shader compiles run at full
 * compiled speed with no JIT cost or WASM file needed at run time. A few very
 * large functions in the Slang compiler exceed what the bytecode emitter can
 * handle and are left interpreted (the build logs one warning per such
 * function); this is expected and harmless.
 *
 * <p>The {@code Path}-based factories load an <em>external</em> WASM artifact
 * instead; those support two further execution modes:
 *
 * <p><b>Runtime compiler.</b> By default an external module runs interpreted.
 * Enabling the compiler ({@link Builder#withRuntimeCompiler}) makes each shader
 * compile far faster (roughly an order of magnitude) but adds a one-time cost to
 * {@link Builder#build()} while the module is translated to JVM bytecode, with
 * the same oversized-function interpreter fallback as above (which does not
 * affect caching below — the cached bytecode simply omits those functions too,
 * so the fallback is reproduced exactly on a cache hit).
 *
 * <p><b>Disk cache.</b> {@link Builder#withCacheDir} persists the runtime
 * compiler's JVM bytecode to disk, keyed by the WASM module's content digest.
 * The first build pays the full JIT cost and writes the cache; subsequent builds
 * (even in a new JVM process) load the compiled classes straight from disk and
 * skip compilation entirely. Only meaningful together with
 * {@link Builder#withRuntimeCompiler}.
 *
 * <p>Not thread-safe: do not share one runtime (or its sessions) across threads.
 */
public final class SlangRuntime implements AutoCloseable {

    private final SlangWasm_ModuleExports wasm;
    private final WasiPreview1 wasi;

    private SlangRuntime(SlangWasm_ModuleExports wasm, WasiPreview1 wasi) {
        this.wasm = wasm;
        this.wasi = wasi;
    }

    SlangWasm_ModuleExports wasm() {
        return wasm;
    }

    /** Load and initialize the bundled, build-time-compiled WASM module. */
    public static SlangRuntime load() throws IOException {
        return builder().build();
    }

    /** Begin configuring a runtime on the bundled, build-time-compiled module. */
    public static Builder builder() {
        return new Builder(null);
    }

    /** Load and initialize an external WASM module with interpreted execution. */
    public static SlangRuntime load(Path wasmPath) throws IOException {
        return builder(wasmPath).build();
    }

    /** Begin configuring a runtime for an external {@code wasmPath}. */
    public static Builder builder(Path wasmPath) {
        return new Builder(wasmPath);
    }

    // ── Session factories (shared instance) ─────────────────────────────────────
    // Sessions opened here share this runtime: closing the returned SlangCompiler
    // tears down only its Slang session, not the WASM instance.

    /** Open a SPIR-V session on this runtime. */
    public SlangCompiler forSpirv() throws IOException {
        return newSession(Target.SPIRV, "");
    }

    /** Open a single-target session ({@code profile} may be empty for the default). */
    public SlangCompiler newSession(Target targetFormat, String profile) throws IOException {
        return SlangCompiler.openSession(this, targetFormat, profile, false);
    }

    /** Open a multi-target session (targets, macro defines, and search paths). */
    public SlangCompiler newSession(
            List<SlangCompiler.TargetSpec> targets,
            Map<String, String> macros,
            List<String> searchPaths) throws IOException {
        return newSession(targets, macros, searchPaths, List.of());
    }

    /** Open a multi-target session, additionally with session-wide compiler options. */
    public SlangCompiler newSession(
            List<SlangCompiler.TargetSpec> targets,
            Map<String, String> macros,
            List<String> searchPaths,
            List<SlangCompiler.CompilerOption> options) throws IOException {
        return SlangCompiler.openSession(this, targets, macros, searchPaths, options, false);
    }

    /**
     * Release this runtime. The endive WASM instance itself has no explicit
     * teardown (it is reclaimed by GC once unreferenced); this closes the WASI
     * layer, which owns the stdio and preopened-directory descriptors.
     */
    @Override
    public void close() {
        wasi.close();
    }

    /** Configures and builds a {@link SlangRuntime}. */
    public static final class Builder {
        /** External WASM artifact, or null for the bundled build-time-compiled module. */
        private final Path wasmPath;
        private boolean runtimeCompiler = false;
        private Cache cache;

        private Builder(Path wasmPath) {
            this.wasmPath = wasmPath;
        }

        /**
         * Enable the endive runtime compiler (compile the WASM to JVM bytecode at
         * {@link #build()} time). Off by default — the module runs interpreted.
         * Only applies to external modules loaded via {@link #builder(Path)}; the
         * bundled module is already compiled at build time.
         */
        public Builder withRuntimeCompiler(boolean enabled) {
            this.runtimeCompiler = enabled;
            return this;
        }

        /**
         * Persist the runtime compiler's output under {@code cacheDir}, keyed by the
         * WASM module's content digest, so later builds (including in a new JVM
         * process) can skip JIT compilation entirely. No effect unless
         * {@link #withRuntimeCompiler} is also enabled. Sugar for {@link #withCache}
         * with a {@link DirectoryCache} backed by {@code cacheDir}.
         */
        public Builder withCacheDir(Path cacheDir) {
            return withCache(cacheDir == null ? null : new DirectoryCache(cacheDir));
        }

        /**
         * Persist the runtime compiler's output in {@code cache}, keyed by the
         * WASM module's content digest. No effect unless {@link #withRuntimeCompiler}
         * is also enabled. Overrides any {@link #withCacheDir} call and vice versa —
         * exposed mainly so tests can substitute an instrumented {@link Cache} to
         * observe hits/misses directly instead of via timing.
         */
        public Builder withCache(Cache cache) {
            this.cache = cache;
            return this;
        }

        public SlangRuntime build() throws IOException {
            if (wasmPath == null && (runtimeCompiler || cache != null)) {
                throw new IllegalStateException(
                        "withRuntimeCompiler/withCache only apply to external modules loaded via "
                        + "builder(Path); the bundled module is already compiled at build time");
            }
            return instantiate(wasmPath, runtimeCompiler, cache);
        }
    }

    /** Parse, instantiate (optionally with the runtime compiler), and run _initialize. */
    private static SlangRuntime instantiate(
            Path wasmPath, boolean useCompiler, Cache cache) throws IOException {

        var wasi = WasiPreview1.builder()
                .withOptions(WasiOptions.builder()
                        .withStdout(System.out)
                        .withStderr(System.err)
                        .build())
                .build();

        Instance instance;
        try {
            if (wasmPath == null) {
                // Bundled module, compiled to JVM bytecode at build time. load()
                // parses the .meta module from the classpath: everything but the
                // (interpreted-fallback) bodies of the compiled functions is
                // stripped, and execution dispatches to the compiled Machine.
                instance = buildInstance(SlangWasmModule.load(), wasi, SlangWasmModule::create);
            } else {
                WasmModule module = Parser.parse(wasmPath.toFile());
                if (useCompiler) {
                    try {
                        instance = buildInstance(module, wasi, compileMachineFactory(module, cache));
                    } catch (Throwable t) {
                        System.err.println(
                                "[slang-wasm-lib] runtime compiler failed (" + t
                                + "); falling back to the interpreter for this instance.");
                        instance = buildInstance(module, wasi, null);
                    }
                } else {
                    instance = buildInstance(module, wasi, null);
                }
            }
        } catch (Throwable t) {
            wasi.close();
            throw t;
        }

        // Typed view over the module's exports, generated from the wasm by the
        // @WasmModuleInterface processor (see SlangWasm).
        var wasm = new SlangWasm_ModuleExports(instance);

        // Reactor protocol: call _initialize before any other export.
        wasm._initialize();
        return new SlangRuntime(wasm, wasi);
    }

    /** Compile {@code module} to JVM bytecode with the runtime compiler. */
    private static Function<Instance, Machine> compileMachineFactory(
            WasmModule module, Cache cache) {
        var compilerBuilder = MachineFactoryCompiler.builder(module);
        if (cache != null) {
            compilerBuilder.withCache(cache);
        }
        return compilerBuilder.compile();
    }

    /** Instantiate the module, running it on {@code machineFactory} (null = interpreter). */
    private static Instance buildInstance(
            WasmModule module, WasiPreview1 wasi, Function<Instance, Machine> machineFactory) {
        return new Store()
                .addFunction(wasi.toHostFunctions())
                .instantiate("slang-wasm-lib", importValues -> {
                    var builder = Instance.builder(module).withImportValues(importValues);
                    if (machineFactory != null) {
                        builder.withMachineFactory(machineFactory);
                    }
                    return builder.build();
                });
    }
}
