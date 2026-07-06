package com.voidvault.listener;

import com.voidvault.config.MessageManager;
import com.voidvault.manager.VaultManager;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.logging.Logger;

/**
 * Listener for Ender Chest interactions.
 * Intercepts right-clicks on Ender Chest blocks and opens the VoidVault GUI instead.
 * Handles data loading errors with user-friendly messages.
 */
public class EnderChestListener implements Listener {
    private final VaultManager vaultManager;
    private final MessageManager messageManager;
    private final Logger logger;
    
    /**
     * Constructor for EnderChestListener.
     *
     * @param vaultManager   The vault manager for opening vaults
     * @param messageManager The message manager for sending messages
     * @param logger         The logger for error logging
     */
    public EnderChestListener(VaultManager vaultManager, MessageManager messageManager, Logger logger) {
        this.vaultManager = vaultManager;
        this.messageManager = messageManager;
        this.logger = logger;
    }
    
    /**
     * Handle player interactions with Ender Chest blocks.
     * Cancels the default Ender Chest opening and opens the VoidVault GUI instead.
     *
     * @param event The PlayerInteractEvent
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEnderChestOpen(PlayerInteractEvent event) {
        // Only handle right-click on blocks
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        
        // Get the clicked block
        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) {
            return;
        }
        
        // Check if it's an Ender Chest
        if (clickedBlock.getType() != Material.ENDER_CHEST) {
            return;
        }
        
        // Cancel the default Ender Chest opening
        event.setCancelled(true);
        
        Player player = event.getPlayer();
        
        // Play chest opening sound at block location
        clickedBlock.getWorld().playSound(clickedBlock.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 1.0f);
        
        // Open the VoidVault asynchronously
        vaultManager.openVault(player, 1).exceptionally(ex -> {
            // Handle data loading errors
            logger.severe("Failed to open vault for " + player.getName() + ": " + ex.getMessage());
            logger.log(java.util.logging.Level.SEVERE, "Exception details:", ex);
            messageManager.send(player, "error.load-failed");
            return null;
        });
    }
}
