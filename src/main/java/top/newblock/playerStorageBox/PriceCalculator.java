package top.newblock.playerStorageBox;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class PriceCalculator {

    private final PlayerStorageBox plugin;
    private final Map<String, Long> namePrices = new HashMap<>();
    private final Map<String, Long> lorePrices = new HashMap<>();
    private final List<Combination> combinations = new ArrayList<>();

    public PriceCalculator(PlayerStorageBox plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        namePrices.clear();
        lorePrices.clear();
        combinations.clear();

        File file = new File(plugin.getDataFolder(), "prices.yml");
        if (!file.exists()) plugin.saveResource("prices.yml", false);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        // 1. 加载 Lore 基础价值
        if (config.getConfigurationSection("prices.lore") != null) {
            for (String key : config.getConfigurationSection("prices.lore").getKeys(false)) {
                lorePrices.put(ChatColor.translateAlternateColorCodes('&', key), config.getLong("prices.lore." + key));
            }
        }

        // 2. 加载 Name 基础价值
        if (config.getConfigurationSection("prices.name") != null) {
            for (String key : config.getConfigurationSection("prices.name").getKeys(false)) {
                namePrices.put(ChatColor.translateAlternateColorCodes('&', key), config.getLong("prices.name." + key));
            }
        }

        // 3. 加载组合规则 (Combinations)
        ConfigurationSection comboSec = config.getConfigurationSection("combinations");
        if (comboSec != null) {
            for (String key : comboSec.getKeys(false)) {
                ConfigurationSection sec = comboSec.getConfigurationSection(key);
                if (sec == null) continue;

                // --- 解析 Lore 要求 ---
                List<String> rawLore = sec.getStringList("required-lore");
                List<String> coloredLore = new ArrayList<>();
                for (String s : rawLore) coloredLore.add(ChatColor.translateAlternateColorCodes('&', s));

                // --- 解析 Lore 的数值调整 ---
                // 默认倍率为 0.0 (表示未设置)，默认固定值为 0
                double loreMult = sec.contains("lore-multiplier") ? sec.getDouble("lore-multiplier") : 0.0;
                boolean hasLoreMult = sec.contains("lore-multiplier"); // 标记是否显式设置了倍率
                long loreVal = sec.getLong("lore-value", 0);

                // --- 解析 Name 的数值调整 (name-settings) ---
                List<NameRule> nameRules = new ArrayList<>();
                ConfigurationSection nameSec = sec.getConfigurationSection("name-settings");
                if (nameSec != null) {
                    for (String namePattern : nameSec.getKeys(false)) {
                        String coloredNamePattern = ChatColor.translateAlternateColorCodes('&', namePattern);
                        double nMult = 0.0;
                        long nVal = 0;
                        boolean hasNMult = false;

                        // 支持两种格式：
                        // 1. "名字": 2.0 (仅设置倍率)
                        // 2. "名字": { multiplier: 2.0, value: 100 } (完整设置)
                        if (nameSec.isConfigurationSection(namePattern)) {
                            ConfigurationSection sub = nameSec.getConfigurationSection(namePattern);
                            if (sub != null) {
                                if (sub.contains("multiplier")) {
                                    nMult = sub.getDouble("multiplier");
                                    hasNMult = true;
                                }
                                nVal = sub.getLong("value", 0);
                            }
                        } else {
                            // 简单格式，视为倍率
                            nMult = nameSec.getDouble(namePattern);
                            hasNMult = true;
                        }
                        nameRules.add(new NameRule(coloredNamePattern, nMult, nVal, hasNMult));
                    }
                }

                combinations.add(new Combination(coloredLore, loreMult, loreVal, hasLoreMult, nameRules));
            }
        }
    }

    public long calculateItemValue(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        ItemMeta meta = item.getItemMeta();

        // ---------------------------------------------------------
        // Step 1: 计算 A 数值 (基础价值 = Lore单行价值 + Name单行价值)
        // ---------------------------------------------------------
        long baseValue = 0;

        // Lore 基础价值
        if (meta.hasLore()) {
            for (String line : meta.getLore()) {
                for (Map.Entry<String, Long> entry : lorePrices.entrySet()) {
                    if (isMatch(line, entry.getKey(), false)) {
                        baseValue += entry.getValue();
                    }
                }
            }
        }

        // Name 基础价值
        if (meta.hasDisplayName()) {
            String dName = meta.getDisplayName();
            for (Map.Entry<String, Long> entry : namePrices.entrySet()) {
                if (isMatch(dName, entry.getKey(), true)) {
                    baseValue += entry.getValue();
                }
            }
        }

        if (baseValue == 0) return 0; // 基础价值为0直接返回

        // ---------------------------------------------------------
        // Step 2 & 3 & 4 & 5: 计算组合加成 (固定值累加 & 倍率累加)
        // ---------------------------------------------------------

        long totalAddedValue = 0;      // 所有匹配组合的固定数值之和
        double totalMultiplier = 0.0;  // 所有匹配组合的倍率之和
        boolean multiplierModified = false; // 是否匹配到了含有倍率的项

        if (meta.hasLore()) {
            List<String> itemLore = meta.getLore();
            String itemName = meta.hasDisplayName() ? meta.getDisplayName() : "";

            for (Combination combo : combinations) {
                // 判断 Lore 是否符合组合条件
                if (containsAllLore(itemLore, combo.requiredLore)) {

                    // 1. 累加 Lore 组的固定数值
                    totalAddedValue += combo.loreValue;

                    // 2. 累加 Lore 组的倍率 (如果有设置)
                    if (combo.hasLoreMultiplier) {
                        totalMultiplier += combo.loreMultiplier;
                        multiplierModified = true;
                    }

                    // 3. 检查该组合内的 Name 规则
                    for (NameRule rule : combo.nameRules) {
                        if (isMatch(itemName, rule.pattern, true)) {
                            // 累加 Name 的固定数值
                            totalAddedValue += rule.value;

                            // 累加 Name 的倍率 (Lore倍率 + Name倍率 的效果在这里体现)
                            if (rule.hasMultiplier) {
                                totalMultiplier += rule.multiplier;
                                multiplierModified = true;
                            }
                            // 匹配到一个名字后，跳出当前 Name 规则循环 (通常一个物品只有一个名字)
                            break;
                        }
                    }
                }
            }
        }

        // 如果没有匹配到任何设置了倍率的项，倍率默认为 1.0 (保持原值)
        // 如果匹配到了（即使是 0.0 或者 0.5），则使用累加值
        if (!multiplierModified) {
            totalMultiplier = 1.0;
        }

        // ---------------------------------------------------------
        // 最终公式： (A数值 + 固定加成) * 总倍率
        // ---------------------------------------------------------
        double finalResult = (baseValue + totalAddedValue) * totalMultiplier;

        return (long) finalResult;
    }

    private boolean containsAllLore(List<String> itemLore, List<String> required) {
        if (required.isEmpty()) return false;
        for (String reqLine : required) {
            boolean found = false;
            for (String iLine : itemLore) {
                if (isMatch(iLine, reqLine, false)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private boolean isMatch(String text, String pattern, boolean exactIfNoWildcard) {
        if (text == null || pattern == null) return false;
        if (pattern.contains("*")) {
            String regex = Pattern.quote(pattern).replace("*", "\\E.*\\Q");
            return text.matches(regex);
        }
        if (exactIfNoWildcard) {
            return text.equals(pattern);
        } else {
            return text.contains(pattern);
        }
    }

    // --- 内部数据结构类 ---

    private static class Combination {
        List<String> requiredLore;
        double loreMultiplier;
        long loreValue;
        boolean hasLoreMultiplier;
        List<NameRule> nameRules;

        public Combination(List<String> requiredLore, double loreMultiplier, long loreValue, boolean hasLoreMultiplier, List<NameRule> nameRules) {
            this.requiredLore = requiredLore;
            this.loreMultiplier = loreMultiplier;
            this.loreValue = loreValue;
            this.hasLoreMultiplier = hasLoreMultiplier;
            this.nameRules = nameRules;
        }
    }

    private static class NameRule {
        String pattern;
        double multiplier;
        long value;
        boolean hasMultiplier;

        public NameRule(String pattern, double multiplier, long value, boolean hasMultiplier) {
            this.pattern = pattern;
            this.multiplier = multiplier;
            this.value = value;
            this.hasMultiplier = hasMultiplier;
        }
    }
}