package com.voidvault.config;

import com.voidvault.model.ButtonConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages loading, validation, and access to the plugin's configuration.
 * Handles config.yml with all plugin settings including mode, cooldowns, economy, and GUI items.
 */
public class ConfigManager {
    private final Plugin plugin;
    private final Logger logger;
    private FileConfiguration config;
    
    // Cached configuration values
    private PluginMode pluginMode;
    private String storageType;
    private int cooldownSeconds;
    private int autoSaveInterval;
    private int defaultSlots;
    private int defaultPages;
    /**
     * Absolute upper bound on the number of pages a player can ever own,
     * regardless of permission. Configurable via {@code vault.max-pages}
     * in config.yml. Defaults to 10. Must be at least 1.
     */
    private int maxPages;
    
    // Search settings
    private boolean searchGrayoutEnabled;
    private String searchMode;
    private boolean searchCaseSensitive;
    
    // Feature toggles
    private boolean sortEnabled;
    private boolean quickDepositEnabled;
    private boolean searchEnabled;
    private boolean lockedSlotsEnabled;
    
    // GUI titles
    private String simpleModeTitle;
    private String pagedModeTitle;
    private String adminModeTitle;
    
    // Button configurations
    private ButtonConfig lockedSlotItem;
    private ButtonConfig fillerBarItem;
    private ButtonConfig previousPageButton;
    private ButtonConfig nextPageButton;
    private ButtonConfig filterButton;
    private ButtonConfig searchButton;
    private ButtonConfig sortButton;
    private ButtonConfig quickDepositButton;
    private ButtonConfig filteredSlotItem;

    // Search GUI overlay
    private String searchGuiTitle;
    private ButtonConfig searchGuiFiller;
    private java.util.List<SearchGuiButton> searchGuiQuickOptions;
    private SearchGuiButton searchGuiCustomButton;
    private SearchGuiButton searchGuiClearButton;
    private SearchGuiButton searchGuiCancelButton;

    public ConfigManager(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }
    
    /**
     * Load or reload the configuration from config.yml.
     * Generates default config if it doesn't exist.
     */
    public void load() {
        // Save default config if it doesn't exist
        plugin.saveDefaultConfig();
        
        // Reload config from disk
        plugin.reloadConfig();
        config = plugin.getConfig();
        
        // Load and validate all configuration values.
        // Order matters: loadVaultSettings() (which fills maxPages) must run
        // BEFORE loadDefaultValues() because PAGED-mode default-pages
        // resolves "0 means follow vault.max-pages" against that field.
        loadPluginMode();
        loadStorageSettings();
        loadRemoteAccessSettings();
        loadAutoSaveSettings();
        loadVaultSettings();
        loadDefaultValues();
        loadGuiTitles();
        loadItemConfigurations();
        loadSearchSettings();
        loadFeatureToggles();
        loadSearchGui();
        
        logger.info("Configuration loaded successfully. Mode: " + pluginMode);
    }
    
    /**
     * Reload the configuration from disk.
     */
    public void reload() {
        load();
        logger.info("Configuration reloaded.");
    }
    
    private void loadPluginMode() {
        String modeString = config.getString("plugin-mode", "PAGED");
        pluginMode = PluginMode.fromString(modeString);
        
        if (!modeString.equalsIgnoreCase(pluginMode.name())) {
            logger.warning("Invalid plugin-mode value '" + modeString + "'. Defaulting to " + pluginMode);
        }
    }
    
    private void loadStorageSettings() {
        // The legacy top-level `storage-type:` key remains for backwards
        // compatibility, but new config blocks live under `storage.type`.
        String legacy = config.getString("storage-type", null);
        String nested = config.getString("storage.type", null);
        storageType = (nested != null ? nested : legacy != null ? legacy : "YAML").toUpperCase();

        if (!storageType.equals("YAML")
                && !storageType.equals("MYSQL")
                && !storageType.equals("REDIS")
                && !storageType.equals("REDIS_PERSISTENT")) {
            logger.warning("Invalid storage-type '" + storageType + "'. Defaulting to YAML.");
            storageType = "YAML";
        }
    }
    
