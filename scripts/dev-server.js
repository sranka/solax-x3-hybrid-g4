const http = require('http');
const net = require('net');
const fs = require('fs');
const path = require('path');

const PORT = parseInt(process.env.PORT || '8080', 10);
const PROXY_TARGET = process.env.PROXY_TARGET || '';
const MODBUS_TARGET = process.env.MODBUS_TARGET || '';
const MODBUS_DEFAULT = !!process.env.MODBUS;
const WEB_DIR = path.join(__dirname, '..', 'web');

const MIME_TYPES = {
  '.html': 'text/html',
  '.js':   'text/javascript',
  '.css':  'text/css',
  '.json': 'application/json',
  '.png':  'image/png',
  '.svg':  'image/svg+xml',
  '.ico':  'image/x-icon',
};

// --- Modbus host resolution ---

function getModbusHostPort() {
  if (MODBUS_TARGET) {
    const parts = MODBUS_TARGET.split(':');
    return { host: parts[0], port: parseInt(parts[1] || '502', 10) };
  }
  if (PROXY_TARGET) {
    const url = new URL(PROXY_TARGET);
    return { host: url.hostname, port: 502 };
  }
  return null;
}

// --- Modbus TCP client ---

const MODBUS_MAX_REGS = 125;

function modbusReadRegisters(host, port, unitId, fc, startAddr, endAddr) {
  const count = endAddr - startAddr + 1;
  // Split into chunks of MODBUS_MAX_REGS
  const chunks = [];
  for (let off = 0; off < count; off += MODBUS_MAX_REGS) {
    const chunkCount = Math.min(MODBUS_MAX_REGS, count - off);
    chunks.push({ start: startAddr + off, count: chunkCount });
  }

  return new Promise((resolve, reject) => {
    const socket = new net.Socket();
    const allRegisters = [];
    let currentChunk = 0;
    let responseData = Buffer.alloc(0);
    let readTimer;

    function chunkAddrHex() {
      return '0x' + chunks[currentChunk].start.toString(16).padStart(4, '0');
    }

    function buildRequest(addr, quantity) {
      const transactionId = Math.floor(Math.random() * 0xFFFF);
      const buf = Buffer.alloc(12);
      buf.writeUInt16BE(transactionId, 0);
      buf.writeUInt16BE(0, 2);
      buf.writeUInt16BE(6, 4);
      buf.writeUInt8(unitId, 6);
      buf.writeUInt8(fc, 7);
      buf.writeUInt16BE(addr, 8);
      buf.writeUInt16BE(quantity, 10);
      return buf;
    }

    function sendNext() {
      const { start, count: qty } = chunks[currentChunk];
      const req = buildRequest(start, qty);
      responseData = Buffer.alloc(0);
      console.log(`[modbus] request ${currentChunk + 1}/${chunks.length} (${chunkAddrHex()}) qty=${qty}: ${req.toString('hex')}`);
      clearTimeout(readTimer);
      readTimer = setTimeout(() => {
        console.log(`[modbus] READ TIMEOUT (${chunkAddrHex()}) after 5s, received ${responseData.length} bytes: ${responseData.toString('hex')}`);
        cleanup();
        reject(new Error(`Modbus read timeout at ${chunkAddrHex()} from ${host}:${port}`));
      }, 5000);
      socket.write(req);
    }

    const cleanup = () => {
      clearTimeout(readTimer);
      socket.destroy();
    };

    const connectTimer = setTimeout(() => {
      console.log(`[modbus] CONNECT TIMEOUT after 5s to ${host}:${port}`);
      cleanup();
      reject(new Error(`Modbus connect timeout to ${host}:${port}`));
    }, 5000);

    socket.connect(port, host, () => {
      clearTimeout(connectTimer);
      console.log(`[modbus] connected to ${host}:${port}, reading ${count} registers from 0x${startAddr.toString(16).padStart(4, '0')} in ${chunks.length} chunk(s)`);
      sendNext();
    });

    socket.on('data', (chunk) => {
      console.log(`[modbus] data chunk (${chunkAddrHex()}): ${chunk.length} bytes: ${chunk.toString('hex')}`);
      responseData = Buffer.concat([responseData, chunk]);
      if (responseData.length < 9) return;
      const respLength = responseData.readUInt16BE(4);
      if (responseData.length < 6 + respLength) return;

      clearTimeout(readTimer);
      console.log(`[modbus] response complete (${chunkAddrHex()}): ${responseData.length} bytes`);

      const respFc = responseData.readUInt8(7);
      if (respFc & 0x80) {
        const errorCode = responseData.readUInt8(8);
        console.log(`[modbus] ERROR (${chunkAddrHex()}): FC=0x${respFc.toString(16)} code=${errorCode}`);
        cleanup();
        reject(new Error(`Modbus error at ${chunkAddrHex()}: FC=0x${respFc.toString(16)} code=${errorCode}`));
        return;
      }

      const byteCount = responseData.readUInt8(8);
      for (let i = 0; i < byteCount; i += 2) {
        allRegisters.push(responseData.readUInt16BE(9 + i));
      }
      console.log(`[modbus] OK (${chunkAddrHex()}): ${byteCount / 2} registers, total ${allRegisters.length}/${count}`);

      currentChunk++;
      if (currentChunk < chunks.length) {
        sendNext();
      } else {
        cleanup();
        resolve(allRegisters);
      }
    });

    socket.on('close', (hadError) => {
      console.log(`[modbus] socket closed hadError=${hadError}`);
    });

    socket.on('error', (err) => {
      console.log(`[modbus] SOCKET ERROR (${chunkAddrHex()}): ${err.message}`);
      cleanup();
      reject(err);
    });
  });
}

