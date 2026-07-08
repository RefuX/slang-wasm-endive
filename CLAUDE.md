# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**Repository**: `slang-wasm-endive` — a pure-JVM library for the [Slang](https://shader-slang.org/)
shader compiler. It runs a WebAssembly build of the Slang compiler (`slang-wasm-wasi.wasm`) in-process
on the [endive](https://github.com/RefuX/endive) WASM runtime, so callers get compile + reflection +
serialization **with no JNI and no native binary**.
**Primary Language**: Java 11 (main library), with a thin Kotlin DSL module.
**Status**: `0.0.1-SNAPSHOT`, not yet published to Maven, **no external consumers** — the API can be
changed freely (no backward-compatibility burden). See `README.md` for user-facing usage.

## Big picture

The Slang compiler is compiled to WASM by Slang's own CMake build (`slang-wasm-wasi.wasm`, produced
by `cmake --build --preset slang-wasm-wasi` — **this repo does not build that artifact, it consumes
it**). endive executes that WASM module on the JVM. A compile-time annotation processor
(`@WasmModuleInterface` on `SlangWasm.java`) reads the wasm and **generates typed Java wrappers** for
its exports, so nothing in this codebase touches raw WASM pointers or memory.

Two things must be present to build:

1. **endive, `run.endive:*:1.0.1`, from Maven Central.** This release carries the
   `@WasmModuleInterface` processor fixes this project depends on, so it resolves straight from
   `mavenCentral()` with no local build. (`endiveVersion` in `build.gradle` pins `1.0.1`.)
2. **`slang-wasm-wasi.wasm`.** Resolved (in order) from `-Pslang.wasm.path=…`, the `SLANG_WASM_PATH`
   env var, or `slang-wasm-wasi.wasm` in the project root. The annotation processor reads it **at
   compile time** to generate the bindings; the Java tests read it at run time.

## Build and Common Commands

```bash
# Full build (both modules) + tests. Pulls endive from Maven Central (above).
./gradlew build -Pslang.wasm.path=/path/to/slang-wasm-wasi.wasm
# or: export SLANG_WASM_PATH=/path/to/slang-wasm-wasi.wasm && ./gradlew build

# Java library tests (against the real wasm; SKIPPED, not failed, if the wasm is absent)
./gradlew test

# Kotlin DSL tests (run against the bundled build-time-compiled module — no external wasm needed)
./gradlew :slang-wasm-endive-kotlin:test
```

### The build pipeline (what generates what)

- **`stageWasmForAnnotationProcessor`** — copies just `slang-wasm-wasi.wasm` into an isolated dir so
  the annotation-processor path fingerprints one file, not ~400 MB of unrelated wasm artifacts.
- **Annotation processing** (during `compileJava`) — `@WasmModuleInterface("slang-wasm-wasi.wasm")`
  generates `SlangWasm_ModuleExports` (typed export wrappers) and import scaffolding.
- **`compileWasmToJvmBytecode`** — endive's **build-time compiler** translates the wasm to JVM
  bytecode: emits `Machine` `.class` files, a `SlangWasmModule.java` facade, and a stripped
  `SlangWasmModule.meta` module. A few oversized Slang functions exceed the JVM method-size limit and
  stay interpreted (`--interpreter-fallback WARN` → one warning each, not a failure). Output is
  **bundled into the jar** (`jar { from endiveCompiledModule }`), so consumers of the jar get a
  zero-JIT, no-wasm-file-needed runtime.
- **Generated source dirs**: `src/generated/java` (the `enums.*` — **checked into git**, on
  `sourceSets.main.java`), and `build/generated/endive/{sources,classes}` (Machine facade + classes,
  regenerated each build).

## Execution modes (see `SlangRuntime`)

1. **Build-time compiled (default, bundled)** — `SlangRuntime.load()` / `SlangCompiler.forSpirv()`
   with no `Path`. Loads the pre-compiled `Machine` classes + `.meta` from the classpath; no wasm
   file, no runtime bytecode generation. Fastest start, full compiled speed. *This is the mode an
   OSGi/embedded integration should use — no wasm to ship and no runtime class generation.*
2. **Runtime (JIT) compiled** — an external wasm via the `Path` factories + `withRuntimeCompiler(true)`;
   endive JITs the module to bytecode at `build()` time (optionally disk-cached via `withCacheDir` /
   `withCache(Cache)`, keyed by the wasm content digest). Same speed, one-time translation cost.
3. **Interpreted** — external wasm run directly on the interpreter; ~10× slower compiles.

## Architecture / key types

Package root `io.github.refux.slangwasm`:

- **`SlangWasm`** — non-instantiated anchor carrying `@WasmModuleInterface`; drives binding generation.
- **`SlangRuntime`** (`AutoCloseable`) — one loaded wasm instance. **Loading it is the expensive
  part**, and it is **not thread-safe** — do not share a runtime or its sessions across threads. Reuse
  one runtime and open many sessions. Wires WASI stdout/stderr to `System.out`/`System.err` and runs
  the reactor `_initialize()` before any export.
- **`SlangCompiler`** (`AutoCloseable`) — one Slang session on a runtime for a target/profile, with
  macros, search paths, and session-wide `CompilerOption`s (optimization/debug/matrix). Factories
  (`forSpirv`, `builder()`, `fromWasm(...)`) create a runtime per call; `SlangRuntime.newSession(...)`
  reuses one. `TargetSpec`/`VulkanVersion` handle the Vulkan→SPIR-V profile mapping.
- **`SlangCompiler.SlangModule`** (`AutoCloseable`) — `loadModule(name, source)` parses once, then
  `compileEntryPoint` / `compileAll` / `compileSpecialized`, `serialize()` / `loadModuleFromIr`,
  `declReflectionJson()`, `disassemble()`, `createTypeConformances()`.
- **`CompileResult`** — `succeeded()`, `code()` (e.g. SPIR-V bytes), `reflectionJson()`,
  `reflection()` (parsed `ShaderReflection`, memoized; throws if the compile failed), `diagnostics()`.
- **`reflection.*`** — typed model: `ShaderReflection` (`parameters()`, `entryPoints()`,
  `find("gCB.count")` → offset/binding, `dump()`), `TypeLayoutReflection`, `VariableLayoutReflection`,
  `EntryPointReflection`, `DeclReflection`.
- **`enums.*`** (generated) — `Target`, `Stage`, `OptimizationLevel`, `ScalarType`, `TypeKind`, … .
- **`diagnostics.*`** — `Diagnostic`, `DiagnosticList`.
- **`io.github.refux.slangwasm.kotlin`** (separate `slang-wasm-endive-kotlin` module/artifact) — the
  `slang { }` / `slangSession { }` / `slangRuntime { }` DSL over the Java API. Thin wrapper, depends
  on the root jar (`api project(':')`), published separately so Java consumers don't pick up
  `kotlin-stdlib` transitively.

## Testing conventions

- **`src/test/.../SlangCompilerSmokeTest.java`** exercises the real wasm and `Assumptions`-skips when
  the artifact is absent — so `./gradlew test` never fails just because the wasm wasn't built.
- The **Kotlin module's tests** use the bundled build-time-compiled module, so they need no external
  wasm and no heap tuning.

## CI

`.github/workflows/ci.yml` (JDK 25): checks out the repo and runs `./gradlew build`, resolving
`run.endive:*:1.0.1` from Maven Central. Uploads test reports on failure and both jars always.