    private void loadRemoteAccessSettings() {
        cooldownSeconds = config.getInt("remote-access.cooldown-seconds", 60);

        if (cooldownSeconds < 0) {
            logger.warning("Invalid cooldown-seconds value. Defaulting to 60.");
            cooldownSeconds = 60;
        }
    }
    
    private void loadAutoSaveSettings() {
        autoSaveInterval = config.getInt("auto-save-interval", 5);

        if (autoSaveInterval < 1) {
            logger.warning("Invalid auto-save-interval value. Defaulting to 5 minutes.");
            autoSaveInterval = 5;
        }
    }

    private void loadVaultSettings() {
        // vault.max-pages caps the absolute number of pages a player can
        // own, regardless of permission. Defaults to 10, must be >= 1.
        maxPages = config.getInt("vault.max-pages", 10);

        if (maxPages < 1) {
            logger.warning("Invalid vault.max-pages value '" + maxPages + "'. Must be >= 1. Defaulting to 10.");
            maxPages = 10;
        }
    }

    /**
     * Load the search overlay's title, filler and clickable buttons from the
     * {@code search-gui.*} section of config.yml. Anything missing falls back
     * to a hard-coded sensible default so the overlay still opens on a fresh
     * server even before the user has edited the file.
     *
     * <p>Quick-option buttons are loaded as a list keyed by an explicit
     * {@code id}, then re-indexed by slot to keep the lookup logic in
     * {@link com.voidvault.gui.SearchGUI} O(1).</p>
     */
    private void loadSearchGui() {
        searchGuiTitle = config.getString("search-gui.title",
                "<gradient:#9CC3FF:#D9B3FF><bold>◎ Search Vault</bold></gradient>");

        searchGuiFiller = loadButtonConfig("search-gui.filler",
                Material.GRAY_STAINED_GLASS_PANE, " ", List.of(), false);

        java.util.List<SearchGuiButton> quick = new java.util.ArrayList<>();
        org.bukkit.configuration.ConfigurationSection quickSection =
                config.getConfigurationSection("search-gui.quick-options");
        if (quickSection != null) {
            for (String id : quickSection.getKeys(false)) {
                org.bukkit.configuration.ConfigurationSection entry =
                        quickSection.getConfigurationSection(id);
                if (entry == null) {
                    continue;
                }
                SearchGuiButton btn = loadSearchGuiButton(entry, "search-gui.quick-options." + id, id);
                if (btn != null) {
                    quick.add(btn);
                }
            }
        } else {
            // Backstop: ship a sensible default set so the GUI is usable
            // out-of-the-box without any author intervention. Anything
            // authors write to config.yml wins over these defaults.
            quick.addAll(SearchGuiButton.defaultQuickOptions());
            logger.info("search-gui.quick-options not configured — using built-in defaults.");
        }
        searchGuiQuickOptions = java.util.List.copyOf(quick);

        searchGuiCustomButton = loadSearchGuiSlotButton(
                config.getConfigurationSection("search-gui.custom-search"),
                "search-gui.custom-search",
                SearchGuiRole.CUSTOM_SEARCH,
                22,
                Material.NAME_TAG,
                "<gradient:#9CC3FF:#D9B3FF><bold>✎ Custom Search</bold></gradient>",
                List.of("<gray>Click to enter your own query.</gray>",
                        "<dark_gray>Type the item name in chat.</dark_gray>"));

        searchGuiClearButton = loadSearchGuiSlotButton(
                config.getConfigurationSection("search-gui.clear-filter"),
                "search-gui.clear-filter",
                SearchGuiRole.CLEAR_FILTER,
                24,
                Material.BARRIER,
                "<red><bold>✕ Clear Filter</bold></red>",
                List.of("<gray>Remove the current filter and</gray>",
                        "<gray>show every item again.</gray>"));

        searchGuiCancelButton = loadSearchGuiSlotButton(
                config.getConfigurationSection("search-gui.cancel"),
                "search-gui.cancel",
                SearchGuiRole.CANCEL,
                26,
                Material.RED_STAINED_GLASS_PANE,
                "<red><bold>↩ Cancel</bold></red>",
                List.of("<gray>Return to the vault.</gray>"));
    }

