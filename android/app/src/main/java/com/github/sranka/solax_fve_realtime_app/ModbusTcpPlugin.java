package com.github.sranka.solax_fve_realtime_app;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@CapacitorPlugin(name = "ModbusTcp")
public class ModbusTcpPlugin extends Plugin {

    private static final int MAX_REGS_PER_READ = 125;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    private final Random random = new Random();

    @PluginMethod
    public void readRegisters(PluginCall call) {
        String hostStr = call.getString("host", "");
        int unitId = call.getInt("unitId", 1);
        int fc = call.getInt("functionCode", 0x04);
        int startAddr = call.getInt("startAddress", 0);
        int endAddr = call.getInt("endAddress", 0);

        if (hostStr == null || hostStr.isEmpty()) {
            call.reject("host is required");
            return;
        }

        // Parse host:port
        String host;
        int port;
        if (hostStr.contains(":")) {
            String[] parts = hostStr.split(":", 2);
            host = parts[0];
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                call.reject("Invalid port: " + parts[1]);
                return;
            }
        } else {
            host = hostStr;
            port = 502;
        }

        int totalCount = endAddr - startAddr + 1;
        if (totalCount <= 0 || totalCount > 0xFFFF) {
            call.reject("Invalid address range");
            return;
        }

        // Run on background thread
        final String fHost = host;
        final int fPort = port;
        getBridge().executeOnMainThread(() -> {
            new Thread(() -> {
                try {
                    List<Integer> allRegisters = readAllRegisters(fHost, fPort, unitId, fc, startAddr, totalCount);
                    JSObject result = new JSObject();
                    JSArray regsArray = new JSArray();
                    for (int val : allRegisters) {
                        regsArray.put(val);
                    }
                    result.put("registers", regsArray);
                    call.resolve(result);
                } catch (Exception e) {
                    call.reject("Modbus error: " + e.getMessage());
                }
            }).start();
        });
    }

    private List<Integer> readAllRegisters(String host, int port, int unitId, int fc, int startAddr, int totalCount) throws IOException {
        List<Integer> allRegisters = new ArrayList<>();

        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            int offset = 0;
            while (offset < totalCount) {
                int chunkCount = Math.min(MAX_REGS_PER_READ, totalCount - offset);
                int chunkAddr = startAddr + offset;

                // Build MBAP header + PDU (12 bytes)
                int transactionId = random.nextInt(0xFFFF);
                byte[] request = new byte[12];
                request[0] = (byte) (transactionId >> 8);
                request[1] = (byte) (transactionId & 0xFF);
                request[2] = 0; // protocol ID high
                request[3] = 0; // protocol ID low
                request[4] = 0; // length high
                request[5] = 6; // length low (unitId + PDU = 6)
                request[6] = (byte) unitId;
                request[7] = (byte) fc;
                request[8] = (byte) (chunkAddr >> 8);
                request[9] = (byte) (chunkAddr & 0xFF);
                request[10] = (byte) (chunkCount >> 8);
                request[11] = (byte) (chunkCount & 0xFF);

                out.write(request);
                out.flush();

                // Read MBAP header (7 bytes)
                byte[] mbapHeader = new byte[7];
                in.readFully(mbapHeader);

                int respLength = ((mbapHeader[4] & 0xFF) << 8) | (mbapHeader[5] & 0xFF);

                // Read remaining PDU
                byte[] pdu = new byte[respLength - 1]; // -1 for unitId already in header
                in.readFully(pdu);

                int respFc = pdu[0] & 0xFF;
                if ((respFc & 0x80) != 0) {
                    int errorCode = pdu[1] & 0xFF;
                    throw new IOException("Modbus exception at 0x" +
                        Integer.toHexString(chunkAddr) + ": FC=0x" +
                        Integer.toHexString(respFc) + " code=" + errorCode);
                }

                int byteCount = pdu[1] & 0xFF;
                for (int i = 0; i < byteCount; i += 2) {
                    int val = ((pdu[2 + i] & 0xFF) << 8) | (pdu[2 + i + 1] & 0xFF);
                    allRegisters.add(val);
                }

                offset += chunkCount;
            }
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }

        return allRegisters;
    }
}
