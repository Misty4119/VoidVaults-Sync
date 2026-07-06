package com.voidvault.storage;

import com.voidvault.model.PlayerVaultData;
import com.voidvault.model.VaultPage;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * YAML-based storage implementation for vault data.
 * Stores each player's data in a separate YAML file for easy management and debugging.
 * <p>
 * File structure: plugins/VoidVault/data/{uuid}.yml
 */
public class YamlStorage implements StorageManager {

    private final Plugin plugin;
    private final Logger logger;
    private final Path dataFolder;
    private final DataCache dataCache;
    private final ExecutorService asyncExecutor;

    /**
     * Creates a new YamlStorage instance.
     *
     * @param plugin    The plugin instance
     * @param dataCache The data cache for managing in-memory data
     */
    public YamlStorage(Plugin plugin, DataCache dataCache) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.dataFolder = plugin.getDataFolder().toPath().resolve("data");
        this.dataCache = dataCache;
        this.asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(dataFolder);
                logger.info("YAML storage initialized at: " + dataFolder);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to create data directory", e);
                throw new RuntimeException("Failed to initialize YAML storage", e);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<PlayerVaultData> loadPlayerData(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path playerFile = getPlayerFile(playerId);
                
                if (!Files.exists(playerFile)) {
                    logger.fine("No data file found for player " + playerId + ", creating empty data");
                    return PlayerVaultData.createEmpty(playerId);
                }

                YamlConfiguration config = YamlConfiguration.loadConfiguration(playerFile.toFile());
                
                // Load custom slots and pages
                int customSlots = config.getInt("custom-slots", 0);
                int customPages = config.getInt("custom-pages", 0);
                
                // Load pages
                Map<Integer, VaultPage> pages = new HashMap<>();
                ConfigurationSection pagesSection = config.getConfigurationSection("pages");
                
                if (pagesSection != null) {
                    for (String pageKey : pagesSection.getKeys(false)) {
                        try {
                            int pageNumber = Integer.parseInt(pageKey);
                            VaultPage page = loadPage(pagesSection, pageKey, pageNumber);
                            if (page != null && !page.isEmpty()) {
                                pages.put(pageNumber, page);
                            }
                        } catch (NumberFormatException e) {
                            logger.warning("Invalid page number in data file for player " + playerId + ": " + pageKey);
                        }
                    }
                }
                
                PlayerVaultData data = new PlayerVaultData(playerId, pages, customSlots, customPages);
                logger.fine("Loaded data for player " + playerId + " with " + pages.size() + " pages");
                return data;
                
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to load data for player " + playerId + ", returning empty data", e);
                return PlayerVaultData.createEmpty(playerId);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<Void> savePlayerData(UUID playerId, PlayerVaultData data) {
        return CompletableFuture.runAsync(() -> {
            try {
                Path playerFile = getPlayerFile(playerId);
                YamlConfiguration config = new YamlConfiguration();
                
                // Save player ID for reference
                config.set("player-id", playerId.toString());
                
                // Save custom slots and pages
                config.set("custom-slots", data.customSlots());
                config.set("custom-pages", data.customPages());
                
                // Save pages
                for (Map.Entry<Integer, VaultPage> entry : data.pages().entrySet()) {
                    int pageNumber = entry.getKey();
                    VaultPage page = entry.getValue();
                    
                    // Skip empty pages to save space
                    if (page.isEmpty()) {
                        continue;
                    }
                    
                    savePage(config, pageNumber, page);
                }
                
                // Save to file
                config.save(playerFile.toFile());
                logger.fine("Saved data for player " + playerId);
                
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to save data for player " + playerId, e);
                throw new RuntimeException("Failed to save player data", e);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<Void> saveAll() {
        return CompletableFuture.runAsync(() -> {
            long startTime = System.currentTimeMillis();
            Set<UUID> dirtyPlayers = dataCache.getDirtyPlayers();
            
            if (dirtyPlayers.isEmpty()) {
                logger.fine("No dirty players to save");
                return;
            }
            
            logger.info("Saving " + dirtyPlayers.size() + " dirty player(s) to YAML");
            
            int[] successCount = {0};
            int[] failCount = {0};
            List<CompletableFuture<Void>> saveFutures = new ArrayList<>();
            
            for (UUID playerId : dirtyPlayers) {
                dataCache.get(playerId).ifPresentOrElse(data -> {
                    CompletableFuture<Void> saveFuture = savePlayerData(playerId, data)
                            .thenRun(() -> {
                                dataCache.clearDirty(playerId);
                                successCount[0]++;
                            })
                            .exceptionally(ex -> {
                                logger.log(Level.SEVERE, "Failed to save data for player " + playerId, ex);
                                failCount[0]++;
                                return null;
                            });
                    saveFutures.add(saveFuture);
                }, () -> {
                    logger.warning("No data found for dirty player " + playerId);
                    dataCache.clearDirty(playerId);
                });
            }
            
            // Wait for all saves to complete with timeout
            CompletableFuture.allOf(saveFutures.toArray(new CompletableFuture[0]))
                .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .thenRun(() -> {
                    long duration = System.currentTimeMillis() - startTime;
                    logger.info(String.format("YAML save completed in %dms (Success: %d, Failed: %d)", 
                        duration, successCount[0], failCount[0]));
                })
                .exceptionally(ex -> {
                    logger.log(Level.SEVERE, "Error or timeout during YAML save operations", ex);
                    return null;
                })
                .join();
            
        }, asyncExecutor);
    }

    @Override
    public void close() {
        logger.info("Closing YAML storage...");
        asyncExecutor.shutdown();
    }

    @Override
    public String getBackendTypeName() {
        return "YAML";
    }

    /**
     * Gets the file path for a player's data.
     *
     * @param playerId The player's UUID
     * @return The path to the player's data file
     */
    private Path getPlayerFile(UUID playerId) {
        return dataFolder.resolve(playerId.toString() + ".yml");
    }

    /**
     * Loads a vault page from the configuration.
     *
     * @param pagesSection The pages configuration section
     * @param pageKey      The page key (page number as string)
     * @param pageNumber   The page number
     * @return The loaded VaultPage, or null if loading fails
     */
    private VaultPage loadPage(ConfigurationSection pagesSection, String pageKey, int pageNumber) {
        try {
            ConfigurationSection pageSection = pagesSection.getConfigurationSection(pageKey);
            if (pageSection == null) {
                return null;
            }
            
            ItemStack[] contents = new ItemStack[50];
            
            for (String slotKey : pageSection.getKeys(false)) {
                try {
                    int slot = Integer.parseInt(slotKey);
                    if (slot >= 0 && slot < contents.length) {
                        String itemData = pageSection.getString(slotKey);
                        if (itemData != null && !itemData.isEmpty()) {
                            ItemStack item = deserializeItemStack(itemData);
                            contents[slot] = item;
                        }
                    }
                } catch (NumberFormatException e) {
                    logger.warning("Invalid slot number in page " + pageNumber + ": " + slotKey);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to deserialize item in page " + pageNumber + " slot " + slotKey, e);
                }
            }
            
            return new VaultPage(pageNumber, contents);
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to load page " + pageNumber, e);
            return null;
        }
    }

    /**
     * Saves a vault page to the configuration.
     *
     * @param config     The YAML configuration
     * @param pageNumber The page number
     * @param page       The VaultPage to save
     */
    private void savePage(YamlConfiguration config, int pageNumber, VaultPage page) {
        String basePath = "pages." + pageNumber;
        
        for (int slot = 0; slot < page.getSize(); slot++) {
            ItemStack item = page.getItem(slot);
            if (item != null) {
                try {
                    String itemData = serializeItemStack(item);
                    config.set(basePath + "." + slot, itemData);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to serialize item in page " + pageNumber + " slot " + slot, e);
                }
            }
        }
    }

    /**
     * Serializes an ItemStack to a Base64 string.
     *
     * @param item The ItemStack to serialize
     * @return The Base64-encoded string representation
     * @throws IOException If serialization fails
     */
    private String serializeItemStack(ItemStack item) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {
            
            dataOutput.writeObject(item);
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        }
    }

    /**
     * Deserializes an ItemStack from a Base64 string.
     *
     * @param data The Base64-encoded string
     * @return The deserialized ItemStack
     * @throws IOException            If deserialization fails
     * @throws ClassNotFoundException If the ItemStack class is not found
     */
    private ItemStack deserializeItemStack(String data) throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {
            
            return (ItemStack) dataInput.readObject();
        }
    }
}
