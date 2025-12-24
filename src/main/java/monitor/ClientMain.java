package monitor;

import com.sun.management.OperatingSystemMXBean;
import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.swing.JOptionPane;

public class ClientMain {
  private static final int DEFAULT_PORT = 5050;
  private static final int HEARTBEAT_SECONDS = 5;
  private static final int MAX_PROCESSES = 50;
  private static final String SCREENSHOT_FORMAT = "png";

  public static void main(String[] args) throws Exception {
    String host = args.length > 0 ? args[0] : "127.0.0.1";
    int port = DEFAULT_PORT;
    if (args.length > 1) {
      try {
        port = Integer.parseInt(args[1]);
      } catch (NumberFormatException ignored) {
        port = DEFAULT_PORT;
      }
    }
    String clientId = args.length > 2 ? args[2] : defaultClientId();

    while (true) {
      boolean monitoringApproved = false;
      try (Socket socket = new Socket(host, port)) {
        socket.setSoTimeout(15000);
        System.out.println("Connected to " + host + ":" + port + " as " + clientId);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        while (true) {
          String payload = buildPayload(clientId);
          writer.write(payload);
          writer.write("\n");
          writer.flush();
          String ack = reader.readLine();
          if (ack == null) {
            throw new IOException("server closed connection");
          }
          if (ack.startsWith("CMD:")) {
            String command = ack.substring("CMD:".length()).trim();
            if ("REQUEST_MONITORING".equalsIgnoreCase(command)) {
              System.out.println("Received REQUEST_MONITORING");
              boolean granted = requestMonitoringApproval(clientId);
              monitoringApproved = granted;
              System.out.println("Monitoring approval: " + granted);
              writer.write(buildApprovalLine(clientId, granted));
              writer.write("\n");
              writer.flush();
              reader.readLine();
              if (granted) {
                TimeUnit.SECONDS.sleep(2);
                String screenshotLine = buildScreenshotLine(clientId, true);
                System.out.println("Sending screenshot: "
                    + (screenshotLine.contains("granted=true") ? "granted" : "denied"));
                writer.write(screenshotLine);
                writer.write("\n");
                writer.flush();
                reader.readLine();
              }
            } else if ("REQUEST_SCREENSHOT".equalsIgnoreCase(command)) {
              System.out.println("Received REQUEST_SCREENSHOT");
              writer.write(buildScreenshotLine(clientId, monitoringApproved));
              writer.write("\n");
              writer.flush();
              reader.readLine();
            }
          }
          TimeUnit.SECONDS.sleep(HEARTBEAT_SECONDS);
        }
      } catch (IOException e) {
        System.out.println("Connection error: " + e.getMessage());
        TimeUnit.SECONDS.sleep(HEARTBEAT_SECONDS);
      }
    }
  }

  private static String buildPayload(String clientId) {
    OperatingSystemMXBean os =
        (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    double cpuLoad = os.getSystemCpuLoad();
    if (cpuLoad < 0) {
      cpuLoad = 0.0;
    }
    long total = os.getTotalPhysicalMemorySize();
    long free = os.getFreePhysicalMemorySize();
    long used = total - free;

    List<ProcInfo> processes = collectProcesses();

    StringBuilder sb = new StringBuilder();
    sb.append("{\"clientId\":\"").append(escape(clientId)).append("\",");
    sb.append("\"ts\":").append(System.currentTimeMillis()).append(",");
    sb.append("\"cpuLoad\":")
        .append(String.format(Locale.US, "%.4f", cpuLoad))
        .append(",");
    sb.append("\"ramUsedMb\":").append(used / (1024 * 1024)).append(",");
    sb.append("\"ramTotalMb\":").append(total / (1024 * 1024)).append(",");
    sb.append("\"processes\":[");
    for (int i = 0; i < processes.size(); i++) {
      ProcInfo proc = processes.get(i);
      if (i > 0) {
        sb.append(",");
      }
      sb.append("{\"pid\":").append(proc.pid).append(",\"cmd\":\"");
      sb.append(escape(proc.cmd)).append("\"}");
    }
    sb.append("]}");
    return sb.toString();
  }

  private static List<ProcInfo> collectProcesses() {
    List<ProcInfo> list = new ArrayList<>();
    ProcessHandle.allProcesses().limit(MAX_PROCESSES).forEach(ph -> {
      ProcessHandle.Info info = ph.info();
      String cmd = info.command().orElse("");
      if (cmd.isEmpty()) {
        cmd = info.commandLine().orElse("unknown");
      }
      list.add(new ProcInfo(ph.pid(), cmd));
    });
    return list;
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

  private static String defaultClientId() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception ignored) {
      return "client-" + UUID.randomUUID();
    }
  }

  private static boolean requestMonitoringApproval(String clientId) {
    if (GraphicsEnvironment.isHeadless()) {
      return false;
    }
    String message =
        "Admin is requesting permission to monitor this device (" + clientId + ").\n"
            + "Allow monitoring?";
    int choice =
        JOptionPane.showConfirmDialog(
            null, message, "Monitoring request", JOptionPane.YES_NO_OPTION);
    return choice == JOptionPane.YES_OPTION;
  }

  private static String buildApprovalLine(String clientId, boolean granted) {
    return "APPROVAL clientId=" + clientId + " action=monitoring granted=" + granted;
  }

  private static String buildScreenshotLine(String clientId, boolean allowed) {
    if (!allowed) {
      return "SCREENSHOT clientId=" + clientId + " granted=false";
    }
    if (GraphicsEnvironment.isHeadless()) {
      return "SCREENSHOT clientId=" + clientId + " granted=false reason=headless";
    }
    try {
      BufferedImage image =
          new Robot()
              .createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      ImageIO.write(image, SCREENSHOT_FORMAT, buffer);
      String encoded = Base64.getEncoder().encodeToString(buffer.toByteArray());
      return "SCREENSHOT clientId=" + clientId + " granted=true format="
          + SCREENSHOT_FORMAT + " data=" + encoded;
    } catch (AWTException | IOException e) {
      return "SCREENSHOT clientId=" + clientId + " granted=false reason=capture_failed";
    }
  }

  private static class ProcInfo {
    final long pid;
    final String cmd;

    ProcInfo(long pid, String cmd) {
      this.pid = pid;
      this.cmd = cmd;
    }
  }
}
