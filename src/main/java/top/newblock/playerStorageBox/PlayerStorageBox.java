package top.newblock.playerStorageBox;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class PlayerStorageBox extends JavaPlugin implements TabCompleter {

    private StorageManager storageManager;
    private FileConfiguration langConfig;
    private final Map<UUID, SearchSession> searchCache = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadLangConfig();
        SQLiteManager.init(this);
        storageManager = new StorageManager(this);
        getCommand("psb").setExecutor(this);
        getCommand("psb").setTabCompleter(this);
        getServer().getPluginManager().registerEvents(new StorageListener(this, storageManager), this);
    }

    /* ================= 参数解析引擎 (支持双引号) ================= */

    private String[] parseArgs(String label, String[] args) {
        // 将原始参数合并成一个字符串
        String fullInput = String.join(" ", args);
        List<String> list = new ArrayList<>();
        // 正则：匹配引号内的内容 OR 不含空格的单词
        Matcher m = Pattern.compile("([^\"]\\S*|\".+?\")\\s*").matcher(fullInput);
        while (m.find()) {
            String s = m.group(1);
            if (s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length() - 1);
            }
            list.add(s);
        }
        return list.toArray(new String[0]);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { sendHelp(sender); return true; }

        // 使用新解析引擎处理参数
        String[] smartArgs = parseArgs(label, args);
        String sub = smartArgs[0].toLowerCase();

        // /psb open [页] [玩家]
        if (sub.equals("open") && sender instanceof Player p) {
            int page = (smartArgs.length > 1 && smartArgs[1].matches("\\d+")) ? Integer.parseInt(smartArgs[1]) : 1;
            UUID target = (smartArgs.length > 2 && p.hasPermission("playerstoragebox.admin")) ?
                    Bukkit.getOfflinePlayer(smartArgs[2]).getUniqueId() : p.getUniqueId();
            storageManager.open(p, target, page);
            return true;
        }

        if (!sender.hasPermission("playerstoragebox.admin")) { sender.sendMessage(getLang("no-permission")); return true; }

        // /psb replace <name|lore> "旧文本" "新文本"
        if (sub.equals("replace")) {
            if (smartArgs.length < 4) { sender.sendMessage("§c用法: /psb replace <name|lore> \"旧文本\" \"新文本\""); return true; }
            sender.sendMessage("§e[!] 正在处理批量替换 (支持通配符 *)...");
            new BukkitRunnable() {
                @Override
                public void run() {
                    try {
                        int count = storageManager.bulkReplace(smartArgs[1], smartArgs[2], smartArgs[3]);
                        sender.sendMessage("§a[√] 完成！修改了 §f" + count + " §a处。");
                    } catch (Exception e) { sender.sendMessage("§c[×] 错误: " + e.getMessage()); }
                }
            }.runTaskAsynchronously(this);
            return true;
        }

        // /psb delete <name|lore> "目标文本"
        if (sub.equals("delete")) {
            if (smartArgs.length < 3) { sender.sendMessage("§c用法: /psb delete <name|lore> \"目标内容\""); return true; }
            sender.sendMessage("§e[!] 正在处理批量删除 (支持通配符 *)...");
            new BukkitRunnable() {
                @Override
                public void run() {
                    try {
                        int count = storageManager.bulkDelete(smartArgs[1], smartArgs[2]);
                        sender.sendMessage("§a[√] 完成！删除了 §f" + count + " §a个匹配物品。");
                    } catch (Exception e) { sender.sendMessage("§c[×] 错误: " + e.getMessage()); }
                }
            }.runTaskAsynchronously(this);
            return true;
        }

        // search, backup 逻辑保持不变...
        if (sub.equals("search") && sender instanceof Player p) {
            if (smartArgs.length < 3) { p.sendMessage("§c用法: /psb search <name|lore|data> <关键词>"); return true; }
            handleSearch(p, smartArgs[1], smartArgs[2]);
            return true;
        }

        if (sub.equals("_page") && sender instanceof Player p) {
            SearchSession session = searchCache.get(p.getUniqueId());
            if (session != null && smartArgs.length > 1) displaySearchPage(p, session, Integer.parseInt(smartArgs[1]));
            return true;
        }

        if (sub.equals("backup")) {
            String file = SQLiteManager.backup(this);
            sender.sendMessage(file != null ? "§a备份成功: " + file : "§c备份失败");
            return true;
        }

        sendHelp(sender);
        return true;
    }

    /* ================= 辅助显示方法 (保持之前版本) ================= */

    private void handleSearch(Player p, String type, String keyword) {
        new BukkitRunnable() {
            @Override
            public void run() {
                List<StorageManager.SearchResult> res = storageManager.searchItems(type, keyword);
                SearchSession session = new SearchSession(type, keyword, res);
                searchCache.put(p.getUniqueId(), session);
                new BukkitRunnable() { @Override public void run() { displaySearchPage(p, session, 1); } }.runTask(PlayerStorageBox.this);
            }
        }.runTaskAsynchronously(this);
    }

    private void displaySearchPage(Player player, SearchSession session, int page) {
        List<SearchGroup> groups = session.groups;
        if (groups.isEmpty()) { player.sendMessage("§c未找到结果。"); return; }
        int totalPages = (int) Math.ceil(groups.size() / 10.0);
        int start = (page - 1) * 10;
        player.sendMessage("\n§6§l搜索结果 (" + page + "/" + totalPages + ") §7关键词: " + session.keyword);
        for (int i = start; i < start + 10 && i < groups.size(); i++) {
            SearchGroup group = groups.get(i);
            TextComponent line = new TextComponent("§8- ");
            String ownerName = Bukkit.getOfflinePlayer(group.owner).getName();
            TextComponent namePart = new TextComponent("§b" + ownerName + " §7(P" + group.page + ")");
            namePart.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/psb open " + group.page + " " + ownerName));
            namePart.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("§e点击直接打开该仓库页面")));
            line.addExtra(namePart);
            line.addExtra("§8: ");
            for (ItemStack item : group.items) { line.addExtra(createItemComponent(item)); line.addExtra(" "); }
            player.spigot().sendMessage(line);
        }
        TextComponent footer = new TextComponent("\n      ");
        if (page > 1) {
            TextComponent prev = new TextComponent("§a§l[◀ 上一页] ");
            prev.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/psb _page " + (page - 1)));
            footer.addExtra(prev);
        }
        if (page < totalPages) {
            TextComponent next = new TextComponent(" §a§l [下一页 ▶]");
            next.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/psb _page " + (page + 1)));
            footer.addExtra(next);
        }
        player.spigot().sendMessage(footer);
    }

    private TextComponent createItemComponent(ItemStack item) {
        String displayName = item.hasItemMeta() && item.getItemMeta().hasDisplayName() ? item.getItemMeta().getDisplayName() : "§f" + item.getType().name();
        TextComponent component = new TextComponent("§e[" + ChatColor.stripColor(displayName) + "§e]");
        ComponentBuilder hover = new ComponentBuilder("§f展示名: " + displayName + "\n");
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof Damageable d && item.getType().getMaxDurability() > 0) {
            hover.append("§7耐久: §a" + (item.getType().getMaxDurability() - d.getDamage()) + "§7/§a" + item.getType().getMaxDurability() + "\n");
        }
        if (meta != null && meta.hasLore()) {
            hover.append("§7Lore:\n");
            for (String line : meta.getLore()) hover.append("§r" + line + "\n");
        }
        component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hover.create())));
        return component;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6§l--- PlayerStorageBox 管理帮助 ---");
        sender.sendMessage("§e/psb open [页] [玩家] §7- 打开仓库");
        if (sender.hasPermission("playerstoragebox.admin")) {
            sender.sendMessage("§e/psb search <name|lore|data> <关键词> §7- 搜索");
            sender.sendMessage("§b/psb replace <name|lore> \"旧\" \"新\" §7- 批量替换");
            sender.sendMessage("§c/psb delete <name|lore> \"内容\" §7- 批量删除");
            sender.sendMessage("§7(支持双引号包裹空格及 * 通配符)");
        }
    }

    private static class SearchSession {
        String type, keyword; List<SearchGroup> groups = new ArrayList<>();
        SearchSession(String t, String k, List<StorageManager.SearchResult> res) {
            this.type = t; this.keyword = k; Map<String, SearchGroup> map = new LinkedHashMap<>();
            for (StorageManager.SearchResult r : res) {
                String key = r.owner().toString() + "_" + r.page();
                map.computeIfAbsent(key, kn -> new SearchGroup(r.owner(), r.page())).items.add(r.item());
            }
            this.groups.addAll(map.values());
        }
    }

    private static class SearchGroup {
        UUID owner; int page; List<ItemStack> items = new ArrayList<>();
        SearchGroup(UUID o, int p) { this.owner = o; this.page = p; }
    }

    @Override public void onDisable() { if (storageManager != null) storageManager.saveAllAndClose(); SQLiteManager.close(); }
    public void loadLangConfig() { File f = new File(getDataFolder(), "lang.yml"); if (!f.exists()) saveResource("lang.yml", false); langConfig = YamlConfiguration.loadConfiguration(f); }
    public String getLang(String p) { return ChatColor.translateAlternateColorCodes('&', getConfig().getString("prefix", "&6NewBlock&e>> ") + langConfig.getString(p, p)); }
    @Override public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) return Arrays.asList("open", "search", "replace", "delete", "backup").stream().filter(i -> i.startsWith(args[0])).collect(Collectors.toList());
        if (args.length == 2 && (args[0].equalsIgnoreCase("search") || args[0].equalsIgnoreCase("replace") || args[0].equalsIgnoreCase("delete"))) return Arrays.asList("name", "lore", "data").stream().filter(i -> i.startsWith(args[1])).collect(Collectors.toList());
        return Collections.emptyList();
    }
}