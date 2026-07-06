package com.voidvault.command;

import com.voidvault.config.ConfigManager;
import com.voidvault.config.MessageManager;
import com.voidvault.gui.VaultGUI;
import com.voidvault.manager.VaultManager;
import com.voidvault.model.PlayerVaultData;
import com.voidvault.storage.DataCache;
import com.voidvault.storage.StorageManager;
import com.voidvault.util.ValidationUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Command executor for VoidVault admin functions.
 * Handles /voidvault command with subcommands: open, reload, setslots, setpages.
 * Uses Java 21 switch expressions for clean subcommand routing.
 */
public class VoidVaultCommand implements CommandExecutor {
    private final Plugin plugin;
    private final Logger logger;
    private final ConfigManager configManager;
    private final MessageManager messageManager;
    private final VaultManager vaultManager;
    private final DataCache dataCache;
    private final StorageManager storageManager;
    
    // Permission nodes
    private static final String PERM_ADMIN_OPEN = "voidvaults.admin.openother";
    private static final String PERM_ADMIN_RELOAD = "voidvaults.admin.reload";
    private static final String PERM_ADMIN_SETSLOTS = "voidvaults.admin.setslots";
    private static final String PERM_ADMIN_SETPAGES = "voidvaults.admin.setpages";
    
    public VoidVaultCommand(Plugin plugin, ConfigManager configManager, MessageManager messageManager,
                           VaultManager vaultManager, DataCache dataCache, StorageManager storageManager) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configManager = configManager;
        this.messageManager = messageManager;
        this.vaultManager = vaultManager;
        this.dataCache = dataCache;
        this.storageManager = storageManager;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // No arguments - show usage
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        
        // Route to appropriate subcommand using switch expression
        String subcommand = args[0].toLowerCase();
        
