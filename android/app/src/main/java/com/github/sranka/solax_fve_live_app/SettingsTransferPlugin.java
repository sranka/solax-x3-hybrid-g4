package com.github.sranka.solax_fve_live_app;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
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
                while (pendingCall != null && serverSocket != null && !serverSocket.isClosed()) {
                    Log.d(TAG, "startServer: waiting for client connection...");
                    Socket client = serverSocket.accept();
                    Log.i(TAG, "startServer: client connected from " + client.getRemoteSocketAddress());
                    if (handleClient(client, token)) {
                        break; // POST handled successfully
                    }
                }
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

    /** Returns true if a POST was successfully handled, false for OPTIONS/errors (caller should accept again). */
    /** Read a line (up to \r\n or \n) from a raw InputStream. Returns null on EOF. */
    private static String readHttpLine(java.io.InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                int next = in.read(); // consume \n after \r
                break;
            }
            if (c == '\n') break;
            sb.append((char) c);
        }
        return (c == -1 && sb.length() == 0) ? null : sb.toString();
    }

    private boolean handleClient(Socket client, String expectedToken) {
        try {
            client.setSoTimeout(10000);
            java.io.InputStream rawIn = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // Read request line
            String requestLine = readHttpLine(rawIn);
            Log.d(TAG, "handleClient: requestLine=" + requestLine);
            if (requestLine == null) {
                Log.w(TAG, "handleClient: null request line, sending 400");
                sendResponse(out, 400, "Bad Request");
                return false;
            }

            // Parse method and path
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                Log.w(TAG, "handleClient: malformed request line: " + requestLine);
                sendResponse(out, 400, "Bad Request");
                return false;
            }

            String method = parts[0];
            String path = parts[1];
            Log.d(TAG, "handleClient: method=" + method + " path=" + path);

            // Handle CORS preflight — respond and let the accept loop pick up the next connection
            if ("OPTIONS".equals(method)) {
                Log.d(TAG, "handleClient: CORS preflight, sending 204");
                sendCorsResponse(out);
                client.close();
                return false;
            }

            if (!"POST".equals(method)) {
                Log.w(TAG, "handleClient: rejecting method " + method + " with 405");
                sendResponse(out, 405, "Method Not Allowed");
                return false;
            }

            // Validate token in path
            if (!expectedToken.isEmpty() && !path.contains("token=" + expectedToken)) {
                Log.w(TAG, "handleClient: token mismatch, sending 403");
                sendResponse(out, 403, "Forbidden");
                return false;
            }

            // Read headers to find Content-Length
            int contentLength = -1;
            String line;
            while ((line = readHttpLine(rawIn)) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }
            Log.d(TAG, "handleClient: content-length=" + contentLength);

            if (contentLength < 0) {
                Log.w(TAG, "handleClient: missing Content-Length, sending 411");
                sendResponse(out, 411, "Length Required");
                return false;
            }
            if (contentLength > 5000) {
                Log.w(TAG, "handleClient: Content-Length " + contentLength + " exceeds limit, sending 413");
                sendResponse(out, 413, "Content Too Large");
                return false;
            }

            // Read body as raw bytes (Content-Length is in bytes, not characters)
            byte[] body = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int r = rawIn.read(body, read, contentLength - read);
                if (r == -1) break;
                read += r;
            }
            String bodyStr = new String(body, 0, read, "UTF-8");
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
            return true;
        } catch (IOException e) {
            Log.e(TAG, "handleClient: error", e);
            return false;
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
