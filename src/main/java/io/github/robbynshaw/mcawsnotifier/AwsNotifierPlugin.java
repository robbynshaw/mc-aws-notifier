package io.github.robbynshaw.mcawsnotifier;

import java.util.Timer;
import java.util.TimerTask;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import lombok.Getter;
import lombok.experimental.Accessors;
// import software.amazon.awssdk.services.ecs.model.UpdateServiceRequest;

public class AwsNotifierPlugin extends JavaPlugin implements Listener {
    private boolean worldIsActive = false;
    private boolean timerIsRunning = false;
    private final Timer timer;
    private int checkInterval;
    private final int defaultCheckMins = 1;
    private int checkMins;
    private int activeUsers = 0;

    @Getter
    @Accessors(fluent = true)
    private static AwsNotifierPlugin instance;

    public AwsNotifierPlugin() {
        instance = this;
        timer = new Timer();

        try {
            String checkVal = System.getenv("ACTIVE_PLAYER_CHECK_INTERVAL_MINUTES");
            checkMins = Integer.parseInt(checkVal);
        } catch (NumberFormatException e) {
            checkMins = defaultCheckMins;
        }
        checkInterval = 1000 * 60 * checkMins;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onWorldInit(WorldLoadEvent event) {
        if (!worldIsActive) {
            worldIsActive = true;
            getLogger().info("Looks like the server is up and running.");
            startNewTimeout();
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        activeUsers++;
        getLogger().info("Player joined. Cancelling the shutdown timer...");
        cancelTimer();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        activeUsers--;
        getLogger().info("Player quit: " + activeUsers + " player(s) on the server.");
        if (activeUsers == 0) {
            startNewTimeout();
        }
    }

    private void zeroService() {
        // UpdateServiceRequest updateServiceRequest = UpdateServiceRequest.builder()
        // .cluster(clusterName)
        // .service(serviceName)
        // .desiredCount(0) // Set desired instances to zero
        // .build();

        // // Update the service
        // Service updatedService =
        // ecsClient.updateService(updateServiceRequest).service();
    }

    private void cancelTimer() {
        try {
            if (timerIsRunning) {
                timer.cancel();
            }
        } catch (IllegalStateException ex) {
            getLogger().warning("Timer was already cancelled");
        }
    }

    private void startNewTimeout() {
        getLogger().info("Scheduling an active player check in " + checkMins + " minute(s)...");
        cancelTimer();
        timer.schedule(new CheckUsersTask(), checkInterval);
        timerIsRunning = true;
    }

    private void checkForActiveUsers() {
        getLogger().info("Checking active players: " + activeUsers + " found.");
        if (activeUsers == 0) {
            getLogger().info("No active users for the last " + checkMins + " minute(s). Shutting down...");
        }
    }

    private class CheckUsersTask extends TimerTask {
        @Override
        public void run() {
            checkForActiveUsers();
            timerIsRunning = false;
        }
    }
}
