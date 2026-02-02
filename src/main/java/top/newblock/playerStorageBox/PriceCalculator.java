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

        // 3. 加载组合规则
        ConfigurationSection comboSec = config.getConfigurationSection("combinations");
        if (comboSec != null) {
            for (String key : comboSec.getKeys(false)) {
                ConfigurationSection sec = comboSec.getConfigurationSection(key);
                if (sec == null) continue;

                // Lore 要求
                List<String> rawLore = sec.getStringList("required-lore");
                List<String> coloredLore = new ArrayList<>();
                for (String s : rawLore) coloredLore.add(ChatColor.translateAlternateColorCodes('&', s));

                // Lore 的数值调整
                double loreMult = sec.getDouble("lore-multiplier", 0.0);
                long loreVal = sec.getLong("lore-value", 0);
                // 标记该组合是否包含显式的倍率设置（用于处理默认1.0逻辑）
                boolean hasLoreMult = sec.contains("lore-multiplier");

                // Name 的数值调整 (name-settings)
                List<NameRule> nameRules = new ArrayList<>();
                ConfigurationSection nameSec = sec.getConfigurationSection("name-settings");
                if (nameSec != null) {
                    for (String namePattern : nameSec.getKeys(false)) {
                        String coloredNamePattern = ChatColor.translateAlternateColorCodes('&', namePattern);
                        double nMult = 0.0;
                        long nVal = 0;
                        boolean hasNMult = false;

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
                            // 简写模式: "Name": 1.5
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

        // Step 1: 计算 A 数值 (基础价值)
        long baseValue = 0;

        if (meta.hasLore()) {
            for (String line : meta.getLore()) {
                for (Map.Entry<String, Long> entry : lorePrices.entrySet()) {
                    if (isMatch(line, entry.getKey(), false)) baseValue += entry.getValue();
                }
            }
        }
        if (meta.hasDisplayName()) {
            String dName = meta.getDisplayName();
            for (Map.Entry<String, Long> entry : namePrices.entrySet()) {
                if (isMatch(dName, entry.getKey(), true)) baseValue += entry.getValue();
            }
        }

        if (baseValue == 0) return 0;

        // Step 2-5: 计算组合加成
        long totalAddedValue = 0;      // 累加所有匹配的固定数值 (lore-value + name-value)
        double totalMultiplier = 0.0;  // 累加所有匹配的倍率
        boolean anyMultiplierApplied = false; // 是否匹配到了任何带有倍率的规则

        if (meta.hasLore()) {
            List<String> itemLore = meta.getLore();
            String itemName = meta.hasDisplayName() ? meta.getDisplayName() : "";

            for (Combination combo : combinations) {
                // 如果 required-lore 是 ["*"]，则匹配任意有Lore的物品（用于全局名字设置）
                boolean isGlobal = combo.requiredLore.size() == 1 && combo.requiredLore.get(0).equals("*");

                if (isGlobal || containsAllLore(itemLore, combo.requiredLore)) {
                    // 1. Lore 固定值累加
                    totalAddedValue += combo.loreValue;

                    // 2. Lore 倍率累加
                    if (combo.hasLoreMultiplier) {
                        totalMultiplier += combo.loreMultiplier;
                        anyMultiplierApplied = true;
                    }

                    // 3. Name 规则匹配
                    for (NameRule rule : combo.nameRules) {
                        if (isMatch(itemName, rule.pattern, true)) {
                            totalAddedValue += rule.value;
                            if (rule.hasMultiplier) {
                                totalMultiplier += rule.multiplier;
                                anyMultiplierApplied = true;
                            }
                            // 一个组合内只匹配一个名字规则
                            break;
                        }
                    }
                }
            }
        }

        // 如果没有任何规则提供倍率，则倍率为 1.0 (不改变价值)
        if (!anyMultiplierApplied) {
            totalMultiplier = 1.0;
        }

        // 最终公式: (基础A + 固定加成) * 总倍率
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
        return exactIfNoWildcard ? text.equals(pattern) : text.contains(pattern);
    }

    // 数据结构
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