package com.voidvault.manager;

import com.voidvault.config.ConfigManager;
import com.voidvault.config.MessageManager;
import com.voidvault.config.PluginMode;
import com.voidvault.gui.PagedVaultGUI;
import com.voidvault.gui.SimpleVaultGUI;
import com.voidvault.gui.VaultGUI;
import com.voidvault.model.PlayerVaultData;
import com.voidvault.model.VaultDataCloner;
import com.voidvault.storage.DataCache;
import com.voidvault.storage.StorageManager;
import com.voidvault.util.SchedulerUtil;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Central coordinator for vault operations.
 * Manages vault opening, closing, GUI creation, and data persistence.
 * Tracks open GUIs per player for event handling.
 */
public class VaultManager {
    private final Plugin plugin;
    private final Logger logger;
    private final ConfigManager configManager;
    private final MessageManager messageManager;
    private final PermissionManager permissionManager;
    private final StorageManager storageManager;
    private final DataCache dataCache;
    private final SearchManager searchManager;
    
    /**
     * Map of player UUIDs to their currently open VaultGUI instances.
     * Used by event listeners to route events to the correct GUI handler.
     */
    private final Map<UUID, VaultGUI> openGuis;
    
    /**
     * Map of player UUIDs to their currently open SearchGUI instances.
     */
    private final Map<UUID, com.voidvault.gui.SearchGUI> openSearchGuis;
    
    /**
     * Set of player UUIDs currently navigating between pages.
     * Used to prevent the close event from interfering with page navigation.
     */
    private final Set<UUID> navigatingPlayers;
    
