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

To pin a SPIR-V version, target a `VulkanVersion` instead of hand-writing the profile string:
Vulkan minor versions don't map onto SPIR-V minor versions 1:1 (Vulkan 1.3 requires SPIR-V 1.6, not
`"spirv_1_3"`), so [`VulkanVersion`](src/main/java/org/shaderslang/wasm/VulkanVersion.java) carries
the correct mapping — `.target(Target.SPIRV, VulkanVersion.VULKAN_1_3)` on the builder (or
`SlangCompiler.TargetSpec.of(Target, VulkanVersion)` for the multi-target descriptor form).

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
`DeclReflection`) — `CompileResult.reflection()` does the `ShaderReflection.parse(reflectionJson())`
step for you (memoized; throws if the compile didn't succeed).

For browsing bindings and packing offsets, `ShaderReflection.find("gCB.material.color")` resolves a
nested field by dotted path in one call instead of manually chaining `.fields()` / `.typeLayout()`
(it also looks straight through a `ConstantBuffer<T>`/`ParameterBlock<T>` wrapper to the struct's
fields, which `VariableLayoutReflection.fields()` now does too), and `ShaderReflection.dump()` renders
the whole tree — every parameter and entry point, with its binding — as indented text for a quick
look while iterating:

```java
CompileResult result = slang.compile("hello", source, "main");
System.out.println(result.reflection().dump());
// parameters:
//   gCB : CONSTANT_BUFFER  DESCRIPTOR_TABLE_SLOT space=0 index=0
//     color : VECTOR<FLOAT32>[3]  offset=0 size=12
//     count : SCALAR<INT32>  offset=12 size=4
// entryPoints:
//   main (COMPUTE) threadGroupSize=[1, 1, 1]

long countOffset = result.reflection().find("gCB.count").orElseThrow().offset();
```

## Kotlin

[`slang-wasm-endive-kotlin`](slang-wasm-endive-kotlin) is a separate module/artifact with Kotlin DSL
builders over the Java API above. It's a thin, separately-published wrapper (depends on the main
jar) rather than being bundled into it, so plain Java consumers don't pick up `kotlin-stdlib` as a
transitive dependency.

The quickest way in is `slang { }`, a single-target session scoped to a block, with a `compile`
that reads straight from a file and (optionally) writes the compiled code back out to one:

```kotlin
val result = slang {
    compile("path/to/shader.slang", output = "path/to/shader.spv")
}
```

`target` defaults to `Target.SPIRV` — pass it explicitly (`slang(target = Target.HLSL) { ... }`) for
another target.

`vulkanVersion` pins a SPIR-V version by target Vulkan version instead of a hand-written profile
string, using the Java API's [`VulkanVersion`](src/main/java/org/shaderslang/wasm/VulkanVersion.java)
directly (no Kotlin-side wrapper needed):

```kotlin
val result = slang(vulkanVersion = VulkanVersion.VULKAN_1_3) {
    compile("path/to/shader.slang")
}
```

`slangSession { }`'s `target(Target, VulkanVersion)` — a plain Java overload, called like any other
Kotlin function — does the same for the multi-target builder below.

The reflection helpers above ([`CompileResult.reflection()`](#reflection-and-modules),
`ShaderReflection.find(path)`, `ShaderReflection.dump()`) are plain Java methods too, so they read
the same way from Kotlin:

```kotlin
val result = slang { compile("path/to/shader.slang") }
println(result.reflection().dump())
val countOffset = result.reflection().find("gCB.count").orElseThrow().offset()
```

For multiple targets, macros, search paths, or session-wide compiler options, drop down to
`slangSession { }` (wraps `SlangCompiler.builder()`), `slangRuntime { }` (wraps
`SlangRuntime.builder()`, for sharing one WASM instance across sessions), and a block form of
`compile` for building a `CompileRequest` fluently:

```kotlin
slangSession {
    target(Target.SPIRV, "spirv_1_4")
    define("MY_MACRO", "1")
    optimizationLevel(OptimizationLevel.HIGH)
}.use { compiler ->
    val result = compiler.compile("hello", source) {
        entryPoint("main")
        target(Target.SPIRV)
    }
}
```

## Testing

```
./gradlew test
```

Tests run against the real `slang-wasm-lib.wasm` artifact (located the same way as the main build —
see [Building](#building)) and are skipped, rather than failed, if it isn't present. The Kotlin
module's tests (`./gradlew :slang-wasm-endive-kotlin:test`) run against the bundled build-time-compiled
module and need no external wasm file.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
