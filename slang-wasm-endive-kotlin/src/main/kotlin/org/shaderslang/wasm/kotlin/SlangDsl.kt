package org.shaderslang.wasm.kotlin

import org.shaderslang.wasm.CompileResult
import org.shaderslang.wasm.SlangCompiler
import org.shaderslang.wasm.SlangRuntime
import org.shaderslang.wasm.VulkanVersion
import org.shaderslang.wasm.enums.Target
import java.nio.file.Files
import java.nio.file.Path

/**
 * Open a single-target [SlangCompiler] session for [target] (defaults to
 * [Target.SPIRV]), run [block] against it, and close the session afterwards.
 * The simplest way into the DSL — for multiple targets, macros, search paths,
 * or session-wide compiler options, use [slangSession] instead.
 *
 * Pin the SPIR-V version by either [profile] (a raw profile string, e.g.
 * `"spirv_1_6"`) or [vulkanVersion] (see [VulkanVersion] for why that's not
 * simply `"spirv_1_x"` for the matching `x`) — at most one of the two.
 * Neither is needed for other targets, or to accept SPIR-V's default profile.
 *
 * ```kotlin
 * val result = slang(vulkanVersion = VulkanVersion.VULKAN_1_3) {
 *     compile("path/to/shader.slang")
 * }
 * ```
 */
inline fun <R> slang(
    target: Target = Target.SPIRV,
    profile: String = "",
    vulkanVersion: VulkanVersion? = null,
    block: SlangCompiler.() -> R,
): R {
    require(profile.isEmpty() || vulkanVersion == null) {
        "Specify either profile or vulkanVersion, not both"
    }
    val resolvedProfile = vulkanVersion?.profile ?: profile
    val builder = SlangCompiler.builder()
    if (resolvedProfile.isEmpty()) builder.target(target) else builder.target(target, resolvedProfile)
    return builder.build().use { it.block() }
}

/**
 * Compile the Slang source file at [path], as module [moduleName] (defaults
 * to the file's name without extension) linking entry point [entryPoint]
 * (defaults to {@code "main"}), for the target this session was opened with.
 * When [output] is given and compilation succeeds, the compiled code bytes
 * are additionally written there.
 *
 * ```kotlin
 * slang(target = Target.SPIRV) {
 *     val result = compile("path/to/shader.slang", output = "path/to/shader.spv")
 * }
 * ```
 */
fun SlangCompiler.compile(
    path: String,
    entryPoint: String = "main",
    moduleName: String? = null,
    output: String? = null,
): CompileResult {
    val file = Path.of(path)
    val source = Files.readString(file)
    val name = moduleName ?: file.fileName.toString().substringBeforeLast('.')

    val result = compile(name, source, entryPoint)
    if (output != null && result.succeeded()) {
        Files.write(Path.of(output), result.code())
    }
    return result
}

/**
 * Build a [SlangCompiler] session via [SlangCompiler.SessionBuilder]:
 *
 * ```kotlin
 * val compiler = slangSession {
 *     target(Target.SPIRV, "spirv_1_4")
 *     define("MY_MACRO", "1")
 *     optimizationLevel(OptimizationLevel.HIGH)
 * }
 * ```
 */
inline fun slangSession(block: SlangCompiler.SessionBuilder.() -> Unit): SlangCompiler =
    SlangCompiler.builder().apply(block).build()

/**
 * Build a [SlangRuntime], sharable across any number of sessions opened on it
 * (e.g. via [SlangRuntime.newSession]). Omit [wasmPath] to use the bundled,
 * build-time-compiled module; [block] configures [SlangRuntime.Builder]
 * options such as `withRuntimeCompiler`/`withCacheDir`, which only apply when
 * [wasmPath] is given.
 *
 * ```kotlin
 * val runtime = slangRuntime(wasmPath) {
 *     withRuntimeCompiler(true)
 *     withCacheDir(cacheDir)
 * }
 * ```
 */
inline fun slangRuntime(
    wasmPath: Path? = null,
    block: SlangRuntime.Builder.() -> Unit = {},
): SlangRuntime {
    val builder = if (wasmPath == null) SlangRuntime.builder() else SlangRuntime.builder(wasmPath)
    return builder.apply(block).build()
}

/**
 * Compile a [SlangCompiler.CompileRequest] built fluently via [block], as an
 * alternative to [SlangCompiler]'s positional-argument `compile` overloads:
 *
 * ```kotlin
 * val result = compiler.compile("hello", source) {
 *     entryPoint("main")
 *     target(Target.SPIRV)
 * }
 * ```
 */
inline fun SlangCompiler.compile(
    moduleName: String,
    source: String,
    block: SlangCompiler.CompileRequest.() -> Unit,
): CompileResult =
    compile(SlangCompiler.CompileRequest.source(moduleName, source).apply(block))
