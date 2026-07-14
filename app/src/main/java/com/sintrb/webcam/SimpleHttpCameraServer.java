package com.sintrb.webcam;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SimpleHttpCameraServer {
    public interface CaptureProvider {
        File captureForHttp(RequestOptions options) throws Exception;
    }

    public interface HomePageProvider {
        String renderHomePage();
    }

    public interface SettingsPageProvider {
        String applyAndRender(Map<String, String> params);
    }

    public interface MediaProvider {
        File resolveMedia(Map<String, String> params) throws Exception;
    }

    public interface BinaryContentProvider {
        BinaryContent resolve(String path) throws Exception;
    }

    public static class BinaryContent {
        public final byte[] data;
        public final String contentType;

        public BinaryContent(byte[] data, String contentType) {
            this.data = data;
            this.contentType = contentType;
        }
    }

    public static class RequestOptions {
        public final int width;
        public final int height;
        public final boolean useFlash;
        public final long delayMs;
        public final Integer rotationDegrees;

        public RequestOptions(int width, int height, boolean useFlash, long delayMs, Integer rotationDegrees) {
            this.width = width;
            this.height = height;
            this.useFlash = useFlash;
            this.delayMs = delayMs;
            this.rotationDegrees = rotationDegrees;
        }

        public boolean hasResize() {
            return width > 0 || height > 0;
        }
    }

    private static final String TAG = "HttpCameraServer";
    private final int port;
    private final CaptureProvider captureProvider;
    private final HomePageProvider homePageProvider;
    private final SettingsPageProvider settingsPageProvider;
    private final MediaProvider mediaProvider;
    private final BinaryContentProvider binaryContentProvider;
    private volatile boolean running;
    private Thread serverThread;
    private ServerSocket serverSocket;

    public SimpleHttpCameraServer(int port, CaptureProvider captureProvider,
                                  HomePageProvider homePageProvider,
                                  SettingsPageProvider settingsPageProvider,
                                  MediaProvider mediaProvider,
                                  BinaryContentProvider binaryContentProvider) {
        this.port = port;
        this.captureProvider = captureProvider;
        this.homePageProvider = homePageProvider;
        this.settingsPageProvider = settingsPageProvider;
        this.mediaProvider = mediaProvider;
        this.binaryContentProvider = binaryContentProvider;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        serverThread = new Thread(this::runServer, "http-camera-server");
        serverThread.start();
    }

    public void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static String getDeviceIp() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface networkInterface : interfaces) {
                List<InetAddress> addresses = Collections.list(networkInterface.getInetAddresses());
                for (InetAddress address : addresses) {
                    if (!address.isLoopbackAddress() && address.getHostAddress().indexOf(':') < 0) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "0.0.0.0";
    }

    private void runServer() {
        try {
            serverSocket = new ServerSocket(port);
            while (running) {
                Socket socket = serverSocket.accept();
                new Thread(() -> handleClient(socket), "http-camera-client").start();
            }
        } catch (IOException e) {
            if (running) {
                Log.e(TAG, "Server error", e);
            }
        } finally {
            running = false;
        }
    }

    private void handleClient(Socket socket) {
        try (Socket client = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             BufferedOutputStream outputStream = new BufferedOutputStream(client.getOutputStream())) {

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.trim().isEmpty()) {
                return;
            }
            String method = "GET";
            String pathWithQuery = "/";
            String[] parts = requestLine.split(" ");
            if (parts.length >= 1) method = parts[0];
            if (parts.length >= 2) pathWithQuery = parts[1];
            while (true) {
                String line = reader.readLine();
                if (line == null || line.isEmpty()) {
                    break;
                }
            }

            String path = pathWithQuery;
            String query = "";
            int index = pathWithQuery.indexOf('?');
            if (index >= 0) {
                path = pathWithQuery.substring(0, index);
                query = pathWithQuery.substring(index + 1);
            }
            Map<String, String> params = parseQuery(query);

            if ("/snapshot.jpg".equals(path)) {
                RequestOptions options = parseOptions(params);
                File file = captureProvider.captureForHttp(options);
                writeJpeg(outputStream, file);
            } else if (binaryContentProvider != null && ("/favicon.png".equals(path) || "/favicon.ico".equals(path))) {
                BinaryContent content = binaryContentProvider.resolve(path);
                byte[] body = content.data;
                writeResponseHeader(outputStream, 200, "OK", content.contentType, body.length, null);
                outputStream.write(body);
                outputStream.flush();
            } else if ("/media".equals(path) && mediaProvider != null) {
                File file = mediaProvider.resolveMedia(params);
                writeJpeg(outputStream, file);
            } else if ("/settings".equals(path) && settingsPageProvider != null) {
                byte[] body = settingsPageProvider.applyAndRender(params).getBytes(StandardCharsets.UTF_8);
                writeResponseHeader(outputStream, 200, "OK", "text/html; charset=utf-8", body.length, null);
                outputStream.write(body);
                outputStream.flush();
            } else if ("/".equals(path) || "/index.html".equals(path)) {
                String html = homePageProvider != null ? homePageProvider.renderHomePage() : defaultHomePage();
                byte[] body = html.getBytes(StandardCharsets.UTF_8);
                writeResponseHeader(outputStream, 200, "OK", "text/html; charset=utf-8", body.length, null);
                outputStream.write(body);
                outputStream.flush();
            } else {
                byte[] body = ("404 Not Found\n").getBytes(StandardCharsets.UTF_8);
                writeResponseHeader(outputStream, 404, "Not Found", "text/plain; charset=utf-8", body.length, null);
                outputStream.write(body);
                outputStream.flush();
            }
        } catch (Exception e) {
            Log.e(TAG, "Handle client error", e);
            try (BufferedOutputStream outputStream = new BufferedOutputStream(socket.getOutputStream())) {
                byte[] body = ("capture failed: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
                writeResponseHeader(outputStream, 500, "Internal Server Error", "text/plain; charset=utf-8", body.length, null);
                outputStream.write(body);
                outputStream.flush();
            } catch (Exception ignored) {
            }
        }
    }

    private String defaultHomePage() {
        return "<html><body><h3>WebCam</h3><p><a href='/snapshot.jpg'>抓拍</a></p></body></html>";
    }

    private RequestOptions parseOptions(Map<String, String> params) {
        int width = parseInt(params.get("width"), 0);
        int height = parseInt(params.get("height"), 0);
        long delayMs = Math.max(0L, parseLong(firstNonEmpty(params.get("delayMs"), params.get("delay")), 0L));
        delayMs = Math.min(delayMs, 30000L);
        boolean useFlash = parseBoolean(params.get("flash"));
        Integer rotation = parseRotation(firstNonEmpty(params.get("rotate"), params.get("rotation")));
        return new RequestOptions(width, height, useFlash, delayMs, rotation);
    }

    private Integer parseRotation(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            int rotation = Integer.parseInt(value.trim());
            return AppSettings.sanitizeRotation(rotation);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> map = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return map;
        }
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] kv = pair.split("=", 2);
            String key = decode(kv[0]);
            String value = kv.length > 1 ? decode(kv[1]) : "";
            map.put(key, value);
        }
        return map;
    }

    private String decode(String text) {
        try {
            return URLDecoder.decode(text, "UTF-8");
        } catch (Exception e) {
            return text;
        }
    }

    private String firstNonEmpty(String first, String second) {
        return first != null && !first.isEmpty() ? first : second;
    }

    private int parseInt(String value, int fallback) {
        try {
            return value == null || value.isEmpty() ? fallback : Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private long parseLong(String value, long fallback) {
        try {
            return value == null || value.isEmpty() ? fallback : Long.parseLong(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private boolean parseBoolean(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return "1".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized);
    }

    private void writeJpeg(BufferedOutputStream outputStream, File file) throws IOException {
        long length = file.length();
        int[] dims = ImageUtils.readDimensions(file.getAbsolutePath());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Capture-File", file.getName());
        headers.put("X-Capture-Width", String.valueOf(dims[0]));
        headers.put("X-Capture-Height", String.valueOf(dims[1]));
        writeResponseHeader(outputStream, 200, "OK", "image/jpeg", length, "inline; filename=\"snapshot.jpg\"", headers);
        try (FileInputStream inputStream = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, count);
            }
        }
        outputStream.flush();
    }

    private void writeResponseHeader(BufferedOutputStream outputStream, int code, String message,
                                     String contentType, long contentLength, String contentDisposition) throws IOException {
        writeResponseHeader(outputStream, code, message, contentType, contentLength, contentDisposition, null);
    }

    private void writeResponseHeader(BufferedOutputStream outputStream, int code, String message,
                                     String contentType, long contentLength, String contentDisposition,
                                     Map<String, String> extraHeaders) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("HTTP/1.1 ").append(code).append(' ').append(message).append("\r\n");
        builder.append("Connection: close\r\n");
        builder.append("Content-Type: ").append(contentType).append("\r\n");
        builder.append("Content-Length: ").append(contentLength).append("\r\n");
        if (contentDisposition != null) {
            builder.append("Content-Disposition: ").append(contentDisposition).append("\r\n");
        }
        if (extraHeaders != null) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                builder.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
        }
        builder.append("\r\n");
        outputStream.write(builder.toString().getBytes(StandardCharsets.UTF_8));
    }
}
