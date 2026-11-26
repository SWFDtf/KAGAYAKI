package de.swfdtf.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import de.swfdtf.math.ColorMatrix4f;
import de.swfdtf.math.Rect2f;
import de.swfdtf.math.Transform2D;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Type-preserving JSON codec for KAGAYAKI persistence and save slots.
 *
 * <p>Cleanly encodes and decodes primitives, boxed numbers (preserving exact Java types such as
 * {@link Integer} vs {@link Long} vs {@link Float} vs {@link Double}), {@link String}, {@link Enum},
 * {@link List}, {@link RevertableList}, {@link Map}, {@link RevertableMap}, {@link Set},
 * primitive/object arrays, {@link Transform2D}, {@link ColorMatrix4f}, {@link Rect2f},
 * and arbitrary custom Java objects (including {@code @->{ ... }<<} {@code VJavaUserTypes} classes)
 * via reflective {@code __vjavaClass} serialization and constructor/field reconstruction.</p>
 */
public final class SaveValueCodec {

    /** JSON discriminator field for custom Java and {@code VJavaUserTypes} classes. */
    public static final String KEY_VJAVA_CLASS = "__vjavaClass";

    /** JSON payload field holding serialized reflective object fields. */
    public static final String KEY_FIELDS = "fields";

    /** JSON discriminator field for strongly typed built-in wrappers, arrays, and collections. */
    public static final String KEY_KTYPE = "__ktype";

    private static final String GENERATED_USER_TYPES_PREFIX = "de.swfdtf.vjava.gen.VJavaUserTypes$";
    private static final String GENERATED_PACKAGE_PREFIX = "de.swfdtf.vjava.gen.";
    private static final String ENGINE_PACKAGE_PREFIX = "de.swfdtf.";
    private static final Set<String> ALLOWED_ARRAY_COMPONENTS = Set.of(
            "java.lang.Object",
            "java.lang.String",
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Short",
            "java.lang.Character",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Float",
            "java.lang.Double"
    );

    private SaveValueCodec() {
    }

    /**
     * Creates a configured {@link Gson} instance with lossless adapters for {@link Instant},
     * {@code byte[]} (Base64), {@link ColorMatrix4f}, and {@link Transform2D} (including {@code NaN} alignments).
     *
     * @param prettyPrinting {@code true} for indented JSON, {@code false} for compact JSON
     * @return configured {@link Gson} instance
     */
    public static Gson createGson(boolean prettyPrinting) {
        GsonBuilder builder = new GsonBuilder()
                .registerTypeAdapter(Instant.class, new InstantTypeAdapter())
                .registerTypeAdapter(byte[].class, new ByteArrayTypeAdapter())
                .registerTypeAdapter(ColorMatrix4f.class, new ColorMatrixTypeAdapter())
                .registerTypeAdapter(Transform2D.class, new Transform2DTypeAdapter());
        if (prettyPrinting) {
            builder.setPrettyPrinting();
        }
        return builder.create();
    }

    /**
     * Encodes an arbitrary Java value into a type-preserving {@link JsonElement}.
     *
     * @param value source value (may be {@code null})
     * @return encoded {@link JsonElement}
     */
    public static JsonElement encode(Object value) {
        return encodeInternal(value, new IdentityHashMap<>());
    }

