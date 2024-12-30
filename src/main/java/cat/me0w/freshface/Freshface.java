package cat.me0w.freshface;

import com.google.inject.Inject;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import org.slf4j.Logger;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

@Plugin(
        id = "freshface",
        name = "Freshface",
        version = BuildConstants.VERSION,
        description = "A plugin that notifies when a new player joins the server.",
        url = "https://catme0w.org",
        authors = {"catme0w"}
)
public class Freshface {

    @Inject
    @SuppressWarnings("unused")
    private Logger logger;

    @Inject
    @DataDirectory
    @SuppressWarnings("unused")
    private Path dataDirectory;

    private Set<String> playerNames;
    private Path playersFile;

    private String chatId;
    private TelegramClient telegramClient;

    private boolean snapperRetainSnapshotEnabled = false;
    private String snapperConfig;

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            if (Files.notExists(dataDirectory)) {
                Files.createDirectory(dataDirectory);
            }
        } catch (IOException e) {
            logger.error("Failed to create data directory", e);
        }

        playerNames = new HashSet<>();
        playersFile = dataDirectory.resolve("players.txt");

        try {
            if (!Files.exists(playersFile)) {
                Files.createFile(playersFile);
            } else {
                try (Stream<String> lines = Files.lines(playersFile)) {
                    lines.forEach(playerNames::add);
                }
            }
        } catch (IOException e) {
            logger.error("Failed to load or create player names file", e);
        }

        try {
            Path config = dataDirectory.resolve("config.yml");
            if (Files.notExists(config)) {
                try (InputStream stream = this.getClass().getClassLoader().getResourceAsStream("config.yml")) {
                    assert stream != null;
                    Files.copy(stream, config);
                }
            }

            Yaml yaml = new Yaml();
            try (InputStream inputStream = Files.newInputStream(config)) {
                Map<String, Object> configData = yaml.load(inputStream);
                String botToken = (String) configData.get("telegram-bot-token");
                chatId = (String) configData.get("telegram-chat-id");
                String telegramUrlSchema = (String) configData.get("telegram-url-schema");
                String telegramUrlHost = (String) configData.get("telegram-url-host");
                String telegramUrlPort = (String) configData.get("telegram-url-port");
                snapperRetainSnapshotEnabled = (boolean) configData.get("snapper-retain-snapshot-enabled");
                snapperConfig = (String) configData.get("snapper-config");

                if (botToken != null && chatId != null) {
                    if (telegramUrlSchema != null && telegramUrlHost != null && telegramUrlPort != null) {
                        TelegramUrl telegramUrl = new TelegramUrl(telegramUrlSchema, telegramUrlHost, Integer.parseInt(telegramUrlPort), false);
                        telegramClient = new OkHttpTelegramClient(botToken, telegramUrl);
                    } else {
                        telegramClient = new OkHttpTelegramClient(botToken);
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Failed to load configuration", e);
        }
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        String playerName = event.getPlayer().getUsername();

        if (!playerNames.contains(playerName)) {
            playerNames.add(playerName);

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(String.valueOf(playersFile), true))) {
                writer.write(playerName);
                writer.newLine();
            } catch (IOException e) {
                logger.error("Failed to write player name to file", e);
            }

            // Retain the last snapshot before the player joined
            String snapshotId = null;
            if (snapperRetainSnapshotEnabled) {
                try {
                    List<String> baseCommand = new ArrayList<>();
                    baseCommand.add("snapper");
                    if (snapperConfig != null) {
                        baseCommand.add("-c");
                        baseCommand.add(snapperConfig);
                    }

                    List<String> retrieveCommand = new ArrayList<>(baseCommand);
                    retrieveCommand.add("list");
                    retrieveCommand.add("--columns");
                    retrieveCommand.add("number");

                    Process retrieveProcess = new ProcessBuilder(retrieveCommand)
                            .redirectErrorStream(true)
                            .start();

                    List<String> outputLines = new ArrayList<>();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(retrieveProcess.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            outputLines.add(line.trim());
                        }
                    }

                    int retrieveExitCode = retrieveProcess.waitFor();
                    if (retrieveExitCode != 0 || outputLines.isEmpty()) {
                        logger.error("Failed to retrieve snapshot list. Exit code: {}", retrieveExitCode);
                    } else {
                        snapshotId = outputLines.get(outputLines.size() - 1); // Get the last line; mock "tail -n 1"
                    }

                    if (snapshotId != null) {
                        List<String> modifyCommand = new ArrayList<>(baseCommand);
                        modifyCommand.add("modify");
                        modifyCommand.add("--userdata");
                        modifyCommand.add("important=yes");
                        modifyCommand.add(snapshotId);

                        Process modifyProcess = new ProcessBuilder(modifyCommand)
                                .redirectErrorStream(true)
                                .start();

                        int modifyExitCode = modifyProcess.waitFor();
                        if (modifyExitCode != 0) {
                            logger.error("Failed to retain snapshot {}. Exit code: {}", snapshotId, modifyExitCode);
                            snapshotId = null;
                        }
                    }
                } catch (IOException | InterruptedException e) {
                    logger.error("Failed to execute snapper command", e);
                }
            }

            if (snapshotId != null) {
                logger.info("New player {} joined the server. Snapshot {} retained.", playerName, snapshotId);
            } else {
                logger.info("New player {} joined the server", playerName);
            }

            if (telegramClient != null && chatId != null) {
                SendMessage sendMessage = new SendMessage(chatId, "New player " + playerName + " joined the server.");

                if (snapshotId != null) {
                    sendMessage.setText(sendMessage.getText() + " Snapshot " + snapshotId + " retained.");
                }

                try {
                    telegramClient.execute(sendMessage);
                } catch (TelegramApiException e) {
                    logger.error("Failed to send message to Telegram", e);
                }
            }
        }
    }
}
