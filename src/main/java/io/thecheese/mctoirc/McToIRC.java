package io.thecheese.mctoirc;

import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.pircbotx.Configuration;
import org.pircbotx.PircBotX;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.ConnectEvent;
import org.pircbotx.hooks.events.MessageEvent;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;

public class McToIRC extends JavaPlugin implements Listener {

    private PircBotX bot;
    private final BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();
    private volatile boolean connected = false;
    private FileConfiguration config;
    private File configFile;

    private String ircServer = "irc.wii-linux.org";
    private int ircPort = 6667;
    private String ircChannel = "#minecraft";
    private String botName = "MinecraftBridge";
    private boolean startupEnabled = true;
    private String startupMessage = "Wii-Linux IRC to MC Bridging Plugin Brought to you by Selim";
    private boolean creditsSent = false;

    @Override
    public void onEnable() {

        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveResource("config.yml", false);
            getLogger().info("Created default config.yml");
        }

        config = new YamlConfiguration();
        try {
            config.load(configFile);
            getLogger().info("Loaded config.yml successfully");
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().log(Level.SEVERE, "Failed to load config.yml! Using defaults.", e);

            File brokenConfig = new File(getDataFolder(), "config_broken.yml");
            if (configFile.renameTo(brokenConfig)) {
                getLogger().warning("Renamed broken config to config_broken.yml");
            }

            saveResource("config.yml", false);
            config = YamlConfiguration.loadConfiguration(configFile);
        }

        loadConfigValues();

        Configuration ircConfig = new Configuration.Builder()
                .setName(botName)
                .setServer(ircServer, ircPort)
                .addAutoJoinChannel(ircChannel)
                .addListener(new IRCListener(this))
                .buildConfiguration();

        bot = new PircBotX(ircConfig);

        ircConfig.getListenerManager().addListener(new ListenerAdapter() {
            @Override
            public void onConnect(ConnectEvent event) {
                connected = true;
                getLogger().info("IRC connection established");

                if (startupEnabled && !creditsSent) {
                    bot.sendIRC().message(ircChannel, startupMessage);

                    config.set("startup.credits", true);
                    try {
                        config.save(configFile);
                        creditsSent = true;
                    } catch (IOException e) {
                        getLogger().warning("Failed to save config: " + e.getMessage());
                    }
                }

                new Thread(() -> {
                    while (!messageQueue.isEmpty()) {
                        String msg = messageQueue.poll();
                        if (msg != null) {
                            bot.sendIRC().message(ircChannel, msg);
                        }
                    }
                }, "Message-Processor").start();
            }
        });

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                bot.startBot();
            } catch (Exception e) {
                getLogger().warning("IRC connection failed: " + e.getMessage());
                connected = false;
            }
        });

        getServer().getPluginManager().registerEvents(this, this);

        startMessageProcessor();
    }

    private void loadConfigValues() {
        try {
            if (config.isConfigurationSection("irc")) {
                ircServer = config.getString("irc.server", ircServer);
                ircPort = config.getInt("irc.port", ircPort);
                ircChannel = config.getString("irc.channel", ircChannel);
                botName = config.getString("irc.botName", botName);
            }

            if (config.isConfigurationSection("startup")) {
                startupEnabled = config.getBoolean("startup.enabled", startupEnabled);
                startupMessage = config.getString("startup.message", startupMessage);
                creditsSent = config.getBoolean("startup.credits", creditsSent);
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error loading config values, using defaults", e);
        }

        getLogger().info("Config values loaded:");
        getLogger().info("IRC Server: " + ircServer + ":" + ircPort);
        getLogger().info("Channel: " + ircChannel);
        getLogger().info("Bot Name: " + botName);
        getLogger().info("Startup Enabled: " + startupEnabled);
        getLogger().info("Startup Message: " + startupMessage);
        getLogger().info("Credits Sent: " + creditsSent);
    }

    private void startMessageProcessor() {
        new Thread(() -> {
            while (true) {
                try {
                    String message = messageQueue.take();
                    if (connected) {
                        bot.sendIRC().message(ircChannel, message);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "Message-Processor").start();
    }

    @Override
    public void onDisable() {
        if (bot != null && bot.isConnected()) {
            bot.sendIRC().quitServer("Plugin disabled");
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        String message = "<" + event.getPlayer().getName() + "> " + event.getMessage();
        messageQueue.offer(message);
    }

    private static class IRCListener extends ListenerAdapter {
        private final McToIRC plugin;

        public IRCListener(McToIRC plugin) {
            this.plugin = plugin;
        }

        @Override
        public void onMessage(MessageEvent event) {
            if (event.getUser().getNick().equals(plugin.bot.getNick())) return;

            String sender = event.getUser().getNick();
            String message = event.getMessage();

            sender = sender.replaceAll("\\p{C}", "");

            String prefix = "§9[IRC]";
            String formatted;

            if ("DiscordBridge".equals(sender) && message.startsWith("<") && message.contains(">")) {
                int endIdx = message.indexOf('>');
                String user = message.substring(1, endIdx);
                String actualMsg = message.substring(endIdx + 1).trim();

                int hashIdx = user.indexOf('#');
                if (hashIdx > 0) user = user.substring(0, hashIdx);

                user = user.replaceAll("\\p{C}", "");

                prefix = "§5[Discord]";
                formatted = prefix + " §7<§f" + user + "§7> §f" + actualMsg;
            } else {
                formatted = prefix + " §7<§f" + sender + "§7> §f" + message;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.broadcastMessage(formatted);
            });
        }
    }
}