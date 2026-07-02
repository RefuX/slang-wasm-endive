package org.shaderslang.wasm;

import org.shaderslang.wasm.reflection.ShaderReflection;

/**
 * Immutable result of a {@link SlangCompiler#compile} call.
 *
 * All byte arrays are copies taken at compile time; they remain valid after
 * the result is returned and are independent of the compiler instance lifetime.
 */
public final class CompileResult {

    private final boolean succeeded;
    private final byte[] code;
    private final String reflectionJson;
    private final String diagnostics;
    private ShaderReflection reflection;

    CompileResult(boolean succeeded, byte[] code, String reflectionJson, String diagnostics) {
        this.succeeded = succeeded;
        this.code = code;
        this.reflectionJson = reflectionJson;
        this.diagnostics = diagnostics;
    }

    /** Returns true if compilation produced target code. */
    public boolean succeeded() { return succeeded; }

    /**
     * Compiled target code bytes (e.g. SPIR-V words). Non-empty only when
     * {@link #succeeded()} is true.
     */
    public byte[] code() { return code; }

    /**
     * Reflection data serialized as a JSON string. Non-empty only when
     * {@link #succeeded()} is true and the layout was serializable.
     */
    public String reflectionJson() { return reflectionJson; }

    /**
     * Concatenated diagnostic messages (warnings and/or errors). May be
     * non-empty even on success (warnings). Always non-empty on failure.
     */
    public String diagnostics() { return diagnostics; }

    /**
     * Parse {@link #reflectionJson()} into a typed {@link ShaderReflection}, so
     * callers don't have to call {@link ShaderReflection#parse} themselves.
     * Parsed once and cached for the lifetime of this result.
     *
     * @throws IllegalStateException if {@link #succeeded()} is false (there is
     *                                no reflection data to parse — see {@link #diagnostics()})
     */
    public ShaderReflection reflection() {
        if (!succeeded) {
            throw new IllegalStateException(
                    "reflection() is only available when compilation succeeded; "
                    + "see diagnostics(): " + diagnostics);
        }
        if (reflection == null) {
            reflection = ShaderReflection.parse(reflectionJson);
        }
        return reflection;
    }
}