// --- Modbus-to-HTTP data mapping ---

function modbusToHttpData(regs, startAddr) {
  const d = new Array(171).fill(0);
  const r = (addr) => regs[addr - startAddr];

  // Grid voltage/current/power
  d[0] = r(0x006A);   // GridAVoltage
  d[1] = r(0x006E);   // GridBVoltage
  d[2] = r(0x0072);   // GridCVoltage
  d[3] = r(0x006B);   // GridACurrent (int16, app interprets as signed)
  d[4] = r(0x006F);   // GridBCurrent
  d[5] = r(0x0073);   // GridCCurrent
  d[6] = r(0x006C);   // GridAPower
  d[7] = r(0x0070);   // GridBPower
  d[8] = r(0x0074);   // GridCPower

  // PV
  d[10] = r(0x0003);  // Vdc1
  d[11] = r(0x0004);  // Vdc2
  d[12] = r(0x0005);  // Idc1
  d[13] = r(0x0006);  // Idc2
  d[14] = r(0x000A);  // PowerDc1
  d[15] = r(0x000B);  // PowerDc2

  // Grid frequency
  d[16] = r(0x006D);  // FreqacA
  d[17] = r(0x0071);  // FreqacB
  d[18] = r(0x0075);  // FreqacC

  // RunMode
  d[19] = r(0x0009);

  // EPS / Off-grid
  d[23] = r(0x0076);  // EPSAVoltage
  d[24] = r(0x007A);  // EPSBVoltage
  d[25] = r(0x007E);  // EPSCVoltage
  d[26] = r(0x0077);  // EPSACurrent
  d[27] = r(0x007B);  // EPSBCurrent
  d[28] = r(0x007F);  // EPSCCurrent
  d[29] = r(0x0078);  // EPSAPower
  d[30] = r(0x007C);  // EPSBPower
  d[31] = r(0x0080);  // EPSCPower

  // Feed-in power — 32-bit signed, two registers map directly
  d[34] = r(0x0046);  // feedInPower low word
  d[35] = r(0x0047);  // feedInPower high word

  // Battery power — int16
  d[41] = r(0x0016);

  // Yield total/today
  d[68] = r(0x0052);  // Etotal_togrid low word
  d[69] = r(0x0053);  // Etotal_togrid high word
  d[70] = r(0x0050);  // Etoday_togrid

  // Feed-in / consume energy totals — 32-bit unsigned
  d[86] = r(0x0048);  // FeedInEnergy low
  d[87] = r(0x0049);  // FeedInEnergy high
  d[88] = r(0x004A);  // ConsumeEnergy low
  d[89] = r(0x004B);  // ConsumeEnergy high

  // Battery state
  d[103] = r(0x001C); // BatteryCapacity (%)
  d[105] = r(0x0018); // BatteryTemperature (int16, °C)

  // BatteryRemainingEnergy: Modbus uint32 Wh → d[106] in 0.1 kWh units
  // d[106]/10 = kWh, so d[106] = Wh / 100
  // Solax 32-bit: low word first (0x0026), high word second (0x0027)
  const battRemWh = r(0x0026) + 65536 * r(0x0027);
  d[106] = Math.round(battRemWh / 100);

  // BatteryVoltage: Modbus reg/10 → V, HTTP r32u(d[169],d[170])/100 → V
  // r32u(a,b) = a + 65536*b, so a=low word, b=high word
  d[169] = r(0x0014) * 10;
  d[170] = 0;

  return d;
}