        return switch (subcommand) {
            case "open" -> handleOpen(sender, args);
            case "reload" -> handleReload(sender);
            case "setslots" -> handleSetSlots(sender, args);
            case "setpages" -> handleSetPages(sender, args);
            case "stats", "statistics" -> handleStats(sender);
            case "cleancache" -> handleCleanCache(sender);
            default -> {
                sendUsage(sender);
                yield true;
            }
        };
    }
    
    /**
     * Handle /voidvault open <player> [page]
     * Opens another player's vault for viewing/editing.
     */
    private boolean handleOpen(CommandSender sender, String[] args) {
        // Check permission
        if (!sender.hasPermission(PERM_ADMIN_OPEN)) {
            messageManager.send(sender, "commands.no-permission");
            return true;
        }
        
        // Must be a player
        if (!(sender instanceof Player admin)) {
            messageManager.send(sender, "commands.player-only");
            return true;
        }
        
        // Validate arguments
        if (args.length < 2) {
            messageManager.send(sender, "commands.usage-open");
            return true;
        }
        
        String targetName = args[1];
        int page = 1;

        // Parse optional page argument
        if (args.length >= 3) {
            Integer parsedPage = ValidationUtil.parsePositiveInteger(args[2]);
            if (parsedPage == null) {
                messageManager.send(sender, "commands.invalid-number",
                    MessageManager.placeholders()
                        .add("input", args[2])
                        .build());
                return true;
            }

            int maxAllowedPages = configManager.getMaxPages();
            page = parsedPage;
            if (page < 1 || page > maxAllowedPages) {
                messageManager.send(sender, "commands.invalid-page",
                    MessageManager.placeholders()
                        .add("page", page)
                        .add("max", maxAllowedPages)
                        .build());
                sender.sendMessage("§cPage must be between 1 and " + maxAllowedPages + ".");
                return true;
            }
        }
        
        // Get target player UUID
        @SuppressWarnings("deprecation")
        OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(targetName);
        
        if (!targetPlayer.hasPlayedBefore() && !targetPlayer.isOnline()) {
            messageManager.send(sender, "commands.player-not-found",
                MessageManager.placeholders()
                    .add("player", targetName)
                    .build());
            return true;
        }
        
        UUID targetUUID = targetPlayer.getUniqueId();
        final int finalPage = page;
        
        // Open vault asynchronously
        vaultManager.openVaultForAdmin(admin, targetUUID, finalPage).thenRun(() -> {
            if (finalPage == 1) {
                messageManager.send(admin, "commands.open-other",
                    MessageManager.placeholders()
                        .add("player", targetPlayer.getName())
                        .build());
            } else {
                messageManager.send(admin, "commands.open-other-page",
                    MessageManager.placeholders()
                        .add("player", targetPlayer.getName())
                        .add("page", finalPage)
                        .build());
            }
        }).exceptionally(ex -> {
            logger.severe("Failed to open vault for admin: " + ex.getMessage());
            logger.log(java.util.logging.Level.SEVERE, "Exception details:", ex);
            messageManager.send(admin, "error.load-failed");
            return null;
        });
        
        return true;
    }
    
    /**
     * Handle /voidvault reload
     * Reloads configuration files.
     */
    private boolean handleReload(CommandSender sender) {
        // Check permission
        if (!sender.hasPermission(PERM_ADMIN_RELOAD)) {
            messageManager.send(sender, "commands.no-permission");
            return true;
        }
        
        try {
            // Reload configurations
            configManager.reload();
            messageManager.reload();
            
            messageManager.send(sender, "commands.reload-success");
            logger.info("Configuration reloaded by " + sender.getName());
        } catch (Exception ex) {
            logger.severe("Failed to reload configuration: " + ex.getMessage());
            logger.log(java.util.logging.Level.SEVERE, "Exception details:", ex);
            messageManager.send(sender, "error.generic");
        }
        
        return true;
    }
    
    /**
     * Handle /voidvault setslots <player> <amount>
     * Overrides a player's slot permissions with a custom value.
     */
    private boolean handleSetSlots(CommandSender sender, String[] args) {
        // Check permission
        if (!sender.hasPermission(PERM_ADMIN_SETSLOTS)) {
            messageManager.send(sender, "commands.no-permission");
            return true;
        }
        
        // Validate arguments
        if (args.length < 3) {
            messageManager.send(sender, "commands.usage-setslots");
            return true;
        }
        
        String targetName = args[1];
        String amountStr = args[2];
        
        // Validate amount
        Integer amount = ValidationUtil.parseNonNegativeInteger(amountStr);
        if (amount == null) {
            messageManager.send(sender, "commands.invalid-number",
                MessageManager.placeholders()
                    .add("input", amountStr)
                    .build());
            return true;
        }
        
        // Validate slot range (0 to reset, or 9-54)
        if (amount != 0 && (amount < 9 || amount > 54)) {
            messageManager.send(sender, "commands.invalid-number",
                MessageManager.placeholders()
                    .add("input", amountStr)
                    .build());
            return true;
        }
        
        // Get target player UUID
        @SuppressWarnings("deprecation")
        OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(targetName);
        
        if (!targetPlayer.hasPlayedBefore() && !targetPlayer.isOnline()) {
            messageManager.send(sender, "commands.player-not-found",
                MessageManager.placeholders()
                    .add("player", targetName)
                    .build());
            return true;
        }
        
        UUID targetUUID = targetPlayer.getUniqueId();
        
        // Load player's vault data from cache or storage
        dataCache.get(targetUUID).ifPresentOrElse(
            currentData -> {
                // Update PlayerVaultData with new customSlots value
                PlayerVaultData updatedData = currentData.withCustomSlots(amount);
                
                // Save updated data through StorageManager and mark as dirty
                dataCache.putAndMarkDirty(targetUUID, updatedData);
                
                // Persist to storage immediately
                storageManager.savePlayerData(targetUUID, updatedData)
                    .thenRun(() -> {
                        dataCache.clearDirty(targetUUID);
                        logger.fine("Saved custom slots for " + targetPlayer.getName());
                        
                        // Refresh open GUIs after setslots
                        // Check if target player has vault open
                        Player onlineTarget = plugin.getServer().getPlayer(targetUUID);
                        if (onlineTarget != null && vaultManager.hasOpenVault(onlineTarget)) {
                            // If vault is open, close and reopen with new slot count
                            VaultGUI currentGui = vaultManager.getOpenGUI(onlineTarget);
                            int currentPage = currentGui != null ? currentGui.getPage() : 1;
                            
                            // Close current vault
                            onlineTarget.closeInventory();
                            
                            // Reopen with new slot count
                            vaultManager.openVault(onlineTarget, currentPage);
                        }
                    })
                    .exceptionally(ex -> {
                        logger.severe("Failed to save custom slots for " + targetPlayer.getName() + ": " + ex.getMessage());
                        return null;
                    });
                
                // Send success message to command sender
                String displayAmount = amount == 0 ? "default (permissions)" : String.valueOf(amount);
                messageManager.send(sender, "commands.setslots-success",
                    MessageManager.placeholders()
                        .add("player", targetPlayer.getName())
                        .add("amount", displayAmount)
                        .build());
                
                logger.info(sender.getName() + " set slots for " + targetPlayer.getName() + " to " + displayAmount);
            },
            () -> {
                // Data not in cache, load from storage first
                storageManager.loadPlayerData(targetUUID)
                    .thenAccept(loadedData -> {
                        // Update PlayerVaultData with new customSlots value
                        PlayerVaultData updatedData = loadedData.withCustomSlots(amount);
                        
                        // Save updated data through StorageManager and mark as dirty
                        dataCache.putAndMarkDirty(targetUUID, updatedData);
                        
                        // Persist to storage immediately
                        storageManager.savePlayerData(targetUUID, updatedData)
                            .thenRun(() -> {
                                dataCache.clearDirty(targetUUID);
                                logger.fine("Saved custom slots for " + targetPlayer.getName());
                                
                                // Refresh open GUIs after setslots
                                // Check if target player has vault open
                                Player onlineTarget = plugin.getServer().getPlayer(targetUUID);
                                if (onlineTarget != null && vaultManager.hasOpenVault(onlineTarget)) {
                                    // If vault is open, close and reopen with new slot count
                                    VaultGUI currentGui = vaultManager.getOpenGUI(onlineTarget);
                                    int currentPage = currentGui != null ? currentGui.getPage() : 1;
                                    
                                    // Close current vault
                                    onlineTarget.closeInventory();
                                    
                                    // Reopen with new slot count
                                    vaultManager.openVault(onlineTarget, currentPage);
                                }
                            })
                            .exceptionally(ex -> {
                                logger.severe("Failed to save custom slots for " + targetPlayer.getName() + ": " + ex.getMessage());
                                return null;
                            });
                        
                        // Send success message to command sender
                        String displayAmount = amount == 0 ? "default (permissions)" : String.valueOf(amount);
                        messageManager.send(sender, "commands.setslots-success",
                            MessageManager.placeholders()
                                .add("player", targetPlayer.getName())
                                .add("amount", displayAmount)
                                .build());
                        
                        logger.info(sender.getName() + " set slots for " + targetPlayer.getName() + " to " + displayAmount);
                    })
                    .exceptionally(ex -> {
                        logger.severe("Failed to load vault data for " + targetPlayer.getName() + ": " + ex.getMessage());
                        messageManager.send(sender, "error.load-failed");
                        return null;
                    });
            }
        );
        
        return true;
    }
    
    /**
     * Handle /voidvault setpages <player> <amount>
     * Overrides a player's page permissions with a custom value.
     */
    private boolean handleSetPages(CommandSender sender, String[] args) {
        // Check permission
        if (!sender.hasPermission(PERM_ADMIN_SETPAGES)) {
            messageManager.send(sender, "commands.no-permission");
            return true;
        }
        
        // Validate arguments
        if (args.length < 3) {
            messageManager.send(sender, "commands.usage-setpages");
            return true;
        }
        
        String targetName = args[1];
        String amountStr = args[2];
        
        // Validate amount
        Integer amount = ValidationUtil.parseNonNegativeInteger(amountStr);
        if (amount == null) {
            messageManager.send(sender, "commands.invalid-number",
                MessageManager.placeholders()
                    .add("input", amountStr)
                    .build());
            return true;
        }
        
        // Validate page range (0 to reset, or 1..max-pages)
        int maxAllowedPages = configManager.getMaxPages();
        if (amount != 0 && (amount < 1 || amount > maxAllowedPages)) {
            messageManager.send(sender, "commands.invalid-number",
                MessageManager.placeholders()
                    .add("input", amountStr)
                    .build());
            sender.sendMessage("§cPages must be between 1 and " + maxAllowedPages + ", or 0 to reset.");
            return true;
        }
        
        // Get target player UUID
        @SuppressWarnings("deprecation")
        OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(targetName);
        
        if (!targetPlayer.hasPlayedBefore() && !targetPlayer.isOnline()) {
            messageManager.send(sender, "commands.player-not-found",
                MessageManager.placeholders()
                    .add("player", targetName)
                    .build());
            return true;
        }
        
        UUID targetUUID = targetPlayer.getUniqueId();
        
        // Load player's vault data from cache or storage
        dataCache.get(targetUUID).ifPresentOrElse(
            currentData -> {
                // Update PlayerVaultData with new customPages value
                PlayerVaultData updatedData = currentData.withCustomPages(amount);
                
                // Save updated data through StorageManager and mark as dirty
                dataCache.putAndMarkDirty(targetUUID, updatedData);
                
                // Persist to storage immediately
                storageManager.savePlayerData(targetUUID, updatedData)
                    .thenRun(() -> {
                        dataCache.clearDirty(targetUUID);
                        logger.fine("Saved custom pages for " + targetPlayer.getName());
                        
                        // Refresh open GUIs after setpages
                        // Check if target player has vault open
                        Player onlineTarget = plugin.getServer().getPlayer(targetUUID);
                        if (onlineTarget != null && vaultManager.hasOpenVault(onlineTarget)) {
                            // If vault is open, refresh GUI to show/hide navigation buttons
                            VaultGUI currentGui = vaultManager.getOpenGUI(onlineTarget);
                            int currentPage = currentGui != null ? currentGui.getPage() : 1;
                            
                            // Close current vault
                            onlineTarget.closeInventory();
                            
                            // Reopen to refresh navigation buttons
                            vaultManager.openVault(onlineTarget, currentPage);
                        }
                    })
                    .exceptionally(ex -> {
                        logger.severe("Failed to save custom pages for " + targetPlayer.getName() + ": " + ex.getMessage());
                        return null;
                    });
                
                // Send success message to command sender
                String displayAmount = amount == 0 ? "default (permissions)" : String.valueOf(amount);
                messageManager.send(sender, "commands.setpages-success",
                    MessageManager.placeholders()
                        .add("player", targetPlayer.getName())
                        .add("amount", displayAmount)
                        .build());
                
                logger.info(sender.getName() + " set pages for " + targetPlayer.getName() + " to " + displayAmount);
            },
            () -> {
                // Data not in cache, load from storage first
                storageManager.loadPlayerData(targetUUID)
                    .thenAccept(loadedData -> {
                        // Update PlayerVaultData with new customPages value
                        PlayerVaultData updatedData = loadedData.withCustomPages(amount);
                        
                        // Save updated data through StorageManager and mark as dirty
                        dataCache.putAndMarkDirty(targetUUID, updatedData);
                        
                        // Persist to storage immediately
                        storageManager.savePlayerData(targetUUID, updatedData)
                            .thenRun(() -> {
                                dataCache.clearDirty(targetUUID);
                                logger.fine("Saved custom pages for " + targetPlayer.getName());
                                
                                // Refresh open GUIs after setpages
                                // Check if target player has vault open
                                Player onlineTarget = plugin.getServer().getPlayer(targetUUID);
                                if (onlineTarget != null && vaultManager.hasOpenVault(onlineTarget)) {
                                    // If vault is open, refresh GUI to show/hide navigation buttons
                                    VaultGUI currentGui = vaultManager.getOpenGUI(onlineTarget);
                                    int currentPage = currentGui != null ? currentGui.getPage() : 1;
                                    
                                    // Close current vault
                                    onlineTarget.closeInventory();
                                    
                                    // Reopen to refresh navigation buttons
                                    vaultManager.openVault(onlineTarget, currentPage);
                                }
                            })
                            .exceptionally(ex -> {
                                logger.severe("Failed to save custom pages for " + targetPlayer.getName() + ": " + ex.getMessage());
                                return null;
                            });
                        
                        // Send success message to command sender
                        String displayAmount = amount == 0 ? "default (permissions)" : String.valueOf(amount);
                        messageManager.send(sender, "commands.setpages-success",
                            MessageManager.placeholders()
                                .add("player", targetPlayer.getName())
                                .add("amount", displayAmount)
                                .build());
                        
                        logger.info(sender.getName() + " set pages for " + targetPlayer.getName() + " to " + displayAmount);
                    })
                    .exceptionally(ex -> {
                        logger.severe("Failed to load vault data for " + targetPlayer.getName() + ": " + ex.getMessage());
                        messageManager.send(sender, "error.load-failed");
                        return null;
                    });
            }
        );
        
        return true;
    }
    
    /**
     * Handle /voidvault stats
     * Shows cache and performance statistics.
     */
    private boolean handleStats(CommandSender sender) {
        // Check permission
        if (!sender.hasPermission(PERM_ADMIN_RELOAD)) {
            messageManager.send(sender, "commands.no-permission");
            return true;
        }
        
        sender.sendMessage("§6=== VoidVault Statistics ===");
        sender.sendMessage("§e" + dataCache.getStatistics());
        sender.sendMessage("§eOpen GUIs: §f" + vaultManager.getOpenGuis().size());
        
        // Memory usage
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
        long maxMemory = runtime.maxMemory() / 1024 / 1024;
        long freeMemory = runtime.freeMemory() / 1024 / 1024;
        sender.sendMessage("§eMemory: §f" + usedMemory + "MB used / " + maxMemory + "MB max (§a" + freeMemory + "MB free§f)");
        
        // Performance metrics
        sender.sendMessage("§6=== Performance Metrics ===");
        var metrics = com.voidvault.util.PerformanceMonitor.getAllMetrics();
        if (metrics.isEmpty()) {
            sender.sendMessage("§7No performance data available yet");
        } else {
            metrics.forEach((name, metric) -> {
                sender.sendMessage(String.format("§e%s: §f%s", name, metric.toString()));
            });
        }
        
        return true;
    }
    
    /**
     * Handle /voidvault cleancache
     * Removes cached data for offline players.
     */
    private boolean handleCleanCache(CommandSender sender) {
        // Check permission
        if (!sender.hasPermission(PERM_ADMIN_RELOAD)) {
            messageManager.send(sender, "commands.no-permission");
            return true;
        }
        
        // Get online player UUIDs
        Set<UUID> onlinePlayerIds = plugin.getServer().getOnlinePlayers().stream()
            .map(Player::getUniqueId)
            .collect(java.util.stream.Collectors.toSet());
        
        // Clean cache
        int removedCount = dataCache.cleanupOfflinePlayers(onlinePlayerIds);
        
        sender.sendMessage("§aCache cleanup complete. Removed " + removedCount + " offline player(s) from cache.");
        logger.info(sender.getName() + " cleaned cache, removed " + removedCount + " entries");
        
        return true;
    }
    
    /**
     * Send interactive usage information with clickable commands and hover tooltips.
     */
    private void sendUsage(CommandSender sender) {
        // Header
        sender.sendMessage("");
        sender.sendMessage("§5§l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");
        sender.sendMessage("§5§l                    VoidVault Commands");
        sender.sendMessage("§5§l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");
        sender.sendMessage("");
        
        // Commands with clickable text and hover tooltips
        if (sender.hasPermission(PERM_ADMIN_OPEN)) {
            sendClickableCommand(sender, 
                "/voidvaults open <player> [page]",
                "Open another player's vault",
                "§7Opens a player's vault for viewing/editing\n§7Example: §e/voidvaults open Steve 1\n§a§lClick to suggest!",
                "/voidvaults open ");
        }
        
        if (sender.hasPermission(PERM_ADMIN_RELOAD)) {
            sendClickableCommand(sender,
                "/voidvaults reload",
                "Reload plugin configuration",
                "§7Reloads config.yml and messages.yml\n§7without restarting the server\n§a§lClick to run!",
                "/voidvaults reload");
        }
        
        if (sender.hasPermission(PERM_ADMIN_SETSLOTS)) {
            sendClickableCommand(sender,
                "/voidvaults setslots <player> <amount>",
                "Set custom slot count",
                "§7Override a player's slot permissions\n§7Amount: 9-54 or 0 to reset\n§7Example: §e/voidvaults setslots Steve 54\n§a§lClick to suggest!",
                "/voidvaults setslots ");
        }
        
        if (sender.hasPermission(PERM_ADMIN_SETPAGES)) {
            sendClickableCommand(sender,
                "/voidvaults setpages <player> <amount>",
                "Set custom page count",
                "§7Override a player's page permissions\n§7Amount: 1-" + configManager.getMaxPages() + " or 0 to reset\n§7Example: §e/voidvaults setpages Steve " + configManager.getMaxPages() + "\n§a§lClick to suggest!",
                "/voidvaults setpages ");
        }
        
        if (sender.hasPermission(PERM_ADMIN_RELOAD)) {
            sendClickableCommand(sender,
                "/voidvaults stats",
                "Show statistics",
                "§7Display cache, memory, and performance stats\n§7Useful for monitoring plugin health\n§a§lClick to run!",
                "/voidvaults stats");
            
            sendClickableCommand(sender,
                "/voidvaults cleancache",
                "Clean cache",
                "§7Remove offline players from cache\n§7Frees up memory and prevents leaks\n§a§lClick to run!",
                "/voidvaults cleancache");
        }
        
        // Footer
        sender.sendMessage("");
        sender.sendMessage("§7§oHover over commands for more info • Click to use");
        sender.sendMessage("§5§l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");
        sender.sendMessage("");
    }
    
    /**
     * Send a clickable command with hover tooltip.
     */
    private void sendClickableCommand(CommandSender sender, String command, String description, 
                                      String hoverText, String clickCommand) {
        if (!(sender instanceof Player)) {
            // Fallback for console
            sender.sendMessage("  §e" + command + " §7- " + description);
            return;
        }
        
        // Create interactive text component
        net.kyori.adventure.text.Component message = net.kyori.adventure.text.Component.text()
            .append(net.kyori.adventure.text.Component.text("  "))
            .append(net.kyori.adventure.text.Component.text("● ", net.kyori.adventure.text.format.NamedTextColor.DARK_PURPLE))
            .append(net.kyori.adventure.text.Component.text(command, net.kyori.adventure.text.format.NamedTextColor.YELLOW)
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                    net.kyori.adventure.text.Component.text(hoverText)
                        .replaceText(net.kyori.adventure.text.TextReplacementConfig.builder()
                            .matchLiteral("§")
                            .replacement("")
                            .build())))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(clickCommand)))
            .append(net.kyori.adventure.text.Component.text(" - ", net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY))
            .append(net.kyori.adventure.text.Component.text(description, net.kyori.adventure.text.format.NamedTextColor.GRAY))
            .build();
        
        sender.sendMessage(message);
    }
}
