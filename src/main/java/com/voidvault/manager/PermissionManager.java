package com.voidvault.manager;

import com.voidvault.config.ConfigManager;
import com.voidvault.config.PluginMode;
import com.voidvault.model.PlayerVaultData;
import com.voidvault.storage.DataCache;
import org.bukkit.entity.Player;

/**
 * Manages permission checks for vault access, storage capacity, and features.
 * Implements mode-aware logic for slot and page calculations.
 */
public class PermissionManager {
    private final ConfigManager configManager;
    private final DataCache dataCache;
    
    // Permission nodes
    private static final String PERM_SLOTS_PREFIX = "voidvaults.slots.";
    private static final String PERM_PAGE_PREFIX = "voidvaults.page.";
    private static final String PERM_REMOTE = "voidvaults.remote";
    private static final String PERM_BYPASS_COOLDOWN = "voidvaults.remote.bypasscooldown";
    
    // Valid slot sizes
    private static final int[] SIMPLE_MODE_SLOTS = {54, 45, 36, 27, 18, 9};
    private static final int[] PAGED_MODE_SLOTS = {52, 45, 36, 27, 18, 9};
    
    public PermissionManager(ConfigManager configManager, DataCache dataCache) {
        this.configManager = configManager;
        this.dataCache = dataCache;
    }
    
    /**
     * Calculate the maximum number of slots a player can access based on their permissions.
     * Logic differs based on plugin mode:
     * - SIMPLE mode: Returns 9-54 slots based on voidvaults.slots.X permissions
     * - PAGED mode: Returns 9-52 slots (52 total storage slots)
     * 
     * Prioritizes custom slot values set by admins over permission-based calculations.
     *
     * @param player The player to check
     * @return The maximum number of accessible slots
     */
    public int getMaxSlots(Player player) {
        // Check PlayerVaultData.customSlots() first
        PlayerVaultData data = dataCache.get(player.getUniqueId()).orElse(null);
        if (data != null && data.hasCustomSlots()) {
            // Return custom value if > 0
            return data.customSlots();
        }
        
        // Otherwise fall through to permission/config checks
        PluginMode mode = configManager.getPluginMode();
        
        return switch (mode) {
            case SIMPLE -> {
                // Check permissions in descending order (54, 45, 36, 27, 18, 9)
                for (int size : SIMPLE_MODE_SLOTS) {
                    if (player.hasPermission(PERM_SLOTS_PREFIX + size)) {
                        yield size;
                    }
                }
                // Default to configured default
                yield configManager.getDefaultSlots();
            }
            case PAGED -> {
                // In PAGED mode, max is 52 slots (52 storage slots)
                // Check permissions in descending order (52, 45, 36, 27, 18, 9)
                for (int size : new int[]{52, 45, 36, 27, 18, 9}) {
                    if (player.hasPermission(PERM_SLOTS_PREFIX + size)) {
                        yield size;
                    }
                }
                // Default to configured default (ensure it's valid for PAGED mode)
                int defaultSlots = configManager.getDefaultSlots();
                // In PAGED mode, max is 52, so cap the default if needed
                yield Math.min(defaultSlots, 52);
            }
        };
    }
    
    /**
     * Calculate the maximum number of pages a player can access.
     * Only applicable in PAGED mode. Maximum is capped at 5 pages.
     * 
     * Prioritizes custom page values set by admins over permission-based calculations.
     *
     * @param player The player to check
     * @return The maximum number of accessible pages (1 if SIMPLE mode, max 5 for PAGED)
     */
    public int getMaxPages(Player player) {
        PluginMode mode = configManager.getPluginMode();

        // SIMPLE mode only has 1 page
        if (mode == PluginMode.SIMPLE) {
            return 1;
        }

        // Absolute upper bound configured in config.yml (defaults to 10).
        // Custom grants, permission tiers, and default-pages are all
        // clamped to this cap so admins can raise or lower it freely.
        int globalMax = configManager.getMaxPages();
        if (globalMax < 1) {
            globalMax = 1;
        }

        // Check PlayerVaultData.customPages() first
        PlayerVaultData data = dataCache.get(player.getUniqueId()).orElse(null);
        if (data != null && data.hasCustomPages()) {
            // Return custom value if > 0, but cap at the configured global max
            return Math.min(data.customPages(), globalMax);
        }

        // Otherwise fall through to permission/config checks.
        // PAGED mode: scan the configured tier list (1..globalMax) for the
        // highest tier the player actually owns.
        for (int page = globalMax; page >= 1; page--) {
            if (player.hasPermission(PERM_PAGE_PREFIX + page)) {
                return page;
            }
        }

        // No page permissions found, use configured default (capped at globalMax)
        return Math.min(configManager.getDefaultPages(), globalMax);
    }
    
    /**
     * Check if the player has permission to use remote access commands.
     *
     * @param player The player to check
     * @return true if the player can use /echest, /pv, /vault
     */
    public boolean hasRemoteAccess(Player player) {
        return player.hasPermission(PERM_REMOTE);
    }
    
    /**
     * Check if the player can bypass the remote access cooldown.
     *
     * @param player The player to check
     * @return true if the player can bypass cooldowns
     */
    public boolean canBypassCooldown(Player player) {
        return player.hasPermission(PERM_BYPASS_COOLDOWN);
    }
}