    /**
     * Encodes a string-keyed variable map into a {@link JsonObject}.
     *
     * @param map source map (must not be {@code null})
     * @return encoded {@link JsonObject}
     */
    public static JsonObject encodeMap(Map<String, ?> map) {
        Objects.requireNonNull(map, "map must not be null");
        IdentityHashMap<Object, Boolean> active = new IdentityHashMap<>();
        JsonObject out = new JsonObject();
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.add(entry.getKey(), encodeInternal(entry.getValue(), active));
            }
        }
        return out;
    }

    /**
     * Decodes a {@link JsonElement} produced by {@link #encode(Object)} back into Java objects,
     * resolving custom {@code __vjavaClass} types with {@code optionalUserClassLoader}.
     *
     * @param element                 JSON element (may be {@code null})
     * @param optionalUserClassLoader optional classloader holding compiled {@code VJavaUserTypes} (nullable)
     * @return decoded Java value
     */
    public static Object decode(JsonElement element, ClassLoader optionalUserClassLoader) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return decodePrimitive(element.getAsJsonPrimitive());
        }
        if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            List<Object> list = new ArrayList<>(arr.size());
            for (JsonElement item : arr) {
                list.add(decode(item, optionalUserClassLoader));
            }
            return list;
        }
        JsonObject obj = element.getAsJsonObject();
        if (obj.has(KEY_KTYPE)) {
            return decodeTaggedObject(obj, optionalUserClassLoader);
        }
        if (obj.has(KEY_VJAVA_CLASS)) {
            return decodeCustomObject(obj, optionalUserClassLoader);
        }
        return decodePlainMap(obj, optionalUserClassLoader);
    }

    /**
     * Decodes a top-level {@link JsonObject} variable map encoded via {@link #encodeMap(Map)}.
     *
     * @param obj                     source JSON object (nullable)
     * @param optionalUserClassLoader optional user classloader (nullable)
     * @return mutable {@link LinkedHashMap} of decoded variables
     */
    public static Map<String, Object> decodeMap(JsonObject obj, ClassLoader optionalUserClassLoader) {
        if (obj == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>(Math.max(16, obj.size() * 2));
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            result.put(entry.getKey(), decode(entry.getValue(), optionalUserClassLoader));
        }
        return result;
    }

    // ---- Encoding Internals ----

    private static JsonElement encodeInternal(Object value, IdentityHashMap<Object, Boolean> active) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof String s) {
            return new JsonPrimitive(s);
        }
        if (value instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (value instanceof Integer i) {
            return new JsonPrimitive(i);
        }
        if (value instanceof Double d) {
            if (Double.isFinite(d)) {
                return new JsonPrimitive(d);
            }
            return taggedValue("double", new JsonPrimitive(Double.toString(d)));
        }
        if (value instanceof Long l) {
            return taggedValue("long", new JsonPrimitive(Long.toString(l)));
        }
        if (value instanceof Float f) {
            return taggedValue("float", new JsonPrimitive(Float.toString(f)));
        }
        if (value instanceof Short s) {
            return taggedValue("short", new JsonPrimitive(s));
        }
        if (value instanceof Byte b) {
            return taggedValue("byte", new JsonPrimitive(b));
        }
        if (value instanceof Character c) {
            return taggedValue("char", new JsonPrimitive(String.valueOf(c)));
        }
        if (value instanceof BigInteger bi) {
            return taggedValue("bigint", new JsonPrimitive(bi.toString()));
        }
        if (value instanceof BigDecimal bd) {
            return taggedValue("bigdec", new JsonPrimitive(bd.toString()));
        }
        if (value instanceof Instant instant) {
            return taggedValue("instant", new JsonPrimitive(instant.toString()));
        }
        if (value instanceof UUID uuid) {
            return taggedValue("uuid", new JsonPrimitive(uuid.toString()));
        }
        if (value instanceof Enum<?> e) {
            JsonObject obj = new JsonObject();
            obj.addProperty(KEY_KTYPE, "enum");
            obj.addProperty("class", e.getDeclaringClass().getName());
            obj.addProperty("name", e.name());
            return obj;
        }
        if (value instanceof Transform2D t) {
            JsonObject obj = new JsonObject();
            obj.addProperty(KEY_KTYPE, "transform2d");
            obj.add("value", encodeTransform2D(t));
            return obj;
        }
        if (value instanceof ColorMatrix4f cm) {
            JsonObject obj = new JsonObject();
            obj.addProperty(KEY_KTYPE, "colormatrix4f");
            obj.add("value", encodeFloatArray(cm.toFloatArray()));
            return obj;
        }
        if (value instanceof Rect2f rect) {
            JsonObject r = new JsonObject();
            r.addProperty(KEY_KTYPE, "rect2f");
            r.addProperty("x", rect.x());
            r.addProperty("y", rect.y());
            r.addProperty("width", rect.width());
            r.addProperty("height", rect.height());
            return r;
        }

        if (active.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException(
                    "Circular object reference detected while serializing " + value.getClass().getName()
            );
        }
        try {
            if (value.getClass().isArray()) {
                return encodeArray(value, active);
            }
            if (value instanceof RevertableList<?> rlist) {
                JsonObject obj = new JsonObject();
                obj.addProperty(KEY_KTYPE, "rlist");
                obj.add("items", encodeCollection(rlist, active));
                return obj;
            }
            if (value instanceof List<?> list) {
                return encodeCollection(list, active);
            }
            if (value instanceof Set<?> set) {
                JsonObject obj = new JsonObject();
                obj.addProperty(KEY_KTYPE, "set");
                obj.add("items", encodeCollection(set, active));
                return obj;
            }
            if (value instanceof RevertableMap<?, ?> rmap) {
                JsonObject obj = new JsonObject();
                obj.addProperty(KEY_KTYPE, "rmap");
                obj.add("pairs", encodeMapPairs(rmap, active));
                return obj;
            }
            if (value instanceof Map<?, ?> map) {
                return encodeMapValue(map, active);
            }
            return encodeCustomObject(value, active);
        } finally {
            active.remove(value);
        }
    }

    private static JsonObject taggedValue(String type, JsonElement value) {
        JsonObject obj = new JsonObject();
        obj.addProperty(KEY_KTYPE, type);
        obj.add("value", value);
        return obj;
    }

    private static JsonArray encodeCollection(Collection<?> col, IdentityHashMap<Object, Boolean> active) {
        JsonArray arr = new JsonArray(col.size());
        for (Object item : col) {
            arr.add(encodeInternal(item, active));
        }
        return arr;
    }

    private static JsonElement encodeMapValue(Map<?, ?> map, IdentityHashMap<Object, Boolean> active) {
        boolean canUsePlainObject = true;
        for (Object key : map.keySet()) {
            if (!(key instanceof String s) || KEY_KTYPE.equals(s) || KEY_VJAVA_CLASS.equals(s)) {
                canUsePlainObject = false;
                break;
            }
        }
        if (canUsePlainObject) {
            JsonObject obj = new JsonObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                obj.add((String) entry.getKey(), encodeInternal(entry.getValue(), active));
            }
            return obj;
        }
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty(KEY_KTYPE, "map");
        wrapper.add("pairs", encodeMapPairs(map, active));
        return wrapper;
    }

    private static JsonArray encodeMapPairs(Map<?, ?> map, IdentityHashMap<Object, Boolean> active) {
        JsonArray pairs = new JsonArray(map.size());
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            JsonArray pair = new JsonArray(2);
            pair.add(encodeInternal(entry.getKey(), active));
            pair.add(encodeInternal(entry.getValue(), active));
            pairs.add(pair);
        }
        return pairs;
    }

    private static JsonObject encodeArray(Object array, IdentityHashMap<Object, Boolean> active) {
        JsonObject obj = new JsonObject();
        if (array instanceof byte[] bytes) {
            obj.addProperty(KEY_KTYPE, "byte[]");
            obj.addProperty("base64", Base64.getEncoder().encodeToString(bytes));
            return obj;
        }
        if (array instanceof int[] ints) {
            obj.addProperty(KEY_KTYPE, "int[]");
            JsonArray items = new JsonArray(ints.length);
            for (int v : ints) {
                items.add(v);
            }
            obj.add("items", items);
            return obj;
        }
        if (array instanceof long[] longs) {
            obj.addProperty(KEY_KTYPE, "long[]");
            JsonArray items = new JsonArray(longs.length);
            for (long v : longs) {
                items.add(Long.toString(v));
            }
            obj.add("items", items);
            return obj;
        }
        if (array instanceof float[] floats) {
            obj.addProperty(KEY_KTYPE, "float[]");
            JsonArray items = new JsonArray(floats.length);
            for (float v : floats) {
                items.add(Float.toString(v));
            }
            obj.add("items", items);
            return obj;
        }
        if (array instanceof double[] doubles) {
            obj.addProperty(KEY_KTYPE, "double[]");
            JsonArray items = new JsonArray(doubles.length);
            for (double v : doubles) {
                items.add(Double.toString(v));
            }
            obj.add("items", items);
            return obj;
        }
        if (array instanceof boolean[] booleans) {
            obj.addProperty(KEY_KTYPE, "boolean[]");
            JsonArray items = new JsonArray(booleans.length);
            for (boolean v : booleans) {
                items.add(v);
            }
            obj.add("items", items);
            return obj;
        }
        if (array instanceof short[] shorts) {
            obj.addProperty(KEY_KTYPE, "short[]");
            JsonArray items = new JsonArray(shorts.length);
            for (short v : shorts) {
                items.add(v);
            }
            obj.add("items", items);
            return obj;
        }
        if (array instanceof char[] chars) {
            obj.addProperty(KEY_KTYPE, "char[]");
            obj.addProperty("value", new String(chars));
            return obj;
        }
        obj.addProperty(KEY_KTYPE, "array");
        obj.addProperty("componentType", array.getClass().getComponentType().getName());
        int len = Array.getLength(array);
        JsonArray items = new JsonArray(len);
        for (int i = 0; i < len; i++) {
            items.add(encodeInternal(Array.get(array, i), active));
        }
        obj.add("items", items);
        return obj;
    }

    private static JsonObject encodeCustomObject(Object value, IdentityHashMap<Object, Boolean> active) {
        JsonObject obj = new JsonObject();
        obj.addProperty(KEY_VJAVA_CLASS, value.getClass().getName());
        JsonObject fieldsObj = new JsonObject();

        for (Field field : collectSerializableFields(value.getClass())) {
            field.trySetAccessible();
            Object fieldVal;
            try {
                fieldVal = field.get(value);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(
                        "Cannot access field '" + field.getName() + "' on " + value.getClass().getName(), e
                );
            }
            fieldsObj.add(field.getName(), encodeInternal(fieldVal, active));
        }
        obj.add(KEY_FIELDS, fieldsObj);
        return obj;
    }

    // ---- Decoding Internals ----

    private static Object decodePrimitive(JsonPrimitive prim) {
        if (prim.isBoolean()) {
            return prim.getAsBoolean();
        }
        if (prim.isString()) {
            return prim.getAsString();
        }
        if (prim.isNumber()) {
            String raw = prim.getAsString();
            if (raw.indexOf('.') >= 0 || raw.indexOf('e') >= 0 || raw.indexOf('E') >= 0) {
                return Double.parseDouble(raw);
            }
            try {
                long parsed = Long.parseLong(raw);
                if (parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE) {
                    return (int) parsed;
                }
                return parsed;
            } catch (NumberFormatException ignored) {
                return prim.getAsDouble();
            }
        }
        return null;
    }

    private static Map<String, Object> decodePlainMap(JsonObject obj, ClassLoader userLoader) {
        Map<String, Object> map = new LinkedHashMap<>(Math.max(16, obj.size() * 2));
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            map.put(entry.getKey(), decode(entry.getValue(), userLoader));
        }
        return map;
    }

    private static Object decodeTaggedObject(JsonObject obj, ClassLoader userLoader) {
        String type = obj.get(KEY_KTYPE).getAsString();
        return switch (type) {
            case "double" -> Double.parseDouble(obj.get("value").getAsString());
            case "long" -> Long.parseLong(obj.get("value").getAsString());
            case "float" -> Float.parseFloat(obj.get("value").getAsString());
            case "short" -> obj.get("value").getAsShort();
            case "byte" -> obj.get("value").getAsByte();
            case "char" -> {
                String s = obj.get("value").getAsString();
                yield s.isEmpty() ? '\0' : s.charAt(0);
            }
            case "bigint" -> new BigInteger(obj.get("value").getAsString());
            case "bigdec" -> new BigDecimal(obj.get("value").getAsString());
            case "instant" -> Instant.parse(obj.get("value").getAsString());
            case "uuid" -> UUID.fromString(obj.get("value").getAsString());
            case "enum" -> decodeEnum(obj.get("class").getAsString(), obj.get("name").getAsString(), userLoader);
            case "transform2d" -> decodeTransform2D(obj.getAsJsonObject("value"));
            case "colormatrix4f" -> ColorMatrix4f.ofColumnMajor(decodeFloatArray(obj.getAsJsonArray("value")));
            case "rect2f" -> new Rect2f(
                    obj.get("x").getAsFloat(),
                    obj.get("y").getAsFloat(),
                    obj.get("width").getAsFloat(),
                    obj.get("height").getAsFloat()
            );
            case "rlist" -> {
                RevertableList<Object> list = new RevertableList<>();
                for (JsonElement item : obj.getAsJsonArray("items")) {
                    list.add(decode(item, userLoader));
                }
                yield list;
            }
            case "set" -> {
                Set<Object> set = new LinkedHashSet<>();
                for (JsonElement item : obj.getAsJsonArray("items")) {
                    set.add(decode(item, userLoader));
                }
                yield set;
            }
            case "rmap" -> {
                RevertableMap<Object, Object> rmap = new RevertableMap<>();
                decodePairsInto(obj.getAsJsonArray("pairs"), rmap, userLoader);
                yield rmap;
            }
            case "map" -> {
                Map<Object, Object> map = new LinkedHashMap<>();
                decodePairsInto(obj.getAsJsonArray("pairs"), map, userLoader);
                yield map;
            }
            case "byte[]" -> Base64.getDecoder().decode(obj.get("base64").getAsString());
            case "int[]" -> {
                JsonArray arr = obj.getAsJsonArray("items");
                int[] out = new int[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    out[i] = arr.get(i).getAsInt();
                }
                yield out;
            }
            case "long[]" -> {
                JsonArray arr = obj.getAsJsonArray("items");
                long[] out = new long[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    out[i] = Long.parseLong(arr.get(i).getAsString());
                }
                yield out;
            }
            case "float[]" -> {
                JsonArray arr = obj.getAsJsonArray("items");
                float[] out = new float[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    out[i] = Float.parseFloat(arr.get(i).getAsString());
                }
                yield out;
            }
            case "double[]" -> {
                JsonArray arr = obj.getAsJsonArray("items");
                double[] out = new double[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    out[i] = Double.parseDouble(arr.get(i).getAsString());
                }
                yield out;
            }
            case "boolean[]" -> {
                JsonArray arr = obj.getAsJsonArray("items");
                boolean[] out = new boolean[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    out[i] = arr.get(i).getAsBoolean();
                }
                yield out;
            }
            case "short[]" -> {
                JsonArray arr = obj.getAsJsonArray("items");
                short[] out = new short[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    out[i] = arr.get(i).getAsShort();
                }
                yield out;
            }
            case "char[]" -> obj.get("value").getAsString().toCharArray();
            case "array" -> {
                String compName = obj.get("componentType").getAsString();
                Class<?> compType = resolveArrayComponentType(compName, userLoader);
                JsonArray items = obj.getAsJsonArray("items");
                Object out = Array.newInstance(compType, items.size());
                for (int i = 0; i < items.size(); i++) {
                    Object decoded = decode(items.get(i), userLoader);
                    Array.set(out, i, coerceValue(decoded, compType));
                }
                yield out;
            }
            default -> throw new IllegalStateException("Unsupported encoded __ktype: " + type);
        };
    }

    private static void decodePairsInto(JsonArray pairs, Map<Object, Object> target, ClassLoader userLoader) {
        for (JsonElement pairElem : pairs) {
            JsonArray pair = pairElem.getAsJsonArray();
            Object k = decode(pair.get(0), userLoader);
            Object v = decode(pair.get(1), userLoader);
            target.put(k, v);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Enum<?> decodeEnum(String className, String enumName, ClassLoader userLoader) {
        Class<?> clazz = resolveClass(className, userLoader);
        if (!clazz.isEnum()) {
            throw new IllegalStateException("Class is not an enum: " + className);
        }
        return Enum.valueOf((Class<? extends Enum>) clazz, enumName);
    }

    private static Object decodeCustomObject(JsonObject obj, ClassLoader userLoader) {
        String className = obj.get(KEY_VJAVA_CLASS).getAsString();
        Class<?> clazz = resolveClass(className, userLoader);

        Map<String, Object> decodedFields = new LinkedHashMap<>();
        if (obj.has(KEY_FIELDS) && obj.get(KEY_FIELDS).isJsonObject()) {
            JsonObject fieldsObj = obj.getAsJsonObject(KEY_FIELDS);
            for (Map.Entry<String, JsonElement> entry : fieldsObj.entrySet()) {
                decodedFields.put(entry.getKey(), decode(entry.getValue(), userLoader));
            }
        }

        if (clazz.isRecord()) {
            return instantiateRecord(clazz, decodedFields);
        }
        if (clazz.isEnum()) {
            Object nameVal = decodedFields.get("name");
            if (nameVal instanceof String enumName) {
                return decodeEnum(clazz.getName(), enumName, userLoader);
            }
        }

        List<Field> fields = collectSerializableFields(clazz);

        // 1. Prefer no-arg constructor if present
        try {
            Constructor<?> noArg = clazz.getDeclaredConstructor();
            noArg.trySetAccessible();
            Object instance = noArg.newInstance();
            assignFields(instance, fields, decodedFields, false);
            return instance;
        } catch (NoSuchMethodException ignored) {
            // Proceed to parameterized constructor matching
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate " + clazz.getName() + " via no-arg constructor", e);
        }

        // 2. Parameterized constructor matching (supports immutable VJavaUserTypes with public final fields)
        Constructor<?>[] ctors = clazz.getDeclaredConstructors();
        if (ctors.length == 0) {
            throw new IllegalStateException("No constructors declared on " + clazz.getName());
        }

        ConstructorCandidate best = null;
        for (Constructor<?> ctor : ctors) {
            ConstructorCandidate candidate = buildConstructorCandidate(ctor, fields, decodedFields);
            if (best == null || candidate.score > best.score) {
                best = candidate;
            }
        }

        try {
            best.constructor.trySetAccessible();
            Object instance = best.constructor.newInstance(best.args);
            assignFields(instance, fields, decodedFields, true);
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Failed to reconstruct custom object of type " + clazz.getName(), e
            );
        }
    }

    private static Object instantiateRecord(Class<?> recordClass, Map<String, Object> decodedFields) {
        RecordComponent[] components = recordClass.getRecordComponents();
        Class<?>[] paramTypes = new Class<?>[components.length];
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent rc = components[i];
            paramTypes[i] = rc.getType();
            args[i] = coerceValue(decodedFields.get(rc.getName()), rc.getType());
        }
        try {
            Constructor<?> canonical = recordClass.getDeclaredConstructor(paramTypes);
            canonical.trySetAccessible();
            return canonical.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate record " + recordClass.getName(), e);
        }
    }

    private record ConstructorCandidate(Constructor<?> constructor, Object[] args, int score) {
    }

    private static ConstructorCandidate buildConstructorCandidate(
            Constructor<?> ctor,
            List<Field> fields,
            Map<String, Object> decodedFields
    ) {
        Parameter[] params = ctor.getParameters();
        Object[] args = new Object[params.length];
        Set<String> claimedFieldNames = new LinkedHashSet<>();
        int matched = 0;
        int unmatched = 0;

        // First pass: match parameters that carry exact parameter names (when compiled with -parameters)
        boolean[] assigned = new boolean[params.length];
        for (int i = 0; i < params.length; i++) {
            Parameter p = params[i];
            if (p.isNamePresent() && decodedFields.containsKey(p.getName())) {
                Object val = decodedFields.get(p.getName());
                if (isValueCompatibleWithType(val, p.getType())) {
                    args[i] = coerceValue(val, p.getType());
                    claimedFieldNames.add(p.getName());
                    assigned[i] = true;
                    matched++;
                }
            }
        }

        // Second pass: match remaining parameters against unclaimed fields in superclass-to-subclass order
        for (int i = 0; i < params.length; i++) {
            if (assigned[i]) {
                continue;
            }
            Class<?> paramType = params[i].getType();
            Field matchedField = null;
            for (Field f : fields) {
                if (claimedFieldNames.contains(f.getName()) || !decodedFields.containsKey(f.getName())) {
                    continue;
                }
                Object val = decodedFields.get(f.getName());
                if (isFieldCompatibleWithParameter(f.getType(), val, paramType)) {
                    matchedField = f;
                    break;
                }
            }
            if (matchedField != null) {
                claimedFieldNames.add(matchedField.getName());
                args[i] = coerceValue(decodedFields.get(matchedField.getName()), paramType);
                matched++;
            } else {
                args[i] = defaultValueFor(paramType);
                unmatched++;
            }
        }

        int score = matched * 100 - unmatched * 25;
        if (params.length == fields.size() && unmatched == 0) {
            score += 50;
        }
        if (Modifier.isPublic(ctor.getModifiers())) {
            score += 5;
        }
        return new ConstructorCandidate(ctor, args, score);
    }

    private static void assignFields(
            Object instance,
            List<Field> fields,
            Map<String, Object> decodedFields,
            boolean skipIfAlreadyEqual
    ) throws IllegalAccessException {
        for (Field field : fields) {
            if (!decodedFields.containsKey(field.getName())) {
                continue;
            }
            field.trySetAccessible();
            Object targetVal = coerceValue(decodedFields.get(field.getName()), field.getType());
            if (skipIfAlreadyEqual) {
                Object existingVal = field.get(instance);
                if (RevertableMap.deepEquals(existingVal, targetVal)) {
                    continue;
                }
            }
            field.set(instance, targetVal);
        }
    }

    private static List<Field> collectSerializableFields(Class<?> clazz) {
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> cur = clazz;
                cur != null && cur != Object.class && cur != Record.class;
                cur = cur.getSuperclass()) {
            hierarchy.add(cur);
        }
        Collections.reverse(hierarchy);

        List<Field> fields = new ArrayList<>();
        for (Class<?> c : hierarchy) {
            for (Field f : c.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || f.isSynthetic()) {
                    continue;
                }
                fields.add(f);
            }
        }
        return fields;
    }

    private static boolean isAllowedCustomClassName(String className) {
        if (className == null || className.isBlank()) {
            return false;
        }
        String trimmed = className.trim();
        if (trimmed.startsWith(GENERATED_PACKAGE_PREFIX)
                || trimmed.startsWith("VJavaUserTypes$")
                || trimmed.startsWith(ENGINE_PACKAGE_PREFIX)) {
            return true;
        }
        return trimmed.indexOf('.') < 0 && trimmed.indexOf('$') < 0;
    }

    private static Class<?> resolveClass(String className, ClassLoader optionalUserClassLoader) {
        if (!isAllowedCustomClassName(className)) {
            throw new SecurityException("Disallowed __vjavaClass not on allowlist: '" + className + "'");
        }
        List<ClassLoader> loaders = new ArrayList<>();
        if (optionalUserClassLoader != null) {
            loaders.add(optionalUserClassLoader);
        }
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null && !loaders.contains(ctx)) {
            loaders.add(ctx);
        }
        ClassLoader self = SaveValueCodec.class.getClassLoader();
        if (self != null && !loaders.contains(self)) {
            loaders.add(self);
        }

        List<String> candidates = new ArrayList<>();
        candidates.add(className);
        if (!className.contains(".") && !className.contains("$")) {
            candidates.add(GENERATED_USER_TYPES_PREFIX + className);
            candidates.add(GENERATED_PACKAGE_PREFIX + className);
        }

        for (String candidate : candidates) {
            for (ClassLoader loader : loaders) {
                try {
                    return Class.forName(candidate, true, loader);
                } catch (ClassNotFoundException ignored) {
                    // Try next loader or candidate
                }
            }
        }
        throw new IllegalStateException("Cannot resolve saved class '" + className + "'");
    }

    private static Class<?> resolveArrayComponentType(String name, ClassLoader userLoader) {
        return switch (name) {
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "short" -> short.class;
            case "char" -> char.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            default -> {
                if (ALLOWED_ARRAY_COMPONENTS.contains(name)) {
                    try {
                        yield Class.forName(name, false, SaveValueCodec.class.getClassLoader());
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException("Cannot resolve component class '" + name + "'", e);
                    }
                }
                yield resolveClass(name, userLoader);
            }
        };
    }

    private static boolean isFieldCompatibleWithParameter(Class<?> fieldType, Object value, Class<?> paramType) {
        if (paramType.isAssignableFrom(fieldType) || boxedType(paramType).isAssignableFrom(boxedType(fieldType))) {
            return true;
        }
        return isValueCompatibleWithType(value, paramType);
    }

    private static boolean isValueCompatibleWithType(Object value, Class<?> targetType) {
        if (value == null) {
            return !targetType.isPrimitive();
        }
        if (targetType.isInstance(value) || boxedType(targetType).isInstance(value)) {
            return true;
        }
        if (value instanceof Number && isNumericType(targetType)) {
            return true;
        }
        if (value instanceof Collection<?>
                && (List.class.isAssignableFrom(targetType) || Set.class.isAssignableFrom(targetType))) {
            return true;
        }
        if (value instanceof Map<?, ?> && Map.class.isAssignableFrom(targetType)) {
            return true;
        }
        return (targetType == char.class || targetType == Character.class)
                && value instanceof String s && s.length() == 1;
    }

    private static Object coerceValue(Object value, Class<?> targetType) {
        if (value == null) {
            return targetType.isPrimitive() ? defaultValueFor(targetType) : null;
        }
        if (targetType.isInstance(value)) {
            return value;
        }
        if (value instanceof Number n) {
            if (targetType == int.class || targetType == Integer.class) {
                return n.intValue();
            }
            if (targetType == long.class || targetType == Long.class) {
                return n.longValue();
            }
            if (targetType == double.class || targetType == Double.class) {
                return n.doubleValue();
            }
            if (targetType == float.class || targetType == Float.class) {
                return n.floatValue();
            }
            if (targetType == short.class || targetType == Short.class) {
                return n.shortValue();
            }
            if (targetType == byte.class || targetType == Byte.class) {
                return n.byteValue();
            }
        }
        if ((targetType == boolean.class || targetType == Boolean.class) && value instanceof Boolean b) {
            return b;
        }
        if ((targetType == char.class || targetType == Character.class) && value instanceof String s && !s.isEmpty()) {
            return s.charAt(0);
        }
        if (value instanceof Collection<?> col) {
            if (targetType == RevertableList.class) {
                return new RevertableList<>(col);
            }
            if (Set.class.isAssignableFrom(targetType)) {
                return new LinkedHashSet<>(col);
            }
            if (List.class.isAssignableFrom(targetType)) {
                return new ArrayList<>(col);
            }
            if (targetType.isArray()) {
                Class<?> comp = targetType.getComponentType();
                Object arr = Array.newInstance(comp, col.size());
                int i = 0;
                for (Object item : col) {
                    Array.set(arr, i++, coerceValue(item, comp));
                }
                return arr;
            }
        }
        if (value instanceof Map<?, ?> map && targetType == RevertableMap.class) {
            return new RevertableMap<>(map);
        }
        return value;
    }

    private static Class<?> boxedType(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }

    private static boolean isNumericType(Class<?> type) {
        Class<?> b = boxedType(type);
        return b == Integer.class || b == Long.class || b == Double.class
                || b == Float.class || b == Short.class || b == Byte.class;
    }

    private static Object defaultValueFor(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0;
        }
        return null;
    }

    // ---- Transform2D / ColorMatrix4f JSON Helpers ----

    private static JsonObject encodeTransform2D(Transform2D t) {
        JsonObject obj = new JsonObject();
        obj.addProperty("xpos", t.getXpos());
        obj.addProperty("ypos", t.getYpos());
        obj.addProperty("xanchor", t.getXanchor());
        obj.addProperty("yanchor", t.getYanchor());
        if (t.hasXalign()) {
            obj.addProperty("xalign", t.getXalign());
        } else {
            obj.add("xalign", JsonNull.INSTANCE);
        }
        if (t.hasYalign()) {
            obj.addProperty("yalign", t.getYalign());
        } else {
            obj.add("yalign", JsonNull.INSTANCE);
        }
        obj.addProperty("zoom", t.getZoom());
        obj.addProperty("xzoom", t.getXzoom());
        obj.addProperty("yzoom", t.getYzoom());
        obj.addProperty("rotate", t.getRotate());
        obj.addProperty("alpha", t.getAlpha());
        obj.addProperty("blur", t.getBlur());
        obj.add("colorMatrix", encodeFloatArray(t.getColorMatrix().toFloatArray()));
        if (t.getCrop() != null) {
            Rect2f crop = t.getCrop();
            JsonObject cropObj = new JsonObject();
            cropObj.addProperty("x", crop.x());
            cropObj.addProperty("y", crop.y());
            cropObj.addProperty("width", crop.width());
            cropObj.addProperty("height", crop.height());
            obj.add("crop", cropObj);
        } else {
            obj.add("crop", JsonNull.INSTANCE);
        }
        obj.addProperty("parallaxX", t.getParallaxX());
        obj.addProperty("parallaxY", t.getParallaxY());
        return obj;
    }

    private static Transform2D decodeTransform2D(JsonObject obj) {
        Transform2D t = new Transform2D();
        if (obj == null) {
            return t;
        }
        if (obj.has("xpos")) {
            t.setXpos(obj.get("xpos").getAsFloat());
        }
        if (obj.has("ypos")) {
            t.setYpos(obj.get("ypos").getAsFloat());
        }
        if (obj.has("xanchor")) {
            t.setXanchor(obj.get("xanchor").getAsFloat());
        }
        if (obj.has("yanchor")) {
            t.setYanchor(obj.get("yanchor").getAsFloat());
        }
        if (obj.has("xalign") && !obj.get("xalign").isJsonNull()) {
            t.setXalign(obj.get("xalign").getAsFloat());
        } else {
            t.setXalign(Float.NaN);
        }
        if (obj.has("yalign") && !obj.get("yalign").isJsonNull()) {
            t.setYalign(obj.get("yalign").getAsFloat());
        } else {
            t.setYalign(Float.NaN);
        }
        if (obj.has("zoom")) {
            t.setZoom(obj.get("zoom").getAsFloat());
        }
        if (obj.has("xzoom")) {
            t.setXzoom(obj.get("xzoom").getAsFloat());
        }
        if (obj.has("yzoom")) {
            t.setYzoom(obj.get("yzoom").getAsFloat());
        }
        if (obj.has("rotate")) {
            t.setRotate(obj.get("rotate").getAsFloat());
        }
        if (obj.has("alpha")) {
            t.setAlpha(obj.get("alpha").getAsFloat());
        }
        if (obj.has("blur")) {
            t.setBlur(obj.get("blur").getAsFloat());
        }
        if (obj.has("colorMatrix") && obj.get("colorMatrix").isJsonArray()) {
            t.setColorMatrix(ColorMatrix4f.ofColumnMajor(decodeFloatArray(obj.getAsJsonArray("colorMatrix"))));
        }
        if (obj.has("crop") && obj.get("crop").isJsonObject()) {
            JsonObject c = obj.getAsJsonObject("crop");
            t.setCrop(new Rect2f(
                    c.get("x").getAsFloat(),
                    c.get("y").getAsFloat(),
                    c.get("width").getAsFloat(),
                    c.get("height").getAsFloat()
            ));
        }
        if (obj.has("parallaxX")) {
            t.setParallaxX(obj.get("parallaxX").getAsFloat());
        }
        if (obj.has("parallaxY")) {
            t.setParallaxY(obj.get("parallaxY").getAsFloat());
        }
        return t;
    }

    private static JsonArray encodeFloatArray(float[] values) {
        JsonArray arr = new JsonArray(values.length);
        for (float v : values) {
            arr.add(v);
        }
        return arr;
    }

    private static float[] decodeFloatArray(JsonArray arr) {
        float[] out = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            out[i] = arr.get(i).getAsFloat();
        }
        return out;
    }

    private static final class InstantTypeAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return Instant.parse(in.nextString());
        }
    }

    private static final class ByteArrayTypeAdapter extends TypeAdapter<byte[]> {
        @Override
        public void write(JsonWriter out, byte[] value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(Base64.getEncoder().encodeToString(value));
            }
        }

        @Override
        public byte[] read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return Base64.getDecoder().decode(in.nextString());
        }
    }

    private static final class ColorMatrixTypeAdapter extends TypeAdapter<ColorMatrix4f> {
        @Override
        public void write(JsonWriter out, ColorMatrix4f value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }
            out.beginArray();
            for (float f : value.toFloatArray()) {
                out.value(f);
            }
            out.endArray();
        }

        @Override
        public ColorMatrix4f read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            float[] m = new float[16];
            int idx = 0;
            in.beginArray();
            while (in.hasNext()) {
                float val = (float) in.nextDouble();
                if (idx < 16) {
                    m[idx++] = val;
                }
            }
            in.endArray();
            return idx == 16 ? ColorMatrix4f.ofColumnMajor(m) : ColorMatrix4f.identity();
        }
    }

    private static final class Transform2DTypeAdapter extends TypeAdapter<Transform2D> {
        @Override
        public void write(JsonWriter out, Transform2D value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }
            out.beginObject();
            out.name("xpos").value(value.getXpos());
            out.name("ypos").value(value.getYpos());
            out.name("xanchor").value(value.getXanchor());
            out.name("yanchor").value(value.getYanchor());
            out.name("xalign");
            if (value.hasXalign()) {
                out.value(value.getXalign());
            } else {
                out.nullValue();
            }
            out.name("yalign");
            if (value.hasYalign()) {
                out.value(value.getYalign());
            } else {
                out.nullValue();
            }
            out.name("zoom").value(value.getZoom());
            out.name("xzoom").value(value.getXzoom());
            out.name("yzoom").value(value.getYzoom());
            out.name("rotate").value(value.getRotate());
            out.name("alpha").value(value.getAlpha());
            out.name("blur").value(value.getBlur());
            out.name("colorMatrix");
            out.beginArray();
            for (float f : value.getColorMatrix().toFloatArray()) {
                out.value(f);
            }
            out.endArray();
            out.name("crop");
            if (value.getCrop() != null) {
                Rect2f crop = value.getCrop();
                out.beginObject();
                out.name("x").value(crop.x());
                out.name("y").value(crop.y());
                out.name("width").value(crop.width());
                out.name("height").value(crop.height());
                out.endObject();
            } else {
                out.nullValue();
            }
            out.name("parallaxX").value(value.getParallaxX());
            out.name("parallaxY").value(value.getParallaxY());
            out.endObject();
        }

        @Override
        public Transform2D read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            Transform2D t = new Transform2D();
            in.beginObject();
            while (in.hasNext()) {
                String name = in.nextName();
                switch (name) {
                    case "xpos" -> t.setXpos((float) in.nextDouble());
                    case "ypos" -> t.setYpos((float) in.nextDouble());
                    case "xanchor" -> t.setXanchor((float) in.nextDouble());
                    case "yanchor" -> t.setYanchor((float) in.nextDouble());
                    case "xalign" -> {
                        if (in.peek() == JsonToken.NULL) {
                            in.nextNull();
                            t.setXalign(Float.NaN);
                        } else {
                            t.setXalign((float) in.nextDouble());
                        }
                    }
                    case "yalign" -> {
                        if (in.peek() == JsonToken.NULL) {
                            in.nextNull();
                            t.setYalign(Float.NaN);
                        } else {
                            t.setYalign((float) in.nextDouble());
                        }
                    }
                    case "zoom" -> t.setZoom((float) in.nextDouble());
                    case "xzoom" -> t.setXzoom((float) in.nextDouble());
                    case "yzoom" -> t.setYzoom((float) in.nextDouble());
                    case "rotate" -> t.setRotate((float) in.nextDouble());
                    case "alpha" -> t.setAlpha((float) in.nextDouble());
                    case "blur" -> t.setBlur((float) in.nextDouble());
                    case "colorMatrix" -> {
                        if (in.peek() == JsonToken.NULL) {
                            in.nextNull();
                        } else {
                            float[] m = new float[16];
                            int idx = 0;
                            in.beginArray();
                            while (in.hasNext()) {
                                float val = (float) in.nextDouble();
                                if (idx < 16) {
                                    m[idx++] = val;
                                }
                            }
                            in.endArray();
                            if (idx == 16) {
                                t.setColorMatrix(ColorMatrix4f.ofColumnMajor(m));
                            }
                        }
                    }
                    case "crop" -> {
                        if (in.peek() == JsonToken.NULL) {
                            in.nextNull();
                            t.setCrop(null);
                        } else {
                            float x = 0;
                            float y = 0;
                            float w = 0;
                            float h = 0;
                            in.beginObject();
                            while (in.hasNext()) {
                                switch (in.nextName()) {
                                    case "x" -> x = (float) in.nextDouble();
                                    case "y" -> y = (float) in.nextDouble();
                                    case "width" -> w = (float) in.nextDouble();
                                    case "height" -> h = (float) in.nextDouble();
                                    default -> in.skipValue();
                                }
                            }
                            in.endObject();
                            t.setCrop(new Rect2f(x, y, w, h));
                        }
                    }
                    case "parallaxX" -> t.setParallaxX((float) in.nextDouble());
                    case "parallaxY" -> t.setParallaxY((float) in.nextDouble());
                    default -> in.skipValue();
                }
            }
            in.endObject();
            return t;
        }
    }
}
