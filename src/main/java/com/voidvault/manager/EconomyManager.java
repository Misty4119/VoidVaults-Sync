package com.voidvault.manager;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.logging.Logger;

/**
 * Manages economy integration through Vault API.
 * Handles economy transactions for remote vault access fees.
 */
public class EconomyManager {
    private final Plugin plugin;
    private final Logger logger;
    private Economy economy;
    private boolean enabled;
    
    public EconomyManager(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.enabled = false;
    }
    
    /**
     * Hook into Vault API and initialize economy integration.
     * Should be called during plugin enable after Vault is loaded.
     */
    public void initialize() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            logger.info("Vault not found. Economy features disabled.");
            enabled = false;
            return;
        }
        
        RegisteredServiceProvider<Economy> rsp = plugin.getServer()
            .getServicesManager()
            .getRegistration(Economy.class);
            
        if (rsp == null) {
            enabled = false;
            return;
        }
        
        economy = rsp.getProvider();
        enabled = true;
        logger.info("Economy integration enabled via Vault (" + economy.getName() + ")");
    }
    
    /**
     * Check if economy integration is available.
     * 
     * @return true if Vault and an economy provider are present
     */
    public boolean isEnabled() {
        return enabled && economy != null;
    }
    
    /**
     * Check if a player has sufficient balance.
     * 
     * @param player the player to check
     * @param amount the amount to check for
     * @return true if the player has at least the specified amount
     */
    public boolean hasBalance(Player player, double amount) {
        if (!isEnabled()) {
            return true; // If economy is disabled, always return true
        }
        
        try {
            return economy.has(player, amount);
        } catch (Exception e) {
            logger.warning("Error checking balance for " + player.getName() + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Withdraw money from a player's account.
     * 
     * @param player the player to withdraw from
     * @param amount the amount to withdraw
     * @return true if the transaction was successful
     */
    public boolean withdraw(Player player, double amount) {
        if (!isEnabled()) {
            return true; // If economy is disabled, always succeed
        }
        
        if (amount <= 0) {
            return true; // No charge needed
        }
        
        try {
            if (!economy.has(player, amount)) {
                return false;
            }
            
            var response = economy.withdrawPlayer(player, amount);
            
            if (response.transactionSuccess()) {
                logger.fine("Withdrew " + formatAmount(amount) + " from " + player.getName());
                return true;
            } else {
                logger.warning("Failed to withdraw from " + player.getName() + ": " + response.errorMessage);
                return false;
            }
        } catch (Exception e) {
            logger.warning("Error withdrawing from " + player.getName() + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Format an amount using the economy provider's format.
     * 
     * @param amount the amount to format
     * @return formatted string representation of the amount
     */
    public String formatAmount(double amount) {
        if (!isEnabled()) {
            return String.format("%.2f", amount);
        }
        
        try {
            return economy.format(amount);
        } catch (Exception e) {
            return String.format("%.2f", amount);
        }
    }
    
    /**
     * Deposit money into a player's account.
     * 
     * @param player the player to deposit to
     * @param amount the amount to deposit
     * @return true if the transaction was successful
     */
    public boolean deposit(Player player, double amount) {
        if (!isEnabled()) {
            return true; // If economy is disabled, always succeed
        }
        
        if (amount <= 0) {
            return true; // No deposit needed
        }
        
        try {
            var response = economy.depositPlayer(player, amount);
            
            if (response.transactionSuccess()) {
                logger.fine("Deposited " + formatAmount(amount) + " to " + player.getName());
                return true;
            } else {
                logger.warning("Failed to deposit to " + player.getName() + ": " + response.errorMessage);
                return false;
            }
        } catch (Exception e) {
            logger.warning("Error depositing to " + player.getName() + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get the economy provider name.
     * 
     * @return the name of the economy provider, or "None" if disabled
     */
    public String getProviderName() {
        if (!isEnabled()) {
            return "None";
        }
        return economy.getName();
    }
}