    /**
     * Load a quick-search button entry. The {@code slot} and {@code query}
     * fields are required; missing entries are skipped with a warning.
     */
    private SearchGuiButton loadSearchGuiButton(ConfigurationSection section,
                                                String path,
                                                String id) {
        if (section == null) {
            return null;
        }
        int slot = section.getInt("slot", -1);
        if (slot < 0 || slot > 26) {
            logger.warning("Invalid slot '" + section.get("slot") + "' in " + path + ". Skipping.");
            return null;
        }
        String query = section.getString("query");
        if (query == null || query.isBlank()) {
            logger.warning("Missing query for quick-option '" + id + "' in " + path + ". Skipping.");
            return null;
        }
        Material material = Material.getMaterial(section.getString("material", "STONE"));
        if (material == null) {
            material = Material.STONE;
        }
        String displayName = section.getString("display-name", "<white>Search</white>");
        List<String> lore = section.getStringList("lore");
        boolean glow = section.getBoolean("glow", false);
        return new SearchGuiButton(id, slot, material, displayName, lore, glow,
                query, SearchGuiRole.QUICK_OPTION);
    }

    /**
     * Load a single non-quick-option button (custom / clear / cancel). These
     * always have a known hard-coded role, so the loader can use a stable
     * fallback chain without touching {@link SearchGuiButton#query()}.
     */
    private SearchGuiButton loadSearchGuiSlotButton(ConfigurationSection section,
                                                   String path,
                                                   SearchGuiRole role,
                                                   int defaultSlot,
                                                   Material defaultMaterial,
                                                   String defaultName,
                                                   List<String> defaultLore) {
        if (section == null) {
            return new SearchGuiButton(role.id(), defaultSlot, defaultMaterial,
                    defaultName, defaultLore, false, "", role);
        }
        int slot = section.getInt("slot", defaultSlot);
        if (slot < 0 || slot > 26) {
            logger.warning("Invalid slot for " + path + " (was '" + section.get("slot")
                    + "'). Falling back to " + defaultSlot + ".");
            slot = defaultSlot;
        }
        Material material = Material.getMaterial(section.getString("material",
                defaultMaterial.name()));
        if (material == null) {
            material = defaultMaterial;
        }
        String displayName = section.getString("display-name", defaultName);
        List<String> lore = section.getStringList("lore");
        if (lore.isEmpty() && !defaultLore.isEmpty()) {
            lore = defaultLore;
        }
        boolean glow = section.getBoolean("glow", false);
        return new SearchGuiButton(role.id(), slot, material, displayName, lore,
                glow, "", role);
    }

    private void loadGuiTitles() {
        // Titles are stored as MiniMessage markup. Legacy & codes still work
        // because the title getters translate them through translateLegacy().
        simpleModeTitle = config.getString("gui-titles.simple",
                "<gradient:#9CC3FF:#D9B3FF><bold>Vault</bold></gradient>");
        pagedModeTitle = config.getString("gui-titles.paged",
                "<gradient:#9CC3FF:#D9B3FF><bold>VoidVault</bold></gradient> <dark_gray>·</dark_gray> <gray>Page {page}</gray>");
        adminModeTitle = config.getString("gui-titles.admin",
                "<gradient:#9CC3FF:#D9B3FF><bold>{target}'s Vault</bold></gradient> <dark_gray>·</dark_gray> <gray>Page {page}</gray>");
    }
    
