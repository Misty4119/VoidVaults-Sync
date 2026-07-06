package com.voidvault.command;

import com.voidvault.config.ConfigManager;
import com.voidvault.config.PluginMode;
import com.voidvault.manager.PermissionManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Tab completion handler for VoidVaults commands.
 * Provides context-aware suggestions for subcommands, player names, and numeric values.
 */
public class VoidVaultTabCompleter implements TabCompleter {

    // Subcommands for /voidvaults
    private static final List<String> SUBCOMMANDS = Arrays.asList(
        "open", "reload", "setslots", "setpages", "stats", "statistics", "cleancache"
    );

    // Slot size suggestions
    private static final List<String> SLOT_SUGGESTIONS = Arrays.asList(
        "9", "18", "27", "36", "45", "52", "54", "0"
    );

    // Default page number suggestions for admin /voidvaults commands
    private static final List<String> PAGE_SUGGESTIONS = Arrays.asList(
        "1", "2", "3", "4", "5", "0"
    );

    // Maximum number of page suggestions we are willing to produce for a single
    // /pv completion. Even if config.max-pages is 999, we cap the suggestion
    // list at this length so clients don't get flooded with thousands of entries.
    private static final int REMOTE_PAGE_SUGGESTION_CAP = 20;

    private final ConfigManager configManager;
    private final PermissionManager permissionManager;

    public VoidVaultTabCompleter(Plugin plugin, ConfigManager configManager, PermissionManager permissionManager) {
        this.configManager = configManager;
        this.permissionManager = permissionManager;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        // Handle /voidvaults command (and aliases)
        if (command.getName().equalsIgnoreCase("voidvaults") ||
            command.getName().equalsIgnoreCase("vvs") ||
            command.getName().equalsIgnoreCase("vv")) {
            return handleVoidVaultCompletion(sender, args);
        }

        // Handle /echest, /pv, /vault commands.
        // First (and only) optional argument is the page number to jump to.
        if (command.getName().equalsIgnoreCase("echest") ||
            command.getName().equalsIgnoreCase("pv") ||
            command.getName().equalsIgnoreCase("vault")) {
            return handleRemoteAccessCompletion(sender, args);
        }

        return completions;
    }

    /**
     * Handle tab completion for /echest, /pv, /vault.
     * In PAGED mode the first argument is a page number, filtered by the
     * player's actual page allowance. In SIMPLE mode there is nothing useful
     * to suggest so we return an empty list.
     */
    private List<String> handleRemoteAccessCompletion(CommandSender sender, String[] args) {
        if (configManager.getPluginMode() != PluginMode.PAGED) {
            return new ArrayList<>();
        }

        if (args.length != 1) {
            return new ArrayList<>();
        }

        int maxPages = sender instanceof Player player
            ? permissionManager.getMaxPages(player)
            : configManager.getMaxPages();

        // Cap the suggestion list so we never produce thousands of entries.
        int suggestionCount = Math.min(maxPages, REMOTE_PAGE_SUGGESTION_CAP);
        if (suggestionCount < 1) {
            return new ArrayList<>();
        }

        String partial = args[0];
        return IntStream.rangeClosed(1, suggestionCount)
            .mapToObj(Integer::toString)
            .filter(option -> option.startsWith(partial))
            .sorted()
            .collect(Collectors.toList());
    }
    
    /**
     * Handle tab completion for /voidvaults command.
     */
    private List<String> handleVoidVaultCompletion(CommandSender sender, String[] args) {
        List<String> completions = new ArrayList<>();
        
        // First argument: subcommand
        if (args.length == 1) {
            return filterMatches(SUBCOMMANDS, args[0]);
        }
        
        // Get the subcommand
        String subcommand = args[0].toLowerCase();
        
        return switch (subcommand) {
            case "open" -> handleOpenCompletion(sender, args);
            case "setslots" -> handleSetSlotsCompletion(sender, args);
            case "setpages" -> handleSetPagesCompletion(sender, args);
            case "reload", "stats", "statistics", "cleancache" -> completions; // No arguments
            default -> completions;
        };
    }
    
    /**
     * Handle tab completion for /voidvaults open <player> [page]
     */
    private List<String> handleOpenCompletion(CommandSender sender, String[] args) {
        // Second argument: player name
        if (args.length == 2) {
            return getOnlinePlayerNames(args[1]);
        }
        
        // Third argument: page number
        if (args.length == 3) {
            return filterMatches(PAGE_SUGGESTIONS, args[2]);
        }
        
        return new ArrayList<>();
    }
    
    /**
     * Handle tab completion for /voidvaults setslots <player> <amount>
     */
    private List<String> handleSetSlotsCompletion(CommandSender sender, String[] args) {
        // Second argument: player name
        if (args.length == 2) {
            return getOnlinePlayerNames(args[1]);
        }
        
        // Third argument: slot amount
        if (args.length == 3) {
            return filterMatches(SLOT_SUGGESTIONS, args[2]);
        }
        
        return new ArrayList<>();
    }
    
    /**
     * Handle tab completion for /voidvaults setpages <player> <amount>
     */
    private List<String> handleSetPagesCompletion(CommandSender sender, String[] args) {
        // Second argument: player name
        if (args.length == 2) {
            return getOnlinePlayerNames(args[1]);
        }
        
        // Third argument: page amount
        if (args.length == 3) {
            return filterMatches(PAGE_SUGGESTIONS, args[2]);
        }
        
        return new ArrayList<>();
    }
    
    /**
     * Get list of online player names that match the partial input.
     */
    private List<String> getOnlinePlayerNames(String partial) {
        return Bukkit.getOnlinePlayers().stream()
            .map(Player::getName)
            .filter(name -> name.toLowerCase().startsWith(partial.toLowerCase()))
            .sorted()
            .collect(Collectors.toList());
    }
    
    /**
     * Filter a list of strings to only include those that start with the partial input.
     */
    private List<String> filterMatches(List<String> options, String partial) {
        return options.stream()
            .filter(option -> option.toLowerCase().startsWith(partial.toLowerCase()))
            .sorted()
            .collect(Collectors.toList());
    }
}
