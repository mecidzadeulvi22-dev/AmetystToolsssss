package net.obstal.mythictools;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.function.Predicate;

public final class ToolListener implements Listener {

    private final MythicTools plugin;
    private final Set<UUID> busy = new HashSet<>();

    public ToolListener(MythicTools plugin) {
        this.plugin = plugin;
    }

    /* ================= mining ================= */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (busy.contains(player.getUniqueId())) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        Tools.Type type = Tools.typeOf(tool);
        if (type == null) return;
        if (!player.hasPermission("mythictools.use")) return;

        activate(tool);
        Block origin = event.getBlock();

        switch (type) {
            case DRILL -> area(player, origin, tool, m -> Tag.MINEABLE_PICKAXE.isTagged(m),
                    plugin.getConfig().getInt("settings.max-drill-blocks", 9));
            case SHOVEL -> area(player, origin, tool, m -> Tag.MINEABLE_SHOVEL.isTagged(m),
                    plugin.getConfig().getInt("settings.max-shovel-blocks", 9));
            case AXE, SELLAXE -> {
                if (Tag.LOGS.isTagged(origin.getType())) tree(player, origin, tool);
            }
            case MULTITOOL -> {
                Material m = origin.getType();
                if (Tag.LOGS.isTagged(m)) tree(player, origin, tool);
                else if (Tag.MINEABLE_SHOVEL.isTagged(m)) area(player, origin, tool,
                        mm -> Tag.MINEABLE_SHOVEL.isTagged(mm), plugin.getConfig().getInt("settings.max-shovel-blocks", 9));
                else if (Tag.MINEABLE_PICKAXE.isTagged(m)) area(player, origin, tool,
                        mm -> Tag.MINEABLE_PICKAXE.isTagged(mm), plugin.getConfig().getInt("settings.max-drill-blocks", 9));
            }
            default -> { }
        }
    }

    /** 3x3 plane perpendicular to where the player is looking. */
    private void area(Player player, Block center, ItemStack tool, Predicate<Material> filter, int max) {
        BlockFace face = facing(player);
        List<Block> targets = new ArrayList<>();
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;
                Block block = switch (face) {
                    case UP, DOWN -> center.getRelative(a, 0, b);
                    case NORTH, SOUTH -> center.getRelative(a, b, 0);
                    default -> center.getRelative(0, b, a);
                };
                if (filter.test(block.getType())) targets.add(block);
                if (targets.size() >= max - 1) break;
            }
        }
        int broken = 0;
        for (Block block : targets) if (mine(player, block, tool)) broken++;
        effects(center.getLocation(), broken);
        damage(tool, player, broken);
    }

    /** Whole-tree cascade: logs first, then the leaves attached to them. */
    private void tree(Player player, Block origin, ItemStack tool) {
        int max = plugin.getConfig().getInt("settings.max-tree-blocks", 200);
        Deque<Block> queue = new ArrayDeque<>();
        Set<Block> seen = new HashSet<>();
        List<Block> order = new ArrayList<>();
        queue.add(origin);
        seen.add(origin);

        while (!queue.isEmpty() && order.size() < max) {
            Block current = queue.poll();
            if (current != origin) order.add(current);
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x == 0 && y == 0 && z == 0) continue;
                        Block next = current.getRelative(x, y, z);
                        if (!seen.add(next)) continue;
                        Material m = next.getType();
                        if (Tag.LOGS.isTagged(m) || Tag.LEAVES.isTagged(m)) queue.add(next);
                    }
                }
            }
        }

        int delay = Math.max(0, plugin.getConfig().getInt("settings.tree-delay-ticks", 1));
        if (delay == 0) {
            int broken = 0;
            for (Block block : order) if (mine(player, block, tool)) broken++;
            damage(tool, player, broken);
            effects(origin.getLocation(), order.size());
            return;
        }
        int step = 0;
        for (Block block : order) {
            long wait = (long) (step++ / 6) * delay; // 6 blocks per cascade step
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (mine(player, block, tool)) {
                    damage(tool, player, 1);
                    effects(block.getLocation(), 1);
                }
            }, wait);
        }
    }

    /** Fires a normal BlockBreakEvent so claim/protection plugins keep working. */
    private boolean mine(Player player, Block block, ItemStack tool) {
        if (block.getType().isAir() || block.getType() == Material.BEDROCK) return false;
        if (block.getState() instanceof Container) return false;
        busy.add(player.getUniqueId());
        try {
            BlockBreakEvent probe = new BlockBreakEvent(block, player);
            Bukkit.getPluginManager().callEvent(probe);
            if (probe.isCancelled()) return false;
            if (player.getGameMode() == GameMode.CREATIVE) block.setType(Material.AIR, false);
            else block.breakNaturally(tool);
            return true;
        } finally {
            busy.remove(player.getUniqueId());
        }
    }

    /* ================= interactions ================= */

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        Tools.Type type = Tools.typeOf(item);
        if (type == null || !player.hasPermission("mythictools.use")) return;

        boolean right = event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR;

        switch (type) {
            case BUCKET -> {
                if (!right) return;
                event.setCancelled(true);
                activate(item);
                drain(player);
            }
            case SELLAXE -> {
                if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
                BlockState state = event.getClickedBlock().getState();
                if (!(state instanceof Container container)) return;
                event.setCancelled(true);
                activate(item);
                sell(player, container.getInventory());
            }
            case FIREWORK -> {
                if (!right) return;
                event.setCancelled(true);
                activate(item);
                launch(player, item);
            }
            default -> { }
        }
    }

    /** Drains a connected pocket of fluid, up to the configured cap. */
    private void drain(Player player) {
        int max = plugin.getConfig().getInt("settings.max-fluid-blocks", 27);
        RayTraceResult hit = player.rayTraceBlocks(6.0D, FluidCollisionMode.ALWAYS);
        if (hit == null || hit.getHitBlock() == null) return;
        Block start = hit.getHitBlock();
        Material fluid = start.getType();
        boolean water = fluid == Material.WATER || isWaterlogged(start);
        boolean lava = fluid == Material.LAVA;
        if (!water && !lava) return;

        Deque<Block> queue = new ArrayDeque<>();
        Set<Block> seen = new HashSet<>();
        int removed = 0;
        queue.add(start);
        seen.add(start);

        while (!queue.isEmpty() && removed < max) {
            Block block = queue.poll();
            boolean handled = false;
            if (block.getType() == (water ? Material.WATER : Material.LAVA)) {
                block.setType(Material.AIR, false);
                handled = true;
            } else if (water && isWaterlogged(block)) {
                BlockData data = block.getBlockData();
                ((Waterlogged) data).setWaterlogged(false);
                block.setBlockData(data, false);
                handled = true;
            }
            if (!handled) continue;
            removed++;
            for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
                    BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Block next = block.getRelative(face);
                if (seen.add(next)) queue.add(next);
            }
        }
        effects(start.getLocation(), removed);
        if (removed > 0 && plugin.getConfig().getBoolean("settings.sounds", true)) {
            player.playSound(start.getLocation(), water ? Sound.ITEM_BUCKET_FILL : Sound.ITEM_BUCKET_FILL_LAVA, 1f, 1.4f);
        }
    }

    private boolean isWaterlogged(Block block) {
        return block.getBlockData() instanceof Waterlogged w && w.isWaterlogged();
    }

    /** Sells every priced item inside the clicked container through Vault. */
    private void sell(Player player, Inventory inventory) {
        if (plugin.economy() == null) {
            player.sendMessage(Tools.c(plugin.prefix() + plugin.getConfig().getString("messages.no-economy")));
            return;
        }
        double total = 0;
        int count = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;
            double price = plugin.getConfig().getDouble("sell.prices." + stack.getType().name(), -1);
            if (price <= 0) continue;
            total += price * stack.getAmount();
            count += stack.getAmount();
            inventory.setItem(slot, null);
        }
        if (count == 0) {
            player.sendMessage(Tools.c(plugin.prefix() + plugin.getConfig().getString("messages.sell-empty")));
            return;
        }
        plugin.economy().depositPlayer(player, total);
        player.sendMessage(Tools.c(plugin.prefix() + plugin.getConfig().getString("messages.sold", "")
                .replace("%amount%", String.valueOf(count))
                .replace("%money%", String.format(Locale.US, "%.2f", total))));
        if (plugin.getConfig().getBoolean("settings.sounds", true)) {
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        }
    }

    /** Infinite firework: nothing is consumed, elytra boost still works. */
    private void launch(Player player, ItemStack item) {
        if (player.isGliding()) {
            player.boostElytra(item.clone().asOne());
            return;
        }
        Location loc = player.getLocation();
        Firework firework = player.getWorld().spawn(loc, Firework.class);
        if (item.getItemMeta() instanceof FireworkMeta source) {
            FireworkMeta meta = firework.getFireworkMeta();
            meta.setPower(Math.max(1, source.getPower()));
            meta.addEffects(source.getEffects());
            firework.setFireworkMeta(meta);
        }
        firework.setShooter(player);
        firework.setVelocity(player.getLocation().getDirection().multiply(0.3));
    }

    /* ================= helpers ================= */

    private void activate(ItemStack item) {
        if (item != null) Tools.startPending(item, plugin.getConfig());
    }

    private void damage(ItemStack tool, Player player, int amount) {
        if (amount <= 0) return;
        if (plugin.getConfig().getBoolean("settings.unbreakable-tools", true)) return;
        tool.damage(amount, player);
    }

    private void effects(Location loc, int amount) {
        if (amount <= 0 || !plugin.getConfig().getBoolean("settings.particles", true)) return;
        World world = loc.getWorld();
        if (world == null) return;
        world.spawnParticle(Particle.DUST, loc.clone().add(0.5, 0.5, 0.5),
                Math.min(24, amount * 3), 0.6, 0.6, 0.6,
                new Particle.DustOptions(Color.fromRGB(167, 102, 255), 1.1f));
    }

    private BlockFace facing(Player player) {
        float pitch = player.getLocation().getPitch();
        if (pitch > 45) return BlockFace.DOWN;
        if (pitch < -45) return BlockFace.UP;
        Vector dir = player.getLocation().getDirection();
        return Math.abs(dir.getX()) > Math.abs(dir.getZ())
                ? (dir.getX() > 0 ? BlockFace.EAST : BlockFace.WEST)
                : (dir.getZ() > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
    }
}
