# Phase 2: Native Modbus TCP in Mobile App

**Prerequisite**: Phase 1 mapping validated and stable.

## Goal

Add Modbus TCP as a protocol option in the Capacitor mobile app, using a custom native plugin for TCP socket access and the register mapping validated in Phase 1.

## Files to Create

### 1. Android Plugin
**`android/app/src/main/java/.../ModbusTcpPlugin.java`** (new)
- `@CapacitorPlugin(name = "ModbusTcp")`
- Single `@PluginMethod readRegisters(PluginCall call)`
- Accepts: `host` (may include `:port`, default port 502), `functionCode`, `startAddress`, `quantity`
- Opens `java.net.Socket` with 5s connect + 5s read timeout
- Builds MBAP header (7 bytes) + PDU (5 bytes), unit ID hardcoded to 1
- Returns `{ registers: number[] }` (unsigned 16-bit values)
- Handles: connection refused, timeout, Modbus exceptions (FC | 0x80), malformed responses

### 2. Register in MainActivity
**`android/app/src/main/java/.../MainActivity.java`** (modify)
- Add `registerPlugin(ModbusTcpPlugin.class)` in `onCreate()`

### 3. iOS Plugin
**`ios/App/App/ModbusTcpPlugin.swift`** (new)
- Same logic using `NWConnection` (Network framework) for TCP

**`ios/App/App/ModbusTcpPlugin.m`** (new)
- ObjC bridge: `CAP_PLUGIN(ModbusTcpPlugin, "ModbusTcp", CAP_PLUGIN_METHOD(readRegisters, CAPPluginReturnPromise))`

## Files to Modify

### 4. `web/index.html`

**New function `formatModbusData(regs1, regs2, regs3)`**:
- Port of the validated `modbusToHttpData()` mapping from Phase 1, but instead of building a `Data` array, directly produces the final named object (same shape as `formatData()` output)
- Uses the same register mapping table (see [modbus-tcp-support.md](modbus-tcp-support.md))

**New function `fetchModbusData(cfg)`**:
- 3 calls to `Capacitor.Plugins.ModbusTcp.readRegisters()`:
  - Read 1: start 0x0003, count 37
  - Read 2: start 0x0046, count 6
  - Read 3: start 0x006A, count 45
- Passes `cfg.host` as-is (plugin handles host:port parsing)
- Returns `formatModbusData(regs1, regs2, regs3)`

**Modify `fetchData()`**:
- Branch on `cfg.protocol === 'modbus'`: use `fetchModbusData(cfg)` instead of `fetch()`
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
| formatModbusData() vs reuse formatData() | Separate function | Different register addresses and some scale factors |
| Browser support | Modbus option hidden | TCP sockets unavailable in WebView |

## Verification

1. `pnpm run build:android` succeeds
2. Test on Android device against real inverter — values match Phase 1 bridge
3. Test on iOS
4. Browser: Modbus option not shown
5. Edge cases: wrong IP, host with/without port, switching protocols