// --- Data-to-values conversion (mirrors index.html parseData) ---

function r16s(n) { return n < 32768 ? n : n - 65536; }
function r32u(a, b) { return a + 65536 * b; }
function r32s(a, b) { return a < 32768 ? a + 65536 * b : a + 65536 * b - 4294967296; }
function readRunMode(m) {
  return ['Waiting', 'Checking', 'Normal', 'Fault', 'Permanent Fault',
    'Updating', 'EPS Check', 'EPS Mode', 'Self Test', 'Idle', 'Standby'][m] || String(m);
}

function dataToValues(d) {
  const r = {
    Yield_Today: d[70] / 10, Yield_Total: r32u(d[68], d[69]) / 10,
    PowerDc1: d[14], PowerDc2: d[15], BAT_Power: r16s(d[41]),
    feedInPower: r32s(d[34], d[35]),
    GridAPower: r16s(d[6]), GridBPower: r16s(d[7]), GridCPower: r16s(d[8]),
    FeedInEnergy: r32u(d[86], d[87]) / 100, ConsumeEnergy: r32u(d[88], d[89]) / 100,
    RunMode: readRunMode(d[19]),
    EPSAPower: r16s(d[29]), EPSBPower: r16s(d[30]), EPSCPower: r16s(d[31]),
    Vdc1: d[10] / 10, Vdc2: d[11] / 10, Idc1: d[12] / 10, Idc2: d[13] / 10,
    EPSAVoltage: d[23] / 10, EPSBVoltage: d[24] / 10, EPSCVoltage: d[25] / 10,
    EPSACurrent: r16s(d[26]) / 10, EPSBCurrent: r16s(d[27]) / 10, EPSCCurrent: r16s(d[28]) / 10,
    BatteryCapacity: d[103], BatteryVoltage: r32u(d[169], d[170]) / 100, BatteryRemainingEnergy: d[106] / 10,
    BatteryTemperature: r16s(d[105]),
    GridAVoltage: d[0] / 10, GridBVoltage: d[1] / 10, GridCVoltage: d[2] / 10,
    GridACurrent: r16s(d[3]) / 10, GridBCurrent: r16s(d[4]) / 10, GridCCurrent: r16s(d[5]) / 10,
    FreqacA: d[16] / 100, FreqacB: d[17] / 100, FreqacC: d[18] / 100,
  };
  r.GridPower = r.GridAPower + r.GridBPower + r.GridCPower;
  r.SolarPower = r.PowerDc1 + r.PowerDc2;
  r.HomePower = r.SolarPower - r.BAT_Power - r.feedInPower;
  if (r.HomePower < 0) r.HomePower = 0;
  r.EPSTotal = r.EPSAPower + r.EPSBPower + r.EPSCPower;
  return r;
}

// --- Route handlers ---

function serveStatic(req, res) {
  let filePath = path.join(WEB_DIR, req.url === '/' ? 'index.html' : req.url);
  filePath = path.normalize(filePath);
  if (!filePath.startsWith(WEB_DIR)) {
    res.writeHead(403);
    res.end('Forbidden');
    return;
  }

  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404);
      res.end('Not found');
      return;
    }
    const ext = path.extname(filePath).toLowerCase();
    res.writeHead(200, {
      'Content-Type': MIME_TYPES[ext] || 'application/octet-stream',
      'Cache-Control': 'no-cache',
    });
    res.end(data);
  });
}

function handleHttpProxy(req, res) {
  if (!PROXY_TARGET) {
    res.writeHead(502, { 'Content-Type': 'text/plain' });
    res.end('PROXY_TARGET not set');
    return;
  }

  let body = [];
  req.on('data', chunk => body.push(chunk));
  req.on('end', () => {
    body = Buffer.concat(body);

    const target = new URL('/', PROXY_TARGET);
    const options = {
      hostname: target.hostname,
      port: target.port || 80,
      path: target.pathname + target.search,
      method: 'POST',
      headers: { ...req.headers, host: target.host },
    };

    const proxyReq = http.request(options, proxyRes => {
      const responseHeaders = { ...proxyRes.headers };
      responseHeaders['access-control-allow-origin'] = '*';
      res.writeHead(proxyRes.statusCode, responseHeaders);
      proxyRes.pipe(res);
    });

    proxyReq.on('error', err => {
      res.writeHead(502, { 'Content-Type': 'text/plain' });
      res.end(`Proxy error: ${err.message}`);
    });

    proxyReq.end(body);
  });
}

