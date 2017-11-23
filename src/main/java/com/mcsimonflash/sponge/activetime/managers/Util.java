package com.mcsimonflash.sponge.activetime.managers;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mcsimonflash.sponge.activetime.ActiveTime;
import com.mcsimonflash.sponge.activetime.objects.TimeWrapper;
import ninja.leaping.configurate.hocon.HoconConfigurationLoader;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.serializer.TextSerializers;
import org.spongepowered.api.util.Identifiable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Util {

    public static final Text prefix = toText("&b[&fActiveTime&b]&r ");
    public static final int[] timeConst = {604800, 86400, 3600, 60, 1};
    public static final String[] unitAbbrev = {"w", "d", "h", "m", "s"};
    public static final Pattern timeFormat = Pattern.compile("(?:([0-9]+)w)?(?:([0-9]+)d)?(?:([0-9]+)h)?(?:([0-9]+)m)?(?:([0-9])+s)?");

    public static void initialize() {
        Config.readConfig();
        Storage.readStorage();
    }

    public static void startTasks() {
        if (Storage.updateTask != null) {
            Storage.updateTask.cancel();
        }
        if (Storage.saveTask != null) {
            Storage.saveTask.cancel();
        }
        if (Storage.milestoneTask != null) {
            Storage.milestoneTask.cancel();
        }
        if (Storage.limitTask != null) {
            Storage.limitTask.cancel();
        }
        startUpdateTask();
        startSaveTask();
        if (Config.milestoneInterval > 0) {
            startMilestoneTask();
        }
        if (Config.limitInterval > 0) {
            startLimitTask();
        }
    }

    public static HoconConfigurationLoader getLoader(Path path, boolean asset) throws IOException {
        try {
            if (Files.notExists(path)) {
                if (asset) {
                    Sponge.getAssetManager().getAsset(ActiveTime.getPlugin(), path.getFileName().toString()).get().copyToFile(path);
                } else {
                    Files.createFile(path);
                }
            }
            return HoconConfigurationLoader.builder().setPath(path).build();
        } catch (IOException e) {
            ActiveTime.getPlugin().getLogger().error("Error loading file! File:[" + path.getFileName().toString() + "]");
            throw e;
        }
    }

    public static Text toText(String msg) {
        return TextSerializers.FORMATTING_CODE.deserialize(msg);
    }

    public static int parseTime(String timeStr) {
        try {
            return Integer.parseInt(timeStr);
        } catch (NumberFormatException ignored) {
            int time = 0;
            Matcher matcher = timeFormat.matcher(timeStr);
            if (matcher.matches()) {
                for (int i = 1; i <= 5; i++) {
                    if (matcher.group(i) != null) {
                        time += Integer.parseInt(matcher.group(i)) * timeConst[i - 1];
                    }
                }
            }
            return time;
        }
    }

    public static String printTime(int time) {
        if (time <= 0) {
            return time + "s";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            if (time / timeConst[i] > 0) {
                builder.append(time / timeConst[i]).append(unitAbbrev[i]);
                time = time % timeConst[i];
            }
        }
        return builder.toString();
    }

    public static String printTime(TimeWrapper time) {
        return "&b" + Util.printTime(time.getActiveTime()) + (ActiveTime.isNucleusEnabled() && time.getActiveTime() + time.getAfkTime() != 0 ? " &f| &b" + Util.printTime(time.getAfkTime()) + " &f- &7" + new DecimalFormat("##.##%").format((double) time.getActiveTime() / (time.getActiveTime() + time.getAfkTime())) : "");
    }

    public static String printDate(LocalDate date) {
        return date.getMonthValue() + "/" + date.getDayOfMonth();
    }

    public static void startNameTask(Player player) {
        Task.builder()
                .name("ActiveTime SetUsername Task (" + player.getName() + ")")
                .execute(task -> {
                    String name = Storage.getUsername(player.getUniqueId());
                    if (!player.getName().equals(name) && !Storage.setUsername(player.getUniqueId(), player.getName())) {
                        ActiveTime.getPlugin().getLogger().error("Error updating username. | UUID:[" + player.getUniqueId() + "] OldName:[" + name + "] NewName:[" + player.getName() + "]");
                    }
                })
                .async()
                .submit(ActiveTime.getPlugin());
    }

    public static void startUpdateTask() {
        if (ActiveTime.isNucleusEnabled()) {
            Storage.updateTask = Task.builder()
                    .name("ActiveTime UpdatePlayerTimes Task (w/ Nucleus)")
                    .execute(task -> {
                        Map<UUID, Boolean> players = Maps.newHashMap();
                        Sponge.getServer().getOnlinePlayers().stream().filter(p -> p.hasPermission("activetime.log.base")).forEach(p -> players.put(p.getUniqueId(), !NucleusIntegration.isPlayerAfk(p)));
                        Task.builder()
                                .name("ActiveTime UpdatePlayerTimes Task (Async Processor w/ Nucleus)")
                                .execute(t -> players.forEach((uuid, active) -> addCachedTime(uuid, 1, active)))
                                .async()
                                .submit(ActiveTime.getPlugin());
                    })
                    .interval(Config.updateInterval * 1000 - 1, TimeUnit.MILLISECONDS)
                    .submit(ActiveTime.getPlugin());
        } else {
            Storage.updateTask = Task.builder()
                    .name("ActiveTime UpdatePlayerTimes Task (w/out Nucleus)")
                    .execute(task -> {
                        List<UUID> players = Sponge.getServer().getOnlinePlayers().stream().filter(p -> p.hasPermission("activetime.log.base")).map(Identifiable::getUniqueId).collect(Collectors.toList());
                        Task.builder()
                                .name("ActiveTime UpdatePlayerTimes Task (Async Processor w/out Nucleus)")
                                .execute(t -> players.forEach((uuid) -> addCachedTime(uuid, Config.updateInterval, true)))
                                .async()
                                .submit(ActiveTime.getPlugin());
                    })
                    .interval(Config.updateInterval * 1000 - 1, TimeUnit.MILLISECONDS)
                    .submit(ActiveTime.getPlugin());
        }
    }

    public static void addCachedTime(UUID uuid, int time, boolean active) {
        Storage.times.computeIfAbsent(uuid, u -> new TimeWrapper(0, 0)).add(time, active);
    }

    public static void startSaveTask() {
        Storage.saveTask = Task.builder()
                .name("ActiveTime SavePlayerTimes Task")
                .execute(task -> {
                    ImmutableMap<UUID, TimeWrapper> times = ImmutableMap.copyOf(Storage.times);
                    Storage.times.clear();
                    Task.builder()
                            .name("ActiveTime SavePlayerTimes Task (Async Processor)")
                            .execute(t -> {
                                times.forEach((Util::saveTime));
                                Storage.syncCurrentDate();
                                Storage.buildLeaderboard();
                            })
                            .async()
                            .submit(ActiveTime.getPlugin());
                })
                .interval(Config.saveInterval * 1000 - 1, TimeUnit.MILLISECONDS)
                .submit(ActiveTime.getPlugin());
    }

    public static void saveTime(UUID uuid, TimeWrapper time) {
        boolean total = Storage.setTotalTime(uuid, Storage.getTotalTime(uuid).add(time));
        boolean daily = Storage.setDailyTime(uuid, Storage.getDailyTime(uuid).add(time));
        if (!daily || !total) {
            ActiveTime.getPlugin().getLogger().error("Error saving time for uuid " + uuid + "!");
        }
    }

    public static void startMilestoneTask() {
        Storage.milestoneTask = Task.builder()
                .name("ActiveTime CheckMilestones Task")
                .execute(task -> {
                    List<Player> players = Lists.newArrayList(Sponge.getServer().getOnlinePlayers());
                    Task.builder()
                            .name("ActiveTime CheckMilestones Task (Async Processor)")
                            .execute(t -> players.forEach(Util::checkMilestones))
                            .async()
                            .submit(ActiveTime.getPlugin());
                })
                .interval(Config.milestoneInterval * 1000 - 1, TimeUnit.MILLISECONDS)
                .submit(ActiveTime.getPlugin());
    }

    public static void checkMilestones(Player player) {
        int activetime = Storage.getTotalTime(player.getUniqueId()).getActiveTime();
        Storage.milestones.forEach(m -> m.process(player, activetime));
    }

    public static void startLimitTask() {
        Storage.limitTask = Task.builder()
                .name("ActiveTime LimitPlayerTimes Task")
                .execute(task -> {
                    List<Player> players = Lists.newArrayList(Sponge.getServer().getOnlinePlayers());
                    Task.builder()
                            .name("ActiveTime LimitPlayerTimes Task (Async Processor)")
                            .execute(t -> players.forEach(Util::checkLimit))
                            .async()
                            .submit(ActiveTime.getPlugin());
                })
                .interval(Config.limitInterval * 1000 - 1, TimeUnit.MILLISECONDS)
                .submit(ActiveTime.getPlugin());
    }

    public static void checkLimit(Player player) {
        player.getOption("playtime").ifPresent(o -> {
            try {
                int limit = parseTime(o);
                if (Storage.getDailyTime(player.getUniqueId()).getActiveTime() >= limit) {
                    Task.builder()
                            .name("ActiveTime KickPlayer Task")
                            .execute(t -> player.kick(Util.toText("You have surpassed your playtime for the day of " + limit + "!")))
                            .submit(ActiveTime.getPlugin());
                }
            } catch (NumberFormatException e) {
                ActiveTime.getPlugin().getLogger().error("Invalid playtime option for player " + player.getName() + "!");
            }
        });
    }

}