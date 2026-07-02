package org.shaderslang.wasm;

/**
 * Vulkan API versions, mapped to the SPIR-V profile string that version's
 * SPIR-V environment requires as a baseline (the {@code profile} argument of
 * {@link SlangCompiler.SessionBuilder#target(org.shaderslang.wasm.enums.Target, String)}).
 * Use this enum (via {@link
 * SlangCompiler.SessionBuilder#target(org.shaderslang.wasm.enums.Target, VulkanVersion)}
 * or {@link SlangCompiler.TargetSpec#of(org.shaderslang.wasm.enums.Target, VulkanVersion)})
 * instead of hand-writing the {@code "spirv_1_x"} string.
 */
public enum VulkanVersion {
    VULKAN_1_0("spirv_1_0"),
    VULKAN_1_1("spirv_1_3"),
    VULKAN_1_2("spirv_1_5"),
    VULKAN_1_3("spirv_1_6"),
    VULKAN_1_4("spirv_1_6");

    /** The SPIR-V profile string this Vulkan version requires as a baseline. */
    public final String profile;

    VulkanVersion(String profile) {
        this.profile = profile;
    }
}