    private void loadDefaultValues() {
        // Load mode-specific defaults
        PluginMode mode = getPluginMode();
        
        if (mode == PluginMode.SIMPLE) {
            // SIMPLE mode defaults
            defaultSlots = config.getInt("defaults.simple.slots", 27);
            defaultPages = 1; // SIMPLE mode always has 1 page
            
            // Validate SIMPLE mode slots (9-54)
            int[] validSlots = {9, 18, 27, 36, 45, 54};
            boolean validSlotCount = false;
            for (int validSlot : validSlots) {
                if (defaultSlots == validSlot) {
                    validSlotCount = true;
                    break;
                }
            }
            
            if (!validSlotCount) {
                logger.warning("Invalid defaults.simple.slots value '" + defaultSlots + "'. Must be one of: 9, 18, 27, 36, 45, 54. Defaulting to 27.");
                defaultSlots = 27;
            }
        } else {
            // PAGED mode defaults
            defaultSlots = config.getInt("defaults.paged.slots", 50);
            // default-pages = 0 (or omitted) means "follow vault.max-pages",
            // so an OP player (who has no explicit voidvaults.page.*
            // permission) still gets the full configured page count out of
            // the box.
            int configuredDefaultPages = config.getInt("defaults.paged.pages", 0);
            defaultPages = configuredDefaultPages <= 0 ? maxPages : configuredDefaultPages;

            // Validate PAGED mode slots (9-50)
            int[] validSlots = {9, 18, 27, 36, 45, 50};
            boolean validSlotCount = false;
            for (int validSlot : validSlots) {
                if (defaultSlots == validSlot) {
                    validSlotCount = true;
                    break;
                }
            }

            if (!validSlotCount) {
                logger.warning("Invalid defaults.paged.slots value '" + defaultSlots + "'. Must be one of: 9, 18, 27, 36, 45, 50. Defaulting to 50.");
                defaultSlots = 50;
            }

            // Validate default-pages is between 1 and max-pages
            if (defaultPages < 1 || defaultPages > maxPages) {
                logger.warning("Invalid defaults.paged.pages value '" + defaultPages + "'. Must be between 1 and " + maxPages + ". Defaulting to " + maxPages + ".");
                defaultPages = maxPages;
            }
        }
    }
    
    private void loadItemConfigurations() {
        lockedSlotItem = loadButtonConfig("items.locked-slot",
            Material.RED_STAINED_GLASS_PANE, "<red><bold>✖ Locked</bold></red>",
            List.of("<gray>Upgrade your vault to</gray>", "<gray>unlock this slot!</gray>"), false);

        fillerBarItem = loadButtonConfig("items.filler-bar",
            Material.GRAY_STAINED_GLASS_PANE, " ",
            List.of(), false);

        previousPageButton = loadButtonConfig("items.previous-page",
            Material.ARROW, "<gradient:#9CC3FF:#D9B3FF><bold>◀ Previous Page</bold></gradient>",
            List.of("<gray>Click to go back</gray>"), false);

        nextPageButton = loadButtonConfig("items.next-page",
            Material.ARROW, "<gradient:#9CC3FF:#D9B3FF><bold>Next Page ▶</bold></gradient>",
            List.of("<gray>Click to continue</gray>"), false);

        filterButton = loadButtonConfig("items.filter",
            Material.PAPER, "<gradient:#9CC3FF:#D9B3FF><bold>◇ Filter Items</bold></gradient>",
            List.of("<gray>Filter by item type</gray>", "<dark_gray>Right-click to clear filter</dark_gray>"), false);

        searchButton = loadButtonConfig("items.search",
            Material.COMPASS, "<gradient:#9CC3FF:#D9B3FF><bold>◎ Search Items</bold></gradient>",
            List.of("<gray>Search for items</gray>", "<dark_gray>Type in chat to search</dark_gray>"), false);

        sortButton = loadButtonConfig("items.sort",
            Material.HOPPER, "<gradient:#9CC3FF:#D9B3FF><bold>⇅ Sort Items</bold></gradient>",
            List.of("<gray>Organize your vault</gray>"), true);

        quickDepositButton = loadButtonConfig("items.quick-deposit",
            Material.CHEST, "<gradient:#9CC3FF:#D9B3FF><bold>⊕ Quick Deposit</bold></gradient>",
            List.of("<gray>Deposit matching items</gray>"), true);

        filteredSlotItem = loadButtonConfig("items.filtered-slot",
            Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray><italic>Filtered</italic></gray>",
            List.of("<gray>This item doesn't match</gray>", "<gray>your search query</gray>"), false);
    }
    
    private void loadSearchSettings() {
        searchGrayoutEnabled = config.getBoolean("search.grayout-enabled", true);
        searchMode = config.getString("search.search-mode", "all");
        searchCaseSensitive = config.getBoolean("search.case-sensitive", false);
        
        if (!searchMode.equals("name") && !searchMode.equals("all")) {
            logger.warning("Invalid search-mode '" + searchMode + "'. Defaulting to 'all'.");
            searchMode = "all";
        }
    }
    
    private void loadFeatureToggles() {
        sortEnabled = config.getBoolean("features.sort-enabled", true);
        quickDepositEnabled = config.getBoolean("features.quick-deposit-enabled", true);
        searchEnabled = config.getBoolean("features.search-enabled", true);
        lockedSlotsEnabled = config.getBoolean("features.locked-slots-enabled", true);
    }
    
