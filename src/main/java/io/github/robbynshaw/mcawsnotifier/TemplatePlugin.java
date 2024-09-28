package io.github.robbynshaw.mcawsnotifier;

import java.io.File;
import java.util.Timer;
import java.util.TimerTask;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;

import lombok.Getter;
import lombok.experimental.Accessors;
// import software.amazon.awssdk.services.ecs.model.UpdateServiceRequest;

public class TemplatePlugin extends JavaPlugin implements Listener {
    private final Timer timer;
    private int checkInterval;
    private final int defaultCheckMins = 10;

    @Getter
    @Accessors(fluent = true)
    private static TemplatePlugin instance;
    // @Getter
    // @Setter(AccessLevel.PACKAGE)
    // private VaultProvider vault;
    // private PaperCommandManager commandManager;

    public TemplatePlugin() {
        instance = this;
        timer = new Timer();

        try {
            String checkVal = System.getenv("USERS_CHECK_INTERVAL_MINUTES");
            checkInterval = Integer.parseInt(checkVal);
        } catch (NumberFormatException e) {
            checkInterval = 1000 * 60 * defaultCheckMins;
        }
    }

    public TemplatePlugin(
            JavaPluginLoader loader, PluginDescriptionFile description, File dataFolder, File file) {
        super(loader, description, dataFolder, file);
        instance = this;

        timer = new Timer();

        try {
            String checkVal = System.getenv("USERS_CHECK_INTERVAL_MINUTES");
            checkInterval = Integer.parseInt(checkVal);
        } catch (NumberFormatException e) {
            checkInterval = 1000 * 60 * defaultCheckMins;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // setupVaultIntegration();
        // setupCommands();

        getServer().getPluginManager().registerEvents(this, this);

    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        getLogger().info("Player joined. Sending notification to SNS.");
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        getLogger().info("Player quit. Sending notification to SNS.");
    }

    // @EventHandler
    // public void onServiceRegistration(ServiceRegisterEvent event) {
    // if (event.getProvider().getService() == Economy.class) {
    // setVault(new VaultProvider((Economy) event.getProvider().getProvider()));
    // getLogger().info("Vault integration enabled.");
    // }
    // }

    // private void setupVaultIntegration() {
    // if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
    // final RegisteredServiceProvider<Economy> serviceProvider =
    // getServer().getServicesManager()
    // .getRegistration(Economy.class);
    // if (serviceProvider != null) {
    // vault = new
    // VaultProvider(Objects.requireNonNull(serviceProvider).getProvider());
    // getLogger().info("Vault integration enabled.");
    // }
    // }
    // if (vault == null) {
    // vault = new VaultProvider();
    // getLogger().warning("Vault integration is not yet available.");
    // }
    // }

    // private void setupCommands() {
    // commandManager = new PaperCommandManager(this);
    // commandManager.enableUnstableAPI("help");

    // loadCommandLocales(commandManager);

    // commandManager.registerCommand(new TemplateCommands());
    // }

    // see https://github.com/aikar/commands/wiki/Locales
    // private void loadCommandLocales(PaperCommandManager commandManager) {
    // try {
    // saveResource("lang_en.yaml", true);
    // commandManager.getLocales().setDefaultLocale(Locale.ENGLISH);
    // commandManager.getLocales().loadYamlLanguageFile("lang_en.yaml",
    // Locale.ENGLISH);
    // // this will detect the client locale and use it where possible
    // commandManager.usePerIssuerLocale(true);
    // } catch (IOException | InvalidConfigurationException e) {
    // getLogger().severe("Failed to load language config 'lang_en.yaml': " +
    // e.getMessage());
    // e.printStackTrace();
    // }
    // }

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

    private void startNewTimeout() {
        timer.schedule(new CheckUsersTask(), checkInterval);
    }

    private void checkForActiveUsers() {
    }

    private class CheckUsersTask extends TimerTask {
        @Override
        public void run() {
            checkForActiveUsers();
            startNewTimeout();
        }
    }
}
