package org.shaderslang.wasm.reflection;

import org.shaderslang.wasm.enums.ScalarType;
import org.shaderslang.wasm.enums.TypeKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.shaderslang.wasm.reflection.JsonUtil.*;

/**
 * A laid-out type (mirrors {@code TypeLayoutReflection} in SlangShaderSharp / {@code
 * slang::TypeLayoutReflection}): a type's shape plus binding/offset information for each
 * of its members. This is the type of {@link VariableLayoutReflection#typeLayout()} — the
 * {@code "type"} field of a parameter, entry-point parameter, or struct field in the
 * reflection JSON.
 */
public final class TypeLayoutReflection {
    private final TypeKind kind;
    private final String name;
    private final ScalarType scalarType;
    private final int rowCount;
    private final int columnCount;
    private final long elementCount;
    private final TypeLayoutReflection elementType;
    private final TypeReflection resultType;
    private final List<VariableLayoutReflection> fields;

    private TypeLayoutReflection(
            TypeKind kind,
            String name,
            ScalarType scalarType,
            int rowCount,
            int columnCount,
            long elementCount,
            TypeLayoutReflection elementType,
            TypeReflection resultType,
            List<VariableLayoutReflection> fields) {
        this.kind = kind;
        this.name = name;
        this.scalarType = scalarType;
        this.rowCount = rowCount;
        this.columnCount = columnCount;
        this.elementCount = elementCount;
        this.elementType = elementType;
        this.resultType = resultType;
        this.fields = fields;
    }

    static TypeLayoutReflection fromJson(Map<String, Object> json) {
        if (json == null) return null;

        TypeKind kind = ReflectionMapping.typeKind(getString(json, "kind"));
        String name = getString(json, "name");
        ScalarType scalarType = json.containsKey("scalarType")
                ? ReflectionMapping.scalarType(getString(json, "scalarType"))
                : null;
        int rowCount = (int) getLong(json, "rowCount", 0);
        int columnCount = (int) getLong(json, "columnCount", 0);
        long elementCount = getLong(json, "elementCount", -1);
        TypeLayoutReflection elementType = fromJson(getObject(json, "elementType"));
        TypeReflection resultType = TypeReflection.fromJson(getObject(json, "resultType"));

        List<VariableLayoutReflection> fields = new ArrayList<>();
        for (Object fieldObj : getArray(json, "fields")) {
            fields.add(VariableLayoutReflection.fromJson(asObject(fieldObj)));
        }

        return new TypeLayoutReflection(
                kind, name, scalarType, rowCount, columnCount, elementCount, elementType,
                resultType, fields);
    }

    /** The kind of type this is (struct, array, scalar, vector, matrix, constantBuffer, resource, ...). */
    public TypeKind kind() {
        return kind;
    }

    /** The type's name, if it has one (e.g. a struct's declared name). May be {@code null}. */
    public String name() {
        return name;
    }

    /** The scalar element type, for {@link TypeKind#SCALAR}. */
    public ScalarType scalarType() {
        return scalarType;
    }

    /** Row count, for {@link TypeKind#MATRIX}. 0 if not a matrix. */
    public int rowCount() {
        return rowCount;
    }

    /** Column count, for {@link TypeKind#MATRIX}. 0 if not a matrix. */
    public int columnCount() {
        return columnCount;
    }

    /** Element count, for {@link TypeKind#ARRAY} or {@link TypeKind#VECTOR}. -1 if unbounded/unknown/not applicable. */
    public long elementCount() {
        return elementCount;
    }

    /**
     * Element type, for {@link TypeKind#ARRAY}, {@link TypeKind#VECTOR}, {@link TypeKind#MATRIX},
     * {@link TypeKind#CONSTANT_BUFFER}, {@link TypeKind#PARAMETER_BLOCK},
     * {@link TypeKind#TEXTURE_BUFFER}, or {@link TypeKind#SHADER_STORAGE_BUFFER} — the type this
     * one wraps, with layout. For a {@code ConstantBuffer<MyStruct>} parameter this is the
     * {@code MyStruct} type layout.
     */
    public TypeLayoutReflection elementType() {
        return elementType;
    }

    /**
     * The element type of a structured/byte-address buffer {@link TypeKind#RESOURCE}, without
     * layout (resources don't lay out their element type the way a constant buffer does).
     * {@code null} for non-resource kinds, or a resource kind that doesn't carry one.
     */
    public TypeReflection resultType() {
        return resultType;
    }

    /** Field layouts, for {@link TypeKind#STRUCT} — each field's name, type, and binding. */
    public List<VariableLayoutReflection> fields() {
        return fields;
    }

    /** Field names, for {@link TypeKind#STRUCT}. Equivalent to mapping {@link #fields()} to its names. */
    public List<String> fieldNames() {
        List<String> names = new ArrayList<>(fields.size());
        for (VariableLayoutReflection field : fields) {
            names.add(field.name());
        }
        return names;
    }

    /**
     * This type's own {@link #fields()} if it has any, otherwise (recursively) the fields of
     * the type it wraps via {@link #elementType()}. A {@code ConstantBuffer<MyStruct>} or
     * {@code ParameterBlock<MyStruct>} parameter's type layout has {@link TypeKind#CONSTANT_BUFFER}/
     * {@link TypeKind#PARAMETER_BLOCK} kind, not {@link TypeKind#STRUCT} — its own {@link #fields()}
     * is empty, and {@code MyStruct}'s fields live on {@link #elementType()} instead. This walks
     * that chain, so {@code gCB.typeLayout().unwrappedFields()} reaches {@code MyStruct}'s fields
     * directly without the caller needing to know about the wrapper. Empty if there are no fields
     * anywhere in the chain (e.g. a scalar, vector, or resource with no struct in sight).
     */
    public List<VariableLayoutReflection> unwrappedFields() {
        TypeLayoutReflection type = this;
        while (type != null && type.fields.isEmpty() && type.elementType != null) {
            type = type.elementType;
        }
        return type == null ? List.of() : type.fields;
    }
}
