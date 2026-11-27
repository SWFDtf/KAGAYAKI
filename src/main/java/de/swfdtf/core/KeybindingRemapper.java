package de.swfdtf.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.lwjgl.glfw.GLFW;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
    private final Map<String, Set<Integer>> alternateKeys = new LinkedHashMap<>();
    private final Map<String, Set<Integer>> mouseBindings = new LinkedHashMap<>();
    private final Map<String, String> actionContexts = new LinkedHashMap<>();

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
        String validAction = requireValidAction(action);
        Integer primary = activeBindings.get(validAction);
        if (primary != null && input.isKeyDown(primary)) {
            return true;
        }
        Set<Integer> alts = alternateKeys.get(validAction);
        if (alts != null) {
            for (int key : alts) {
                if (input.isKeyDown(key)) {
                    return true;
                }
            }
        }
        Set<Integer> buttons = mouseBindings.get(validAction);
        if (buttons != null) {
            for (int btn : buttons) {
                if (input.isMouseButtonDown(btn)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns whether primary, alternate keys, or mouse buttons bound to {@code action}
     * were pressed this frame ({@code #0626}, {@code #0645}).
     *
     * @param action logical action name
     * @param input  input manager to query
     * @return {@code true} if just pressed on any bound input source
     */
    public boolean isActionJustPressed(String action, InputManager input) {
        Objects.requireNonNull(input, "input must not be null");
        String validAction = requireValidAction(action);
        Integer primary = activeBindings.get(validAction);
        if (primary != null && input.wasKeyJustPressed(primary)) {
            return true;
        }
        Set<Integer> alts = alternateKeys.get(validAction);
        if (alts != null) {
            for (int key : alts) {
                if (input.wasKeyJustPressed(key)) {
                    return true;
                }
            }
        }
        Set<Integer> buttons = mouseBindings.get(validAction);
        if (buttons != null) {
            for (int btn : buttons) {
                if (input.wasMouseButtonJustPressed(btn)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Adds secondary or tertiary keyboard key bindings for {@code action} ({@code #0626}).
     *
     * @param action  registered action name
     * @param keyCode GLFW key code
     */
    public void addAlternateKeyBinding(String action, int keyCode) {
        String validAction = requireRegisteredAction(action);
        requireValidKeyCode(keyCode);
        alternateKeys.computeIfAbsent(validAction, k -> new LinkedHashSet<>()).add(keyCode);
    }

    /**
     * Adds a mouse button binding (including side buttons {@code BUTTON_4}..{@code BUTTON_8}) ({@code #0645}).
     *
     * @param action      registered action name
     * @param mouseButton GLFW mouse button index in {@code [0, InputManager.MOUSE_BUTTON_COUNT)}
     */
    public void addMouseBinding(String action, int mouseButton) {
        String validAction = requireRegisteredAction(action);
        if (mouseButton < 0 || mouseButton >= InputManager.MOUSE_BUTTON_COUNT) {
            throw new IllegalArgumentException("Invalid mouseButton: " + mouseButton);
        }
        mouseBindings.computeIfAbsent(validAction, k -> new LinkedHashSet<>()).add(mouseButton);
    }

    /**
     * Assigns an input context (e.g., {@code "UI"}, {@code "GAMEPLAY"}, {@code "ARENA"}) so actions in
     * distinct contexts can share keys cleanly ({@code #0670}).
     *
     * @param action  action name
     * @param context context identifier
     */
    public void setActionContext(String action, String context) {
        String validAction = requireRegisteredAction(action);
        actionContexts.put(validAction, context != null && !context.isBlank()
                ? context.trim().toUpperCase(Locale.ROOT) : "GLOBAL");
    }

    /**
     * Checks whether assigning {@code keyCode} to {@code action} conflicts with another action inside the
     * SAME input context ({@code #0670}).
     *
     * @param action  candidate action name
     * @param keyCode candidate GLFW key code
     * @return conflicting action name inside the same context, or {@link Optional#empty()}
     */
    public Optional<String> findContextConflict(String action, int keyCode) {
        String validAction = requireRegisteredAction(action);
        requireValidKeyCode(keyCode);
        String targetCtx = actionContexts.getOrDefault(validAction, "GLOBAL");
        for (Map.Entry<String, Integer> entry : activeBindings.entrySet()) {
            String other = entry.getKey();
            if (!other.equals(validAction) && entry.getValue() == keyCode) {
                String otherCtx = actionContexts.getOrDefault(other, "GLOBAL");
                if (targetCtx.equals(otherCtx)) {
                    return Optional.of(other);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Resets only {@code action} back to its default key binding while preserving all other actions ({@code #0643}).
     *
     * @param action registered action name
     */
    public void resetAction(String action) {
        String validAction = requireRegisteredAction(action);
        activeBindings.put(validAction, defaults.get(validAction));
        alternateKeys.remove(validAction);
        mouseBindings.remove(validAction);
    }

    /**
     * Applies project-defined default keybindings from {@code project.vjava.json} without overwriting any
     * player-customized bindings already modified from defaults ({@code #0637}).
     *
     * @param projectDefaults map of action name to GLFW key code
     */
    public void applyProjectDefaults(Map<String, Integer> projectDefaults) {
        Objects.requireNonNull(projectDefaults, "projectDefaults must not be null");
        for (Map.Entry<String, Integer> entry : projectDefaults.entrySet()) {
            String validAction = requireValidAction(entry.getKey());
            int keyCode = entry.getValue();
            requireValidKeyCode(keyCode);
            boolean customized = activeBindings.containsKey(validAction)
                    && defaults.containsKey(validAction)
                    && !Objects.equals(activeBindings.get(validAction), defaults.get(validAction));
            defaults.put(validAction, keyCode);
            if (!customized) {
                activeBindings.put(validAction, keyCode);
            }
        }
    }

    /**
     * Returns localized display name for common GLFW key caps ({@code DE} vs {@code EN}) ({@code #0653}).
     *
     * @param keyCode  GLFW key constant
     * @param language locale name (e.g. {@code "deutsch"}, {@code "english"})
     * @return human-readable localized key label
     */
    public static String localizeKeyName(int keyCode, String language) {
        boolean de = language != null && language.toLowerCase(Locale.ROOT).startsWith("de");
        return switch (keyCode) {
            case GLFW.GLFW_KEY_SPACE -> de ? "Leertaste" : "Space";
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> de ? "Eingabe" : "Enter";
            case GLFW.GLFW_KEY_DELETE -> de ? "Entf" : "Del";
            case GLFW.GLFW_KEY_ESCAPE -> "Esc";
            case GLFW.GLFW_KEY_BACKSPACE -> de ? "Rücktaste" : "Backspace";
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> de ? "Umschalt" : "Shift";
            case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> de ? "Strg" : "Ctrl";
            default -> keyCode >= GLFW.GLFW_KEY_A && keyCode <= GLFW.GLFW_KEY_Z
                    ? String.valueOf((char) ('A' + (keyCode - GLFW.GLFW_KEY_A)))
                    : "Key " + keyCode;
        };
    }

    /**
     * Restores all registered actions to their default key codes.
     */
    public void resetToDefaults() {
        activeBindings.clear();
        activeBindings.putAll(defaults);
        alternateKeys.clear();
        mouseBindings.clear();
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
