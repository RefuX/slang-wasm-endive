package org.shaderslang.wasm.kotlin

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.shaderslang.wasm.VulkanVersion
import org.shaderslang.wasm.enums.OptimizationLevel
import org.shaderslang.wasm.enums.Target
import java.nio.file.Files
import java.nio.file.Path

private const val TRIVIAL_SHADER =
    "[shader(\"compute\")] [numthreads(1,1,1)] void main() {}"

class SlangDslTest {

    @Test
    fun `slangSession compiles a trivial shader`() {
        slangSession {
            target(Target.SPIRV)
            optimizationLevel(OptimizationLevel.HIGH)
        }.use { compiler ->
            val result = compiler.compile("hello", TRIVIAL_SHADER, "main")
            assertTrue(result.succeeded(), "Diagnostics:\n${result.diagnostics()}")
            assertTrue(result.code().isNotEmpty())
        }
    }

    @Test
    fun `slangRuntime shares one instance across sessions`() {
        slangRuntime().use { runtime ->
            runtime.forSpirv().use { compiler ->
                val result = compiler.compile("hello", TRIVIAL_SHADER, "main")
                assertTrue(result.succeeded(), "Diagnostics:\n${result.diagnostics()}")
            }
        }
    }

    @Test
    fun `compile block form builds a CompileRequest`() {
        slangSession { target(Target.SPIRV) }.use { compiler ->
            val result = compiler.compile("hello", TRIVIAL_SHADER) {
                entryPoint("main")
                target(Target.SPIRV)
            }
            assertTrue(result.succeeded(), "Diagnostics:\n${result.diagnostics()}")
        }
    }

    @Test
    fun `slang scope defaults to SPIRV and compiles a shader file`(@TempDir dir: Path) {
        val source = dir.resolve("hello.slang")
        Files.writeString(source, TRIVIAL_SHADER)

        val result = slang {
            compile(source.toString())
        }

        assertTrue(result.succeeded(), "Diagnostics:\n${result.diagnostics()}")
        assertTrue(result.code().isNotEmpty())
    }

    @Test
    fun `compile writes the compiled code to output when given`(@TempDir dir: Path) {
        val source = dir.resolve("hello.slang")
        val output = dir.resolve("hello.spv")
        Files.writeString(source, TRIVIAL_SHADER)

        val result = slang(target = Target.SPIRV) {
            compile(source.toString(), output = output.toString())
        }

        assertTrue(result.succeeded(), "Diagnostics:\n${result.diagnostics()}")
        assertArrayEquals(result.code(), Files.readAllBytes(output))
    }

    // ── VulkanVersion -> SPIR-V profile mapping ──────────────────────

    @Test
    fun `VulkanVersion maps to the SPIR-V profile Vulkan actually requires, not the naive one`() {
        assertEquals("spirv_1_0", VulkanVersion.VULKAN_1_0.profile)
        assertEquals("spirv_1_3", VulkanVersion.VULKAN_1_1.profile)
        assertEquals("spirv_1_5", VulkanVersion.VULKAN_1_2.profile)
        assertEquals("spirv_1_6", VulkanVersion.VULKAN_1_3.profile)
        assertEquals("spirv_1_6", VulkanVersion.VULKAN_1_4.profile)
    }

    @Test
    fun `slang with vulkanVersion compiles using the mapped profile`() {
        val result = slang(vulkanVersion = VulkanVersion.VULKAN_1_3) {
            compile("hello", TRIVIAL_SHADER, "main")
        }
        assertTrue(result.succeeded(), "Diagnostics:\n${result.diagnostics()}")
        assertTrue(result.code().isNotEmpty())
    }

    @Test
    fun `slang rejects both profile and vulkanVersion`() {
        assertThrows(IllegalArgumentException::class.java) {
            slang(profile = "spirv_1_4", vulkanVersion = VulkanVersion.VULKAN_1_3) {
                compile("hello", TRIVIAL_SHADER, "main")
            }
        }
    }

    @Test
    fun `SessionBuilder target accepts a VulkanVersion`() {
        slangSession {
            target(Target.SPIRV, VulkanVersion.VULKAN_1_2)
        }.use { compiler ->
            val result = compiler.compile("hello", TRIVIAL_SHADER, "main")
            assertTrue(result.succeeded(), "Diagnostics:\n${result.diagnostics()}")
        }
    }

    // ── reflection browsing: CompileResult.reflection(), find(), dump() ──

    private val constantBufferShader =
        """
        struct MyStruct {
            float3 color;
            int count;
            float2 offset;
        };
        ConstantBuffer<MyStruct> gCB;
        RWStructuredBuffer<float> output;
        [shader("compute")] [numthreads(1,1,1)]
        void main() { output[0] = gCB.color.x + gCB.count + gCB.offset.x; }
        """.trimIndent()

    @Test
    fun `CompileResult reflection() parses without calling ShaderReflection parse directly`() {
        val result = slang { compile("cb", constantBufferShader, "main") }
        assertTrue(result.succeeded(), "Diagnostics:\n${result.diagnostics()}")

        val reflection = result.reflection()
        assertTrue(reflection.parameters().any { it.name() == "gCB" })
    }

    @Test
    fun `find resolves a nested field by dotted path`() {
        val result = slang { compile("cb", constantBufferShader, "main") }
        assertTrue(result.succeeded(), "Diagnostics:\n${result.diagnostics()}")

        val count = result.reflection().find("gCB.count").orElseThrow()
        assertEquals("count", count.name())
        assertTrue(count.offset() > 0)

        assertTrue(result.reflection().find("gCB.nonexistent").isEmpty)
    }

    @Test
    fun `dump reports offsets for every member`() {
        val result = slang { compile("cb", constantBufferShader, "main") }
        assertTrue(result.succeeded(), "Diagnostics:\n${result.diagnostics()}")

        val dump = result.reflection().dump()
        assertTrue(dump.contains("gCB"))
        assertTrue(dump.contains("offset="))
    }
}
