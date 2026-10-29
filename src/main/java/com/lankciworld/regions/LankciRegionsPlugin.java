package com.lankciworld.regions;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * LankciRegions: у вибраному регіоні WorldGuard можна ламати лише ті блоки, які OP дозволив командою
 *   /lrg add [регіон] block <блок>      /lrg remove [регіон] block <блок>      /lrg list [регіон]
 * Решту блоків у регіоні, як і раніше, захищає WorldGuard. У кожному регіоні максимум 20 різних блоків.
 */
public final class LankciRegionsPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    /** світ (як є) -> id регіону (малими літерами) -> дозволені блоки */
    private final Map<String, Map<String, Set<Material>>> data = new HashMap<>();
    private File dataFile;
    private final AuthMe authMe = new AuthMe(this);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand pc = getCommand("lrg");
        if (pc != null) {
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
        dataFile = new File(getDataFolder(), "regions.yml");
        load();
        getLogger().info("Готово. Регіонів із дозволеними блоками: " + count());
    }

    /* ───────── дані ───────── */
    private void load() {
        data.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection worlds = y.getConfigurationSection("regions");
        if (worlds == null) return;
        for (String w : worlds.getKeys(false)) {
            ConfigurationSection regs = worlds.getConfigurationSection(w);
            if (regs == null) continue;
            for (String r : regs.getKeys(false)) {
                Set<Material> set = EnumSet.noneOf(Material.class);
                for (String name : regs.getStringList(r)) {
                    Material m = Material.matchMaterial(name);
                    if (m != null) set.add(m);
                }
                if (!set.isEmpty()) data.computeIfAbsent(w, k -> new HashMap<>()).put(r, set);
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, Map<String, Set<Material>>> w : data.entrySet()) {
            for (Map.Entry<String, Set<Material>> r : w.getValue().entrySet()) {
                List<String> names = new ArrayList<>();
                for (Material m : r.getValue()) names.add(m.name());
                y.set("regions." + w.getKey() + "." + r.getKey(), names);
            }
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Не вдалося зберегти regions.yml: " + e.getMessage());
        }
    }

    private int count() {
        int n = 0;
        for (Map<String, Set<Material>> m : data.values()) n += m.size();
        return n;
    }

    private Set<Material> allowedIn(String world, String region) {
        Map<String, Set<Material>> m = data.get(world);
        Set<Material> s = (m == null) ? null : m.get(region);
        return s == null ? Set.of() : s;
    }

    private Component msg(String key, String... kv) {
        String t = getConfig().getString("messages." + key, key);
        for (int i = 0; i + 1 < kv.length; i += 2) t = t.replace("{" + kv[i] + "}", kv[i + 1]);
        return LegacyComponentSerializer.legacyAmpersand().deserialize(t);
    }

    /* ───────── ламання блоків ───────── */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent e) {
        if (!e.isCancelled() || data.isEmpty()) return;
        Player p = e.getPlayer();
        String world = e.getBlock().getWorld().getName();
        Map<String, Set<Material>> regs = data.get(world);
        if (regs == null) return;
        Material type = e.getBlock().getType();
        // не чіпаємо рішення AuthMe для тих, хто ще не ввійшов
        if (authMe.present() && !authMe.isAuthenticated(p)) return;
        try {
            ApplicableRegionSet set = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                    .getApplicableRegions(BukkitAdapter.adapt(e.getBlock().getLocation()));
            int top = Integer.MIN_VALUE;
            for (ProtectedRegion r : set) top = Math.max(top, r.getPriority());
            boolean any = false;
            for (ProtectedRegion r : set) {
                if (r.getPriority() != top) continue;
                any = true;
                Set<Material> s = regs.get(r.getId().toLowerCase(Locale.ROOT));
                // дозволяємо лише якщо КОЖЕН регіон найвищого пріоритету дозволяє цей блок
                if (s == null || !s.contains(type)) return;
            }
            if (any) e.setCancelled(false);
        } catch (Throwable t) {
            getLogger().warning("Помилка перевірки регіону: " + t);
        }
    }

    /* ───────── команди ───────── */
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("lankciregions.admin")) { s.sendMessage(msg("no-permission")); return true; }
        if (a.length == 0) { s.sendMessage(msg("usage")); return true; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                reloadConfig();
                load();
                s.sendMessage(msg("reloaded"));
            }
            case "add", "remove" -> {
                // /lrg add [регіон] block <блок>
                if (a.length < 3 || a.length > 4 || !a[a.length - 2].equalsIgnoreCase("block")) {
                    s.sendMessage(msg("usage"));
                    return true;
                }
                if (!(s instanceof Player p)) { s.sendMessage(msg("only-player")); return true; }
                String regionId = resolveRegion(p, a.length == 4 ? a[1] : null, sub);
                if (regionId == null) return true;
                changeBlock(p, sub.equals("add"), regionId, a[a.length - 1]);
            }
            case "list" -> {
                if (!(s instanceof Player p)) { s.sendMessage(msg("only-player")); return true; }
                String regionId = resolveRegion(p, a.length >= 2 ? a[1] : null, "list");
                if (regionId == null) return true;
                Set<Material> set = allowedIn(p.getWorld().getName(), regionId);
                if (set.isEmpty()) {
                    s.sendMessage(msg("list-empty", "region", regionId));
                } else {
                    TreeSet<String> names = new TreeSet<>();
                    for (Material m : set) names.add(m.name().toLowerCase(Locale.ROOT));
                    s.sendMessage(msg("list", "region", regionId, "blocks", String.join(", ", names)));
                }
            }
            default -> s.sendMessage(msg("usage"));
        }
        return true;
    }

    /** Повертає id регіону (малими літерами) або null (і пише гравцю причину). */
    private String resolveRegion(Player p, String name, String sub) {
        RegionManager rm = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(p.getWorld()));
        if (name != null) {
            ProtectedRegion r = (rm == null) ? null : rm.getRegion(name);
            if (r == null) {
                p.sendMessage(msg("region-not-found", "region", name, "world", p.getWorld().getName()));
                return null;
            }
            return r.getId().toLowerCase(Locale.ROOT);
        }
        ApplicableRegionSet set = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                .getApplicableRegions(BukkitAdapter.adapt(p.getLocation()));
        int top = Integer.MIN_VALUE;
        for (ProtectedRegion r : set) top = Math.max(top, r.getPriority());
        List<ProtectedRegion> best = new ArrayList<>();
        for (ProtectedRegion r : set) if (r.getPriority() == top) best.add(r);
        if (best.isEmpty()) { p.sendMessage(msg("no-region-here", "sub", sub)); return null; }
        if (best.size() > 1) { p.sendMessage(msg("many-regions")); return null; }
        return best.get(0).getId().toLowerCase(Locale.ROOT);
    }

    private void changeBlock(Player p, boolean add, String regionId, String blockName) {
        Material m = Material.matchMaterial(blockName.toUpperCase(Locale.ROOT));
        if (m == null || !m.isBlock() || m.isAir()) {
            p.sendMessage(msg("bad-block", "block", blockName));
            return;
        }
        String world = p.getWorld().getName();
        String shown = m.name().toLowerCase(Locale.ROOT);
        int max = Math.max(1, getConfig().getInt("max-blocks-per-region", 20));
        Map<String, Set<Material>> regs = data.computeIfAbsent(world, k -> new HashMap<>());
        Set<Material> set = regs.get(regionId);
        if (add) {
            if (set != null && set.contains(m)) { p.sendMessage(msg("already", "region", regionId, "block", shown)); return; }
            if (set != null && set.size() >= max) { p.sendMessage(msg("limit", "region", regionId, "max", String.valueOf(max))); return; }
            if (set == null) { set = EnumSet.noneOf(Material.class); regs.put(regionId, set); }
            set.add(m);
            save();
            p.sendMessage(msg("added", "region", regionId, "block", shown, "count", String.valueOf(set.size()), "max", String.valueOf(max)));
        } else {
            if (set == null || !set.remove(m)) { p.sendMessage(msg("not-in-list", "region", regionId, "block", shown)); return; }
            if (set.isEmpty()) regs.remove(regionId);
            save();
            p.sendMessage(msg("removed", "region", regionId, "block", shown));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("lankciregions.admin")) return out;
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        if (a.length == 1) {
            for (String o : new String[] {"add", "remove", "list", "reload"}) if (o.startsWith(last)) out.add(o);
            return out;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (!(s instanceof Player p)) return out;
        if (sub.equals("add") || sub.equals("remove")) {
            boolean blockWord = a.length >= 2 && a[a.length - 2].equalsIgnoreCase("block");
            if (blockWord) {
                int n = 0;
                for (Material m : Material.values()) {
                    if (m.isBlock() && !m.isAir() && m.name().toLowerCase(Locale.ROOT).startsWith(last)) {
                        out.add(m.name().toLowerCase(Locale.ROOT));
                        if (++n >= 50) break;
                    }
                }
            } else if (a.length == 2 || a.length == 3) {
                if ("block".startsWith(last)) out.add("block");
                if (a.length == 2) addRegionNames(p, last, out);
            }
        } else if (sub.equals("list") && a.length == 2) {
            addRegionNames(p, last, out);
        }
        return out;
    }

    private void addRegionNames(Player p, String prefix, List<String> out) {
        RegionManager rm = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(p.getWorld()));
        if (rm == null) return;
        for (String id : rm.getRegions().keySet()) {
            if (id.toLowerCase(Locale.ROOT).startsWith(prefix) && !id.startsWith("__")) out.add(id);
            if (out.size() >= 50) break;
        }
    }

    /* ═════════ Зв'язок з AuthMe (reflection, без залежності при збірці) ═════════ */
    static final class AuthMe {
        private final LankciRegionsPlugin pl;
        private Object api;
        private Method isAuth;
        private boolean warned;

        AuthMe(LankciRegionsPlugin pl) { this.pl = pl; }

        synchronized boolean present() {
            init();
            return api != null && isAuth != null;
        }

        private void init() {
            if (api != null && isAuth != null) return;
            Plugin am = Bukkit.getPluginManager().getPlugin("AuthMe");
            if (am == null || !am.isEnabled()) return;
            try {
                Class<?> cls = Class.forName("fr.xephi.authme.api.v3.AuthMeApi", true, am.getClass().getClassLoader());
                api = cls.getMethod("getInstance").invoke(null);
                isAuth = cls.getMethod("isAuthenticated", Player.class);
            } catch (Throwable t) {
                api = null;
                isAuth = null;
                if (!warned) {
                    warned = true;
                    pl.getLogger().warning("Не вдалося підключитися до API AuthMe: " + t);
                }
            }
        }

        boolean isAuthenticated(Player p) {
            if (!present()) return true;
            try {
                Object r = isAuth.invoke(api, p);
                return r instanceof Boolean b && b;
            } catch (Throwable t) {
                return false;
            }
        }
    }
}
