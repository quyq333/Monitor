package monitor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerMain {
  private static final int DEFAULT_PORT = 5050;
  private static final long OFFLINE_MS = 15000;
  private final Map<String, ClientStatus> statusByClient = new ConcurrentHashMap<>();
  private final Map<String, Boolean> monitoringAllowedByClient = new ConcurrentHashMap<>();
  private final Map<String, String> pendingCommandByClient = new ConcurrentHashMap<>();
  private final ExecutorService pool = Executors.newCachedThreadPool();
  private HttpServer httpServer;

  public static void main(String[] args) throws Exception {
    new ServerMain().start(args);
  }

  private void start(String[] args) throws Exception {
    int port = DEFAULT_PORT;
    int httpPort = DEFAULT_PORT + 1;
    if (args.length > 0) {
      try {
        port = Integer.parseInt(args[0]);
      } catch (NumberFormatException ignored) {
        System.out.println("Invalid port, using default: " + DEFAULT_PORT);
        port = DEFAULT_PORT;
      }
    }
    if (args.length > 1) {
      try {
        httpPort = Integer.parseInt(args[1]);
      } catch (NumberFormatException ignored) {
        httpPort = port + 1;
      }
    } else {
      httpPort = port + 1;
    }

    startHttpServer(httpPort);

    try (ServerSocket server = new ServerSocket(port)) {
      System.out.println("Monitor server listening on port " + port);
      System.out.println("Web UI listening on http://localhost:" + httpPort);
      while (true) {
        Socket socket = server.accept();
        pool.submit(() -> handleClient(socket));
      }
    }
  }

  private void startHttpServer(int httpPort) throws IOException {
    httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
    httpServer.createContext("/api/status", this::handleStatusApi);
    httpServer.createContext("/api/command", this::handleCommandApi);
    httpServer.createContext("/", exchange -> serveStatic(exchange, "web/index.html", "text/html"));
    httpServer.createContext("/app.js", exchange -> serveStatic(exchange, "web/app.js", "text/javascript"));
    httpServer.createContext("/app.css", exchange -> serveStatic(exchange, "web/app.css", "text/css"));
    httpServer.setExecutor(pool);
    httpServer.start();
  }

  private void handleClient(Socket socket) {
    String remote = String.valueOf(socket.getRemoteSocketAddress());
    System.out.println("Client connected: " + remote);
    String lastClientId = null;
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
         BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("APPROVAL ")) {
          String clientId = extractTokenValue(line, "clientId");
          String action = extractTokenValue(line, "action");
          String grantedToken = extractTokenValue(line, "granted");
          boolean granted = "true".equalsIgnoreCase(grantedToken);
          if (clientId != null && "monitoring".equalsIgnoreCase(action)) {
            pendingCommandByClient.remove(clientId);
            monitoringAllowedByClient.put(clientId, granted);
          }
          writer.write("OK\n");
          writer.flush();
          continue;
        }

        ClientStatus status = parseStatus(line);
        if (status != null && status.clientId != null && !status.clientId.isEmpty()) {
          status.lastSeen = System.currentTimeMillis();
          lastClientId = status.clientId;
          upsertStatus(status);
          System.out.println(status.toSummary());
          String pending = pendingCommandByClient.remove(status.clientId);
          if (pending != null) {
            writer.write("CMD:" + pending + "\n");
          } else {
            writer.write("OK\n");
          }
          writer.flush();
          continue;
        }
        System.out.println("Heartbeat from " + remote + ": " + line);
        writer.write("OK\n");
        writer.flush();
      }
      if (lastClientId != null) {
        markOffline(lastClientId, System.currentTimeMillis());
      }
    } catch (IOException e) {
      System.out.println("Client disconnected: " + remote + " (" + e.getMessage() + ")");
      if (lastClientId != null) {
        markOffline(lastClientId, System.currentTimeMillis());
      }
    }
  }

  private void handleStatusApi(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      return;
    }
    String body = buildStatusJson();
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private void handleCommandApi(HttpExchange exchange) throws IOException {
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      return;
    }
    String query = exchange.getRequestURI().getRawQuery();
    String clientId = extractQueryParam(query, "clientId");
    String action = extractQueryParam(query, "action");
    if (clientId == null || action == null || clientId.isEmpty()) {
      exchange.sendResponseHeaders(400, -1);
      return;
    }
    if ("request_monitoring".equalsIgnoreCase(action)) {
      pendingCommandByClient.put(clientId, "REQUEST_MONITORING");
      exchange.sendResponseHeaders(202, -1);
      return;
    }
    exchange.sendResponseHeaders(404, -1);
  }

  private String buildStatusJson() {
    refreshOnlineStates(System.currentTimeMillis());
    List<ClientStatus> snapshot = new ArrayList<>(statusByClient.values());
    StringBuilder sb = new StringBuilder();
    sb.append("{\"serverTime\":").append(System.currentTimeMillis()).append(",");
    sb.append("\"clients\":[");
    for (int i = 0; i < snapshot.size(); i++) {
      ClientStatus status = snapshot.get(i);
      if (i > 0) {
        sb.append(",");
      }
      sb.append("{\"clientId\":\"").append(escape(status.clientId)).append("\",");
      sb.append("\"ts\":").append(status.timestamp).append(",");
      sb.append("\"cpuLoad\":").append(formatDouble(status.cpuLoad)).append(",");
      sb.append("\"ramUsedMb\":").append(status.ramUsedMb).append(",");
      sb.append("\"ramTotalMb\":").append(status.ramTotalMb).append(",");
      sb.append("\"processCount\":").append(status.processCount).append(",");
      sb.append("\"lastSeen\":").append(status.lastSeen).append(",");
      sb.append("\"online\":").append(status.online).append(",");
      sb.append("\"lastChange\":").append(status.lastChange).append(",");
      sb.append("\"monitoringAllowed\":")
          .append(isMonitoringAllowed(status.clientId)).append(",");
      sb.append("\"pendingCommand\":")
          .append(formatJsonString(pendingCommandByClient.get(status.clientId)))
          .append("}");
    }
    sb.append("]}");
    return sb.toString();
  }

  private void serveStatic(HttpExchange exchange, String resourcePath, String contentType)
      throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      return;
    }
    byte[] data = readResource(resourcePath);
    if (data == null) {
      exchange.sendResponseHeaders(404, -1);
      return;
    }
    exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
    exchange.sendResponseHeaders(200, data.length);
    exchange.getResponseBody().write(data);
    exchange.close();
  }

  private byte[] readResource(String resourcePath) throws IOException {
    try (InputStream input = ServerMain.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (input == null) {
        return null;
      }
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      byte[] chunk = new byte[4096];
      int read;
      while ((read = input.read(chunk)) >= 0) {
        buffer.write(chunk, 0, read);
      }
      return buffer.toByteArray();
    }
  }

  private ClientStatus parseStatus(String json) {
    ClientStatus status = new ClientStatus();
    status.clientId = extractString(json, "clientId");
    status.timestamp = extractLong(json, "ts", 0L);
    status.cpuLoad = extractDouble(json, "cpuLoad", -1.0);
    status.ramUsedMb = extractLong(json, "ramUsedMb", -1L);
    status.ramTotalMb = extractLong(json, "ramTotalMb", -1L);
    status.processCount = countOccurrences(json, "\"pid\":");
    if (status.clientId == null) {
      return null;
    }
    return status;
  }

  private void upsertStatus(ClientStatus incoming) {
    long now = incoming.lastSeen > 0 ? incoming.lastSeen : System.currentTimeMillis();
    statusByClient.compute(incoming.clientId, (id, current) -> {
      if (current == null) {
        incoming.online = true;
        incoming.lastChange = now;
        return incoming;
      }
      current.timestamp = incoming.timestamp;
      current.cpuLoad = incoming.cpuLoad;
      current.ramUsedMb = incoming.ramUsedMb;
      current.ramTotalMb = incoming.ramTotalMb;
      current.processCount = incoming.processCount;
      current.lastSeen = now;
      if (!current.online) {
        current.online = true;
        current.lastChange = now;
      }
      return current;
    });
  }

  private void refreshOnlineStates(long now) {
    for (ClientStatus status : statusByClient.values()) {
      if (status.lastSeen > 0 && now - status.lastSeen > OFFLINE_MS) {
        if (status.online) {
          status.online = false;
          status.lastChange = now;
        }
      }
    }
  }

  private void markOffline(String clientId, long now) {
    ClientStatus status = statusByClient.get(clientId);
    if (status != null && status.online) {
      status.online = false;
      status.lastChange = now;
    }
  }

  private static String extractString(String json, String key) {
    String needle = "\"" + key + "\":\"";
    int idx = json.indexOf(needle);
    if (idx < 0) {
      return null;
    }
    int start = idx + needle.length();
    StringBuilder sb = new StringBuilder();
    boolean escape = false;
    for (int i = start; i < json.length(); i++) {
      char c = json.charAt(i);
      if (escape) {
        sb.append(c);
        escape = false;
        continue;
      }
      if (c == '\\') {
        escape = true;
        continue;
      }
      if (c == '"') {
        return sb.toString();
      }
      sb.append(c);
    }
    return null;
  }

  private static long extractLong(String json, String key, long fallback) {
    String token = extractNumberToken(json, key);
    if (token == null) {
      return fallback;
    }
    try {
      return Long.parseLong(token);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static double extractDouble(String json, String key, double fallback) {
    String token = extractNumberToken(json, key);
    if (token == null) {
      return fallback;
    }
    try {
      return Double.parseDouble(token);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static String extractNumberToken(String json, String key) {
    String needle = "\"" + key + "\":";
    int idx = json.indexOf(needle);
    if (idx < 0) {
      return null;
    }
    int start = idx + needle.length();
    int end = start;
    while (end < json.length()) {
      char c = json.charAt(end);
      if ((c >= '0' && c <= '9') || c == '.' || c == '-') {
        end++;
      } else {
        break;
      }
    }
    if (end == start) {
      return null;
    }
    return json.substring(start, end);
  }

  private static int countOccurrences(String text, String token) {
    int count = 0;
    int idx = 0;
    while ((idx = text.indexOf(token, idx)) >= 0) {
      count++;
      idx += token.length();
    }
    return count;
  }

  private static String escape(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\':
          sb.append("\\\\");
          break;
        case '"':
          sb.append("\\\"");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        default:
          sb.append(c);
      }
    }
    return sb.toString();
  }

  private boolean isMonitoringAllowed(String clientId) {
    Boolean allowed = monitoringAllowedByClient.get(clientId);
    return Boolean.TRUE.equals(allowed);
  }

  private static String formatJsonString(String value) {
    if (value == null) {
      return "null";
    }
    return "\"" + escape(value) + "\"";
  }

  private static String extractQueryParam(String query, String key) {
    if (query == null || query.isEmpty()) {
      return null;
    }
    String needle = key + "=";
    String[] parts = query.split("&");
    for (String part : parts) {
      if (part.startsWith(needle)) {
        return decodeComponent(part.substring(needle.length()));
      }
    }
    return null;
  }

  private static String decodeComponent(String value) {
    if (value == null) {
      return null;
    }
    return value.replace("+", " ");
  }

  private static String extractTokenValue(String line, String key) {
    String token = key + "=";
    int idx = line.indexOf(token);
    if (idx < 0) {
      return null;
    }
    int start = idx + token.length();
    int end = line.indexOf(' ', start);
    if (end < 0) {
      end = line.length();
    }
    return line.substring(start, end);
  }

  private static String formatDouble(double value) {
    if (value < 0) {
      return "null";
    }
    return String.format(java.util.Locale.US, "%.4f", value);
  }

  private static class ClientStatus {
    String clientId;
    long timestamp;
    double cpuLoad;
    long ramUsedMb;
    long ramTotalMb;
    int processCount;
    long lastSeen;
    boolean online;
    long lastChange;

    String toSummary() {
      String cpu = cpuLoad < 0 ? "n/a" : String.format("%.1f%%", cpuLoad * 100.0);
      String ram;
      if (ramUsedMb < 0 || ramTotalMb < 0) {
        ram = "n/a";
      } else {
        ram = ramUsedMb + "/" + ramTotalMb + "MB";
      }
      return "clientId=" + clientId + " cpu=" + cpu + " ram=" + ram + " processes=" + processCount;
    }
  }
}
