package net.obstal.mythictools;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Tools {

    public enum Type {
        DRILL("drill"), AXE("axe"), SHOVEL("shovel"), SELLAXE("sellaxe"),
        BUCKET("bucket"), MULTITOOL("multitool"), FIREWORK("firework");

        public final String id;
        Type(String id) { this.id = id; }

        public static Type byId(String s) {
            if (s == null) return null;
            for (Type t : values()) if (t.id.equalsIgnoreCase(s)) return t;
            return null;
        }
    }

    public static NamespacedKey KEY_TOOL, KEY_EXPIRE, KEY_DELAY, KEY_TIMER_LINE;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final Pattern TIME = Pattern.compile("(\\d+)([dhms])");

    private Tools() {}

    public static void init(Plugin plugin) {
        KEY_TOOL = new NamespacedKey(plugin, "tool_type");
        KEY_EXPIRE = new NamespacedKey(plugin, "expire_at");
        KEY_DELAY = new NamespacedKey(plugin, "delay_ms");
        KEY_TIMER_LINE = new NamespacedKey(plugin, "timer_line");
    }

    /* ---------- text ---------- */

    public static Component c(String legacy) {
        return LEGACY.deserialize(legacy == null ? "" : legacy).decoration(TextDecoration.ITALIC, false);
    }

    public static String plain(Component comp) {
        return PlainTextComponentSerializer.plainText().serialize(comp);
    }

    public static String plainName(ItemStack item) {
        ItemMeta m = item.getItemMeta();
        if (m != null && m.hasDisplayName()) return plain(m.displayName());
        return item.getType().name();
    }

    /* ---------- item factory ---------- */

    public static ItemStack create(Type type, FileConfiguration cfg) {
        ConfigurationSection sec = cfg.getConfigurationSection("tools." + type.id);
        if (sec == null) return null;

        Material mat = Material.matchMaterial(sec.getString("material", "STONE"));
        if (mat == null) mat = Material.STONE;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(c(sec.getString("name", type.id)));

        List<Component> lore = new ArrayList<>();
        for (String line : sec.getStringList("lore")) lore.add(c(line));
        meta.lore(lore);

        for (String raw : sec.getStringList("enchants")) {
            String[] parts = raw.split(":");
            Enchantment ench = enchant(parts[0]);
            int lvl = parts.length > 1 ? parseInt(parts[1], 1) : 1;
            if (ench != null) meta.addEnchant(ench, lvl, true);
        }

        if (cfg.getBoolean("settings.unbreakable-tools", true)) {
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(KEY_TOOL, PersistentDataType.STRING, type.id);

        item.setItemMeta(meta);
        return item;
    }

    private static Enchantment enchant(String name) {
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT).contains(":")
                ? name.toLowerCase(Locale.ROOT) : "minecraft:" + name.toLowerCase(Locale.ROOT));
        if (key == null) return null;
        try {
            return RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    /* ---------- tool identification ---------- */

    public static Type typeOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(KEY_TOOL, PersistentDataType.STRING);
        return Type.byId(id);
    }

    /* ---------- self-destruct data ---------- */

    public static Long expiry(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(KEY_EXPIRE, PersistentDataType.LONG);
    }

    public static Long pendingDelay(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(KEY_DELAY, PersistentDataType.LONG);
    }

    /** Applies a timer. If delayed, the countdown only starts on first use. */
    public static void applyTimer(ItemStack item, long millis, boolean delayed, FileConfiguration cfg) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (delayed) {
            pdc.set(KEY_DELAY, PersistentDataType.LONG, millis);
            pdc.remove(KEY_EXPIRE);
        } else {
            pdc.set(KEY_EXPIRE, PersistentDataType.LONG, System.currentTimeMillis() + millis);
            pdc.remove(KEY_DELAY);
        }
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        String line = cfg.getString("self-destruct.lore-format", "&7Expires in: &c%time%")
                .replace("%time%", format(millis));
        if (pdc.has(KEY_TIMER_LINE, PersistentDataType.BYTE) && !lore.isEmpty()) lore.set(lore.size() - 1, c(line));
        else lore.add(c(line));
        pdc.set(KEY_TIMER_LINE, PersistentDataType.BYTE, (byte) 1);
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    /** Starts a pending (delayed) timer when the item is first used. */
    public static boolean startPending(ItemStack item, FileConfiguration cfg) {
        Long delay = pendingDelay(item);
        if (delay == null) return false;
        long offset = cfg.getLong("self-destruct.default-delay-minutes", 1L) * 60_000L;
        applyTimer(item, delay + offset, false, cfg);
        return true;
    }

    /** Rewrites the countdown line; returns false if nothing changed. */
    public static void updateTimerLore(ItemStack item, long remaining, FileConfiguration cfg) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        if (!meta.getPersistentDataContainer().has(KEY_TIMER_LINE, PersistentDataType.BYTE)) return;
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        if (lore.isEmpty()) return;
        Component line = c(cfg.getString("self-destruct.lore-format", "&7Expires in: &c%time%")
                .replace("%time%", format(remaining)));
        if (plain(lore.get(lore.size() - 1)).equals(plain(line))) return; // avoid needless packets
        lore.set(lore.size() - 1, line);
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    /* ---------- time ---------- */

    /** Parses strings like 1d5h30m10s. Returns -1 when invalid. */
    public static long parse(String input) {
        if (input == null || input.isEmpty()) return -1;
        Matcher m = TIME.matcher(input.toLowerCase(Locale.ROOT));
        long total = 0;
        boolean found = false;
        while (m.find()) {
            found = true;
            long value = Long.parseLong(m.group(1));
            total += switch (m.group(2)) {
                case "d" -> value * 86_400_000L;
                case "h" -> value * 3_600_000L;
                case "m" -> value * 60_000L;
                default -> value * 1_000L;
            };
        }
        return found && total > 0 ? total : -1;
    }

    public static String format(long millis) {
        long s = Math.max(0, millis) / 1000;
        long d = s / 86400; s %= 86400;
        long h = s / 3600; s %= 3600;
        long m = s / 60; s %= 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (d == 0 && (s > 0 || sb.isEmpty())) sb.append(s).append("s");
        return sb.toString().trim();
    }
}
