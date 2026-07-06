package com.voidvault.listener;

import com.voidvault.manager.VaultManager;
import com.voidvault.model.PlayerVaultData;
import com.voidvault.storage.DataCache;
import com.voidvault.storage.StorageManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Listener for player connection events.
 * Handles loading vault data when players join and saving/cleaning up data when players quit.
 * Ensures data is properly cached and persisted across player sessions.
 */
public class PlayerConnectionListener implements Listener {
    private final StorageManager storageManager;
    private final DataCache dataCache;
    private final VaultManager vaultManager;
    private final Logger logger;
    
    /**
     * Constructor for PlayerConnectionListener.
     *
     * @param storageManager The storage manager for loading/saving data
     * @param dataCache      The data cache for caching player data
     * @param vaultManager   The vault manager for closing open vaults
     * @param logger         The logger for error logging
     */
    public PlayerConnectionListener(StorageManager storageManager, DataCache dataCache,
                                    VaultManager vaultManager, Logger logger) {
        this.storageManager = storageManager;
        this.dataCache = dataCache;
        this.vaultManager = vaultManager;
        this.logger = logger;
    }
    
    /**
     * Handle player join events.
     * Loads the player's vault data asynchronously and caches it for quick access.
     *
     * @param event The PlayerJoinEvent
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // Load player data asynchronously
        storageManager.loadPlayerData(playerId)
            .thenAccept(data -> {
                // Cache the loaded data
                dataCache.put(playerId, data);
                logger.fine("Loaded vault data for " + player.getName());
            })
            .exceptionally(ex -> {
                // Log error and create empty data as fallback
                logger.severe("Failed to load vault data for " + player.getName() + ": " + ex.getMessage());
                logger.log(java.util.logging.Level.SEVERE, "Exception details:", ex);
                
                // Create and cache empty data to prevent issues
                PlayerVaultData emptyData = PlayerVaultData.createEmpty(playerId);
                dataCache.put(playerId, emptyData);
                
                return null;
            });
    }
    
    /**
     * Handle player quit events.
     * Closes any open vaults, saves the player's vault data, and removes it from cache.
     *
     * @param event The PlayerQuitEvent
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // Close any open vault for this player
        if (vaultManager.hasOpenVault(player)) {
            vaultManager.closeVault(player).exceptionally(ex -> {
                logger.severe("Failed to close vault for " + player.getName() + " on quit: " + ex.getMessage());
                logger.log(java.util.logging.Level.SEVERE, "Exception details:", ex);
                return null;
            });
        }
        
        // Get the cached data
        PlayerVaultData data = dataCache.get(playerId).orElse(null);
        
        if (data == null) {
            // No data to save
            logger.fine("No cached data found for " + player.getName() + " on quit");
            return;
        }
        
        // Save the data asynchronously
        storageManager.savePlayerData(playerId, data)
            .thenRun(() -> {
                // Clear dirty flag after successful save
                dataCache.clearDirty(playerId);
                
                // Remove from cache
                dataCache.remove(playerId);
                
                logger.fine("Saved and removed vault data for " + player.getName());
            })
            .exceptionally(ex -> {
                // Log error but still remove from cache
                logger.severe("Failed to save vault data for " + player.getName() + " on quit: " + ex.getMessage());
                logger.log(java.util.logging.Level.SEVERE, "Exception details:", ex);
                
                // Still remove from cache to prevent memory leaks
                dataCache.remove(playerId);
                
                return null;
            });
    }
}
