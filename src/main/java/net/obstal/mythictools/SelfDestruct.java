package net.obstal.mythictools;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/** Scans online players in small batches so thousands of timed items stay cheap. */
public final class SelfDestruct {

    private final MythicTools plugin;
    private BukkitTask task;
    private int cursor = 0;

    public SelfDestruct(MythicTools plugin) {
        this.plugin = plugin;
    }

    public void start() {
        long period = Math.max(5L, plugin.getConfig().getLong("self-destruct.check-interval-ticks", 20L));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, period);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    private void tick() {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (online.isEmpty()) { cursor = 0; return; }
        int batch = Math.max(1, plugin.getConfig().getInt("self-destruct.players-per-tick", 8));
        if (cursor >= online.size()) cursor = 0;
        int end = Math.min(online.size(), cursor + batch);
        for (int i = cursor; i < end; i++) scan(online.get(i));
        cursor = end;
    }

    private void scan(Player player) {
        long now = System.currentTimeMillis();
        PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            Long expire = Tools.expiry(item);
            if (expire == null) continue;
            if (now >= expire) {
                String name = Tools.plainName(item);
                inv.setItem(slot, null);
                player.sendMessage(Tools.c(plugin.prefix()
                        + plugin.getConfig().getString("messages.expired", "&c%item% expired.").replace("%item%", name)));
                if (plugin.getConfig().getBoolean("settings.sounds", true)) {
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
                }
            } else {
                Tools.updateTimerLore(item, expire - now, plugin.getConfig());
            }
        }
    }
}
