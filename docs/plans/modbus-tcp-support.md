# Plan: Add Modbus TCP Protocol Support

## Context

The Solax FVE Monitor currently communicates with inverters only via their WiFi dongle HTTP API. Some users may want direct Modbus TCP communication (port 502) — e.g., when no WiFi dongle is available, when using Ethernet-connected inverters, or for lower-latency polling.

## Two-Phase Approach

The implementation is split into two independent phases:

1. **[Phase 1: Modbus-to-HTTP Bridge in Dev Server](modbus-phase1-dev-server.md)** — Add a `/modbus` endpoint to the Node.js dev server that reads Modbus TCP registers from the inverter and returns data in the same HTTP API JSON format. This validates the register mapping without touching the app or native code.

2. **[Phase 2: Native Modbus TCP in Mobile App](modbus-phase2-native-plugin.md)** — Integrate Modbus TCP directly into the Capacitor app via a custom native plugin, using the mapping validated in Phase 1.

## Register Mapping Reference (FC 0x04, Input Registers)

| App Field | HTTP `d[idx]` | Modbus Reg | Modbus Variable | Scale |
|-----------|---------------|------------|-----------------|-------|
| GridAVoltage | d[0]/10 | 0x006A | GridVoltage_R(X3) | /10 → V |
| GridBVoltage | d[1]/10 | 0x006E | GridVoltage_S(X3) | /10 → V |
| GridCVoltage | d[2]/10 | 0x0072 | GridVoltage_T(X3) | /10 → V |
| GridACurrent | r16s(d[3])/10 | 0x006B | GridCurrent_R(X3) int16 | /10 → A |
| GridBCurrent | r16s(d[4])/10 | 0x006F | GridCurrent_S(X3) int16 | /10 → A |
| GridCCurrent | r16s(d[5])/10 | 0x0073 | GridCurrent_T(X3) int16 | /10 → A |
| GridAPower | r16s(d[6]) | 0x006C | GridPower_R(X3) int16 | 1W |
| GridBPower | r16s(d[7]) | 0x0070 | GridPower_S(X3) int16 | 1W |
| GridCPower | r16s(d[8]) | 0x0074 | GridPower_T(X3) int16 | 1W |
| Vdc1 | d[10]/10 | 0x0003 | PV1_Voltage | /10 → V |
| Vdc2 | d[11]/10 | 0x0004 | PV2_Voltage | /10 → V |
| Idc1 | d[12]/10 | 0x0005 | PV1_Current | /10 → A |
| Idc2 | d[13]/10 | 0x0006 | PV2_Current | /10 → A |
| PowerDc1 | d[14] | 0x000A | PV1_Power | 1W |
| PowerDc2 | d[15] | 0x000B | PV2_Power | 1W |
| FreqacA | d[16]/100 | 0x006D | GridFrequency_R(X3) | /100 → Hz |
| FreqacB | d[17]/100 | 0x0071 | GridFrequency_S(X3) | /100 → Hz |
| FreqacC | d[18]/100 | 0x0075 | GridFrequency_T(X3) | /100 → Hz |
| RunMode | d[19] | 0x0009 | RunMode | enum |
| EPSAVoltage | d[23]/10 | 0x0076 | Off-gridVolt_R(X3) | /10 → V |
| EPSBVoltage | d[24]/10 | 0x007A | Off-gridVolt_S(X3) | /10 → V |
| EPSCVoltage | d[25]/10 | 0x007E | Off-gridVolt_T(X3) | /10 → V |
| EPSACurrent | r16s(d[26])/10 | 0x0077 | Off-gridCurrent_R(X3) | /10 → A |
| EPSBCurrent | r16s(d[27])/10 | 0x007B | Off-gridCurrent_S(X3) | /10 → A |
| EPSCCurrent | r16s(d[28])/10 | 0x007F | Off-gridCurrent_T(X3) | /10 → A |
| EPSAPower | r16s(d[29]) | 0x0078 | Off-gridPowerActive_R(X3) int16 | 1W |
| EPSBPower | r16s(d[30]) | 0x007C | Off-gridPowerActive_S(X3) int16 | 1W |
| EPSCPower | r16s(d[31]) | 0x0080 | Off-gridPowerActive_T(X3) int16 | 1W |
| feedInPower | r32s(d[34],d[35]) | 0x0046-0x0047 | feedin_power int32 | 1W |
| BAT_Power | r16s(d[41]) | 0x0016 | Batpower_Charge1 int16 | 1W |
| Yield_Total | r32u(d[68],d[69])/10 | 0x0094-0x0095 | EnergyTotal uint32 | /10 → kWh |
| Yield_Today | d[70]/10 | 0x0096 | SolarEnergyToday | /10 → kWh |
| FeedInEnergy | r32u(d[86],d[87])/100 | 0x0048-0x0049 | feedin_energy_total uint32 | /100 → kWh |
| ConsumeEnergy | r32u(d[88],d[89])/100 | 0x004A-0x004B | consum_energy_total uint32 | /100 → kWh |
| BatteryCapacity | d[103] | 0x001C | Battery_Capacity | 1% |
| BatteryTemperature | r16s(d[105]) | 0x0018 | TemperatureBat int16 | 1°C |
| BatteryRemainingEnergy | d[106]/10 | 0x0026-0x0027 | BMS_BatteryCapacity uint32 | Wh→kWh: /1000 |
| BatteryVoltage | r32u(d[169],d[170])/100 | 0x0014 | BatVoltage_Charge1 int16 | /10 → V |

### Optimized Modbus Read Strategy (3 reads)

| Read | Start | Count | Covers |
|------|-------|-------|--------|
| 1 | 0x0003 | 37 (→0x0027) | PV, RunMode, Battery (voltage/power/temp/capacity), BMS_BatteryCapacity |
| 2 | 0x0046 | 6 (→0x004B) | Feed-in power, feed-in/consume energy total |
| 3 | 0x006A | 45 (→0x0096) | Grid X3, Off-grid X3, feed-in per phase, energy total/today |

Source: `docs/solax_modbus_registers.csv`
