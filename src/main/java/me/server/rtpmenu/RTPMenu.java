package me.server.rtpmenu;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RTPMenu extends JavaPlugin implements Listener, TabExecutor {

    static class Dest {
        String id, display, world, permission;
        int radius, cx, cz, y, minY, maxY;
        boolean cave;
        List<String> avoid = new ArrayList<>();
    }

    static class MenuHolder implements InventoryHolder {
        final Map<Integer, String> slots = new HashMap<>();
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Set<Material> BAD = EnumSet.of(Material.LAVA, Material.WATER, Material.CACTUS,
            Material.MAGMA_BLOCK, Material.FIRE, Material.SOUL_FIRE, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW, Material.WITHER_ROSE);

    private final Map<String, Dest> dests = new LinkedHashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<UUID> searching = new HashSet<>();
    private final Set<UUID> forceTp = new HashSet<>();

    // ------------------------------------------------------------ lifecycle
    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadDestinations();
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("rtp").setExecutor(this);
        getCommand("rtp").setTabCompleter(this);
        getLogger().info("RTPMenu enabled with " + dests.size() + " destinations.");
    }

    private void loadDestinations() {
        reloadConfig();
        dests.clear();
        ConfigurationSection ds = getConfig().getConfigurationSection("destinations");
        if (ds == null) return;
        for (String key : ds.getKeys(false)) {
            ConfigurationSection s = ds.getConfigurationSection(key);
            if (s == null) continue;
            Dest d = new Dest();
            d.id = key.toLowerCase(Locale.ROOT);
            d.display = s.getString("display-name", key);
            d.world = s.getString("world", key);
            d.radius = Math.max(1, s.getInt("radius", 5000));
            d.cx = s.getInt("center-x", 0);
            d.cz = s.getInt("center-z", 0);
            d.y = s.getInt("y", -1);
            d.minY = s.getInt("min-y", 32);
            d.maxY = s.getInt("max-y", 120);
            d.cave = "CAVE".equalsIgnoreCase(s.getString("mode", "SURFACE"));
            d.permission = s.getString("permission", "");
            for (String a : s.getStringList("avoid-biomes")) d.avoid.add(a.toLowerCase(Locale.ROOT));
            dests.put(d.id, d);
        }
    }

    // ------------------------------------------------------------ text helpers
    private String color(String s) {
        Matcher m = HEX.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(ChatColor.of("#" + m.group(1)).toString()));
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    private void msg(CommandSender to, String key, String... repl) {
        String m = getConfig().getString("messages." + key, "");
        if (m == null || m.isEmpty()) return;
        for (int i = 0; i + 1 < repl.length; i += 2) m = m.replace(repl[i], repl[i + 1]);
        to.sendMessage(color(m));
    }

    private String fmt(long s) { return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s"; }

    // ------------------------------------------------------------ commands
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("rtpmenu.reload")) { msg(sender, "no-permission"); return true; }
            loadDestinations();
            msg(sender, "reloaded");
            return true;
        }
        if (!(sender instanceof Player p)) { msg(sender, "players-only"); return true; }
        if (!p.hasPermission("rtpmenu.use")) { msg(p, "no-permission"); return true; }

        if (args.length == 0) { openGui(p); return true; }

        Dest d = dests.get(args[0].toLowerCase(Locale.ROOT));
        if (d == null) { msg(p, "unknown-destination", "{destination}", args[0]); return true; }
        rtp(p, d);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length != 1) return Collections.emptyList();
        String pre = a[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Dest d : dests.values()) if (d.id.startsWith(pre)) out.add(d.id);
        if (s.hasPermission("rtpmenu.reload") && "reload".startsWith(pre)) out.add("reload");
        return out;
    }

    // ------------------------------------------------------------ teleport logic
    /** Asks RTPDuels (if installed) whether the player is currently in a duel. */
    private boolean inDuel(Player p) {
        Plugin duels = Bukkit.getPluginManager().getPlugin("RTPDuels");
        if (duels == null || !duels.isEnabled()) return false;
        try {
            Method m = duels.getClass().getMethod("isBusy", UUID.class);
            return Boolean.TRUE.equals(m.invoke(duels, p.getUniqueId()));
        } catch (Exception e) {
            return false;
        }
    }

    private void rtp(Player p, Dest d) {
        UUID id = p.getUniqueId();
        if (!p.hasPermission("rtpmenu.use") || (!d.permission.isEmpty() && !p.hasPermission(d.permission))) {
            msg(p, "no-permission");
            return;
        }
        if (inDuel(p)) { msg(p, "in-duel"); return; }
        if (searching.contains(id)) { msg(p, "already-searching"); return; }

        if (!p.hasPermission("rtpmenu.bypass")) {
            long cd = getConfig().getLong("cooldown-seconds", 120) * 1000L;
            Long last = cooldowns.get(id);
            if (last != null) {
                long rem = cd - (System.currentTimeMillis() - last);
                if (rem > 0) { msg(p, "cooldown", "{time}", fmt((rem + 999) / 1000)); return; }
            }
        }

        World w = Bukkit.getWorld(d.world);
        if (w == null) { msg(p, "world-missing", "{world}", d.world); return; }

        // fixed Y: no search
        if (d.y >= 0) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            for (int i = 0; i < 20; i++) {
                int x = d.cx + r.nextInt(-d.radius, d.radius + 1);
                int z = d.cz + r.nextInt(-d.radius, d.radius + 1);
                if (!w.getWorldBorder().isInside(new Location(w, x, d.y, z))) continue;
                finish(p, d, new Location(w, x + 0.5, d.y, z + 0.5, p.getLocation().getYaw(), p.getLocation().getPitch()));
                return;
            }
            msg(p, "failed");
            return;
        }

        searching.add(id);
        msg(p, "searching");
        int max = getConfig().getInt("max-search-attempts", 60);

        new BukkitRunnable() {
            int tries = 0;

            @Override
            public void run() {
                if (!p.isOnline()) { searching.remove(id); cancel(); return; }
                for (int i = 0; i < 2; i++) {
                    tries++;
                    Location loc;
                    try { loc = find(d, w, p); } catch (Exception ex) { loc = null; }
                    if (loc != null) {
                        cancel();
                        searching.remove(id);
                        finish(p, d, loc);
                        return;
                    }
                }
                if (tries >= max) {
                    cancel();
                    searching.remove(id);
                    msg(p, "failed");
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void finish(Player p, Dest d, Location loc) {
        loc.getWorld().getChunkAt(loc);
        boolean ok;
        forceTp.add(p.getUniqueId());
        try { ok = p.teleport(loc, PlayerTeleportEvent.TeleportCause.PLUGIN); }
        finally { forceTp.remove(p.getUniqueId()); }
        if (ok) {
            cooldowns.put(p.getUniqueId(), System.currentTimeMillis());
            msg(p, "teleported", "{destination}", d.display);
        } else {
            msg(p, "teleport-failed");
        }
    }

    /** One random attempt at a safe spot. */
    private Location find(Dest d, World w, Player p) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int x = d.cx + r.nextInt(-d.radius, d.radius + 1);
        int z = d.cz + r.nextInt(-d.radius, d.radius + 1);
        if (!w.getWorldBorder().isInside(new Location(w, x, 64, z))) return null;
        float yaw = p.getLocation().getYaw(), pitch = p.getLocation().getPitch();

        if (d.cave) {
            int hi = Math.min(d.maxY, w.getMaxHeight() - 3);
            int lo = Math.max(d.minY, w.getMinHeight() + 1);
            if (hi <= lo) return null;
            for (int y = r.nextInt(lo, hi + 1); y > lo; y--) {
                if (!w.getBlockAt(x, y, z).isEmpty() || !w.getBlockAt(x, y + 1, z).isEmpty()) continue;
                Material floor = w.getBlockAt(x, y - 1, z).getType();
                if (!floor.isSolid() || BAD.contains(floor)) continue;
                return new Location(w, x + 0.5, y, z + 0.5, yaw, pitch);
            }
            return null;
        }

        int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (y <= w.getMinHeight()) return null;
        Block b = w.getBlockAt(x, y, z);
        if (b.isLiquid() || BAD.contains(b.getType())) return null;
        if (!d.avoid.isEmpty()) {
            String biome = w.getBiome(x, y, z).getKey().getKey().toLowerCase(Locale.ROOT);
            for (String a : d.avoid) if (biome.contains(a)) return null;
        }
        return new Location(w, x + 0.5, y + 1, z + 0.5, yaw, pitch);
    }

    // ------------------------------------------------------------ GUI
    private void openGui(Player p) {
        ConfigurationSection g = getConfig().getConfigurationSection("gui");
        if (g == null) return;
        int rows = Math.max(1, Math.min(6, g.getInt("rows", 3)));
        MenuHolder h = new MenuHolder();
        Inventory inv = Bukkit.createInventory(h, rows * 9, color(g.getString("title", "&6&lRTP Menu")));
        h.inv = inv;

        if (g.getBoolean("filler", true)) {
            Material fm = Material.matchMaterial(g.getString("filler-material", "GRAY_STAINED_GLASS_PANE"));
            if (fm == null) fm = Material.GRAY_STAINED_GLASS_PANE;
            ItemStack f = new ItemStack(fm);
            ItemMeta fmeta = f.getItemMeta();
            if (fmeta != null) { fmeta.setDisplayName(" "); f.setItemMeta(fmeta); }
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, f);
        }

        ConfigurationSection bs = g.getConfigurationSection("buttons");
        if (bs != null) {
            for (String key : bs.getKeys(false)) {
                ConfigurationSection s = bs.getConfigurationSection(key);
                if (s == null) continue;
                int slot = s.getInt("slot", -1);
                if (slot < 0 || slot >= inv.getSize()) continue;
                Material m = Material.matchMaterial(s.getString("material", "GRASS_BLOCK"));
                if (m == null) m = Material.GRASS_BLOCK;
                ItemStack item = new ItemStack(m);
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(color(s.getString("name", key)));
                    List<String> lore = new ArrayList<>();
                    for (String line : s.getStringList("lore")) lore.add(color(line));
                    meta.setLore(lore);
                    meta.addItemFlags(ItemFlag.values());
                    item.setItemMeta(meta);
                }
                inv.setItem(slot, item);
                h.slots.put(slot, s.getString("destination", key).toLowerCase(Locale.ROOT));
            }
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder h)) return;
        e.setCancelled(true);
        if (!top.equals(e.getClickedInventory())) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String destId = h.slots.get(e.getSlot());
        if (destId == null) return;
        p.closeInventory();
        Dest d = dests.get(destId);
        if (d == null) { msg(p, "unknown-destination", "{destination}", destId); return; }
        rtp(p, d);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof MenuHolder) e.setCancelled(true);
    }

    // ------------------------------------------------------------ misc
    /** Runs last: undo cancellations (e.g. Multiverse access checks) of RTPMenu's own teleports. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onTeleport(PlayerTeleportEvent e) {
        if (getConfig().getBoolean("force-teleport", true)
                && forceTp.contains(e.getPlayer().getUniqueId()) && e.isCancelled()) {
            e.setCancelled(false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { searching.remove(e.getPlayer().getUniqueId()); }
}
