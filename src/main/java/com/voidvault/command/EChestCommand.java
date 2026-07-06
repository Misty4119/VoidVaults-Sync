package com.voidvault.command;

import com.voidvault.config.ConfigManager;
import com.voidvault.config.MessageManager;
import com.voidvault.config.PluginMode;
import com.voidvault.manager.CooldownManager;
import com.voidvault.manager.PermissionManager;
import com.voidvault.manager.VaultManager;
import com.voidvault.util.ValidationUtil;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/**
 * Command executor for remote vault access.
 * Handles /echest, /pv, and /vault commands.
 * Supports an optional page argument (e.g. /pv 3) to jump directly to a page
 * in PAGED mode. The page argument is ignored in SIMPLE mode.
 * Implements permission checks, cooldown enforcement, and economy integration.
 */
public class EChestCommand implements CommandExecutor {
    private final Plugin plugin;
    private final Logger logger;
    private final VaultManager vaultManager;
    private final PermissionManager permissionManager;
    private final CooldownManager cooldownManager;
    private final MessageManager messageManager;
    private final ConfigManager configManager;

    public EChestCommand(Plugin plugin, VaultManager vaultManager, PermissionManager permissionManager,
                         CooldownManager cooldownManager, MessageManager messageManager,
                         ConfigManager configManager) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.vaultManager = vaultManager;
        this.permissionManager = permissionManager;
        this.cooldownManager = cooldownManager;
        this.messageManager = messageManager;
        this.configManager = configManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Must be a player
        if (!(sender instanceof Player player)) {
            logger.fine("Non-player sender attempted to use remote access command: " + sender.getName());
            messageManager.send(sender, "commands.player-only");
            return true;
        }

        logger.fine("Player " + player.getName() + " attempting remote vault access via /" + label);

        // Check remote access permission
        if (!permissionManager.hasRemoteAccess(player)) {
            logger.fine("Player " + player.getName() + " denied remote access: missing voidvaults.remote permission");
            messageManager.send(player, "commands.no-permission");
            return true;
        }

        // Determine target page.
        // SIMPLE mode ignores any supplied page argument.
        // PAGED mode honors the optional argument and clamps to the player's max.
        int targetPage = 1;
        PluginMode mode = configManager.getPluginMode();
        if (mode == PluginMode.PAGED && args.length >= 1) {
            Integer parsed = ValidationUtil.parsePositiveInteger(args[0]);
            if (parsed == null) {
                messageManager.send(player, "commands.invalid-number",
                    MessageManager.placeholders()
                        .add("input", args[0])
                        .build());
                return true;
            }
            int maxAllowedPages = permissionManager.getMaxPages(player);
            if (parsed > maxAllowedPages) {
                messageManager.send(player, "remote-access.page-out-of-range",
                    MessageManager.placeholders()
                        .add("page", parsed)
                        .add("max", maxAllowedPages)
                        .build());
                return true;
            }
            targetPage = parsed;
            logger.fine("Player " + player.getName() + " requested page " + targetPage + " via /" + label);
        }

        // Check cooldown (unless player has bypass permission)
        if (!permissionManager.canBypassCooldown(player)) {
            if (cooldownManager.isOnCooldown(player)) {
                long remainingSeconds = cooldownManager.getRemainingSeconds(player);
                logger.fine("Player " + player.getName() + " on cooldown: " + remainingSeconds + " seconds remaining");
                messageManager.send(player, "remote-access.on-cooldown",
                    MessageManager.placeholders()
                        .add("seconds", remainingSeconds)
                        .build());
                return true;
            }
        } else {
            logger.fine("Player " + player.getName() + " bypassing cooldown check");
        }

        // Set cooldown (unless player has bypass permission)
        if (!permissionManager.canBypassCooldown(player)) {
            cooldownManager.setCooldown(player);
            logger.fine("Cooldown set for " + player.getName());
        }

        // Play chest opening sound at player location
        player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 1.0f);

        final int finalPage = targetPage;

        // Open vault asynchronously
        logger.info("Opening remote vault for " + player.getName() + " (page " + finalPage + ")");
        messageManager.send(player, "vault.opening");

        vaultManager.openVault(player, finalPage).exceptionally(ex -> {
            // Detailed error logging for different failure scenarios
            String errorType = ex.getClass().getSimpleName();
            String errorMessage = ex.getMessage() != null ? ex.getMessage() : "Unknown error";

            logger.severe("=== Remote Vault Access Failure ===");
            logger.severe("Player: " + player.getName() + " (UUID: " + player.getUniqueId() + ")");
            logger.severe("Command: /" + label);
            logger.severe("Error Type: " + errorType);
            logger.severe("Error Message: " + errorMessage);
            logger.severe("Stack trace:");
            logger.log(java.util.logging.Level.SEVERE, "Exception details:", ex);
            logger.severe("===================================");

            // Send user-friendly error message
            messageManager.send(player, "error.load-failed");

            // Clear cooldown since the operation failed
            if (!permissionManager.canBypassCooldown(player)) {
                cooldownManager.clearCooldown(player);
                logger.info("Cleared cooldown for " + player.getName() + " due to vault opening failure");
            }

            return null;
        }).thenRun(() -> {
            // Success logging
            logger.info("Successfully opened remote vault for " + player.getName());
        });

        return true;
    }
}
