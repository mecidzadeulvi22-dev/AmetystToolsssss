package net.obstal.mythictools;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MTCommand implements CommandExecutor, TabCompleter {

    private final MythicTools plugin;

    public MTCommand(MythicTools plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mythictools.admin")) {
            sender.sendMessage(Tools.c(plugin.prefix() + plugin.getConfig().getString("messages.no-permission")));
            return true;
        }
        if (args.length == 0) return usage(sender);

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage(Tools.c(plugin.prefix() + plugin.getConfig().getString("messages.reloaded")));
            }
            case "give" -> {
                if (args.length < 2) return usage(sender);
                Tools.Type type = Tools.Type.byId(args[1]);
                if (type == null) {
                    sender.sendMessage(Tools.c(plugin.prefix() + plugin.getConfig()
                            .getString("messages.unknown-tool", "").replace("%tool%", args[1])));
                    return true;
                }
                Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2])
                        : (sender instanceof Player p ? p : null);
                if (target == null) return usage(sender);
                ItemStack item = Tools.create(type, plugin.getConfig());
                if (item == null) return usage(sender);
                target.getInventory().addItem(item);
                sender.sendMessage(Tools.c(plugin.prefix() + plugin.getConfig().getString("messages.given", "")
                        .replace("%tool%", type.id).replace("%player%", target.getName())));
            }
            case "selfdestruct", "sd" -> {
                if (!(sender instanceof Player player)) return usage(sender);
                boolean delayed = args.length >= 2 && args[1].equals("-1");
                String raw = delayed ? (args.length >= 3 ? args[2] : null) : (args.length >= 2 ? args[1] : null);
                long millis = Tools.parse(raw);
                if (millis <= 0) {
                    player.sendMessage(Tools.c(plugin.prefix() + plugin.getConfig().getString("messages.bad-time")));
                    return true;
                }
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand.getType().isAir()) {
                    player.sendMessage(Tools.c(plugin.prefix() + plugin.getConfig().getString("messages.hold-item")));
                    return true;
                }
                Tools.applyTimer(hand, millis, delayed, plugin.getConfig());
                String path = delayed ? "messages.timer-delayed" : "messages.timer-set";
                player.sendMessage(Tools.c(plugin.prefix() + plugin.getConfig().getString(path, "")
                        .replace("%time%", Tools.format(millis))));
            }
            default -> usage(sender);
        }
        return true;
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(Tools.c(plugin.prefix() + plugin.getConfig().getString("messages.usage")));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("mythictools.admin")) return out;
        if (args.length == 1) {
            out.add("give"); out.add("selfdestruct"); out.add("reload");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Tools.Type type : Tools.Type.values()) out.add(type.id);
        } else if (args.length == 2 && args[0].toLowerCase(Locale.ROOT).startsWith("s")) {
            out.add("-1"); out.add("1d"); out.add("5h"); out.add("30m");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
