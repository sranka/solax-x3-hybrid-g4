package com.github.sranka.solax_fve_realtime_app;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@CapacitorPlugin(name = "SettingsTransfer")
public class SettingsTransferPlugin extends Plugin {

    private static final String TAG = "SettingsTransfer";
    private static final int DEFAULT_PORT = 8765;
    private static final int TIMEOUT_SECONDS = 600;

    private ServerSocket serverSocket;
    private PluginCall pendingCall;
    private ScheduledFuture<?> timeoutFuture;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @PluginMethod
    public void getLocalIp(PluginCall call) {
        Log.d(TAG, "getLocalIp called");
        WifiManager wm = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            Log.e(TAG, "getLocalIp: WifiManager not available");
            call.reject("WifiManager not available");
            return;
        }
        android.net.wifi.WifiInfo info = wm.getConnectionInfo();
        int ip = info.getIpAddress();
        if (ip == 0) {
            Log.w(TAG, "getLocalIp: Not connected to WiFi (ip=0)");
            call.reject("Not connected to WiFi");
            return;
        }
        String ipStr = (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
        Log.i(TAG, "getLocalIp: " + ipStr);
        JSObject result = new JSObject();
        result.put("ip", ipStr);
        call.resolve(result);
    }

    @PluginMethod
    public synchronized void startServer(PluginCall call) {
        if (serverSocket != null) {
            Log.w(TAG, "startServer: server already running");
            call.reject("Server already running");
            return;
        }

        int port = call.getInt("port", DEFAULT_PORT);
        String token = call.getString("token", "");
        Log.i(TAG, "startServer: port=" + port + " token=" + (token.isEmpty() ? "(none)" : token.substring(0, Math.min(4, token.length())) + "..."));

        call.setKeepAlive(true);
        pendingCall = call;

        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setSoTimeout(TIMEOUT_SECONDS * 1000);
            Log.i(TAG, "startServer: listening on port " + port + " (timeout=" + TIMEOUT_SECONDS + "s)");
        } catch (IOException e) {
            Log.e(TAG, "startServer: failed to bind port " + port, e);
            pendingCall = null;
            call.setKeepAlive(false);
            call.reject("Failed to start server: " + e.getMessage());
            return;
        }

        timeoutFuture = scheduler.schedule(() -> {
            Log.w(TAG, "startServer: timeout after " + TIMEOUT_SECONDS + "s, closing server");
            closeServer();
            if (pendingCall != null) {
                pendingCall.reject("Timeout waiting for connection");
                pendingCall = null;
            }
        }, TIMEOUT_SECONDS, TimeUnit.SECONDS);

