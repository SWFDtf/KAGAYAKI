package de.swfdtf.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Rebindable registry mapping logical action names to GLFW key codes with conflict detection,
 * default restoration, {@link InputManager} state queries, and Gson JSON serialization.
 */
public final class KeybindingRemapper {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private final Map<String, Integer> defaults = new LinkedHashMap<>();
    private final Map<String, Integer> activeBindings = new LinkedHashMap<>();

    /** Creates a new empty {@code KeybindingRemapper}. */
    public KeybindingRemapper() {
    }

    /**
     * Registers a default keybinding for a logical action and binds it if not yet active.
     *
     * @param action  logical action name (must not be null or blank)
     * @param keyCode GLFW key code in {@code [0, InputManager.KEY_COUNT)}
     * @throws IllegalStateException    if another action already defaults to or uses {@code keyCode}
     * @throws IllegalArgumentException if {@code action} is blank or {@code keyCode} is out of range
     */
    public void registerDefault(String action, int keyCode) {
        String validAction = requireValidAction(action);
        requireValidKeyCode(keyCode);

        for (Map.Entry<String, Integer> entry : defaults.entrySet()) {
            if (!entry.getKey().equals(validAction) && entry.getValue() == keyCode) {
                throw new IllegalStateException(
                        "Default key " + keyCode + " is already registered to action '" + entry.getKey() + "'"
                );
            }
        }

        boolean hadActiveBinding = activeBindings.containsKey(validAction);
        if (!hadActiveBinding) {
            Optional<String> activeConflict = findActionForKey(keyCode);
            if (activeConflict.isPresent() && !activeConflict.get().equals(validAction)) {
                throw new IllegalStateException(
                        "Key " + keyCode + " is already actively bound to action '" + activeConflict.get() + "'"
                );
            }
            activeBindings.put(validAction, keyCode);
        }
        defaults.put(validAction, keyCode);
    }

    /**
     * Binds {@code action} to {@code keyCode}, throwing if another action is already bound to {@code keyCode}.
     *
     * @param action  registered logical action name
     * @param keyCode target GLFW key code in {@code [0, InputManager.KEY_COUNT)}
     * @throws IllegalArgumentException if {@code action} is not registered or {@code keyCode} is out of range
     * @throws IllegalStateException    if another action is already bound to {@code keyCode}
     */
    public void bind(String action, int keyCode) {
        String validAction = requireRegisteredAction(action);
        requireValidKeyCode(keyCode);

        Optional<String> conflict = conflictFor(validAction, keyCode);
        if (conflict.isPresent()) {
            throw new IllegalStateException(
                    "Key " + keyCode + " is already bound to action '" + conflict.get() + "'"
            );
        }
        activeBindings.put(validAction, keyCode);
    }

    /**
     * Rebinds {@code action} to {@code newKeyCode}, throwing if another action is already bound to {@code newKeyCode}.
     *
     * @param action     registered logical action name
     * @param newKeyCode target GLFW key code in {@code [0, InputManager.KEY_COUNT)}
     * @throws IllegalArgumentException if {@code action} is not registered or {@code newKeyCode} is out of range
     * @throws IllegalStateException    if another action is already bound to {@code newKeyCode}
     */
    public void rebind(String action, int newKeyCode) {
        bind(action, newKeyCode);
    }

    /**
     * Rebinds {@code action} to {@code newKeyCode}, swapping key codes if another action currently
     * occupies {@code newKeyCode}.
     *
     * @param action     registered logical action name
     * @param newKeyCode target GLFW key code in {@code [0, InputManager.KEY_COUNT)}
     * @return optional name of the conflicting action whose key was swapped, or {@link Optional#empty()} if uncontested
     * @throws IllegalArgumentException if {@code action} is not registered or {@code newKeyCode} is out of range
     */
    public Optional<String> rebindAllowingSwap(String action, int newKeyCode) {
        String validAction = requireRegisteredAction(action);
        requireValidKeyCode(newKeyCode);

        Integer previousKey = activeBindings.get(validAction);
        Optional<String> conflict = conflictFor(validAction, newKeyCode);
        if (conflict.isPresent()) {
            String otherAction = conflict.get();
            activeBindings.put(otherAction, previousKey);
            activeBindings.put(validAction, newKeyCode);
            return Optional.of(otherAction);
        }

        activeBindings.put(validAction, newKeyCode);
        return Optional.empty();
    }

