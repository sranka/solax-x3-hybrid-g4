# Phase 1: Modbus-to-HTTP Bridge in Dev Server

## Goal

Add a `/modbus` POST endpoint to the Node.js dev server that connects to a Solax inverter via Modbus TCP, reads input registers, and returns the data in the same JSON format as the WiFi dongle HTTP API (`{Data: [...]}`). This allows validating and tuning the register mapping without modifying the web app or native code.

## How It Works

1. The existing app is configured with `protocol: http` pointing to the dev server
2. The dev server's `/modbus` endpoint connects to the inverter via Modbus TCP
3. It reads the 3 register ranges, maps Modbus values into the HTTP API's `Data` array format
4. Returns `{"type":"...", "sn":"...", "ver":"...", "Data":[...], "Information":[...]}` — same shape as the WiFi dongle
5. The app sees no difference — it thinks it's talking to the WiFi dongle

## File to Modify

**`scripts/dev-server.js`**

### Changes

**Add Modbus TCP client logic** (using Node.js `net` module, no dependencies):
- `modbusReadRegisters(host, port, unitId, fc, startAddr, quantity)` → `Promise<number[]>`
  - Opens TCP socket to `host:port`
  - Builds MBAP header + PDU, sends request
  - Reads response, validates, returns unsigned 16-bit register array
  - 5s connect + 5s read timeout

**Add mapping function** `modbusToHttpData(regs1, regs2, regs3)`:
- Takes the 3 register arrays from the 3 Modbus reads
- Builds a 171-element `Data` array matching the HTTP API indices
- Maps each Modbus register value to the correct `Data[idx]` position
- Handles scale differences (e.g., BatteryVoltage: Modbus 0x0014 is /10 → V, but HTTP d[169],d[170] is a 32-bit /100 → V)
- See mapping table in [modbus-tcp-support.md](modbus-tcp-support.md)

**Add `/modbus` POST handler**:
- Reads `DEV_MODBUS_TARGET` env var (e.g., `192.168.1.10` or `192.168.1.10:502`)
- On POST to `/modbus`:
  1. Parse host:port from `DEV_MODBUS_TARGET` (default port 502)
  2. Execute 3 Modbus reads (FC 0x04):
     - Read 1: start 0x0003, count 37
     - Read 2: start 0x0046, count 6
     - Read 3: start 0x006A, count 45
  3. Call `modbusToHttpData()` to build the `Data` array
  4. Return JSON response: `{"type":"X3-Hybrid G4","sn":"MODBUS","ver":"3.006.04","Data":[...],"Information":[0,0,0,0,0,0,0,0,"MODBUS"]}`
- Error handling: return 502 with error message on connection failure

**Existing routes unchanged**:
- `POST /` → still proxies to `DEV_PROXY_TARGET` (WiFi dongle HTTP API)
- `GET *` → still serves static files from `web/`

### Usage

```bash
# Both HTTP proxy and Modbus bridge:
DEV_PROXY_TARGET=http://192.168.1.10 DEV_MODBUS_TARGET=192.168.1.10 pnpm start

# Modbus only (no HTTP proxy needed):
DEV_MODBUS_TARGET=192.168.1.10 pnpm start
```

In the app, configure a connection with:
- Protocol: HTTP
- Host: `localhost:8080/modbus` (or just configure the dev server proxy path)

Actually, simpler: the app POSTs to `/` with the body `optType=ReadRealTimeData&pwd=...`. The dev server can detect a query param or a different path. Since the app always POSTs to the root, we need a way to route to Modbus.

**Revised routing approach**: Add a `DEV_MODBUS_TARGET` env var. When set, ALL POST requests to the dev server go through Modbus (instead of proxying to `DEV_PROXY_TARGET`). When both are set, `DEV_MODBUS_TARGET` takes precedence. This way no app changes needed — just set the env var and the dev server serves Modbus data transparently.

### Revised Usage

```bash
# Use Modbus to read from inverter (app connects to localhost:8080 as usual):
DEV_MODBUS_TARGET=192.168.1.10 pnpm start

# Use HTTP proxy to WiFi dongle (existing behavior):
DEV_PROXY_TARGET=http://192.168.1.10 pnpm start

# Both available (Modbus takes precedence for POST):
DEV_MODBUS_TARGET=192.168.1.10 DEV_PROXY_TARGET=http://192.168.1.10 pnpm start
```

## Verification

1. Start dev server with `DEV_MODBUS_TARGET` pointing to a real inverter
2. Open `http://localhost:8080` in browser
3. Configure a connection in the app: protocol HTTP, host `localhost:8080`, password (any value)
4. Verify all dashboard values match what you see through the WiFi dongle HTTP API
5. Compare side-by-side: run two browser tabs, one via Modbus bridge, one via HTTP proxy to WiFi dongle
6. Tune any mapping discrepancies in `modbusToHttpData()` and iterate

## Key Benefit

The mapping logic (`modbusToHttpData`) can be tested and tuned independently of the mobile app. Once all values match the HTTP API, the same mapping knowledge feeds into Phase 2's `formatModbusData()` function.
