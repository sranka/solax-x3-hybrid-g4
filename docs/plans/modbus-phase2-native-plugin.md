# Phase 2: Native Modbus TCP in Mobile App

**Prerequisite**: Phase 1 mapping validated and stable in `scripts/dev-server.js`.

## Goal

Add Modbus TCP as a protocol option in the Capacitor mobile app, using a custom native plugin for TCP socket access and the register-to-Values mapping validated in Phase 1.

## Source of Truth

All register addresses, scale conversions, and mapping logic are defined in **`scripts/dev-server.js`**:
- `modbusReadRegisters()` — TCP client with auto-split at 125 registers, single connection reuse
- `modbusToHttpData(regs, startAddr)` — register-to-Data mapping (lines 152–228)
- `dataToValues(data)` — Data-to-Values conversion (lines 240–264)

## Files to Create

### 1. Android Plugin
**`android/app/src/main/java/.../ModbusTcpPlugin.java`** (new)
- `@CapacitorPlugin(name = "ModbusTcp")`
- Single `@PluginMethod readRegisters(PluginCall call)`
- Accepts: `host` (may include `:port`, default port 502), `unitId`, `functionCode`, `startAddress`, `endAddress` (inclusive)
- Opens `java.net.Socket`, reuses single TCP connection for all chunks
- Auto-splits into 125-register chunks internally (same as dev-server's `modbusReadRegisters`)
- Builds MBAP header (7 bytes) + PDU (5 bytes) per chunk, sends sequentially on same socket
- 5s connect + 5s read timeout per chunk
- Returns `{ registers: number[] }` — flat array of unsigned 16-bit values
- Handles: connection refused, timeout, Modbus exceptions (FC | 0x80), malformed responses

### 2. Register in MainActivity
**`android/app/src/main/java/.../MainActivity.java`** (modify)
- Add `registerPlugin(ModbusTcpPlugin.class)` in `onCreate()`

### 3. iOS Plugin
**`ios/App/App/ModbusTcpPlugin.swift`** (new)
- Same logic using `NWConnection` (Network framework) for TCP
- Same auto-split + single-connection behavior

**`ios/App/App/ModbusTcpPlugin.m`** (new)
- ObjC bridge: `CAP_PLUGIN(ModbusTcpPlugin, "ModbusTcp", CAP_PLUGIN_METHOD(readRegisters, CAPPluginReturnPromise))`

## Files to Modify

### 4. `web/index.html`

**New function `formatModbusData(regs, startAddr)`** — same signature as `modbusToHttpData` in dev-server.js:
- Takes flat register array + start address (e.g., `0x0003`)
- Maps registers directly to the Values object (same shape as `formatData()` output)
- Combines the logic of `modbusToHttpData()` and `dataToValues()` from dev-server.js into one function
- All register addresses and scale conversions ported directly from dev-server.js
- Includes computed fields: `GridPower`, `SolarPower`, `HomePower`, `EPSTotal`

**New function `fetchModbusData(cfg)`**:
- Single call to native plugin:
  ```js
  const result = await Capacitor.Plugins.ModbusTcp.readRegisters({
    host: cfg.host, unitId: 1, functionCode: 0x04,
    startAddress: 0x0003, endAddress: 0x0096
  });
  return formatModbusData(result.registers, 0x0003);
  ```
- Passes `cfg.host` as-is (plugin handles `host:port` parsing)
- Returns Values object ready for `updateUI()`

**Modify `fetchData()`**:
- Branch on `cfg.protocol === 'modbus'`: call `fetchModbusData(cfg)` instead of `fetch()`
- Result assigned to `lastData` and passed to `updateUI()` — same as HTTP path
- HTTP path unchanged in `else` branch

**Protocol select**:
- Add `<option value="modbus">Modbus TCP</option>` dynamically only when `Capacitor.isNativePlatform()` is true

**UI when protocol=modbus**:
- Hide password field (not needed)
- Host field supports `host:port` (default port 502)
- No new form fields

**Config save/load**:
- Skip password validation when protocol=modbus
- Trigger field visibility in `openEditModal()`

**Connection list display**:
- Show `modbus://<host>` for Modbus connections

## Key Decisions

| Decision | Choice | Why |
|----------|--------|-----|
| Custom plugin vs 3rd party | Custom | Reliable, no fragile dependency |
| Connection model | Open/close per poll | Simple, negligible overhead at 10s intervals |
| Auto-split in plugin | 125-reg chunks | Modbus protocol limit, validated in Phase 1 |
| formatModbusData() vs reuse formatData() | Separate function | Goes directly from registers to Values, no intermediate Data array |
| Browser support | Modbus option hidden | TCP sockets unavailable in WebView |
| Source of truth | dev-server.js | Tested against real inverter in Phase 1 |

## Verification

1. `pnpm run build:android` succeeds
2. Test on Android device against real inverter — values match Phase 1 dev-server bridge
3. Test on iOS
4. Browser: Modbus option not shown
5. Edge cases: wrong IP, host with/without port, switching protocols
6. Compare `formatModbusData()` output with dev-server's `Values` response for same inverter