    /**
     * Constructor for VaultManager.
     *
     * @param plugin            The plugin instance
     * @param configManager     Configuration manager
     * @param messageManager    Message manager
     * @param permissionManager Permission manager
     * @param storageManager    Storage manager
     * @param dataCache         Data cache
     */
    public VaultManager(Plugin plugin, ConfigManager configManager, MessageManager messageManager,
                        PermissionManager permissionManager, StorageManager storageManager,
                        DataCache dataCache) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configManager = configManager;
        this.messageManager = messageManager;
        this.permissionManager = permissionManager;
        this.storageManager = storageManager;
        this.dataCache = dataCache;
        this.searchManager = new SearchManager();
        this.openGuis = new ConcurrentHashMap<>();
        this.openSearchGuis = new ConcurrentHashMap<>();
        this.navigatingPlayers = ConcurrentHashMap.newKeySet();
    }
    
    /**
     * Open a player's vault asynchronously.
     * Loads vault data if not cached, then creates and displays the appropriate GUI.
     *
     * @param player The player opening their vault
     * @param page   The page number to open (1-indexed)
     * @return A CompletableFuture that completes when the vault is opened
     */
    public CompletableFuture<Void> openVault(Player player, int page) {
        long startTime = System.currentTimeMillis();
        UUID playerId = player.getUniqueId();
        
        // Validate player is online
        if (!player.isOnline()) {
            logger.warning("Attempted to open vault for offline player: " + player.getName());
            return CompletableFuture.failedFuture(
                new IllegalStateException("Player is not online")
            );
        }
        
        logger.fine("Opening vault for " + player.getName() + " (page " + page + ")");
        
        // Load data asynchronously if not cached
        CompletableFuture<PlayerVaultData> dataFuture;
        if (dataCache.contains(playerId)) {
            // Data already cached, use it
            logger.fine("Using cached vault data for " + player.getName());
            dataFuture = CompletableFuture.completedFuture(
                dataCache.get(playerId).orElseThrow()
            );
        } else {
            // Load data from storage
            logger.fine("Loading vault data from storage for " + player.getName());
            dataFuture = storageManager.loadPlayerData(playerId)
                .thenApply(data -> {
                    // Null check for loaded data
                    if (data == null) {
                        logger.warning("Storage returned null data for " + player.getName() + ", creating empty vault");
                        data = PlayerVaultData.createEmpty(playerId);
                    }

                    // Defensive deep copy before crossing the I/O thread →
                    // main thread boundary and before stashing in the cache.
                    // Both the storage layer and DataCache are shared between
                    // threads; cloning here ensures the main-thread reference
                    // is fully independent of any I/O buffers.
                    PlayerVaultData cached = VaultDataCloner.deepClone(data);
                    dataCache.put(playerId, cached);
                    logger.fine("Cached vault data for " + player.getName());
                    return cached;
                })
                .exceptionally(ex -> {
                    logger.severe("=== Vault Data Loading Failure ===");
                    logger.severe("Player: " + player.getName() + " (UUID: " + playerId + ")");
                    logger.severe("Error Type: " + ex.getClass().getSimpleName());
                    logger.severe("Error Message: " + (ex.getMessage() != null ? ex.getMessage() : "Unknown error"));
                    logger.severe("Stack trace:");
                    logger.log(java.util.logging.Level.SEVERE, "Exception details:", ex);
                    logger.severe("Creating empty vault as fallback");
                    logger.severe("===================================");
                    
                    // Return empty data as fallback
                    PlayerVaultData emptyData = PlayerVaultData.createEmpty(playerId);
                    dataCache.put(playerId, emptyData);
                    return emptyData;
                });
        }
        
        // Once data is loaded, create and open the GUI on the main thread
        return dataFuture.thenAccept(data -> {
            // Additional null check before proceeding
            if (data == null) {
                logger.severe("Vault data is null after loading for " + player.getName() + ", aborting");
                throw new IllegalStateException("Vault data is null after loading");
            }
            
            // Check if player is still online before opening GUI
            if (!player.isOnline()) {
                logger.warning("Player " + player.getName() + " went offline during vault loading, aborting");
                throw new IllegalStateException("Player went offline during vault loading");
            }
            
            SchedulerUtil.runSync(plugin, player, () -> {
                try {
                    // Final online check on main thread
                    if (!player.isOnline()) {
                        logger.warning("Player " + player.getName() + " is offline on main thread, cannot open vault");
                        return;
                    }
                    
                    logger.fine("Creating GUI for " + player.getName() + " (page " + page + ")");
                    
                    // Create the appropriate GUI based on mode
                    VaultGUI gui = createGUI(player, page);
                    
                    // Null check for GUI
                    if (gui == null) {
                        logger.severe("Failed to create GUI for " + player.getName() + " - createGUI returned null");
                        messageManager.send(player, "error.load-failed");
                        return;
                    }
                    
                    // Render the GUI
                    logger.fine("Rendering GUI for " + player.getName());
                    gui.render();
                    
                    // Null check for inventory
                    if (gui.getInventory() == null) {
                        logger.severe("GUI inventory is null for " + player.getName());
                        messageManager.send(player, "error.load-failed");
                        return;
                    }
                    
                    // Track the open GUI
                    openGuis.put(playerId, gui);
                    logger.fine("Tracked open GUI for " + player.getName());
                    
                    // Open the inventory for the player
                    player.openInventory(gui.getInventory());
                    
                    // Record performance metrics
                    long duration = System.currentTimeMillis() - startTime;
                    com.voidvault.util.PerformanceMonitor.recordOperation("vault_open", duration);
                    logger.fine("Successfully opened vault inventory for " + player.getName() + " in " + duration + "ms");
                    
                } catch (Exception ex) {
                    logger.severe("=== GUI Creation/Opening Failure ===");
                    logger.severe("Player: " + player.getName() + " (UUID: " + playerId + ")");
                    logger.severe("Page: " + page);
                    logger.severe("Error Type: " + ex.getClass().getSimpleName());
                    logger.severe("Error Message: " + (ex.getMessage() != null ? ex.getMessage() : "Unknown error"));
                    logger.severe("Stack trace:");
                    logger.log(java.util.logging.Level.SEVERE, "Exception details:", ex);
                    logger.severe("===================================");
                    
                    messageManager.send(player, "error.load-failed");
                }
            });
        });
    }
    
    /**
     * Open another player's vault for an administrator.
     * Loads the target player's vault data and displays it to the admin.
     *
     * @param admin      The administrator opening the vault
     * @param targetUUID The UUID of the target player
     * @param page       The page number to open (1-indexed)
     * @return A CompletableFuture that completes when the vault is opened
     */
    public CompletableFuture<Void> openVaultForAdmin(Player admin, UUID targetUUID, int page) {
        // Validate admin is online
        if (!admin.isOnline()) {
            logger.warning("Attempted to open vault for offline admin: " + admin.getName());
            return CompletableFuture.failedFuture(
                new IllegalStateException("Admin is not online")
            );
        }
        
        logger.info("Admin " + admin.getName() + " opening vault for UUID " + targetUUID + " (page " + page + ")");
        
        // Load target player's data asynchronously
        CompletableFuture<PlayerVaultData> dataFuture;
        if (dataCache.contains(targetUUID)) {
            // Data already cached, use it
            logger.fine("Using cached vault data for target UUID " + targetUUID);
            dataFuture = CompletableFuture.completedFuture(
                dataCache.get(targetUUID).orElseThrow()
            );
        } else {
            // Load data from storage
            logger.fine("Loading vault data from storage for target UUID " + targetUUID);
            dataFuture = storageManager.loadPlayerData(targetUUID)
                .thenApply(data -> {
                    // Null check for loaded data
                    if (data == null) {
                        logger.warning("Storage returned null data for UUID " + targetUUID + ", creating empty vault");
                        data = PlayerVaultData.createEmpty(targetUUID);
                    }

                    // Defensive deep copy before caching — see openVault()
                    // for the rationale.
                    PlayerVaultData cached = VaultDataCloner.deepClone(data);
                    dataCache.put(targetUUID, cached);
                    logger.fine("Cached vault data for target UUID " + targetUUID);
                    return cached;
                })
                .exceptionally(ex -> {
                    logger.severe("=== Admin Vault Data Loading Failure ===");
                    logger.severe("Admin: " + admin.getName() + " (UUID: " + admin.getUniqueId() + ")");
                    logger.severe("Target UUID: " + targetUUID);
                    logger.severe("Error Type: " + ex.getClass().getSimpleName());
                    logger.severe("Error Message: " + (ex.getMessage() != null ? ex.getMessage() : "Unknown error"));
                    logger.severe("Stack trace:");
                    logger.log(java.util.logging.Level.SEVERE, "Exception details:", ex);
                    logger.severe("Creating empty vault as fallback");
                    logger.severe("=========================================");
                    
                    // Return empty data as fallback
                    PlayerVaultData emptyData = PlayerVaultData.createEmpty(targetUUID);
                    dataCache.put(targetUUID, emptyData);
                    return emptyData;
                });
        }
        
        // Once data is loaded, create and open the GUI on the main thread
        return dataFuture.thenAccept(data -> {
            // Additional null check before proceeding
            if (data == null) {
                logger.severe("Vault data is null after loading for target UUID " + targetUUID + ", aborting");
                throw new IllegalStateException("Vault data is null after loading");
            }
            
            // Check if admin is still online before opening GUI
            if (!admin.isOnline()) {
                logger.warning("Admin " + admin.getName() + " went offline during vault loading, aborting");
                throw new IllegalStateException("Admin went offline during vault loading");
            }
            
            SchedulerUtil.runSync(plugin, admin, () -> {
                try {
                    // Final online check on main thread
                    if (!admin.isOnline()) {
                        logger.warning("Admin " + admin.getName() + " is offline on main thread, cannot open vault");
                        return;
                    }
                    
                    logger.fine("Creating admin GUI for " + admin.getName() + " viewing UUID " + targetUUID);
                    
                    // Create a GUI for the admin viewing the target player's vault
                    VaultGUI gui = createGUIForAdmin(admin, targetUUID, page);
                    
                    // Null check for GUI
                    if (gui == null) {
                        logger.severe("Failed to create admin GUI - createGUIForAdmin returned null");
                        messageManager.send(admin, "error.load-failed");
                        return;
                    }
                    
                    // Render the GUI
                    logger.fine("Rendering admin GUI for " + admin.getName());
                    gui.render();
                    
                    // Null check for inventory
                    if (gui.getInventory() == null) {
                        logger.severe("Admin GUI inventory is null");
                        messageManager.send(admin, "error.load-failed");
                        return;
                    }
                    
                    // Track the open GUI (using admin's UUID as the key)
                    openGuis.put(admin.getUniqueId(), gui);
                    logger.fine("Tracked open admin GUI for " + admin.getName());
                    
                    // Open the inventory for the admin
                    admin.openInventory(gui.getInventory());
                    logger.info("Successfully opened vault for admin " + admin.getName() + " viewing UUID " + targetUUID);
                    
                } catch (Exception ex) {
                    logger.severe("=== Admin GUI Creation/Opening Failure ===");
                    logger.severe("Admin: " + admin.getName() + " (UUID: " + admin.getUniqueId() + ")");
                    logger.severe("Target UUID: " + targetUUID);
                    logger.severe("Page: " + page);
                    logger.severe("Error Type: " + ex.getClass().getSimpleName());
                    logger.severe("Error Message: " + (ex.getMessage() != null ? ex.getMessage() : "Unknown error"));
                    logger.severe("Stack trace:");
                    logger.log(java.util.logging.Level.SEVERE, "Exception details:", ex);
                    logger.severe("==========================================");
                    
                    messageManager.send(admin, "error.load-failed");
                }
            });
        });
    }
    
    /**
     * Create the appropriate GUI based on the current plugin mode.
     * Mode-aware GUI selection: SIMPLE or PAGED.
     *
     * @param player The player viewing the vault
     * @param page   The page number to display
     * @return A VaultGUI instance (SimpleVaultGUI or PagedVaultGUI)
     */
    public VaultGUI createGUI(Player player, int page) {
        PluginMode mode = configManager.getPluginMode();
        
        VaultGUI gui = switch (mode) {
            case SIMPLE -> SimpleVaultGUI.create(player, configManager, permissionManager, dataCache);
            case PAGED -> PagedVaultGUI.create(player, page, configManager, permissionManager, dataCache);
        };
        
        // Set VaultManager reference for PagedVaultGUI to enable page navigation
        if (gui instanceof PagedVaultGUI pagedGui) {
            pagedGui.setVaultManager(this);
            
            // Apply active search query if exists
            String searchQuery = searchManager.getSearchQuery(player);
            if (searchQuery != null) {
                pagedGui.setSearchQuery(searchQuery);
            }
        }
        
        return gui;
    }
    
    /**
     * Create a GUI for an admin viewing another player's vault.
     * This is a special case where the viewer (admin) is different from the data owner.
     *
     * @param admin      The administrator viewing the vault
     * @param targetUUID The UUID of the target player whose vault is being viewed
     * @param page       The page number to display
     * @return A VaultGUI instance
     */
    private VaultGUI createGUIForAdmin(Player admin, UUID targetUUID, int page) {
        PluginMode mode = configManager.getPluginMode();
        
        // For admin viewing, we use the admin's permissions but the target's data
        // This is handled by temporarily using the admin as the viewer
        return switch (mode) {
            case SIMPLE -> SimpleVaultGUI.create(admin, configManager, permissionManager, dataCache);
            case PAGED -> PagedVaultGUI.create(admin, page, configManager, permissionManager, dataCache);
        };
    }
    
    /**
     * Close a player's vault and save the data.
     * Saves inventory contents to vault data and persists to storage.
     *
     * @param player The player whose vault is being closed
     * @return A CompletableFuture that completes when the vault is closed and saved
     */
    public CompletableFuture<Void> closeVault(Player player) {
        UUID playerId = player.getUniqueId();
        
        // Check if player is navigating - if so, don't close (navigation handles it)
        if (navigatingPlayers.contains(playerId)) {
            logger.fine("Skipping close for " + player.getName() + " - player is navigating pages");
            return CompletableFuture.completedFuture(null);
        }
        
        // Get the open GUI
        VaultGUI gui = openGuis.remove(playerId);
        
        if (gui == null) {
            // No GUI open for this player
            return CompletableFuture.completedFuture(null);
        }
        
        // Save inventory contents to vault data
        gui.saveInventoryToData();
        
        // Get the updated vault data
        PlayerVaultData vaultData = dataCache.get(playerId).orElse(null);
        
        if (vaultData == null) {
            logger.warning("No vault data found for " + player.getName() + " during close");
            return CompletableFuture.completedFuture(null);
        }
        
        // Save to storage asynchronously if data is dirty
        if (dataCache.isDirty(playerId)) {
            return storageManager.savePlayerData(playerId, vaultData)
                .thenRun(() -> {
                    // Clear dirty flag after successful save
                    dataCache.clearDirty(playerId);
                    logger.fine("Saved vault data for " + player.getName());
                })
                .exceptionally(ex -> {
                    logger.severe("Failed to save vault data for " + player.getName() + ": " + ex.getMessage());
                    logger.log(java.util.logging.Level.SEVERE, "Exception details:", ex);
                    return null;
                });
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Get the currently open GUI for a player.
     *
     * @param player The player to check
     * @return The VaultGUI instance, or null if no GUI is open
     */
    public VaultGUI getOpenGUI(Player player) {
        return openGuis.get(player.getUniqueId());
    }
    
    /**
     * Check if a player has a vault GUI open.
     *
     * @param player The player to check
     * @return true if the player has a vault open, false otherwise
     */
    public boolean hasOpenVault(Player player) {
        return openGuis.containsKey(player.getUniqueId());
    }
    
    /**
     * Get all players with open vaults.
     *
     * @return A map of player UUIDs to their open VaultGUI instances
     */
    public Map<UUID, VaultGUI> getOpenGuis() {
        return new ConcurrentHashMap<>(openGuis);
    }
    
    /**
     * Navigate to a different page in the player's vault.
     * <p>
     * Saves the current page, then either renders the new page in-place on
     * the existing inventory (preferred — keeps the player's hotbar slot
     * and cursor item stable) or falls back to a close-then-reopen cycle.
     * The in-place path avoids the bug where clicking Next/Previous Page
     * would teleport the player's cursor back to the middle slot.
     * </p>
     *
     * @param player     The player navigating pages
     * @param targetPage The page number to navigate to
     */
    public void navigateToPage(Player player, int targetPage) {
        UUID playerId = player.getUniqueId();

        // Validate target page
        if (targetPage < 1) {
            logger.warning("Invalid target page " + targetPage + " for " + player.getName());
            return;
        }

        // Clamp to the player's actual page allowance so navigation cannot
        // walk past pages the player does not own.
        int maxAllowedPages = permissionManager.getMaxPages(player);
        if (targetPage > maxAllowedPages) {
            logger.fine("Refusing to navigate " + player.getName() + " to page " + targetPage
                    + " (max " + maxAllowedPages + ")");
            return;
        }

        // Get the current GUI
        VaultGUI currentGui = openGuis.get(playerId);

        if (currentGui == null) {
            logger.warning("No open GUI found for " + player.getName() + " during page navigation");
            return;
        }

        // Mark player as navigating so the close handler ignores the
        // extremely brief moment where the inventory briefly closes if we
        // fall back to the close-then-reopen path.
        navigatingPlayers.add(playerId);

        try {
            // Save the current page before swapping contents.
            currentGui.saveInventoryToData();

            // Fast path: in-place render for two PAGED-mode GUIs that share
            // inventory size (they always do — 54-slot).
            if (currentGui instanceof PagedVaultGUI currentPaged) {
                // Reuse the player's current inventory for the new page so
                // that the cursor and hotbar slot are not reset.
                currentPaged.setPage(targetPage);
                currentPaged.render();
                // Force the client to refresh the contents so the new items
                // and arrows actually appear immediately.
                player.updateInventory();
                return;
            }

            // Fallback path (e.g. SIMPLE mode swapping pages). This is rare
            // because SIMPLE mode only ever has one page, but it keeps the
            // behaviour correct if anything else ever calls navigateToPage
            // with a non-PAGED GUI.
            final VaultGUI newGui = createGUI(player, targetPage);
            newGui.render();
            player.closeInventory();
            SchedulerUtil.runSync(plugin, player, () -> {
                try {
                    openGuis.put(playerId, newGui);
                    player.openInventory(newGui.getInventory());
                } catch (Exception ex) {
                    logger.severe("Failed to open page " + targetPage + " for "
                            + player.getName() + ": " + ex.getMessage());
                    logger.log(java.util.logging.Level.SEVERE, "Exception details:", ex);
                    messageManager.send(player, "error.load-failed");
                } finally {
                    navigatingPlayers.remove(playerId);
                }
            });
            return;
        } catch (Exception ex) {
            navigatingPlayers.remove(playerId);
            logger.severe("Error during page navigation for " + player.getName() + ": " + ex.getMessage());
            ex.printStackTrace();
            return;
        } finally {
            // For the in-place path above the flag was already left set so
            // we still need to clear it here. (The finally clause only
            // fires when the in-place path returned normally.)
            navigatingPlayers.remove(playerId);
        }
    }
    
    /**
     * Close all open vaults.
     * This should be called during plugin shutdown to ensure all data is saved.
     *
     * @return A CompletableFuture that completes when all vaults are closed
     */
    public CompletableFuture<Void> closeAllVaults() {
        logger.info("Closing all open vaults...");
        
        // Create a list of close futures
        CompletableFuture<?>[] closeFutures = openGuis.keySet().stream()
            .map(playerId -> {
                Player player = plugin.getServer().getPlayer(playerId);
                if (player != null) {
                    return closeVault(player);
                }
                return CompletableFuture.completedFuture(null);
            })
            .toArray(CompletableFuture[]::new);
        
        // Wait for all closes to complete
        return CompletableFuture.allOf(closeFutures)
            .thenRun(() -> {
                openGuis.clear();
                logger.info("All vaults closed successfully");
            });
    }
    
    /**
     * Set the open GUI for a player (used for shulker preview).
     *
     * @param player The player
     * @param gui    The GUI to track
     */
    public void setOpenGUI(Player player, VaultGUI gui) {
        openGuis.put(player.getUniqueId(), gui);
    }
    
    /**
     * Get the ConfigManager instance.
     *
     * @return The config manager
     */
    public ConfigManager getConfigManager() {
        return configManager;
    }
    
    /**
     * Get the PermissionManager instance.
     *
     * @return The permission manager
     */
    public PermissionManager getPermissionManager() {
        return permissionManager;
    }
    
    /**
     * Get the DataCache instance.
     *
     * @return The data cache
     */
    public DataCache getDataCache() {
        return dataCache;
    }
    
    /**
     * Get the SearchManager instance.
     *
     * @return The search manager
     */
    public SearchManager getSearchManager() {
        return searchManager;
    }
    
    /**
     * Initiate search mode for a player.
     * Opens the search GUI for input.
     *
     * @param player The player initiating search
     * @param page   The page they're searching on
     */
    public void initiateSearch(Player player, int page) {
        // Close the current vault
        player.closeInventory();

        // Open search GUI. Pass the configuration + message managers so the
        // overlay can pull its title, icons, lore and chat prompts from the
        // plugin's configurable sources rather than hardcoded strings.
        com.voidvault.gui.SearchGUI searchGUI = new com.voidvault.gui.SearchGUI(
                player, page, this, configManager, messageManager);
        openSearchGuis.put(player.getUniqueId(), searchGUI);
        searchGUI.open();
    }
    
    /**
     * Get the open SearchGUI for a player.
     *
     * @param player The player
     * @return The SearchGUI, or null if none open
     */
    public com.voidvault.gui.SearchGUI getOpenSearchGUI(Player player) {
        return openSearchGuis.get(player.getUniqueId());
    }
    
    /**
     * Remove the open SearchGUI for a player.
     *
     * @param player The player
     */
    public void removeSearchGUI(Player player) {
        openSearchGuis.remove(player.getUniqueId());
    }
}