    private ButtonConfig loadButtonConfig(String path, Material defaultMaterial, 
                                         String defaultName, List<String> defaultLore, 
                                         boolean defaultGlow) {
        ConfigurationSection section = config.getConfigurationSection(path);
        
        if (section == null) {
            return new ButtonConfig(defaultMaterial, defaultName, defaultLore, defaultGlow);
        }
        
        Material material = Material.getMaterial(section.getString("material", defaultMaterial.name()));
        if (material == null) {
            logger.warning("Invalid material for " + path + ". Using default.");
            material = defaultMaterial;
        }
        
        String displayName = section.getString("display-name", defaultName);
        List<String> lore = section.getStringList("lore");
        if (lore.isEmpty() && !defaultLore.isEmpty()) {
            lore = defaultLore;
        }
        boolean glow = section.getBoolean("glow", defaultGlow);
        
        return new ButtonConfig(material, displayName, lore, glow);
    }
    
    // Getters for configuration values
    
    public PluginMode getPluginMode() {
        return pluginMode;
    }
    
    public String getStorageType() {
        return storageType;
    }
    
    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public boolean isRemoteAccessEnabled() {
        return config.getBoolean("remote-access.enabled", true);
    }
    
    public int getAutoSaveInterval() {
        return autoSaveInterval;
    }
    
    public int getDefaultSlots() {
        return defaultSlots;
    }
    
    public int getDefaultPages() {
        return defaultPages;
    }

    /**
     * Get the absolute maximum number of pages a player may own,
     * regardless of permission grants. Controlled by {@code vault.max-pages}
     * in config.yml (default 10).
     */
    public int getMaxPages() {
        return maxPages;
    }
    
    public ButtonConfig getLockedSlotItem() {
        return lockedSlotItem;
    }
    
    public ButtonConfig getFillerBarItem() {
        return fillerBarItem;
    }
    
    public ButtonConfig getPreviousPageButton() {
        return previousPageButton;
    }
    
    public ButtonConfig getNextPageButton() {
        return nextPageButton;
    }
    
    public ButtonConfig getSortButton() {
        return sortButton;
    }
    
    public ButtonConfig getFilterButton() {
        return filterButton;
    }
    
    public ButtonConfig getSearchButton() {
        return searchButton;
    }
    
    public ButtonConfig getQuickDepositButton() {
        return quickDepositButton;
    }
    
    public ButtonConfig getFilteredSlotItem() {
        return filteredSlotItem;
    }

    // Search GUI overlay getters

    /**
     * Parsed MiniMessage title for the 27-slot search overlay.
     */
    public Component getSearchGuiTitle() {
        return parse(searchGuiTitle == null ? "" : searchGuiTitle);
    }

    /**
     * Glass-pane config used to fill every empty slot of the search overlay.
     */
    public ButtonConfig getSearchGuiFiller() {
        return searchGuiFiller;
    }

    /**
     * The list of quick-search buttons (each carrying its own
     * {@code query} string). Order matches the order in config.yml.
     */
    public List<SearchGuiButton> getSearchGuiQuickOptions() {
        return searchGuiQuickOptions;
    }

    /** "Type your own" button. */
    public SearchGuiButton getSearchGuiCustomButton() {
        return searchGuiCustomButton;
    }

    /** Clear-filter button. */
    public SearchGuiButton getSearchGuiClearButton() {
        return searchGuiClearButton;
    }

    /** Cancel / back button. */
    public SearchGuiButton getSearchGuiCancelButton() {
        return searchGuiCancelButton;
    }

    // Search settings getters
    
    public boolean isSearchGrayoutEnabled() {
        return searchGrayoutEnabled;
    }
    
    public String getSearchMode() {
        return searchMode;
    }
    
    public boolean isSearchCaseSensitive() {
        return searchCaseSensitive;
    }
    
    // Feature toggle getters
    
    public boolean isSortEnabled() {
        return sortEnabled;
    }
    
    public boolean isQuickDepositEnabled() {
        return quickDepositEnabled;
    }
    
    public boolean isSearchEnabled() {
        return searchEnabled;
    }
    
    public boolean isLockedSlotsEnabled() {
        return lockedSlotsEnabled;
    }
    
