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
- Modbus host is derived from the hostname of `PROXY_TARGET` (e.g., `PROXY_TARGET=http://192.168.1.10` → Modbus host `192.168.1.10`). Optionally, `MODBUS_TARGET` can override the host/port (e.g., `192.168.1.10:503`).
- On POST to `/modbus`:
  1. Parse host:port from `MODBUS_TARGET` if set, otherwise extract hostname from `PROXY_TARGET` (default port 502)
  2. Execute 3 Modbus reads (FC 0x04):
     - Read 1: start 0x0003, count 37
     - Read 2: start 0x0046, count 6
     - Read 3: start 0x006A, count 45
  3. Call `modbusToHttpData()` to build the `Data` array
  4. Return JSON response: `{"type":"X3-Hybrid G4","sn":"MODBUS","ver":"3.006.04","Data":[...],"Information":[0,0,0,0,0,0,0,0,"MODBUS"]}`
- Error handling: return 502 with error message on connection failure

**Route handlers**:
- `POST /http` → always proxies to `PROXY_TARGET` (WiFi dongle HTTP API)
- `POST /modbus` → always reads via Modbus TCP (host from `MODBUS_TARGET` or `PROXY_TARGET` hostname)
- `POST /` → routes to `/modbus` when `MODBUS=1`, otherwise to `/http` (see revised routing below)
- `GET *` → still serves static files from `web/`

The explicit `/http` and `/modbus` endpoints are always available (assuming `PROXY_TARGET` is set), enabling side-by-side comparison regardless of the default POST routing.

**Revised routing for `POST /`**: The `MODBUS` env var flag controls which backend the default `POST /` uses. When set (e.g., `MODBUS=1`), `POST /` goes through Modbus; otherwise it proxies via HTTP. The Modbus host is extracted from `PROXY_TARGET`'s hostname, with an optional `MODBUS_TARGET` override. No app changes needed — just set the env var and the dev server serves Modbus data transparently.

### Usage

```bash
# Use HTTP proxy to WiFi dongle (existing behavior):
# POST / → HTTP proxy, POST /http → HTTP proxy, POST /modbus → Modbus
PROXY_TARGET=http://192.168.1.10 pnpm start

# Use Modbus as default (host derived from PROXY_TARGET):
# POST / → Modbus, POST /http → HTTP proxy, POST /modbus → Modbus
MODBUS=1 PROXY_TARGET=http://192.168.1.10 pnpm start

# Override Modbus host/port (e.g., non-standard port):
MODBUS=1 PROXY_TARGET=http://192.168.1.10 MODBUS_TARGET=192.168.1.10:503 pnpm start
```

## Verification

1. Start dev server with `PROXY_TARGET=http://<inverter-ip>` pointing to a real inverter
2. Open `http://localhost:8080` in browser
3. Compare side-by-side using explicit endpoints:
   - `curl -X POST localhost:8080/http -d 'optType=ReadRealTimeData&pwd=...'` → WiFi dongle response
   - `curl -X POST localhost:8080/modbus` → Modbus bridge response
4. Verify all values match between `/http` and `/modbus` responses
5. Test default routing: add `MODBUS=1`, confirm `POST /` returns Modbus data
6. Tune any mapping discrepancies in `modbusToHttpData()` and iterate

## Key Benefit

The mapping logic (`modbusToHttpData`) can be tested and tuned independently of the mobile app. Once all values match the HTTP API, the same mapping knowledge feeds into Phase 2's `formatModbusData()` function.
