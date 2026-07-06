package com.voidvault.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Manages loading and sending of localized messages with MiniMessage + placeholder support.
 *
 * <p>All strings in messages.yml are interpreted as MiniMessage by default, so authors can
 * use modern features such as {@code <gradient:#9CC3FF:#D9B3FF>...</gradient>},
 * {@code <rainbow>}, {@code <hover>}, {@code <click>} and so on. Legacy {@code &c}-style
 * colour codes are still translated automatically so older messages keep working.</p>
 */
public class MessageManager {
    private final Plugin plugin;
    private final Logger logger;
    private final File messagesFile;
    private FileConfiguration messages;

    // Legacy colour-code serializer used to translate legacy & codes into
    // a MiniMessage-friendly form before parsing.
    private static final LegacyComponentSerializer LEGACY_SERIALIZER =
            LegacyComponentSerializer.legacyAmpersand();

    // Shared MiniMessage instance. Tag.stripTags is NOT applied — we want
    // authors to keep full control over the markup in messages.yml.
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    public MessageManager(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.messagesFile = new File(plugin.getDataFolder(), "messages.yml");
    }

    /**
     * Load or reload messages from messages.yml.
     * Generates default messages.yml if it doesn't exist.
     */
    public void load() {
        // Create messages.yml if it doesn't exist
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
            logger.info("Created default messages.yml");
        }

        // Load messages from file
        messages = YamlConfiguration.loadConfiguration(messagesFile);

        // Load defaults from jar
        try (InputStream defaultStream = plugin.getResource("messages.yml")) {
            if (defaultStream != null) {
                YamlConfiguration defaultMessages = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
                messages.setDefaults(defaultMessages);
            }
        } catch (IOException e) {
            logger.warning("Could not load default messages: " + e.getMessage());
        }

        logger.info("Messages loaded successfully (MiniMessage enabled).");
    }

    /**
     * Reload messages from disk.
     */
    public void reload() {
        load();
        logger.info("Messages reloaded.");
    }

    /**
     * Get a raw message string from messages.yml.
     *
     * @param key the message key (e.g., "commands.reload-success")
     * @return the raw message string, or the key if not found
     */
    public String getRaw(String key) {
        String message = messages.getString(key);
        if (message == null) {
            logger.warning("Missing message key: " + key);
            return key;
        }
        return message;
    }

    /**
     * Get a message with placeholders replaced. The returned string is the
     * raw MiniMessage markup so callers can pass it to {@link #parse(String, Map)}
     * for rendering or to ItemMeta setters etc.
     *
     * @param key          the message key
     * @param placeholders map of placeholder names to values (without braces)
     * @return the formatted MiniMessage string
     */
    public String get(String key, Map<String, String> placeholders) {
        String message = getRaw(key);

        // Substitute the brand prefix placeholder so messages.yml can stay
        // readable. parse() will later rewrite any remaining {key} braces
        // into MiniMessage tags via rewriteBracePlaceholder().
        String prefix = getRaw("prefix");
        if (prefix != null && !prefix.isEmpty()) {
            message = message.replace("{prefix}", prefix);
        }

        return message;
    }

    /**
     * Get a message without placeholders.
     *
     * @param key the message key
     * @return the formatted MiniMessage string
     */
    public String get(String key) {
        return get(key, null);
    }

    /**
     * Parse a MiniMessage string into a Component.
     * <p>Legacy {@code &}-codes are first translated to MiniMessage form so
     * older messages keep working, then MiniMessage parses the result.</p>
     * <p>Placeholder keys are normalised to satisfy MiniMessage's tag-name
     * pattern {@code [!?#]?[a-z0-9_-]*}: upper-case becomes lower-case,
     * non-conforming characters are rewritten to {@code _}, and the matching
     * {@code {key}} occurrences in the template are updated in lock-step.
     * This prevents {@link IllegalArgumentException}s from upstream when an
     * author uses {@code maxPages}-style names in their config.</p>
     *
     * @param raw          raw MiniMessage (or legacy) markup
     * @param placeholders optional placeholder map
     * @return parsed Component
     */
    public Component parse(String raw, Map<String, String> placeholders) {
        if (raw == null) {
            return Component.empty();
        }

        // Translate legacy & codes into MiniMessage-friendly text (so &c → <red>).
        String translated = translateLegacy(raw);

        // Build tag resolvers for placeholders.
        List<TagResolver> resolvers = new ArrayList<>();
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                String original = entry.getKey();
                String normalised = normalisePlaceholderKey(original);
                String value = entry.getValue() == null ? "" : entry.getValue();
                if (!normalised.equals(original)) {
                    translated = rewritePlaceholder(translated, original, normalised);
                    logger.warning("Placeholder key '" + original + "' contained characters"
                            + " not allowed in MiniMessage tag names; rewritten to '"
                            + normalised + "'.");
                }
                // Legacy messages use the {key} brace form; rewrite any leftover
                // occurrences to MiniMessage's <key> tag so the placeholder
                // resolves correctly.
                translated = rewriteBracePlaceholder(translated, normalised);
                resolvers.add(Placeholder.parsed(normalised, value));
            }
        }
        TagResolver resolver = TagResolver.resolver(resolvers);

        return MINI.deserialize(translated, resolver);
    }

    /**
     * Convert a placeholder key into a MiniMessage-valid tag name. All
     * upper-case letters become lower-case; any character outside
     * {@code [a-z0-9_-]} becomes an underscore; empty results fall back to
     * {@code "value"}.
     */
    private static String normalisePlaceholderKey(String key) {
        if (key == null || key.isEmpty()) {
            return "value";
        }
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                sb.append((char) (c + 32));
            } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String out = sb.toString();
        if (out.isEmpty()) {
            out = "value";
        }
        return out;
    }

    /**
     * Rewrite every {@code {key}} / {@code <key>} token in the template to the
     * normalised name so the tag resolver can resolve them.
     */
    private static String rewritePlaceholder(String template, String original, String normalised) {
        if (template == null || template.isEmpty() || original.equals(normalised)) {
            return template;
        }
        return template
                .replace("{" + original + "}", "{" + normalised + "}")
                .replace("<" + original + ">", "<" + normalised + ">");
    }

    /**
     * Convert any remaining {@code {key}} brace placeholders to the MiniMessage
     * {@code <key>} tag form so the registered {@link Placeholder#parsed}
     * actually triggers when the messages.yml author writes e.g. {@code {page}}.
     */
    private static String rewriteBracePlaceholder(String template, String normalised) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        return template.replace("{" + normalised + "}", "<" + normalised + ">");
    }

    /**
     * Send a message to a command sender.
     *
     * @param sender       the command sender
     * @param key          the message key
     * @param placeholders map of placeholder names to values
     */
    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        String message = get(key, placeholders);
        Component component = parse(message, placeholders);
        sender.sendMessage(component);
    }

    /**
     * Send a message to a command sender without placeholders.
     *
     * @param sender the command sender
     * @param key    the message key
     */
    public void send(CommandSender sender, String key) {
        send(sender, key, null);
    }

    /**
     * Send a message to a player.
     *
     * @param player       the player
     * @param key          the message key
     * @param placeholders map of placeholder names to values
     */
    public void send(Player player, String key, Map<String, String> placeholders) {
        send((CommandSender) player, key, placeholders);
    }

    /**
     * Send a message to a player without placeholders.
     *
     * @param player the player
     * @param key    the message key
     */
    public void send(Player player, String key) {
        send(player, key, null);
    }

    /**
     * Send a pre-built MiniMessage string directly to a command sender.
     *
     * @param sender the command sender
     * @param raw    the raw MiniMessage markup
     */
    public void sendRaw(CommandSender sender, String raw) {
        sender.sendMessage(parse(raw, null));
    }

    /**
     * Translate legacy {@code &}-codes into MiniMessage tags so old messages
     * keep working without forcing authors to rewrite their text. Common
     * colour codes are mapped one-to-one to their MiniMessage equivalent.
     */
    private static String translateLegacy(String input) {
        if (input == null) {
            return "";
        }
        // Fast path: nothing to translate.
        if (input.indexOf('&') < 0) {
            return input;
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
                    i++; // skip code letter
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Create a placeholder map builder for convenient placeholder creation.
     *
     * @return a new PlaceholderBuilder
     */
    public static PlaceholderBuilder placeholders() {
        return new PlaceholderBuilder();
    }

    /**
     * Builder class for creating placeholder maps.
     */
    public static class PlaceholderBuilder {
        private final Map<String, String> placeholders = new HashMap<>();

        /**
         * Add a placeholder.
         *
         * @param key   the placeholder key (without braces)
         * @param value the placeholder value
         * @return this builder
         */
        public PlaceholderBuilder add(String key, String value) {
            placeholders.put(key, value);
            return this;
        }

        /**
         * Add a placeholder with an integer value.
         *
         * @param key   the placeholder key (without braces)
         * @param value the placeholder value
         * @return this builder
         */
        public PlaceholderBuilder add(String key, int value) {
            return add(key, String.valueOf(value));
        }

        /**
         * Add a placeholder with a double value.
         *
         * @param key   the placeholder key (without braces)
         * @param value the placeholder value
         * @return this builder
         */
        public PlaceholderBuilder add(String key, double value) {
            return add(key, String.valueOf(value));
        }

        /**
         * Add a placeholder with a long value.
         *
         * @param key   the placeholder key (without braces)
         * @param value the placeholder value
         * @return this builder
         */
        public PlaceholderBuilder add(String key, long value) {
            return add(key, String.valueOf(value));
        }

        /**
         * Build the placeholder map.
         *
         * @return the placeholder map
         */
        public Map<String, String> build() {
            return placeholders;
        }
    }
}