    // MySQL configuration getters (reads storage.mysql.* with fallback to legacy mysql.*)
    
    public String getMySqlHost() {
        return config.getString("storage.mysql.host",
                config.getString("mysql.host", "localhost"));
    }
    
    public int getMySqlPort() {
        return config.getInt("storage.mysql.port",
                config.getInt("mysql.port", 3306));
    }
    
    public String getMySqlDatabase() {
        return config.getString("storage.mysql.database",
                config.getString("mysql.database", "voidvault"));
    }
    
    public String getMySqlUsername() {
        return config.getString("storage.mysql.username",
                config.getString("mysql.username", "root"));
    }
    
    public String getMySqlPassword() {
        return config.getString("storage.mysql.password",
                config.getString("mysql.password", "password"));
    }
    
    public int getMySqlPoolSize() {
        return config.getInt("storage.mysql.pool-size",
                config.getInt("mysql.pool-size", 30));
    }

    /**
     * Maximum time (ms) to wait for a free HikariCP connection. Default 3000ms
     * — fail fast at the command level rather than blocking the main thread
     * for the Hikari default of 30s.
     */
    public int getMySqlConnectionTimeoutMs() {
        return config.getInt("storage.mysql.connection-timeout-ms", 3000);
    }

    /**
     * How long (ms) a connection may be held before HikariCP logs a leak
     * warning. Default 10000ms — anything longer is almost certainly a leak.
     */
    public long getMySqlLeakDetectionThresholdMs() {
        return config.getLong("storage.mysql.leak-detection-threshold-ms", 10000L);
    }
    
// GUI Title getters

    /**
     * Get the GUI title for SIMPLE mode.
     * Supports placeholders: {player}
     *
     * @param playerName The player's name
     * @return The formatted Component
     */
    public Component getSimpleModeTitle(String playerName) {
        return parseMiniMessage(simpleModeTitle, "player", playerName);
    }

    /**
     * Get the GUI title for PAGED mode.
     * Supports placeholders: {player}, {page}, {max_pages}
     *
     * @param playerName The player's name
     * @param page The current page number
     * @param maxPages The maximum number of pages
     * @return The formatted Component
     */
    public Component getPagedModeTitle(String playerName, int page, int maxPages) {
        return parseMiniMessage(pagedModeTitle,
                "player", playerName,
                "page", String.valueOf(page),
                "max_pages", String.valueOf(maxPages));
    }

    /**
     * Get the GUI title for admin viewing mode.
     * Supports placeholders: {player}, {target}, {page}, {max_pages}
     *
     * @param adminName The admin's name
     * @param targetName The target player's name
     * @param page The current page number
     * @param maxPages The maximum number of pages
     * @return The formatted Component
     */
    public Component getAdminModeTitle(String adminName, String targetName, int page, int maxPages) {
        return parseMiniMessage(adminModeTitle,
                "player", adminName,
                "target", targetName,
                "page", String.valueOf(page),
                "max_pages", String.valueOf(maxPages));
    }

    /**
     * Shared MiniMessage instance used by every GUI-title and item parser.
     */
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /**
     * Legacy {@code &}-code translator reused by GUI titles, item names and lore.
     */
    private static final LegacyComponentSerializer LEGACY_SERIALIZER =
            LegacyComponentSerializer.legacyAmpersand();