    /**
     * Checks whether binding {@code action} to {@code keyCode} would conflict with a different action.
     *
     * @param action  registered logical action name
     * @param keyCode candidate GLFW key code
     * @return optional conflicting action name, or {@link Optional#empty()} if no other action holds {@code keyCode}
     */
    public Optional<String> conflictFor(String action, int keyCode) {
        String validAction = requireRegisteredAction(action);
        requireValidKeyCode(keyCode);

        for (Map.Entry<String, Integer> entry : activeBindings.entrySet()) {
            if (!entry.getKey().equals(validAction) && entry.getValue() == keyCode) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /**
     * Finds the logical action currently bound to {@code keyCode}.
     *
     * @param keyCode GLFW key code
     * @return optional action name bound to {@code keyCode}
     */
    public Optional<String> findActionForKey(int keyCode) {
        if (keyCode < 0 || keyCode >= InputManager.KEY_COUNT) {
            return Optional.empty();
        }
        for (Map.Entry<String, Integer> entry : activeBindings.entrySet()) {
            if (entry.getValue() == keyCode) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the currently bound GLFW key code for {@code action}.
     *
     * @param action logical action name (must not be null or blank)
     * @return optional GLFW key code
     */
    public Optional<Integer> getKeyCode(String action) {
        String validAction = requireValidAction(action);
        return Optional.ofNullable(activeBindings.get(validAction));
    }

    /**
     * Returns whether the key currently bound to {@code action} is held down in {@code input}.
     *
     * @param action logical action name (must not be null or blank)
     * @param input  input manager to query (must not be null)
     * @return {@code true} if {@code action} is bound and its key is currently down
     */
    public boolean isActionDown(String action, InputManager input) {
        Objects.requireNonNull(input, "input must not be null");
        return getKeyCode(action)
                .map(input::isKeyDown)
                .orElse(false);
    }

    /**
     * Restores all registered actions to their default key codes.
     */
    public void resetToDefaults() {
        activeBindings.clear();
        activeBindings.putAll(defaults);
    }

    /**
     * Returns an unmodifiable snapshot of the active action-to-keycode bindings.
     *
     * @return unmodifiable map of action names to GLFW key codes
     */
    public Map<String, Integer> snapshotBindings() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(activeBindings));
    }

    /**
     * Serializes all active keybindings to a formatted JSON object string.
     *
     * @return JSON representation of active keybindings
     */
    public String toJson() {
        return GSON.toJson(activeBindings);
    }

    /**
     * Deserializes keybindings from a JSON object string, starting from registered defaults
     * and validating all action names, key code ranges, and uniqueness constraints atomically.
     *
     * @param json JSON object mapping action names to integer GLFW key codes (must not be null)
     * @throws IllegalArgumentException if JSON is malformed, not an object, or contains unknown actions or invalid keys
     * @throws IllegalStateException    if two actions resolve to the same key code
     */
    public void loadJson(String json) {
        Objects.requireNonNull(json, "json must not be null");
        if (json.isBlank()) {
            throw new IllegalArgumentException("json must not be blank");
        }

        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Malformed keybindings JSON", ex);
        }
        if (root == null || !root.isJsonObject()) {
            throw new IllegalArgumentException("Keybindings JSON must be a JSON object");
        }

        JsonObject jsonObject = root.getAsJsonObject();
        Map<String, Integer> candidate = new LinkedHashMap<>(defaults);

        for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
            String action = requireRegisteredAction(entry.getKey());
            int keyCode = parseKeyCode(action, entry.getValue());
            candidate.put(action, keyCode);
        }

        Map<Integer, String> seenKeys = new HashMap<>(candidate.size());
        for (Map.Entry<String, Integer> entry : candidate.entrySet()) {
            String existingAction = seenKeys.putIfAbsent(entry.getValue(), entry.getKey());
            if (existingAction != null) {
                throw new IllegalStateException(
                        "Key " + entry.getValue() + " is assigned to multiple actions: '"
                                + existingAction + "' and '" + entry.getKey() + "'"
                );
            }
        }

        activeBindings.clear();
        activeBindings.putAll(candidate);
    }

    private static int parseKeyCode(String action, JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException("Key code for action '" + action + "' must be an integer");
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            throw new IllegalArgumentException("Key code for action '" + action + "' must be an integer");
        }
        double numeric = primitive.getAsDouble();
        if (!Double.isFinite(numeric) || numeric != Math.rint(numeric)
                || numeric < Integer.MIN_VALUE || numeric > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Key code for action '" + action + "' must be an exact integer");
        }
        int keyCode = (int) numeric;
        requireValidKeyCode(keyCode);
        return keyCode;
    }

    private String requireRegisteredAction(String action) {
        String validAction = requireValidAction(action);
        if (!defaults.containsKey(validAction)) {
            throw new IllegalArgumentException("Unknown keybinding action: " + validAction);
        }
        return validAction;
    }

    private static String requireValidAction(String action) {
        Objects.requireNonNull(action, "action must not be null");
        String trimmed = action.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("action must not be blank");
        }
        return trimmed;
    }

    private static void requireValidKeyCode(int keyCode) {
        if (keyCode < 0 || keyCode >= InputManager.KEY_COUNT) {
            throw new IllegalArgumentException(
                    "keyCode must be in [0, " + InputManager.KEY_COUNT + "), got: " + keyCode
            );
        }
    }
}
