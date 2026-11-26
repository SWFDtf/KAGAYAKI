package de.swfdtf.parser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exports a compiled {@link VJavaAst.ProjectAst} to a deterministic structured JSON document
 * for external story editors, translation pipelines, and CI dashboards.
 */
public final class AstJsonSerializer {

    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private AstJsonSerializer() {
    }

    /**
     * Serializes {@code project} including project metadata, config entries, characters, images,
     * transforms, labels, statements, choices, screens, and translations to JSON.
     *
     * @param project unified project AST (must not be {@code null})
     * @return formatted JSON representation
     */
    public static String toJson(VJavaAst.ProjectAst project) {
        Objects.requireNonNull(project, "project must not be null");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("descriptor", serializeValue(project.descriptor()));
        root.put("config", project.configValues());
        root.put("includes", project.includes());
        root.put("characters", serializeMapValues(project.characters()));
        root.put("images", serializeMapValues(project.images()));
        root.put("layeredImages", serializeMapValues(project.layeredImages()));
        root.put("transforms", serializeMapValues(project.transforms()));
        root.put("labels", serializeMapValues(project.labels()));
        root.put("screens", serializeMapValues(project.screens()));
        root.put("translations", serializeValue(project.translations()));
        root.put("defaultVariables", serializeValue(project.defaultVariables()));
        root.put("discoveredAssets", project.discoveredAssets());
        root.put("warnings", project.warnings());
        return PRETTY_GSON.toJson(root);
    }

    private static Map<String, Object> serializeMapValues(Map<String, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>(map.size());
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            out.put(entry.getKey(), serializeValue(entry.getValue()));
        }
        return out;
    }

    private static Object serializeValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object elem : list) {
                out.add(serializeValue(elem));
            }
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), serializeValue(entry.getValue()));
            }
            return out;
        }
        if (value.getClass().isRecord()) {
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("type", value.getClass().getSimpleName());
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                try {
                    Object compVal = component.getAccessor().invoke(value);
                    obj.put(component.getName(), serializeValue(compVal));
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(
                            "Failed serializing record component " + component.getName(), ex);
                }
            }
            return obj;
        }
        return String.valueOf(value);
    }
}
