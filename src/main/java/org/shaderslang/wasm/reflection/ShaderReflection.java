package org.shaderslang.wasm.reflection;

import org.shaderslang.wasm.enums.Stage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.shaderslang.wasm.reflection.JsonUtil.*;

/**
 * The top-level reflection result for one compiled program (mirrors {@code ShaderReflection} in
 * SlangShaderSharp / {@code slang::ShaderReflection}). Parse it from
 * {@link org.shaderslang.wasm.CompileResult#reflectionJson()} via {@link #parse(String)} to get
 * binding layout, type information, and entry-point metadata without parsing JSON or knowing its
 * schema directly.
 */
public final class ShaderReflection {
    private final List<VariableLayoutReflection> parameters;
    private final List<EntryPointReflection> entryPoints;
    private final int bindlessSpaceIndex;

    private ShaderReflection(
            List<VariableLayoutReflection> parameters,
            List<EntryPointReflection> entryPoints,
            int bindlessSpaceIndex) {
        this.parameters = parameters;
        this.entryPoints = entryPoints;
        this.bindlessSpaceIndex = bindlessSpaceIndex;
    }

    /**
     * Parse {@code json} (the output of {@code spReflection_ToJson}, as returned by
     * {@link org.shaderslang.wasm.CompileResult#reflectionJson()}) into a typed reflection tree.
     *
     * @throws IllegalArgumentException if {@code json} is not well-formed
     */
    public static ShaderReflection parse(String json) {
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);

        List<VariableLayoutReflection> parameters = new ArrayList<>();
        for (Object paramObj : getArray(root, "parameters")) {
            parameters.add(VariableLayoutReflection.fromJson(asObject(paramObj)));
        }

        List<EntryPointReflection> entryPoints = new ArrayList<>();
        for (Object epObj : getArray(root, "entryPoints")) {
            entryPoints.add(EntryPointReflection.fromJson(asObject(epObj)));
        }

        int bindlessSpaceIndex = (int) getLong(root, "bindlessSpaceIndex", -1);

        return new ShaderReflection(parameters, entryPoints, bindlessSpaceIndex);
    }

    /** Global shader parameters (uniforms, resources, constant buffers, ...) outside any entry point. */
    public List<VariableLayoutReflection> parameters() {
        return parameters;
    }

    /** Every entry point linked into this program. */
    public List<EntryPointReflection> entryPoints() {
        return entryPoints;
    }

    /** The named entry point, if one with that name was linked into this program. */
    public Optional<EntryPointReflection> entryPoint(String name) {
        return entryPoints.stream().filter(ep -> name.equals(ep.name())).findFirst();
    }

    /** The descriptor space reserved for bindless resource heaps, or -1 if not used. */
    public int bindlessSpaceIndex() {
        return bindlessSpaceIndex;
    }

    /**
     * Find a top-level parameter or nested field by dotted path (e.g.
     * {@code "gCB.material.color"}), descending one {@link VariableLayoutReflection#find}
     * per segment. Empty if any segment along the path isn't found.
     */
    public Optional<VariableLayoutReflection> find(String path) {
        String[] segments = path.split("\\.");
        Optional<VariableLayoutReflection> current = parameters.stream()
                .filter(p -> segments[0].equals(p.name()))
                .findFirst();
        for (int i = 1; i < segments.length && current.isPresent(); i++) {
            String segment = segments[i];
            current = current.flatMap(v -> v.find(segment));
        }
        return current;
    }

    /**
     * Render this reflection tree as indented, human-readable text — every parameter and entry
     * point, with its binding (descriptor index/space for a resource, byte offset/size for a
     * uniform member) and recursively its fields. Meant for quickly eyeballing a shader's layout
     * (e.g. {@code System.out.println(result.reflection().dump())}), not machine parsing.
     */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("parameters:\n");
        for (VariableLayoutReflection p : parameters) {
            appendMember(sb, p, 1);
        }
        sb.append("entryPoints:\n");
        for (EntryPointReflection ep : entryPoints) {
            sb.append("  ").append(ep.name()).append(" (").append(ep.stage()).append(')');
            if (ep.stage() == Stage.COMPUTE) {
                sb.append(" threadGroupSize=").append(Arrays.toString(ep.threadGroupSize()));
            }
            sb.append('\n');
            for (VariableLayoutReflection p : ep.parameters()) {
                appendMember(sb, p, 2);
            }
        }
        return sb.toString();
    }

    private static void appendMember(StringBuilder sb, VariableLayoutReflection member, int depth) {
        sb.append("  ".repeat(depth))
                .append(member.name() == null ? "<unnamed>" : member.name())
                .append(" : ").append(describeType(member.typeLayout()))
                .append(describeBinding(member))
                .append('\n');
        for (VariableLayoutReflection field : member.fields()) {
            appendMember(sb, field, depth + 1);
        }
    }

    private static String describeType(TypeLayoutReflection type) {
        if (type == null) return "?";
        StringBuilder sb = new StringBuilder(String.valueOf(type.kind()));
        if (type.name() != null) sb.append(' ').append(type.name());

        // A vector/matrix's own scalarType() is unset; its scalar component type is one level
        // down, on elementType() (e.g. float3's elementType is a SCALAR<FLOAT32>).
        var scalarType = type.scalarType() != null
                ? type.scalarType()
                : (type.elementType() != null ? type.elementType().scalarType() : null);
        if (scalarType != null) sb.append('<').append(scalarType).append('>');

        if (type.rowCount() > 0 || type.columnCount() > 0) {
            sb.append(' ').append(type.rowCount()).append('x').append(type.columnCount());
        }
        if (type.elementCount() >= 0) sb.append('[').append(type.elementCount()).append(']');
        return sb.toString();
    }

    private static String describeBinding(VariableLayoutReflection member) {
        switch (member.bindingCategory()) {
            case NONE:
                return "";
            case UNIFORM:
                return "  offset=" + member.offset() + " size=" + member.size();
            default:
                String count = member.size() != 1 ? " count=" + member.size() : "";
                return "  " + member.bindingCategory() + " space=" + member.bindingSpace()
                        + " index=" + member.bindingIndex() + count;
        }
    }
}
