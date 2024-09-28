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
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.UpdateServiceRequest;

public class AwsNotifierPlugin extends JavaPlugin implements Listener {
    private boolean worldIsActive = false;
    private boolean timerIsRunning = false;
    private final Timer timer;
    private int shutdownTimeoutMS;
    private final int defaultTimeoutMins = 1;
    private final int timeoutMins;
    private int activeUsers = 0;

    private final String CLUSTER_NAME;
    private final String SERVICE_ARN;

    @Getter
    @Accessors(fluent = true)
    private static AwsNotifierPlugin instance;

    public AwsNotifierPlugin() {
        instance = this;
        timer = new Timer();

        timeoutMins = AwsNotifierPlugin.GetEnvInt("SHUTDOWN_TIMEOUT_MINUTES", defaultTimeoutMins);
        shutdownTimeoutMS = 1000 * 60 * timeoutMins;

        CLUSTER_NAME = System.getenv("CLUSTER_NAME");
        SERVICE_ARN = System.getenv("SERVICE_ARN");
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
        getLogger().info("Scheduling an active player check in " + shutdownTimeoutMS + " minute(s)...");
        cancelTimer();
        timer.schedule(new CheckUsersTask(), shutdownTimeoutMS);
        timerIsRunning = true;
    }

    private void checkForActiveUsers() {
        getLogger().info("Checking active players: " + activeUsers + " found.");
        if (activeUsers == 0) {
            getLogger().info("No active users for the last " + timeoutMins + " minute(s). Shutting down...");
            zeroService();
        }
    }

    private void zeroService() {
        EcsClient ecsClient = EcsClient.builder().region(Region.US_EAST_1).build();

        UpdateServiceRequest updateServiceRequest = UpdateServiceRequest.builder()
                .cluster(CLUSTER_NAME)
                .service(SERVICE_ARN)
                .desiredCount(0) // Set desired instances to zero
                .build();

        ecsClient.updateService(updateServiceRequest).service();

        ecsClient.close();
    }

    private class CheckUsersTask extends TimerTask {
        @Override
        public void run() {
            checkForActiveUsers();
            timerIsRunning = false;
        }
    }

    private static int GetEnvInt(String key, int defaultValue) {
        try {
            String checkVal = System.getenv(key);
            return Integer.parseInt(checkVal);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