    /**
     * Parse a MiniMessage string with placeholder key/value pairs (varargs).
     * Also translates legacy {@code &}-codes so old configs keep working.
     * Placeholder keys are automatically normalised to satisfy MiniMessage's
     * tag-name pattern {@code [!?#]?[a-z0-9_-]*} — any non-conforming
     * character is rewritten to {@code _}; upper-case characters are
     * lower-cased. We also rewrite the matching {@code {key}} occurrences in
     * the input so that templates written with camelCase still resolve.
     *
     * <p>Templates may refer to placeholders either with the MiniMessage
     * tag syntax ({@code <key>}) or with the legacy {@code {key}} brace
     * syntax used by the chat messages system. Both forms are rewritten
     * to the normalised MiniMessage tag so they both resolve to the
     * same {@link Placeholder} entry.</p>
     */
    private Component parseMiniMessage(String raw, String... placeholderPairs) {
        if (raw == null) {
            return Component.empty();
        }
        String translated = translateLegacy(raw);
        List<TagResolver> resolvers = new ArrayList<>();
        for (int i = 0; i + 1 < placeholderPairs.length; i += 2) {
            String key = placeholderPairs[i];
            String value = placeholderPairs[i + 1];
            String normalised = normalisePlaceholderKey(key);
            if (!normalised.equals(key)) {
                translated = rewritePlaceholder(translated, key, normalised);
                logger.warning("Placeholder key '" + key + "' contained characters"
                        + " not allowed in MiniMessage tag names; rewritten to '"
                        + normalised + "'.");
            }
            // Also rewrite any {key} form left in the template (legacy brace
            // syntax) to <key> so it resolves through the placeholder.
            translated = rewriteBracePlaceholder(translated, normalised);
            resolvers.add(Placeholder.parsed(normalised, value));
        }
        return MINI.deserialize(translated, TagResolver.resolver(resolvers));
    }

