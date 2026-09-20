package net.obstal.mythictools;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class MythicTools extends JavaPlugin {

    private Economy economy;
    private SelfDestruct selfDestruct;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Tools.init(this);
        hookVault();

        selfDestruct = new SelfDestruct(this);
        selfDestruct.start();

        getServer().getPluginManager().registerEvents(new ToolListener(this), this);

        MTCommand command = new MTCommand(this);
        if (getCommand("mythictools") != null) {
            getCommand("mythictools").setExecutor(command);
            getCommand("mythictools").setTabCompleter(command);
        }
        getLogger().info("Enabled (economy: " + (economy != null ? "Vault" : "none") + ")");
    }

    @Override
    public void onDisable() {
        if (selfDestruct != null) selfDestruct.stop();
    }

    private void hookVault() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
    }

    public Economy economy() {
        return economy;
    }

    public String prefix() {
        return getConfig().getString("messages.prefix", "");
    }
}
