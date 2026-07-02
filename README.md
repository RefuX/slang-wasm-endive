# slang-wasm-endive

[![CI](https://github.com/RefuX/slang-wasm-endive/actions/workflows/ci.yml/badge.svg)](https://github.com/RefuX/slang-wasm-endive/actions/workflows/ci.yml)

A pure-Java library for the [Slang](https://shader-slang.org/) shader compiler: compile, reflect on,
and serialize Slang shaders without any JNI or native binary — the Slang compiler runs as a WASM
module executed entirely on the JVM via [endive](https://github.com/RefuX/endive) (a WASM runtime/AOT
compiler for the JVM).

## How it works

`slang-wasm-lib.wasm` — a build of the Slang compiler to WebAssembly — is executed in-process
using endive. Three execution modes are available:

- **Build-time compiled** (default): endive's build-time compiler translates the module to JVM
  bytecode during the Gradle build (the `compileWasmToJvmBytecode` task) and bundles the resulting
  classes in the jar. Instances start with zero JIT cost and compile shaders at full compiled
  speed; no WASM file is needed at run time. A few very large Slang functions exceed the JVM
  method-size limit and stay interpreted — the build is configured with
  `--interpreter-fallback WARN`, so it logs one warning per such function instead of failing.
- **Runtime (JIT) compiled**: for an external WASM file, endive's runtime compiler translates the
  module to JVM bytecode when the runtime is built — same execution speed, but with a one-time
  translation delay per process. The compiled bytecode can be persisted to disk and reused across
  JVM processes.
- **Interpreted**: an external WASM file runs directly on the interpreter. No startup cost, but
  shader compiles are roughly an order of magnitude slower.

At compile time, an annotation processor (`run.endive:annotations-processor`) reads
`slang-wasm-lib.wasm` and generates typed Java wrappers for its exports (see
[SlangWasm.java](src/main/java/org/shaderslang/wasm/SlangWasm.java)), so callers never touch raw
WASM exports or memory pointers directly.

## Requirements

- JDK 11+
- The `slang-wasm-lib.wasm` artifact, built from the Slang compiler's CMake project:
  ```
  cmake --build --preset slang-wasm-lib
  ```
  This repository does not build that artifact itself; it only consumes it.
- A locally-published build of the `run.endive` fork this project depends on (see
  [Building](#building) below).

## Building

This project currently builds against a local fork of `endive` (version `999-SNAPSHOT`, set via
`endiveVersion` in [build.gradle](build.gradle)) rather than an upstream release, because it carries
fixes needed for the `@WasmModuleInterface` annotation processor. Build and install it (including
the build-time compiler CLI used by the `compileWasmToJvmBytecode` task) to your local Maven
repository first:

```
cd /path/to/endive
./mvnw -DskipTests -pl compiler,runtime,wasi,dircache,annotations/processor,build-time-compiler-cli -am install
```

Then point this build at your `slang-wasm-lib.wasm` artifact, either via a Gradle project property
or an environment variable:

```
./gradlew build -Pslang.wasm.path=/path/to/slang-wasm-lib.wasm
# or
export SLANG_WASM_PATH=/path/to/slang-wasm-lib.wasm
./gradlew build
```

If neither is set, the build looks for `slang-wasm-lib.wasm` in the project root.

## Usage

```java
try (var slang = SlangCompiler.forSpirv()) {
    CompileResult result = slang.compile(
        "hello",
        "[shader(\"compute\")] [numthreads(1,1,1)] void main() {}",
        "main");

    if (result.succeeded()) {
        byte[] spirv = result.code();
    } else {
        System.err.println(result.diagnostics());
    }
}
```

This runs on the bundled build-time-compiled module. To load an external WASM artifact instead,
use `SlangCompiler.forSpirvFromWasm(Path)` (interpreted) or the builder's `wasm(Path)` +
`runtimeCompiler(true)` (runtime compiled).

`SlangCompiler` owns one WASM instance and one Slang session configured for a single compile
target; it's `AutoCloseable`. The fluent `builder()` API supports multiple targets, preprocessor
macros, search paths, and session-wide compiler options (optimization level, debug info, matrix
layout):

```java
try (var slang = SlangCompiler.builder()
        .target(Target.SPIRV)
        .define("MY_MACRO", "1")
        .optimizationLevel(OptimizationLevel.HIGH)
        .build()) {
    CompileResult result = slang.compile("hello", source, "main");
}
```

### Compiling many shaders: reuse a `SlangRuntime`

Loading the WASM module is the expensive part. `SlangCompiler`'s static factories create one
`SlangRuntime` per call, which is fine for one-shot use. To compile many shaders, load the runtime
once and open a cheap session per configuration on top of it:

```java
try (var runtime = SlangRuntime.load()) {
    try (var spirv = runtime.forSpirv()) {
        CompileResult r = spirv.compile("hello", source, "main");
    }
    // ... more sessions on the same instance, no re-load ...
}
```

To run an external WASM artifact through the *runtime* compiler instead of the bundled
build-time-compiled module, use the `Path`-based builder; `withCacheDir` persists the JIT'd
bytecode to disk, keyed by the WASM module's content digest, so a later run (even in a new JVM
process) can skip compilation entirely (for a custom cache backend, use `withCache(Cache)` —
`Cache` is `run.endive.compiler.Cache`):

```java
try (var runtime = SlangRuntime.builder(wasmPath)
        .withRuntimeCompiler(true)
        .withCacheDir(Path.of(".slang-cache"))
        .build()) {
    // ...
}
```

### Reflection and modules

Beyond one-shot `compile()`, `SlangCompiler.loadModule(...)` parses a module once and returns a
`SlangModule` that can compile individual entry points, compile all of them together, specialize
generic entry points, serialize/deserialize checked IR (to skip re-parsing later), and produce
declaration and disassembly output. Reflection data is available as JSON
(`CompileResult.reflectionJson()` / `SlangModule.declReflectionJson()`) and can be parsed into typed
models under [`org.shaderslang.wasm.reflection`](src/main/java/org/shaderslang/wasm/reflection)
(`ShaderReflection`, `EntryPointReflection`, `TypeLayoutReflection`, `VariableLayoutReflection`,
`DeclReflection`).

## Testing

```
./gradlew test
```

Tests run against the real `slang-wasm-lib.wasm` artifact (located the same way as the main build —
see [Building](#building)) and are skipped, rather than failed, if it isn't present.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