        new Thread(() -> {
            try {
                Log.d(TAG, "startServer: waiting for client connection...");
                Socket client = serverSocket.accept();
                Log.i(TAG, "startServer: client connected from " + client.getRemoteSocketAddress());
                handleClient(client, token);
            } catch (SocketTimeoutException e) {
                Log.w(TAG, "startServer: socket accept timed out");
            } catch (IOException e) {
                Log.e(TAG, "startServer: server error", e);
                synchronized (SettingsTransferPlugin.this) {
                    if (pendingCall != null) {
                        pendingCall.reject("Server error: " + e.getMessage());
                        pendingCall = null;
                    }
                }
            } finally {
                closeServer();
            }
        }).start();
    }

    private void handleClient(Socket client, String expectedToken) {
        try {
            client.setSoTimeout(10000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            OutputStream out = client.getOutputStream();

            // Read request line
            String requestLine = reader.readLine();
            Log.d(TAG, "handleClient: requestLine=" + requestLine);
            if (requestLine == null) {
                Log.w(TAG, "handleClient: null request line, sending 400");
                sendResponse(out, 400, "Bad Request");
                return;
            }

            // Parse method and path
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                Log.w(TAG, "handleClient: malformed request line: " + requestLine);
                sendResponse(out, 400, "Bad Request");
                return;
            }

            String method = parts[0];
            String path = parts[1];
            Log.d(TAG, "handleClient: method=" + method + " path=" + path);

            // Handle CORS preflight
            if ("OPTIONS".equals(method)) {
                Log.d(TAG, "handleClient: CORS preflight, sending 204 and waiting for POST");
                sendCorsResponse(out);
                // After preflight, accept another connection for the actual POST
                client.close();
                try {
                    Socket postClient = serverSocket.accept();
                    Log.d(TAG, "handleClient: post-preflight client connected from " + postClient.getRemoteSocketAddress());
                    handleClient(postClient, expectedToken);
                } catch (IOException e) {
                    Log.e(TAG, "handleClient: error accepting post-preflight connection", e);
                }
                return;
            }

            if (!"POST".equals(method)) {
                Log.w(TAG, "handleClient: rejecting method " + method + " with 405");
                sendResponse(out, 405, "Method Not Allowed");
                return;
            }

            // Validate token in path
            if (!expectedToken.isEmpty() && !path.contains("token=" + expectedToken)) {
                Log.w(TAG, "handleClient: token mismatch, sending 403");
                sendResponse(out, 403, "Forbidden");
                return;
            }

            // Read headers to find Content-Length
            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }
            Log.d(TAG, "handleClient: content-length=" + contentLength);

            // Read body
            char[] body = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int r = reader.read(body, read, contentLength - read);
                if (r == -1) break;
                read += r;
            }
            String bodyStr = new String(body, 0, read);
            Log.i(TAG, "handleClient: received body (" + read + " bytes)");
            Log.d(TAG, "handleClient: body=" + bodyStr.substring(0, Math.min(200, bodyStr.length())) + (bodyStr.length() > 200 ? "..." : ""));

            // Send success response with CORS headers
            String responseBody = "{\"ok\":true}";
            String response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: " + responseBody.length() + "\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Access-Control-Allow-Methods: POST, OPTIONS\r\n" +
                    "Access-Control-Allow-Headers: Content-Type\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    responseBody;
            out.write(response.getBytes("UTF-8"));
            out.flush();
            client.close();
            Log.d(TAG, "handleClient: sent 200 OK, client closed");

            // Resolve the pending call with received data
            synchronized (this) {
                if (pendingCall != null) {
                    if (timeoutFuture != null) {
                        timeoutFuture.cancel(false);
                    }
                    JSObject result = new JSObject();
                    result.put("data", bodyStr);
                    pendingCall.resolve(result);
                    Log.i(TAG, "handleClient: resolved pending call with data");
                    pendingCall = null;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "handleClient: error", e);
            synchronized (this) {
                if (pendingCall != null) {
                    pendingCall.reject("Error handling client: " + e.getMessage());
                    pendingCall = null;
                }
            }
        }
    }

    private void sendResponse(OutputStream out, int code, String message) throws IOException {
        String body = "{\"error\":\"" + message + "\"}";
        String response = "HTTP/1.1 " + code + " " + message + "\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                body;
        out.write(response.getBytes("UTF-8"));
        out.flush();
    }

    private void sendCorsResponse(OutputStream out) throws IOException {
        String response = "HTTP/1.1 204 No Content\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n" +
                "Access-Control-Max-Age: 86400\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        out.write(response.getBytes("UTF-8"));
        out.flush();
    }

    @PluginMethod
    public void sendSettings(PluginCall call) {
        String url = call.getString("url", "");
        String payload = call.getString("payload", "");
        if (url.isEmpty()) {
            call.reject("Missing url");
            return;
        }
        Log.i(TAG, "sendSettings: url=" + url + " payload length=" + payload.length());

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "text/plain");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setDoOutput(true);

                byte[] bodyBytes = payload.getBytes("UTF-8");
                conn.setFixedLengthStreamingMode(bodyBytes.length);
                OutputStream os = conn.getOutputStream();
                os.write(bodyBytes);
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                Log.i(TAG, "sendSettings: response code=" + code);
                if (code >= 200 && code < 300) {
                    JSObject result = new JSObject();
                    result.put("status", code);
                    call.resolve(result);
                } else {
                    call.reject("HTTP " + code);
                }
            } catch (Exception e) {
                Log.e(TAG, "sendSettings: error", e);
                call.reject("Send failed: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    @PluginMethod
    public synchronized void stopServer(PluginCall call) {
        Log.i(TAG, "stopServer called");
        closeServer();
        if (pendingCall != null) {
            pendingCall.reject("Server stopped");
            pendingCall = null;
        }
        call.resolve();
    }

    private synchronized void closeServer() {
        Log.d(TAG, "closeServer: closing server socket");
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
    }
}
