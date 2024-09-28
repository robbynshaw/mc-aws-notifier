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
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Attachment;
import software.amazon.awssdk.services.ecs.model.DescribeTasksRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTasksResponse;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.ListTasksRequest;
import software.amazon.awssdk.services.ecs.model.ListTasksResponse;
import software.amazon.awssdk.services.ecs.model.Task;
import software.amazon.awssdk.services.ecs.model.UpdateServiceRequest;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.Change;
import software.amazon.awssdk.services.route53.model.ChangeAction;
import software.amazon.awssdk.services.route53.model.ChangeBatch;
import software.amazon.awssdk.services.route53.model.ChangeResourceRecordSetsRequest;
import software.amazon.awssdk.services.route53.model.RRType;
import software.amazon.awssdk.services.route53.model.ResourceRecord;
import software.amazon.awssdk.services.route53.model.ResourceRecordSet;
import software.amazon.awssdk.services.route53.model.Route53Exception;

public class AwsNotifierPlugin extends JavaPlugin implements Listener {
    private boolean worldIsActive = false;
    private boolean timerIsRunning = false;
    private final Timer timer;
    private int shutdownTimeoutMS;
    private final int defaultTimeoutMins = 1;
    private final int timeoutMins;
    private int activeUsers = 0;

    private final String CLUSTER_NAME;
    private final String SERVER_NAME;
    private final String SERVICE_NAME;
    private final String HOSTED_ZONE_ID;

    private final Region REGION = Region.US_EAST_1;

    @Getter
    @Accessors(fluent = true)
    private static AwsNotifierPlugin instance;

    public AwsNotifierPlugin() {
        instance = this;
        timer = new Timer();

        timeoutMins = AwsNotifierPlugin.GetEnvInt("SHUTDOWN_TIMEOUT_MINUTES", defaultTimeoutMins);
        shutdownTimeoutMS = 1000 * 60 * timeoutMins;

        CLUSTER_NAME = System.getenv("CLUSTER_NAME");
        SERVER_NAME = System.getenv("SERVER_NAME");
        SERVICE_NAME = System.getenv("SERVICE_NAME");
        HOSTED_ZONE_ID = System.getenv("HOSTED_ZONE_ID");
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
            updateDnsRecord();
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

    private void updateDnsRecord() {
        EcsClient ecs = EcsClient.builder().region(REGION).build();
        Ec2Client ec2 = Ec2Client.builder().region(REGION).build();
        Route53Client route53 = Route53Client.builder().region(REGION).build();

        try {

            TaskInfo info = getTaskInfo(ecs);
            getLogger().info("Our task ID is " + info.ID);
            getLogger().info("Our ENI is " + info.Eni);

            String ip = getPublicIp(ec2, info.Eni);
            getLogger().info("Public IP is " + ip);

            addARecord(route53, ip);
            getLogger().info("DNS record updated successfully!");
        } catch (UpdateDNSException err) {
            getLogger().warning("Failed to update DNS record: " + err.getMessage());
        } finally {
            ec2.close();
            ecs.close();
            route53.close();
        }
    }

    private void addARecord(Route53Client client, String ip) throws UpdateDNSException {
        ChangeBatch changeBatch = ChangeBatch.builder()
                .comment("Fargate Public IP change for Minecraft Server")
                .changes(Change.builder()
                        .action(ChangeAction.UPSERT)
                        .resourceRecordSet(ResourceRecordSet.builder()
                                .name(SERVER_NAME)
                                .type(RRType.A)
                                .ttl(30L)
                                .resourceRecords(ResourceRecord.builder().value(ip).build())
                                .build())
                        .build())
                .build();

        ChangeResourceRecordSetsRequest request = ChangeResourceRecordSetsRequest.builder()
                .hostedZoneId(HOSTED_ZONE_ID)
                .changeBatch(changeBatch)
                .build();

        try {
            client.changeResourceRecordSets(request);
        } catch (Route53Exception e) {
            throw new UpdateDNSException("Error updating DNS record: " + e.getMessage());
        }
    }

    private TaskInfo getTaskInfo(EcsClient client) throws UpdateDNSException {
        TaskInfo info = new TaskInfo();

        ListTasksRequest lsReq = ListTasksRequest.builder().cluster(CLUSTER_NAME).serviceName(SERVICE_NAME).build();
        ListTasksResponse lsResp = client.listTasks(lsReq);
        if (!lsResp.hasTaskArns()) {
            throw new UpdateDNSException("No tasks found");
        }
        String taskArn = lsResp.taskArns().get(0);

        DescribeTasksRequest req = DescribeTasksRequest.builder().cluster(CLUSTER_NAME).tasks(taskArn).build();

        DescribeTasksResponse resp = client.describeTasks(req);
        if (!resp.hasTasks()) {
            throw new UpdateDNSException("No tasks found");
        }

        Task task = resp.tasks().get(0);
        String[] segments = task.taskArn().split("/");
        if (segments.length == 1) {
            throw new UpdateDNSException("Unable to find task ID");
        }
        info.ID = segments[segments.length - 1];

        if (!task.hasAttachments()) {
            throw new UpdateDNSException("No attachments on task");
        }

        Attachment attachment = task.attachments().get(0);
        if (!attachment.hasDetails()) {
            throw new UpdateDNSException("Attachment has no details");
        }

        for (KeyValuePair detail : attachment.details()) {
            if (detail.name() == "networkInterfaceId") {
                info.Eni = detail.value();
                return info;
            }
        }
        throw new UpdateDNSException("No network interface detail found");
    }

    private String getPublicIp(Ec2Client client, String eni) {
        return eni;
        // DescribeAddressesResponse response = client.describeAddresses();
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
        EcsClient ecsClient = EcsClient.builder().region(REGION).build();

        UpdateServiceRequest updateServiceRequest = UpdateServiceRequest.builder()
                .cluster(CLUSTER_NAME)
                .service(SERVICE_NAME)
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