    /**
     * Convert a placeholder key into a string MiniMessage will accept as a tag
     * name. Upper-case characters become lower-case; anything outside
     * {@code [a-z0-9_-]} is replaced with {@code _}; leading characters that
     * are not allowed are stripped. The result is always non-empty.
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
     * Replace every {@code {key}} / {@code <key>} occurrence inside the input
     * template with the normalised version so that MiniMessage resolves them
     * through the tag resolver.
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
     * Rewrite any remaining {@code {key}} occurrences in the template to the
     * MiniMessage {@code <key>} tag form, so authors can use the more
     * familiar brace syntax (matching the messages.yml convention) inside
     * config GUI titles.
     */
    private static String rewriteBracePlaceholder(String template, String normalised) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        return template.replace("{" + normalised + "}", "<" + normalised + ">");
    }

    /**
     * Expose the MiniMessage parser so other components (e.g. ItemBuilder)
     * can reuse the same instance.
     */
    public MiniMessage getMiniMessage() {
        return MINI;
    }

    /**
     * Parse a single MiniMessage string (no placeholders). Legacy {@code &}-codes
     * are translated first so older configs still render correctly.
     */
    public Component parse(String raw) {
        return parseMiniMessage(raw == null ? "" : raw);
    }

    /**
     * Parse a MiniMessage string with the given placeholder map. Useful for
     * things like item lore lines. Map keys are normalised through
     * {@link #normalisePlaceholderKey(String)} so camelCase / non-conforming
     * keys still resolve. The matching {@code {key}} occurrences in the
     * template are rewritten accordingly.
     */
    public Component parse(String raw, java.util.Map<String, String> placeholders) {
        if (raw == null) {
            return Component.empty();
        }
        String translated = translateLegacy(raw);
        List<TagResolver> resolvers = new ArrayList<>();
        if (placeholders != null) {
            for (var entry : placeholders.entrySet()) {
                String original = entry.getKey();
                String normalised = normalisePlaceholderKey(original);
                String value = entry.getValue() == null ? "" : entry.getValue();
                if (!normalised.equals(original)) {
                    translated = rewritePlaceholder(translated, original, normalised);
                    logger.warning("Placeholder key '" + original + "' contained characters"
                            + " not allowed in MiniMessage tag names; rewritten to '"
                            + normalised + "'.");
                }
                resolvers.add(Placeholder.parsed(normalised, value));
            }
        }
        return MINI.deserialize(translated, TagResolver.resolver(resolvers));
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

    // ---------------------------------------------------------------------
    // Search GUI overlay types
    // ---------------------------------------------------------------------

    /**
     * Behavioural role of a button inside the search overlay. Used by
     * {@link com.voidvault.gui.SearchGUI} to decide what action to run
     * when the button is clicked (run a query, prompt for chat input,
     * clear the filter, or cancel back to the vault).
     */
    public enum SearchGuiRole {
        QUICK_OPTION,
        CUSTOM_SEARCH,
        CLEAR_FILTER,
        CANCEL;

        /** Stable identifier suitable for config keys / map keys. */
        public String id() {
            return switch (this) {
                case QUICK_OPTION -> "quick-option";
                case CUSTOM_SEARCH -> "custom-search";
                case CLEAR_FILTER -> "clear-filter";
                case CANCEL -> "cancel";
            };
        }
    }

    /**
     * A single button on the 27-slot search overlay. Combines a
     * {@link ButtonConfig} (material / display name / lore / glow) with
     * a target inventory slot and an optional {@code query} string used
     * when the role is {@link SearchGuiRole#QUICK_OPTION}.
     *
     * @param id          Stable identifier used for config keys.
     * @param slot        Target inventory slot (0–26 inclusive).
     * @param material    Item material.
     * @param displayName MiniMessage display name.
     * @param lore        MiniMessage lore lines.
     * @param glow        Whether the icon should be enchanted (for glow).
     * @param query       Search query (only used when role is QUICK_OPTION).
     * @param role        Behavioural role of this button.
     */
    public record SearchGuiButton(
            String id,
            int slot,
            Material material,
            String displayName,
            List<String> lore,
            boolean glow,
            String query,
            SearchGuiRole role
    ) {
        public SearchGuiButton {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("SearchGuiButton id cannot be blank");
            }
            if (slot < 0 || slot > 26) {
                throw new IllegalArgumentException(
                        "SearchGuiButton slot must be 0–26, got " + slot);
            }
            if (material == null) {
                throw new IllegalArgumentException("SearchGuiButton material cannot be null");
            }
            displayName = displayName == null ? "" : displayName;
            lore = lore == null ? List.of() : List.copyOf(lore);
            query = query == null ? "" : query;
        }

        /** Build an {@link org.bukkit.inventory.ItemStack} from this entry. */
        public ItemStack toItemStack() {
            // Delegate to a ButtonConfig built on the fly so MiniMessage
            // handling stays in exactly one place.
            ButtonConfig button = new ButtonConfig(material, displayName, lore, glow);
            return button.toItemStack();
        }

        /**
         * Default quick-options used when the {@code search-gui.quick-options}
         * section is missing from config.yml. Keeping them here (rather than
         * hard-coding the same MiniMessage into Java twice) ensures the
         * out-of-the-box overlay matches what authors see in the YAML file.
         */
        public static List<SearchGuiButton> defaultQuickOptions() {
            return List.of(
                    new SearchGuiButton("diamond", 10, Material.DIAMOND,
                            "<aqua><bold>◇ Diamond Items</bold></aqua>",
                            List.of("<gray>Search for any diamond item.</gray>"),
                            false, "diamond", SearchGuiRole.QUICK_OPTION),
                    new SearchGuiButton("iron", 11, Material.IRON_INGOT,
                            "<white><bold>◇ Iron Items</bold></white>",
                            List.of("<gray>Search for any iron item.</gray>"),
                            false, "iron", SearchGuiRole.QUICK_OPTION),
                    new SearchGuiButton("gold", 12, Material.GOLD_INGOT,
                            "<yellow><bold>◇ Gold Items</bold></yellow>",
                            List.of("<gray>Search for any gold item.</gray>"),
                            false, "gold", SearchGuiRole.QUICK_OPTION),
                    new SearchGuiButton("netherite", 13, Material.NETHERITE_INGOT,
                            "<dark_gray><bold>◇ Netherite Items</bold></dark_gray>",
                            List.of("<gray>Search for any netherite item.</gray>"),
                            true, "netherite", SearchGuiRole.QUICK_OPTION),
                    new SearchGuiButton("tools-weapons", 14, Material.WOODEN_SWORD,
                            "<gold><bold>◇ Tools &amp; Weapons</bold></gold>",
                            List.of("<gray>Search for tools and weapons.</gray>"),
                            false, "sword pickaxe axe shovel hoe",
                            SearchGuiRole.QUICK_OPTION),
                    new SearchGuiButton("armor", 15, Material.DIAMOND_CHESTPLATE,
                            "<light_purple><bold>◇ Armor</bold></light_purple>",
                            List.of("<gray>Search for armour pieces.</gray>"),
                            false, "helmet chestplate leggings boots",
                            SearchGuiRole.QUICK_OPTION),
                    new SearchGuiButton("blocks", 16, Material.COBBLESTONE,
                            "<gray><bold>◇ Blocks</bold></gray>",
                            List.of("<gray>Search for common building blocks.</gray>"),
                            false, "stone dirt cobblestone", SearchGuiRole.QUICK_OPTION)
            );
        }
    }
}
