// THIS FILE IS GENERATED — DO NOT EDIT.
// Source: include/slang.h
// Generator: source/slang-wasm-wasi/tools/generate-slang-bindings.py
// Re-run: python source/slang-wasm-wasi/tools/generate-slang-bindings.py  (or cmake --preset default)


package io.github.refux.slangwasm.enums;

import javax.annotation.processing.Generated;

/** Debug info verbosity. Mirrors {@code SlangDebugInfoLevel} in slang.h. */
@Generated("source/slang-wasm-wasi/tools/generate-slang-bindings.py")
public enum DebugInfoLevel {
    /** Don't emit debug information at all. */
    NONE(0),
    /** Emit as little debug information as possible, while still supporting stack trackers. */
    MINIMAL(1),
    /** Emit whatever is the standard level of debug information for each target. */
    STANDARD(2),
    /** Emit as much debug information as possible for each target. */
    MAXIMAL(3);

    public final int value;

    DebugInfoLevel(int value) {
        this.value = value;
    }

    /** Return the DebugInfoLevel constant for the given integer value. */
    public static DebugInfoLevel fromValue(int v) {
        for (DebugInfoLevel t : values()) if (t.value == v) return t;
        throw new IllegalArgumentException("Unknown DebugInfoLevel value: " + v);
    }
}
