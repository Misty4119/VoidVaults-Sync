package com.voidvault.model;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable record representing GUI button configuration.
 * Uses Java 21 record syntax for concise configuration data.
 *
 * <p>The display name and lore fields are stored as raw MiniMessage markup.
 * Modern tags like {@code <gradient:#9CC3FF:#D9B3FF>...</gradient>} and
 * {@code <bold>} are supported directly. Legacy {@code &c}-style colour codes
 * are still translated automatically so older configs keep working.</p>
 *
 * @param material    The material type for the button
 * @param displayName The display name of the button (MiniMessage markup)
 * @param lore        The lore lines for the button (MiniMessage markup)
 * @param glow        Whether the button should have a glow effect
 */
public record ButtonConfig(
        Material material,
        String displayName,
        List<String> lore,
        boolean glow
) {
    /** Shared MiniMessage instance used for every GUI button. */
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    /** Legacy colour-code serializer kept for any string fallback paths. */
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacySection();

    /**
     * Compact constructor with validation and defensive copying.
     */
    public ButtonConfig {
        if (material == null) {
            throw new IllegalArgumentException("Material cannot be null");
        }
        if (displayName == null) {
            displayName = "";
        }
        if (lore == null) {
            lore = List.of();
        } else {
            // Create an immutable copy of the lore list
            lore = List.copyOf(lore);
        }
    }

    /**
     * Creates a simple ButtonConfig with just material and display name.
     *
     * @param material    The material type
     * @param displayName The display name
     * @return A new ButtonConfig instance
     */
    public static ButtonConfig simple(Material material, String displayName) {
        return new ButtonConfig(material, displayName, List.of(), false);
    }

    /**
     * Creates a ButtonConfig with material, display name, and glow.
     *
     * @param material    The material type
     * @param displayName The display name
     * @param glow        Whether to add glow effect
     * @return A new ButtonConfig instance
     */
    public static ButtonConfig withGlow(Material material, String displayName, boolean glow) {
        return new ButtonConfig(material, displayName, List.of(), glow);
    }

    /**
     * Converts this ButtonConfig to an ItemStack.
     * Applies MiniMessage markup (with legacy &-code fallback), lore, and glow.
     *
     * @return A new ItemStack representing this button
     */
    public ItemStack toItemStack() {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            // Apply display name via MiniMessage. Empty names stay empty so
            // the slot can act as a filler/placeholder.
            if (!displayName.isEmpty()) {
                meta.displayName(parseComponent(displayName));
            }

            // Apply lore via MiniMessage, skipping empty lines.
            if (!lore.isEmpty()) {
                List<Component> parsedLore = new ArrayList<>(lore.size());
                for (String line : lore) {
                    if (line == null || line.isEmpty()) {
                        continue;
                    }
                    parsedLore.add(parseComponent(line));
                }
                if (!parsedLore.isEmpty()) {
                    meta.lore(parsedLore);
                }
            }

            // Apply glow effect if enabled
            if (glow) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }

            item.setItemMeta(meta);
        }

        return item;
    }

    /**
     * Parse a MiniMessage string into a Component.
     * Legacy {@code &}-codes are first translated to MiniMessage tags so
     * older configs continue to render correctly.
     */
    private static Component parseComponent(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        return MINI.deserialize(translateLegacy(raw));
    }

    /**
     * Translate legacy {@code &}-colour codes into MiniMessage tags. Common
     * colour codes are mapped one-to-one to their MiniMessage equivalent.
     */
    private static String translateLegacy(String input) {
        if (input == null || input.indexOf('&') < 0) {
            return input == null ? "" : input;
        }
        StringBuilder out = new StringBuilder(input.length() + 16);
        char[] chars = input.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '&' && i + 1 < chars.length) {
                char next = chars[i + 1];
                String mm = switch (Character.toLowerCase(next)) {
                    case '0' -> "<black>";
                    case '1' -> "<dark_blue>";
                    case '2' -> "<dark_green>";
                    case '3' -> "<dark_aqua>";
                    case '4' -> "<dark_red>";
                    case '5' -> "<dark_purple>";
                    case '6' -> "<gold>";
                    case '7' -> "<gray>";
                    case '8' -> "<dark_gray>";
                    case '9' -> "<blue>";
                    case 'a' -> "<green>";
                    case 'b' -> "<aqua>";
                    case 'c' -> "<red>";
                    case 'd' -> "<light_purple>";
                    case 'e' -> "<yellow>";
                    case 'f' -> "<white>";
                    case 'l' -> "<bold>";
                    case 'o' -> "<italic>";
                    case 'n' -> "<underlined>";
                    case 'm' -> "<strikethrough>";
                    case 'k' -> "<obfuscated>";
                    case 'r' -> "<reset>";
                    default  -> null;
                };
                if (mm != null) {
                    out.append(mm);
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Creates a new ButtonConfig with updated display name.
     *
     * @param newDisplayName The new display name
     * @return A new ButtonConfig instance with updated display name
     */
    public ButtonConfig withDisplayName(String newDisplayName) {
        return new ButtonConfig(material, newDisplayName, lore, glow);
    }

    /**
     * Creates a new ButtonConfig with updated lore.
     *
     * @param newLore The new lore lines
     * @return A new ButtonConfig instance with updated lore
     */
    public ButtonConfig withLore(List<String> newLore) {
        return new ButtonConfig(material, displayName, newLore, glow);
    }

    /**
     * Creates a new ButtonConfig with updated glow setting.
     *
     * @param newGlow The new glow setting
     * @return A new ButtonConfig instance with updated glow setting
     */
    public ButtonConfig withGlow(boolean newGlow) {
        return new ButtonConfig(material, displayName, lore, newGlow);
    }

    /**
     * Creates a new ButtonConfig with an additional lore line.
     *
     * @param loreLine The lore line to add
     * @return A new ButtonConfig instance with the added lore line
     */
    public ButtonConfig addLoreLine(String loreLine) {
        List<String> newLore = new ArrayList<>(lore);
        newLore.add(loreLine);
        return new ButtonConfig(material, displayName, newLore, glow);
    }
}