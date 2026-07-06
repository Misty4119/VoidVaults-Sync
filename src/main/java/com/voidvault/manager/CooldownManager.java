package com.voidvault.manager;

import com.voidvault.config.ConfigManager;
import com.voidvault.model.CooldownEntry;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Manages cooldowns for remote vault access.
 * Tracks cooldown entries per player and provides automatic expiry cleanup.
 * Thread-safe implementation using ConcurrentHashMap.
 */
public class CooldownManager {
    private final Plugin plugin;
    private final ConfigManager configManager;
    private final Logger logger;
    private final Map<UUID, CooldownEntry> cooldowns;
    
    /**
     * Creates a new CooldownManager instance.
     *
     * @param plugin        The plugin instance
     * @param configManager The configuration manager for cooldown duration
     */
    public CooldownManager(Plugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.logger = plugin.getLogger();
        this.cooldowns = new ConcurrentHashMap<>();
        
        // Start automatic cleanup task
        startCleanupTask();
    }
    
    /**
     * Checks if a player is currently on cooldown.
     *
     * @param player The player to check
     * @return true if the player is on cooldown, false otherwise
     */
    public boolean isOnCooldown(Player player) {
        return isOnCooldown(player.getUniqueId());
    }
    
    /**
     * Checks if a player UUID is currently on cooldown.
     *
     * @param playerId The player's UUID
     * @return true if the player is on cooldown, false otherwise
     */
    public boolean isOnCooldown(UUID playerId) {
        CooldownEntry entry = cooldowns.get(playerId);
        
        if (entry == null) {
            return false;
        }
        
        // Check if cooldown has expired
        if (entry.isExpired()) {
            // Remove expired cooldown
            cooldowns.remove(playerId);
            return false;
        }
        
        return true;
    }
    
    /**
     * Sets a cooldown for the specified player using the configured duration.
     *
     * @param player The player to set cooldown for
     */
    public void setCooldown(Player player) {
        setCooldown(player.getUniqueId());
    }
    
    /**
     * Sets a cooldown for the specified player UUID using the configured duration.
     *
     * @param playerId The player's UUID
     */
    public void setCooldown(UUID playerId) {
        int durationSeconds = configManager.getCooldownSeconds();
        setCooldown(playerId, durationSeconds);
    }
    
    /**
     * Sets a cooldown for the specified player with a custom duration.
     *
     * @param player          The player to set cooldown for
     * @param durationSeconds The cooldown duration in seconds
     */
    public void setCooldown(Player player, int durationSeconds) {
        setCooldown(player.getUniqueId(), durationSeconds);
    }
    
    /**
     * Sets a cooldown for the specified player UUID with a custom duration.
     *
     * @param playerId        The player's UUID
     * @param durationSeconds The cooldown duration in seconds
     */
    public void setCooldown(UUID playerId, int durationSeconds) {
        if (durationSeconds <= 0) {
            logger.warning("Attempted to set cooldown with invalid duration: " + durationSeconds);
            return;
        }
        
        CooldownEntry entry = CooldownEntry.create(playerId, durationSeconds);
        cooldowns.put(playerId, entry);
        
        logger.fine("Set cooldown for player " + playerId + " for " + durationSeconds + " seconds");
    }
    
    /**
     * Gets the remaining cooldown time in seconds for a player.
     *
     * @param player The player to check
     * @return The remaining seconds, or 0 if not on cooldown
     */
    public long getRemainingSeconds(Player player) {
        return getRemainingSeconds(player.getUniqueId());
    }
    
    /**
     * Gets the remaining cooldown time in seconds for a player UUID.
     *
     * @param playerId The player's UUID
     * @return The remaining seconds, or 0 if not on cooldown
     */
    public long getRemainingSeconds(UUID playerId) {
        CooldownEntry entry = cooldowns.get(playerId);
        
        if (entry == null) {
            return 0;
        }
        
        // Check if expired
        if (entry.isExpired()) {
            cooldowns.remove(playerId);
            return 0;
        }
        
        return entry.getRemainingSeconds();
    }
    
    /**
     * Gets the remaining cooldown time formatted as a human-readable string.
     *
     * @param player The player to check
     * @return A formatted string like "1m 30s" or "45s", or "0s" if not on cooldown
     */
    public String getFormattedRemaining(Player player) {
        return getFormattedRemaining(player.getUniqueId());
    }
    
    /**
     * Gets the remaining cooldown time formatted as a human-readable string.
     *
     * @param playerId The player's UUID
     * @return A formatted string like "1m 30s" or "45s", or "0s" if not on cooldown
     */
    public String getFormattedRemaining(UUID playerId) {
        CooldownEntry entry = cooldowns.get(playerId);
        
        if (entry == null || entry.isExpired()) {
            if (entry != null) {
                cooldowns.remove(playerId);
            }
            return "0s";
        }
        
        return entry.getFormattedRemaining();
    }
    
    /**
     * Clears the cooldown for a specific player.
     *
     * @param player The player to clear cooldown for
     */
    public void clearCooldown(Player player) {
        clearCooldown(player.getUniqueId());
    }
    
    /**
     * Clears the cooldown for a specific player UUID.
     *
     * @param playerId The player's UUID
     */
    public void clearCooldown(UUID playerId) {
        CooldownEntry removed = cooldowns.remove(playerId);
        if (removed != null) {
            logger.fine("Cleared cooldown for player " + playerId);
        }
    }
    
    /**
     * Clears all cooldowns.
     */
    public void clearAllCooldowns() {
        int count = cooldowns.size();
        cooldowns.clear();
        logger.info("Cleared " + count + " cooldown(s)");
    }
    
    /**
     * Gets the number of active cooldowns.
     *
     * @return The number of players currently on cooldown
     */
    public int getActiveCooldownCount() {
        // Clean up expired entries first
        cleanupExpiredCooldowns();
        return cooldowns.size();
    }
    
    /**
     * Starts the automatic cleanup task that removes expired cooldowns.
     * Runs every 30 seconds to clean up expired entries.
     */
    private void startCleanupTask() {
        // Run cleanup every 30 seconds (600 ticks)
        com.voidvault.util.SchedulerUtil.runAsyncRepeating(plugin, this::cleanupExpiredCooldowns, 600L, 600L);
        
        logger.info("Cooldown cleanup task started (runs every 30 seconds)");
    }
    
    /**
     * Cleans up expired cooldown entries from the map.
     * This method is called automatically by the cleanup task.
     */
    private void cleanupExpiredCooldowns() {
        int removedCount = 0;
        
        // Iterate through all cooldowns and remove expired ones
        for (Map.Entry<UUID, CooldownEntry> entry : cooldowns.entrySet()) {
            if (entry.getValue().isExpired()) {
                cooldowns.remove(entry.getKey());
                removedCount++;
            }
        }
        
        if (removedCount > 0) {
            logger.fine("Cleaned up " + removedCount + " expired cooldown(s)");
        }
    }
    
    /**
     * Stops the automatic cleanup task.
     * Should be called when the plugin is disabled.
     */
    public void shutdown() {
        logger.info("Cooldown cleanup task stopped");
        
        // Clear all cooldowns
        cooldowns.clear();
    }
    
    /**
     * Gets a copy of all active cooldowns for debugging purposes.
     *
     * @return A map of player UUIDs to their cooldown entries
     */
    public Map<UUID, CooldownEntry> getActiveCooldowns() {
        // Clean up expired entries first
        cleanupExpiredCooldowns();
        return Map.copyOf(cooldowns);
    }
}
