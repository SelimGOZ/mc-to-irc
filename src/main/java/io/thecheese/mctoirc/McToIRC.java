package io.thecheese.mctoirc;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class McToIRC extends JavaPlugin implements Listener {

    private String ircServer;
    private int ircPort;
    private String ircChannel;
    private String botName;
    private boolean startupEnabled;
    private String startupMessage;
    private boolean creditsSent;

    private static final String SPECIAL_PLAYER = "TechflashYT";
    private static final String JOIN_MESSAGE = "The Lord Has Joined The Game";
    private static final String QUIT_MESSAGE = "The Lord Has Left The Game";

    private final Map<String, String> kickReasons = new HashMap<>();
    private boolean serverStopping = false;

    private Socket ircSocket;
    private BufferedReader reader;
    private PrintWriter writer;
    private Thread ircThread;
    private final BlockingQueue<String> outQueue = new ArrayBlockingQueue<>(100);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(true);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        connectToIRC();
        getServer().getPluginManager().registerEvents(this, this);
        startOutputProcessor();
    }

    private void loadConfigValues() {
        FileConfiguration config = getConfig();
        ircServer = config.getString("irc.server", "irc.example.net");
        ircPort = config.getInt("irc.port", 6667);
        ircChannel = config.getString("irc.channel", "#example");
        String rawBotName = config.getString("irc.botName", "Example");
        botName = rawBotName.substring(0, Math.min(rawBotName.length(), 20));
        startupEnabled = config.getBoolean("startup.enabled", true);
        startupMessage = config.getString("startup.message", "Example for Example by Example to Example");
        creditsSent = config.getBoolean("startup.credits", false);
    }

    private void connectToIRC() {
        ircThread = new Thread(() -> {
            while (running.get()) {
                try {
                    ircSocket = new Socket(ircServer, ircPort);
                    reader = new BufferedReader(new InputStreamReader(ircSocket.getInputStream(), StandardCharsets.UTF_8));
                    writer = new PrintWriter(new OutputStreamWriter(ircSocket.getOutputStream(), StandardCharsets.UTF_8), true);

                    writer.println("NICK " + botName);
                    writer.println("USER " + botName + " 0 * :Minecraft IRC Bridge");
                    writer.println("JOIN " + ircChannel);

                    if (startupEnabled && !creditsSent) {
                        writer.println("PRIVMSG " + ircChannel + " :" + startupMessage);
                        getConfig().set("startup.credits", true);
                        saveConfig();
                        creditsSent = true;
                    }

                    connected.set(true);

                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("PING")) {
                            writer.println("PONG " + line.substring(5));
                            continue;
                        }

                        if (line.contains("PRIVMSG " + ircChannel)) {
                            processIRCMessage(line);
                        }
                    }
                } catch (Exception e) {
                } finally {
                    connected.set(false);
                    closeResources();
                    if (running.get()) {
                        try { Thread.sleep(10000); } catch (InterruptedException ignored) {}
                    }
                }
            }
        }, "IRC-Connection");
        ircThread.setDaemon(true);
        ircThread.start();
    }

    private void processIRCMessage(String raw) {
        try {
            int firstSpace = raw.indexOf(' ');
            int secondSpace = raw.indexOf(' ', firstSpace + 1);
            int colonIndex = raw.indexOf(':', 1);

            if (firstSpace <= 0 || secondSpace <= 0 || colonIndex <= 0) return;

            String sender = raw.substring(1, raw.indexOf('!'));
            String message = raw.substring(colonIndex + 1);

            sender = cleanString(sender);

            String formatted;

            if ("DiscordBridge".equals(sender) && message.startsWith("<")) {
                int endIdx = message.indexOf('>');
                if (endIdx > 0) {
                    String user = message.substring(1, endIdx);
                    String actualMsg = message.substring(endIdx + 1).trim();

                    int hashIdx = user.indexOf('#');
                    if (hashIdx > 0) user = user.substring(0, hashIdx);

                    user = cleanString(user);

                    formatted = "§5[Discord] §7<§f" + user + "§7> §f" + actualMsg;
                    Bukkit.getScheduler().runTask(this, () -> Bukkit.broadcastMessage(formatted));
                    return;
                }
            }

            formatted = "§9[IRC] §7<§f" + sender + "§7> §f" + cleanString(message);
            Bukkit.getScheduler().runTask(this, () -> Bukkit.broadcastMessage(formatted));
        } catch (Exception e) {
        }
    }

    private String cleanString(String input) {
        StringBuilder clean = new StringBuilder(input.length());
        for (char c : input.toCharArray()) {
            if (c >= 32 || (c >= 9 && c <= 13)) {
                clean.append(c);
            }
        }
        return clean.toString();
    }

    private void startOutputProcessor() {
        Thread outputThread = new Thread(() -> {
            while (running.get()) {
                try {
                    String message = outQueue.take();
                    if (connected.get() && writer != null) {
                        String clean = message.replaceAll("§[0-9a-fk-or]", "");
                        if (clean.length() > 400) {
                            clean = clean.substring(0, 400) + "...";
                        }
                        writer.println("PRIVMSG " + ircChannel + " :" + clean);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "IRC-Output");
        outputThread.setDaemon(true);
        outputThread.start();
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        String message = "<" + event.getPlayer().getName() + "> " + event.getMessage();
        outQueue.offer(message);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String message;
        if (SPECIAL_PLAYER.equalsIgnoreCase(player.getName())) {
            message = JOIN_MESSAGE;
        } else {
            message = "[+] " + player.getName() + " joined the game";
        }
        outQueue.offer(message);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerKick(PlayerKickEvent event) {
        Player player = event.getPlayer();
        String reason = event.getReason();
        kickReasons.put(player.getName(), reason);

        String message;
        if (SPECIAL_PLAYER.equalsIgnoreCase(player.getName())) {
            message = "The Lord Has Been Kicked: " + reason;
        } else {
            message = "[-] " + player.getName() + " was kicked: " + reason;
        }
        outQueue.offer(message);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();

        String kickReason = kickReasons.remove(playerName);
        if (kickReason != null) {
            return;
        }

        boolean isBanned = Bukkit.getBanList(BanList.Type.NAME).isBanned(playerName);

        String message;
        if (SPECIAL_PLAYER.equalsIgnoreCase(playerName)) {
            message = QUIT_MESSAGE + (isBanned ? " (Banned)" : "");
        } else {
            message = "[-] " + playerName + " left the game" + (isBanned ? " (Banned)" : "");
        }

        outQueue.offer(message);
    }

    @Override
    public void onDisable() {
        serverStopping = true;
        running.set(false);
        closeResources();
        if (ircThread != null && ircThread.isAlive()) {
            ircThread.interrupt();
        }
    }

    private void closeResources() {
        try {
            if (writer != null) writer.close();
            if (reader != null) reader.close();
            if (ircSocket != null) ircSocket.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("tpss")) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    String stats = getServerStats();
                    Bukkit.broadcastMessage(ChatColor.GOLD + "[Server Stats] " + ChatColor.RESET + stats);
                    outQueue.offer("[Server Stats] " + stats);
                }
            }.runTaskAsynchronously(this);
            return true;
        }
        return false;
    }

    private String getServerStats() {
        try {
            DecimalFormat df = new DecimalFormat("0.00");

            long maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024);
            long allocatedMemory = Runtime.getRuntime().totalMemory() / (1024 * 1024);
            long freeMemory = Runtime.getRuntime().freeMemory() / (1024 * 1024);
            double cpuLoad = getCpuLoad();

            int entities = 0;
            int players = Bukkit.getOnlinePlayers().size();
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                entities += world.getEntities().size();
            }

            String tps = "Unknown";
            try {
                if (Bukkit.getServer().getClass().getMethod("getTPS") != null) {
                    double[] tpsArray = Bukkit.getTPS();
                    tps = df.format(tpsArray[0]) + ", " + df.format(tpsArray[1]) + ", " + df.format(tpsArray[2]);
                }
            } catch (NoSuchMethodException ignored) {}

            return String.format(
                    "TPS: %s | CPU: %s%% | Memory: %d/%dMB | Entities: %d | Players: %d",
                    tps,
                    df.format(cpuLoad * 100),
                    allocatedMemory - freeMemory,
                    maxMemory,
                    entities,
                    players
            );
        } catch (Exception e) {
            return "Error gathering server stats";
        }
    }

    private double getCpuLoad() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                return ((com.sun.management.OperatingSystemMXBean) osBean).getProcessCpuLoad();
            }
            return osBean.getSystemLoadAverage() / osBean.getAvailableProcessors();
        } catch (Exception e) {
            return -1;
        }
    }
}