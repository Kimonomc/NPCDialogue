package com.kim;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NPCDialogue extends JavaPlugin implements Listener, CommandExecutor {
    private FileConfiguration config;
    private File configFile;
    private final Map<UUID, DialogueState> playerDialogueStates = new ConcurrentHashMap<>();
    private final Set<UUID> waitingForChoice = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Set<String>> completedFirstInteractions = new ConcurrentHashMap<>();
    private File dataFile;
    private Gson gson;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("nd")).setExecutor(this);
        gson = new GsonBuilder().setPrettyPrinting().create();
        loadConfig();
        loadData();
        getLogger().info("NPCDialogue 插件已启用!");
    }

    @Override
    public void onDisable() {
        saveData();
        getLogger().info("NPCDialogue 插件已禁用!");
    }

    public void loadConfig() {
        configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            configFile.getParentFile().mkdirs();
            saveResource("config.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(configFile);
    }

    public void saveConfig() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            getLogger().severe("无法保存配置文件: " + e.getMessage());
        }
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.json");
        completedFirstInteractions.clear();

        if (!dataFile.exists()) {
            dataFile.getParentFile().mkdirs();
            try {
                dataFile.createNewFile();
                try (FileWriter writer = new FileWriter(dataFile)) {
                    writer.write("{}");
                }
            } catch (IOException e) {
                getLogger().severe("无法创建数据文件: " + e.getMessage());
            }
            return;
        }

        try (FileReader reader = new FileReader(dataFile)) {
            Type type = new TypeToken<Map<String, List<String>>>(){}.getType();
            Map<String, List<String>> data = gson.fromJson(reader, type);

            if (data != null) {
                for (Map.Entry<String, List<String>> entry : data.entrySet()) {
                    try {
                        UUID playerId = UUID.fromString(entry.getKey());
                        Set<String> dialogues = new HashSet<>(entry.getValue());
                        completedFirstInteractions.put(playerId, dialogues);
                    } catch (IllegalArgumentException e) {
                        getLogger().warning("无效的UUID格式: " + entry.getKey() + "，已跳过");
                    }
                }
            }
        } catch (IOException e) {
            getLogger().severe("无法加载数据文件: " + e.getMessage());
        }
    }

    private void saveData() {
        try (FileWriter writer = new FileWriter(dataFile)) {
            Map<String, List<String>> data = new HashMap<>();
            for (Map.Entry<UUID, Set<String>> entry : completedFirstInteractions.entrySet()) {
                data.put(entry.getKey().toString(), new ArrayList<>(entry.getValue()));
            }
            gson.toJson(data, writer);
        } catch (IOException e) {
            getLogger().severe("无法保存数据文件: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (sender.hasPermission("npcdialogue.reload") || sender.isOp() || sender instanceof ConsoleCommandSender) {
                loadConfig();
                loadData();
                sender.sendMessage(ChatColor.GREEN + "配置和数据已重新加载!");
            } else {
                sender.sendMessage(ChatColor.RED + "你没有权限执行此操作!");
            }
            return true;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("choose")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "只有玩家可以使用选择命令!");
                return true;
            }
            Player player = (Player) sender;
            if (waitingForChoice.contains(player.getUniqueId())) {
                try {
                    int choice = Integer.parseInt(args[1]);
                    handlePlayerChoice(player, choice);
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "无效的选择!");
                }
            }
            return true;
        }

        if (args.length >= 1) {
            Player targetPlayer;
            String dialogueId;

            if (sender instanceof ConsoleCommandSender && args.length >= 2) {
                dialogueId = args[0];
                String playerName = args[1];
                targetPlayer = Bukkit.getPlayer(playerName);
                if (targetPlayer == null) {
                    sender.sendMessage(ChatColor.RED + "玩家 " + playerName + " 不在线!");
                    return true;
                }
            } else if (sender instanceof Player) {
                dialogueId = args[0];
                targetPlayer = (Player) sender;
            } else {
                sender.sendMessage(ChatColor.RED + "命令格式错误!");
                sender.sendMessage(ChatColor.YELLOW + "控制台使用: /nd <对话ID> <玩家名>");
                sender.sendMessage(ChatColor.YELLOW + "玩家使用: /nd <对话ID>");
                return true;
            }

            startDialogue(targetPlayer, dialogueId);
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "使用方法:");
        if (sender instanceof ConsoleCommandSender) {
            sender.sendMessage(ChatColor.YELLOW + "/nd <对话ID> <玩家名> - 给指定玩家发送对话");
        } else {
            sender.sendMessage(ChatColor.YELLOW + "/nd <对话ID> - 与ID为<对话ID>的NPC对话");
        }
        sender.sendMessage(ChatColor.YELLOW + "/nd reload - 重新加载配置和数据");
        return true;
    }

    private void startDialogue(Player player, String dialogueId) {
        if (!config.contains("dialogue." + dialogueId)) {
            player.sendMessage(ChatColor.RED + "找不到ID为 " + dialogueId + " 的对话!");
            return;
        }

        boolean isFirstInteraction = !isFirstInteractionCompleted(player, dialogueId);

        List<String> dialogueSequence;
        if (isFirstInteraction && config.contains("dialogue." + dialogueId + ".first-interaction")) {
            dialogueSequence = new ArrayList<>(config.getStringList("dialogue." + dialogueId + ".first-interaction"));
        } else {
            dialogueSequence = new ArrayList<>(config.getStringList("dialogue." + dialogueId + ".dialog"));
        }

        DialogueState state = new DialogueState(dialogueId, dialogueSequence, 0, false, isFirstInteraction);
        playerDialogueStates.put(player.getUniqueId(), state);

        executeNextStep(player);
    }

    private boolean isFirstInteractionCompleted(Player player, String dialogueId) {
        return completedFirstInteractions.getOrDefault(player.getUniqueId(), Collections.emptySet()).contains(dialogueId);
    }

    private void markFirstInteractionCompleted(Player player, String dialogueId) {
        completedFirstInteractions.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>()).add(dialogueId);
        saveData();
    }

    private void executeNextStep(Player player) {
        UUID playerId = player.getUniqueId();
        DialogueState state = playerDialogueStates.get(playerId);

        if (state == null || state.isComplete() || state.isInChoiceBranch()) {
            return;
        }

        List<String> sequence = state.getSequence();
        int currentStep = state.getCurrentStep();

        if (currentStep >= sequence.size()) {
            state.setComplete(true);
            if (state.isFirstInteraction()) {
                markFirstInteractionCompleted(player, state.getDialogueId());
            }
            playerDialogueStates.remove(playerId);
            return;
        }

        String step = sequence.get(currentStep);
        state.setCurrentStep(currentStep + 1);
        processStep(player, step, state);
    }

    private void processStep(Player player, String step, DialogueState state) {
        if (step == null || step.trim().isEmpty()) {
            executeNextStep(player);
            return;
        }

        String[] parts = step.split(":", 2);
        if (parts.length < 2) {
            player.sendMessage(ChatColor.RED + "无效的对话配置: " + step);
            executeNextStep(player);
            return;
        }

        String action = parts[0].trim().toUpperCase();
        String value = parts[1].trim();

        try {
            switch (action) {
                case "SEND":
                    handleSendAction(player, value);
                    executeNextStep(player);
                    break;
                case "SOUND":
                    handleSoundAction(player, value);
                    executeNextStep(player);
                    break;
                case "WAIT":
                    handleWaitAction(player, value);
                    break;
                case "COMMAND":
                    handlePlayerCommandAction(player, value);
                    executeNextStep(player);
                    break;
                case "COMMANDOP":
                    handleOpCommandAction(player, value);
                    executeNextStep(player);
                    break;
                case "COMMANDCMD":
                    handleConsoleCommandAction(player, value);
                    executeNextStep(player);
                    break;
                case "CHOICE":
                    handleChoiceAction(player, value, state);
                    break;
                case "EFFECT":
                    handleEffectAction(player, value);
                    executeNextStep(player);
                    break;
                default:
                    player.sendMessage(ChatColor.RED + "未知的动作类型: " + action);
                    executeNextStep(player);
            }
        } catch (Exception e) {
            getLogger().warning("处理步骤时出错: " + e.getMessage());
            executeNextStep(player);
        }
    }

    private void handleSendAction(Player player, String message) {
        message = message.replace("%player_name%", player.getName())
                .replace("%user%", player.getName());
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    private void handleSoundAction(Player player, String soundData) {
        String[] parts = soundData.split(",");
        if (parts.length < 1) {
            getLogger().warning("无效的声音配置: " + soundData);
            return;
        }

        String soundName = parts[0].trim().replace("_", ".");
        if (!soundName.startsWith("minecraft:")) {
            soundName = "minecraft:" + soundName;
        }

        float volume = parts.length > 1 ? Float.parseFloat(parts[1].trim()) : 1.0f;
        float pitch = parts.length > 2 ? Float.parseFloat(parts[2].trim()) : 1.0f;

        try {
            String enumName = soundName.toUpperCase().replace("MINECRAFT:", "").replace(".", "_");
            Sound sound = Sound.valueOf(enumName);
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException e) {
            getLogger().warning("无效的声音名称: " + soundName + " - " + e.getMessage());
        }
    }

    private void handleWaitAction(Player player, String waitTime) {
        try {
            int seconds = Integer.parseInt(waitTime);
            long ticks = seconds * 20L;

            new BukkitRunnable() {
                @Override
                public void run() {
                    executeNextStep(player);
                }
            }.runTaskLater(this, ticks);

        } catch (NumberFormatException e) {
            getLogger().warning("无效的等待时间: " + waitTime);
            executeNextStep(player);
        }
    }

    private void handlePlayerCommandAction(Player player, String command) {
        command = command.replace("%player_name%", player.getName())
                .replace("%user%", player.getName());
        Bukkit.dispatchCommand(player, command);
    }

    private void handleOpCommandAction(Player player, String command) {
        command = command.replace("%player_name%", player.getName())
                .replace("%user%", player.getName());

        boolean wasOp = player.isOp();
        try {
            player.setOp(true);
            Bukkit.dispatchCommand(player, command);
        } finally {
            player.setOp(wasOp);
        }
    }

    private void handleConsoleCommandAction(Player player, String command) {
        command = command.replace("%player_name%", player.getName())
                .replace("%user%", player.getName());
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    private void handleEffectAction(Player player, String effectData) {
        String[] parts = effectData.split(",");
        if (parts.length < 3) {
            getLogger().warning("无效的药水效果配置: " + effectData);
            return;
        }

        String effectName = parts[0].trim().replace(" ", "_").toUpperCase();
        String durationStr = parts[1].trim();
        String levelStr = parts[2].trim();

        try {
            int durationSeconds = Integer.parseInt(durationStr.replaceAll("[^0-9]", ""));
            int durationTicks = durationSeconds * 20;

            int level = Integer.parseInt(levelStr.replaceAll("[^0-9]", "")) - 1;
            level = Math.max(0, level);

            PotionEffectType effectType = PotionEffectType.getByName(effectName);
            if (effectType == null) {
                getLogger().warning("无效的药水效果名称: " + effectName);
                return;
            }

            player.addPotionEffect(new PotionEffect(effectType, durationTicks, level));
        } catch (NumberFormatException e) {
            getLogger().warning("无效的药水效果数值: " + effectData);
        }
    }

    private void handleChoiceAction(Player player, String prompt, DialogueState state) {
        int[] choices = extractChoices(prompt);
        if (choices == null || choices.length == 0) {
            player.sendMessage(ChatColor.RED + "无效的选择配置!");
            executeNextStep(player);
            return;
        }

        String dialogueId = state.getDialogueId();
        List<Integer> validChoices = new ArrayList<>();
        for (int choiceNumber : choices) {
            if (config.contains("dialogue." + dialogueId + ".choice" + choiceNumber) &&
                    config.contains("dialogue." + dialogueId + ".choice" + choiceNumber + "-text")) {
                validChoices.add(choiceNumber);
            } else {
                getLogger().warning("选择 " + choiceNumber + " 的配置不完整，缺少对话内容或文本");
            }
        }

        if (validChoices.isEmpty()) {
            player.sendMessage(ChatColor.RED + "没有有效的选择配置!");
            executeNextStep(player);
            return;
        }

        state.setValidChoices(validChoices);

        String cleanPrompt = prompt.replaceAll("\\{[^}]+}", "").trim();
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', cleanPrompt));

        player.sendMessage(ChatColor.GOLD + "请选择:");
        for (int choiceNumber : validChoices) {
            String choiceText = config.getString("dialogue." + dialogueId + ".choice" + choiceNumber + "-text");
            sendClickableOption(player, choiceNumber, choiceText);
        }

        waitingForChoice.add(player.getUniqueId());
        state.setWaitingForChoice(true);
    }

    private int[] extractChoices(String prompt) {
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{(\\d+(,\\s*\\d+)*)}").matcher(prompt);
            if (!matcher.find()) return null;

            String choicesStr = matcher.group(1);
            if (choicesStr.isEmpty()) return null;

            String[] parts = choicesStr.split(",\\s*");
            int[] choices = new int[parts.length];

            for (int i = 0; i < parts.length; i++) {
                choices[i] = Integer.parseInt(parts[i].trim());
            }

            return choices;
        } catch (Exception e) {
            getLogger().warning("解析选项配置时出错: " + e.getMessage() + "，提示文本: " + prompt);
            return null;
        }
    }

    private void sendClickableOption(Player player, int choiceNumber, String text) {
        TextComponent message = new TextComponent();

        TextComponent number = new TextComponent(choiceNumber + ". ");
        number.setColor(net.md_5.bungee.api.ChatColor.YELLOW);
        message.addExtra(number);

        TextComponent option = new TextComponent(text);
        option.setColor(net.md_5.bungee.api.ChatColor.AQUA);
        option.setUnderlined(true);

        option.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/nd choose " + choiceNumber));

        option.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("点击选择: " + text).color(net.md_5.bungee.api.ChatColor.GRAY).create()));

        // 关键修复：将选项文本添加到消息组件中
        message.addExtra(option);

        player.spigot().sendMessage(message);
    }

    private void handlePlayerChoice(Player player, int choice) {
        UUID playerId = player.getUniqueId();
        DialogueState state = playerDialogueStates.get(playerId);

        if (state == null || !state.isWaitingForChoice()) {
            return;
        }

        if (!state.getValidChoices().contains(choice)) {
            player.sendMessage(ChatColor.RED + "无效的选择! 请选择提供的选项。");
            return;
        }

        waitingForChoice.remove(playerId);
        state.setWaitingForChoice(false);

        String dialogueId = state.getDialogueId();
        String choicePath = "dialogue." + dialogueId + ".choice" + choice;

        if (!config.contains(choicePath)) {
            player.sendMessage(ChatColor.RED + "无效的选择配置!");
            executeNextStep(player);
            return;
        }

        List<String> choiceSequence = config.getStringList(choicePath);
        state.setInChoiceBranch(true);

        startChoiceSequenceProcessing(player, state, choiceSequence, 0);
    }

    private void startChoiceSequenceProcessing(Player player, DialogueState state, List<String> sequence, int currentIndex) {
        if (currentIndex >= sequence.size()) {
            state.setInChoiceBranch(false);
            executeNextStep(player);
            return;
        }

        String step = sequence.get(currentIndex);
        processStep(player, step, state);

        new BukkitRunnable() {
            @Override
            public void run() {
                startChoiceSequenceProcessing(player, state, sequence, currentIndex + 1);
            }
        }.runTaskLater(this, step.trim().toUpperCase().startsWith("WAIT:") ? 1 : 1);
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (waitingForChoice.contains(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "请点击选项进行选择，或使用/nd choose [id]进行选择。");
        }
    }

    private static class DialogueState {
        private final String dialogueId;
        private final List<String> sequence;
        private int currentStep;
        private boolean isComplete;
        private boolean isWaitingForChoice;
        private boolean isInChoiceBranch;
        private final boolean isFirstInteraction;
        private List<Integer> validChoices;

        public DialogueState(String dialogueId, List<String> sequence, int currentStep, boolean isComplete, boolean isFirstInteraction) {
            this.dialogueId = dialogueId;
            this.sequence = sequence;
            this.currentStep = currentStep;
            this.isComplete = isComplete;
            this.isWaitingForChoice = false;
            this.isInChoiceBranch = false;
            this.isFirstInteraction = isFirstInteraction;
            this.validChoices = new ArrayList<>();
        }

        public String getDialogueId() {
            return dialogueId;
        }

        public List<String> getSequence() {
            return sequence;
        }

        public int getCurrentStep() {
            return currentStep;
        }

        public void setCurrentStep(int currentStep) {
            this.currentStep = currentStep;
        }

        public boolean isComplete() {
            return isComplete;
        }

        public void setComplete(boolean complete) {
            isComplete = complete;
        }

        public boolean isWaitingForChoice() {
            return isWaitingForChoice;
        }

        public void setWaitingForChoice(boolean waitingForChoice) {
            isWaitingForChoice = waitingForChoice;
        }

        public boolean isInChoiceBranch() {
            return isInChoiceBranch;
        }

        public void setInChoiceBranch(boolean inChoiceBranch) {
            isInChoiceBranch = inChoiceBranch;
        }

        public boolean isFirstInteraction() {
            return isFirstInteraction;
        }

        public List<Integer> getValidChoices() {
            return validChoices;
        }

        public void setValidChoices(List<Integer> validChoices) {
            this.validChoices = validChoices;
        }
    }
}
