package ru.kozel.smp.webapi;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class KozelSmpWebApiPlugin extends JavaPlugin {

    private static final String EMPTY_RESPONSE = """
            {"online":0,"max":0,"updatedAt":0,"players":{"all":{"count":0,"list":[]},"overworld":{"count":0,"list":[]},"nether":{"count":0,"list":[]},"end":{"count":0,"list":[]},"other":{"count":0,"list":[]}}}
            """.trim();

    private HttpServer httpServer;
    private ExecutorService httpExecutor;
    private BukkitTask snapshotTask;
    private volatile String cachedPlayersJson = EMPTY_RESPONSE;
    private String apiToken;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.apiToken = getConfig().getString("token", "");
        int updateIntervalTicks = Math.max(1, getConfig().getInt("update-interval-ticks", 20));

        updateSnapshot();
        this.snapshotTask = Bukkit.getScheduler().runTaskTimer(
                this,
                this::updateSnapshot,
                updateIntervalTicks,
                updateIntervalTicks
        );

        try {
            startHttpServer();
            getLogger().info("Kozel SMP Web API enabled.");
        } catch (IOException exception) {
            getLogger().severe("Could not start HTTP API: " + exception.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (snapshotTask != null) {
            snapshotTask.cancel();
            snapshotTask = null;
        }

        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }

        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }

        getLogger().info("Kozel SMP Web API disabled.");
    }

    private void startHttpServer() throws IOException {
        String host = getConfig().getString("host", "127.0.0.1");
        int port = getConfig().getInt("port", 8087);

        this.httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.httpServer.createContext("/players", this::handlePlayers);
        this.httpServer.createContext("/health", this::handleHealth);

        this.httpExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "KozelSmpWebApi-HTTP");
            thread.setDaemon(true);
            return thread;
        });

        this.httpServer.setExecutor(httpExecutor);
        this.httpServer.start();

        getLogger().info("HTTP API started at http://" + host + ":" + port + "/players");
    }

    private void updateSnapshot() {
        Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();

        List<PlayerDto> all = new ArrayList<>();
        List<PlayerDto> overworld = new ArrayList<>();
        List<PlayerDto> nether = new ArrayList<>();
        List<PlayerDto> end = new ArrayList<>();
        List<PlayerDto> other = new ArrayList<>();

        for (Player player : onlinePlayers) {
            PlayerDto dto = PlayerDto.from(player);

            all.add(dto);
            switch (player.getWorld().getEnvironment()) {
                case NORMAL -> overworld.add(dto);
                case NETHER -> nether.add(dto);
                case THE_END -> end.add(dto);
                default -> other.add(dto);
            }
        }

        sortPlayers(all);
        sortPlayers(overworld);
        sortPlayers(nether);
        sortPlayers(end);
        sortPlayers(other);

        this.cachedPlayersJson = buildResponseJson(
                all.size(),
                Bukkit.getMaxPlayers(),
                all,
                overworld,
                nether,
                end,
                other
        );
    }

    private static void sortPlayers(List<PlayerDto> players) {
        players.sort(Comparator.comparing(player -> player.name().toLowerCase(Locale.ROOT)));
    }

    private void handlePlayers(HttpExchange exchange) throws IOException {
        applyCorsHeaders(exchange);

        if (isOptions(exchange)) {
            sendEmpty(exchange, 204);
            return;
        }

        if (!isGet(exchange)) {
            sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }

        if (!isAuthorized(exchange)) {
            sendJson(exchange, 401, "{\"error\":\"unauthorized\"}");
            return;
        }

        exchange.getResponseHeaders().set("Cache-Control", "no-store");

        sendJson(exchange, 200, cachedPlayersJson);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        applyCorsHeaders(exchange);

        if (isOptions(exchange)) {
            sendEmpty(exchange, 204);
            return;
        }

        if (!isGet(exchange)) {
            sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }

        sendJson(exchange, 200, "{\"status\":\"ok\"}");
    }

    private static boolean isGet(HttpExchange exchange) {
        return "GET".equalsIgnoreCase(exchange.getRequestMethod());
    }

    private static boolean isOptions(HttpExchange exchange) {
        return "OPTIONS".equalsIgnoreCase(exchange.getRequestMethod());
    }

    private static void applyCorsHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Authorization, X-API-Key, Content-Type");
    }

    private boolean isAuthorized(HttpExchange exchange) {
        if (apiToken == null || apiToken.isBlank()) {
            return true;
        }

        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String apiKey = exchange.getRequestHeaders().getFirst("X-API-Key");

        return apiToken.equals(apiKey) || ("Bearer " + apiToken).equals(authorization);
    }

    private static void sendEmpty(HttpExchange exchange, int statusCode) throws IOException {
        exchange.sendResponseHeaders(statusCode, -1);
        exchange.close();
    }

    private static void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] response = json.getBytes(StandardCharsets.UTF_8);

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");

        exchange.sendResponseHeaders(statusCode, response.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(response);
        }
    }

    private static String buildResponseJson(
            int online,
            int max,
            List<PlayerDto> all,
            List<PlayerDto> overworld,
            List<PlayerDto> nether,
            List<PlayerDto> end,
            List<PlayerDto> other
    ) {
        StringBuilder json = new StringBuilder(4096);

        json.append('{');
        json.append("\"online\":").append(online).append(',');
        json.append("\"max\":").append(max).append(',');
        json.append("\"updatedAt\":").append(System.currentTimeMillis()).append(',');
        json.append("\"players\":{");
        appendCategory(json, "all", all);
        json.append(',');
        appendCategory(json, "overworld", overworld);
        json.append(',');
        appendCategory(json, "nether", nether);
        json.append(',');
        appendCategory(json, "end", end);
        json.append(',');
        appendCategory(json, "other", other);
        json.append("}}");

        return json.toString();
    }

    private static void appendCategory(StringBuilder json, String key, List<PlayerDto> players) {
        json.append('"').append(escapeJson(key)).append("\":{");
        json.append("\"count\":").append(players.size()).append(',');
        json.append("\"list\":");
        appendPlayerList(json, players);
        json.append('}');
    }

    private static void appendPlayerList(StringBuilder json, List<PlayerDto> players) {
        json.append('[');

        for (int i = 0; i < players.size(); i++) {
            PlayerDto player = players.get(i);

            if (i > 0) {
                json.append(',');
            }

            json.append('{');
            json.append("\"name\":\"").append(escapeJson(player.name())).append("\",");
            json.append("\"uuid\":\"").append(escapeJson(player.uuid().toString())).append("\",");
            json.append("\"world\":\"").append(escapeJson(player.world())).append("\",");
            json.append("\"dimension\":\"").append(escapeJson(player.dimension())).append('"');
            json.append('}');
        }

        json.append(']');
    }

    private static String escapeJson(String input) {
        if (input == null) {
            return "";
        }

        StringBuilder escaped = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char character = input.charAt(i);

            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }

        return escaped.toString();
    }

    private record PlayerDto(String name, UUID uuid, String world, String dimension) {
        private static PlayerDto from(Player player) {
            World world = player.getWorld();

            return new PlayerDto(
                    player.getName(),
                    player.getUniqueId(),
                    world.getName(),
                    toDimensionName(world.getEnvironment())
            );
        }

        private static String toDimensionName(World.Environment environment) {
            return switch (environment) {
                case NORMAL -> "overworld";
                case NETHER -> "nether";
                case THE_END -> "end";
                default -> "other";
            };
        }
    }
}
