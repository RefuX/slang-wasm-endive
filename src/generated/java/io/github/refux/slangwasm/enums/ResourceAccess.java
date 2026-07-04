// THIS FILE IS GENERATED — DO NOT EDIT.
// Source: include/slang.h
// Generator: source/slang-wasm-wasi/tools/generate-slang-bindings.py
// Re-run: python source/slang-wasm-wasi/tools/generate-slang-bindings.py  (or cmake --preset default)


package io.github.refux.slangwasm.enums;

import javax.annotation.processing.Generated;

/** Resource access mode. Mirrors {@code SlangResourceAccess} in slang.h. */
@Generated("source/slang-wasm-wasi/tools/generate-slang-bindings.py")
public enum ResourceAccess {
    NONE(0),
    READ(1),
    READ_WRITE(2),
    RASTER_ORDERED(3),
    APPEND(4),
    CONSUME(5),
    WRITE(6),
    FEEDBACK(7),
    UNKNOWN(2147483647);

    public final int value;

    ResourceAccess(int value) {
        this.value = value;
    }

    /** Return the ResourceAccess constant for the given integer value. */
    public static ResourceAccess fromValue(int v) {
        for (ResourceAccess t : values()) if (t.value == v) return t;
        throw new IllegalArgumentException("Unknown ResourceAccess value: " + v);
    }
}