async function handleModbus(req, res) {
  const target = getModbusHostPort();
  if (!target) {
    res.writeHead(502, { 'Content-Type': 'text/plain' });
    res.end('No Modbus target: set PROXY_TARGET or MODBUS_TARGET');
    return;
  }

  // Drain request body (not used, but must be consumed)
  req.resume();
  await new Promise(resolve => req.on('end', resolve));

  try {
    const { host, port } = target;
    const unitId = 1;
    const fc = 0x04;

    // Read 0x0003–0x0096 (148 registers), auto-split into chunks
    const regs = await modbusReadRegisters(host, port, unitId, fc, 0x0003, 0x0096);

    const data = modbusToHttpData(regs, 0x0003);
    const result = {
      creator: 'modbus-proxy:1.0',
      type: 'X3-Hybrid G4',
      sn: 'MODBUS',
      ver: '3.006.04',
      Data: data,
      Information: [0, 0, 0, 0, 0, 0, 0, 0, 'MODBUS'],
      Values: dataToValues(data),
    };

    res.writeHead(200, {
      'Content-Type': 'application/json',
      'Access-Control-Allow-Origin': '*',
    });
    res.end(JSON.stringify(result));
  } catch (err) {
    res.writeHead(502, { 'Content-Type': 'text/plain' });
    res.end(`Modbus error: ${err.message}`);
  }
}

async function handleModbusRead(req, res, addrHex) {
  const target = getModbusHostPort();
  if (!target) {
    res.writeHead(502, { 'Content-Type': 'text/plain' });
    res.end('No Modbus target: set PROXY_TARGET or MODBUS_TARGET');
    return;
  }

  const startAddr = parseInt(addrHex, 16);
  if (isNaN(startAddr) || startAddr < 0 || startAddr > 0xFFFF) {
    res.writeHead(400, { 'Content-Type': 'text/plain' });
    res.end(`Invalid address: ${addrHex}`);
    return;
  }

  try {
    const { host, port } = target;
    const regs = await modbusReadRegisters(host, port, 1, 0x04, startAddr, startAddr + 1);

    const [a, b] = regs;
    const result = {
      address: '0x' + startAddr.toString(16).padStart(4, '0'),
      data: [a, b],
      r16s: [r16s(a), r16s(b)],
      r32u: a + 65536 * b,
      r32s: r32s(a, b),
      'r32u-reversed': b + 65536 * a,
      'r32s-reversed': r32s(b, a),
    };

    res.writeHead(200, {
      'Content-Type': 'application/json',
      'Access-Control-Allow-Origin': '*',
    });
    res.end(JSON.stringify(result, null, 2));
  } catch (err) {
    res.writeHead(502, { 'Content-Type': 'text/plain' });
    res.end(`Modbus error: ${err.message}`);
  }
}

// --- Server ---

const server = http.createServer((req, res) => {
  const url = req.url.split('?')[0];

  if (req.method === 'POST') {
    if (url === '/http') {
      handleHttpProxy(req, res);
    } else if (url === '/modbus') {
      handleModbus(req, res);
    } else if (MODBUS_DEFAULT) {
      handleModbus(req, res);
    } else {
      handleHttpProxy(req, res);
    }
  } else {
    const modbusReadMatch = url.match(/^\/modbus\/([0-9a-fA-F]+)$/);
    if (modbusReadMatch) {
      handleModbusRead(req, res, modbusReadMatch[1]);
    } else if (url === '/modbus') {
      handleModbus(req, res);
    } else {
      serveStatic(req, res);
    }
  }
});

server.listen(PORT, () => {
  console.log(`Dev server: http://localhost:${PORT}`);
  if (PROXY_TARGET) {
    console.log(`  POST /http → proxy to ${PROXY_TARGET}`);
  }
  const modbusTarget = getModbusHostPort();
  if (modbusTarget) {
    console.log(`  POST /modbus → Modbus TCP ${modbusTarget.host}:${modbusTarget.port}`);
  }
  console.log(`  POST / → ${MODBUS_DEFAULT ? 'Modbus' : 'HTTP proxy'} (MODBUS=${MODBUS_DEFAULT ? '1' : 'unset'})`);
  if (!PROXY_TARGET && !modbusTarget) {
    console.log('Warning: neither PROXY_TARGET nor MODBUS_TARGET set, POST requests will fail');
  }
});
