package com.github.sranka.solax_fve_live_app;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@CapacitorPlugin(name = "NetworkScanner")
public class NetworkScannerPlugin extends Plugin {

    private static final int MODBUS_PORT = 502;
    private static final int CONNECT_TIMEOUT_MS = 500;
    private static final int READ_TIMEOUT_MS = 1000;
    private static final int THREAD_POOL_SIZE = 32;
    private final Random random = new Random();
    private AtomicBoolean scanning = new AtomicBoolean(false);

    @PluginMethod
    public void getSubnet(PluginCall call) {
        WifiManager wm = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            call.reject("WifiManager not available");
            return;
        }
        android.net.wifi.WifiInfo info = wm.getConnectionInfo();
        int ip = info.getIpAddress();
        if (ip == 0) {
            call.reject("Not connected to WiFi");
            return;
        }
        // Android WiFi IP is little-endian
        String ipStr = (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
        // Assume /24 subnet
        String prefix = (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + ".";

        JSObject result = new JSObject();
        result.put("ip", ipStr);
        result.put("prefix", prefix);
        result.put("count", 254);
        call.resolve(result);
    }

    @PluginMethod
    public void scanHost(PluginCall call) {
        String host = call.getString("host", "");
        if (host == null || host.isEmpty()) {
            call.reject("host is required");
            return;
        }

        new Thread(() -> {
            JSObject result = new JSObject();
            result.put("host", host);
            try {
                String[] info = probeModbus(host);
                result.put("found", true);
                result.put("serial", info[0]);
                result.put("factory", info[1]);
                result.put("module", info[2]);
            } catch (Exception e) {
                result.put("found", false);
            }
            call.resolve(result);
        }).start();
    }

    @PluginMethod
    public void stopScan(PluginCall call) {
        scanning.set(false);
        call.resolve();
    }

    /**
     * Probe a host via Modbus TCP FC 0x03: read registers 0x0000-0x0014 (21 regs).
     * Returns [InverterSN, FactoryName, ModuleName] as trimmed strings.
     */
    private String[] probeModbus(String host) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, MODBUS_PORT), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // Read 21 registers at 0x0000 with FC 0x03 (read holding registers)
            int transactionId = random.nextInt(0xFFFF);
            int startAddr = 0x0000;
            int regCount = 21; // 0x0000-0x0014
            byte[] request = new byte[12];
            request[0] = (byte) (transactionId >> 8);
            request[1] = (byte) (transactionId & 0xFF);
            request[2] = 0; request[3] = 0; // protocol ID
            request[4] = 0; request[5] = 6; // length
            request[6] = 1; // unit ID
            request[7] = 0x03; // function code: read holding registers
            request[8] = (byte) (startAddr >> 8);
            request[9] = (byte) (startAddr & 0xFF);
            request[10] = (byte) (regCount >> 8);
            request[11] = (byte) (regCount & 0xFF);

            out.write(request);
            out.flush();

            // Read MBAP header (7 bytes)
            byte[] mbap = new byte[7];
            in.readFully(mbap);

            int respLength = ((mbap[4] & 0xFF) << 8) | (mbap[5] & 0xFF);
            byte[] pdu = new byte[respLength - 1];
            in.readFully(pdu);

            int respFc = pdu[0] & 0xFF;
            if ((respFc & 0x80) != 0) {
                throw new IOException("Modbus exception");
            }

            // Parse register bytes (skip pdu[0]=fc, pdu[1]=byteCount)
            // InverterSN: regs 0x0000-0x0006 (7 regs = 14 chars)
            String serial = regsToString(pdu, 2, 7).trim();
            // FactoryName: regs 0x0007-0x000D (7 regs = 14 chars)
            String factory = regsToString(pdu, 2 + 14, 7).trim();
            // ModuleName: regs 0x000E-0x0014 (7 regs = 14 chars)
            String module = regsToString(pdu, 2 + 28, 7).trim();

            return new String[] { serial, factory, module };
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    /** Convert register bytes to a trimmed string (2 chars per register, MSB first). */
    private String regsToString(byte[] pdu, int offset, int regCount) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < regCount * 2; i++) {
            int ch = pdu[offset + i] & 0xFF;
            if (ch == 0) break;
            sb.append((char) ch);
        }
        return sb.toString().trim();
    }
}
