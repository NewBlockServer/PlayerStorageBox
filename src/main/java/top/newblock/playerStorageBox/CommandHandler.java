package top.newblock.playerStorageBox;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CommandHandler implements CommandExecutor, TabCompleter {

    private final PlayerStorageBox plugin;
    private final StorageManager storageManager;
    private final Map<UUID, SearchSession> searchCache = new HashMap<>();

    public CommandHandler(PlayerStorageBox plugin, StorageManager storageManager) {
        this.plugin = plugin;
        this.storageManager = storageManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String[] smartArgs = parseArgs(label, args);
        String sub = smartArgs[0].toLowerCase();

        if (sub.equals("open") && sender instanceof Player p) {
            int page = 1;
            UUID targetUUID = p.getUniqueId();
            boolean isAdmin = p.hasPermission("playerstoragebox.admin");

            if (smartArgs.length > 1) {
                String arg1 = smartArgs[1];
                String arg2 = (smartArgs.length > 2) ? smartArgs[2] : null;

                if (isInteger(arg1)) {
                    page = Integer.parseInt(arg1);
                    if (arg2 != null && isAdmin) targetUUID = resolveTarget(arg2);
                } else if (isAdmin) {
                    targetUUID = resolveTarget(arg1);
                    if (arg2 != null && isInteger(arg2)) page = Integer.parseInt(arg2);
                }
            }

            storageManager.open(p, targetUUID, page);
            return true;
        }

        if (!sender.hasPermission("playerstoragebox.admin")) {
            sender.sendMessage(plugin.getLang("no-permission"));
            return true;
        }

        switch (sub) {
            case "reload" -> {
                plugin.reloadAllConfigs();
                sender.sendMessage("§a[PlayerStorageBox] 配置已全部重载。");
                return true;
            }
            case "backup" -> {
                runAsync(sender, () -> {
                    String file = SQLiteManager.backup(plugin);
                    sender.sendMessage(file != null ? "§a备份成功: " + file : "§c备份失败");
                });
                return true;
            }
            case "search" -> {
                if (sender instanceof Player p) {
                    if (smartArgs.length < 3) {
                        p.sendMessage("§c用法: /psb search <name|lore|data> <关键词>");
                        return true;
                    }
                    handleSearch(p, smartArgs[1], smartArgs[2]);
                } else { sender.sendMessage("§c仅限玩家使用。"); }
                return true;
            }
            case "_page" -> {
                if (sender instanceof Player p) {
                    SearchSession session = searchCache.get(p.getUniqueId());
                    if (session != null && smartArgs.length > 1) {
                        displaySearchPage(p, session, Integer.parseInt(smartArgs[1]));
                    }
                }
                return true;
            }
            case "replace" -> {
                if (smartArgs.length < 4) { sender.sendMessage("§c用法: /psb replace <name|lore> \"旧\" \"新\""); return true; }
                runAsync(sender, () -> {
                    try {
                        int count = storageManager.bulkReplace(smartArgs[1], smartArgs[2], smartArgs[3]);
                        sender.sendMessage("§a[√] 修改了 " + count + " 处。");
                    } catch (Exception e) { sender.sendMessage("§c错误: " + e.getMessage()); }
                });
                return true;
            }
            case "delete" -> {
                if (smartArgs.length < 3) { sender.sendMessage("§c用法: /psb delete <name|lore> \"内容\""); return true; }
                runAsync(sender, () -> {
                    try {
                        int count = storageManager.bulkDelete(smartArgs[1], smartArgs[2]);
                        sender.sendMessage("§a[√] 删除了 " + count + " 个物品。");
                    } catch (Exception e) { sender.sendMessage("§c错误: " + e.getMessage()); }
                });
                return true;
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private UUID resolveTarget(String arg) {
        try { return UUID.fromString(arg); }
        catch (IllegalArgumentException e) { return Bukkit.getOfflinePlayer(arg).getUniqueId(); }
    }

    private boolean isInteger(String s) {
        try { Integer.parseInt(s); return true; } catch (NumberFormatException e) { return false; }
    }

    private void runAsync(CommandSender sender, Runnable r) {
        new BukkitRunnable() { @Override public void run() { r.run(); } }.runTaskAsynchronously(plugin);
    }

    private String[] parseArgs(String label, String[] args) {
        String fullInput = String.join(" ", args);
        List<String> list = new ArrayList<>();
        Matcher m = Pattern.compile("([^\"]\\S*|\".+?\")\\s*").matcher(fullInput);
        while (m.find()) {
            String s = m.group(1);
            if (s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
            list.add(s);
        }
        return list.toArray(new String[0]);
    }

    private void handleSearch(Player p, String type, String keyword) {
        runAsync(p, () -> {
            List<StorageManager.SearchResult> res = storageManager.searchItems(type, keyword);
            SearchSession session = new SearchSession(type, keyword, res);
            searchCache.put(p.getUniqueId(), session);
            new BukkitRunnable() { @Override public void run() { displaySearchPage(p, session, 1); } }.runTask(plugin);
        });
    }

    private void displaySearchPage(Player player, SearchSession session, int page) {
        List<SearchGroup> groups = session.groups;
        if (groups.isEmpty()) { player.sendMessage("§c未找到结果。"); return; }
        int totalPages = (int) Math.ceil(groups.size() / 10.0);
        if (page < 1) page = 1; if (page > totalPages) page = totalPages;

        int start = (page - 1) * 10;
        player.sendMessage("\n§6§l搜索结果 (" + page + "/" + totalPages + ") §7关键词: " + session.keyword);
        for (int i = start; i < start + 10 && i < groups.size(); i++) {
            SearchGroup group = groups.get(i);
            TextComponent line = new TextComponent("§8- ");
            String ownerName = Bukkit.getOfflinePlayer(group.owner).getName();
            TextComponent namePart = new TextComponent("§b" + (ownerName != null ? ownerName : "Unknown") + " §7(P" + group.page + ")");
            namePart.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/psb open " + group.page + " " + ownerName));
            namePart.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("§e点击打开")));
            line.addExtra(namePart);
            line.addExtra("§8: ");
            for (ItemStack item : group.items) { line.addExtra(createItemComponent(item)); line.addExtra(" "); }
            player.spigot().sendMessage(line);
        }
        TextComponent footer = new TextComponent("\n      ");
        if (page > 1) {
            TextComponent prev = new TextComponent("§a§l[◀] ");
            prev.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/psb _page " + (page - 1)));
            footer.addExtra(prev);
        }
        if (page < totalPages) {
            TextComponent next = new TextComponent(" §a§l [▶]");
            next.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/psb _page " + (page + 1)));
            footer.addExtra(next);
        }
        player.spigot().sendMessage(footer);
    }

    private TextComponent createItemComponent(ItemStack item) {
        String displayName = item.hasItemMeta() && item.getItemMeta().hasDisplayName() ? item.getItemMeta().getDisplayName() : "§f" + item.getType().name();
        TextComponent component = new TextComponent("§e[" + ChatColor.stripColor(displayName) + "§e]");
        ComponentBuilder hover = new ComponentBuilder("§f" + displayName + "\n");
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof Damageable d && item.getType().getMaxDurability() > 0) {
            hover.append("§7耐久: " + (item.getType().getMaxDurability() - d.getDamage()) + "\n");
        }
        if (meta != null && meta.hasLore()) {
            hover.append("§7Lore:\n");
            for (String line : meta.getLore()) hover.append("§r" + line + "\n");
        }
        component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hover.create())));
        return component;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6§l--- PlayerStorageBox ---");
        sender.sendMessage("§e/psb open [页] [玩家/UUID]");
        if (sender.hasPermission("playerstoragebox.admin")) {
            sender.sendMessage("§a/psb reload");
            sender.sendMessage("§e/psb search <name|lore|data> <kw>");
            sender.sendMessage("§b/psb replace <type> \"old\" \"new\"");
            sender.sendMessage("§c/psb delete <type> \"val\"");
            sender.sendMessage("§d/psb backup");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) return Arrays.asList("open", "search", "replace", "delete", "backup", "reload").stream().filter(i -> i.startsWith(args[0])).collect(Collectors.toList());
        return Collections.emptyList();
    }

    private static class SearchSession {
        String type, keyword; List<SearchGroup> groups = new ArrayList<>();
        SearchSession(String t, String k, List<StorageManager.SearchResult> res) {
            this.type = t; this.keyword = k;
            Map<String, SearchGroup> map = new LinkedHashMap<>();
            for (StorageManager.SearchResult r : res) {
                String key = r.owner().toString() + "_" + r.page();
                map.computeIfAbsent(key, kn -> new SearchGroup(r.owner(), r.page())).items.add(r.item());
            }
            this.groups.addAll(map.values());
        }
    }
    private static class SearchGroup { UUID owner; int page; List<ItemStack> items = new ArrayList<>(); SearchGroup(UUID o, int p) { this.owner = o; this.page = p; } }
}