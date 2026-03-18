import Foundation
import Capacitor
import Network

@objc(ModbusTcpPlugin)
public class ModbusTcpPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "ModbusTcpPlugin"
    public let jsName = "ModbusTcp"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "readRegisters", returnType: CAPPluginReturnPromise)
    ]

    private static let maxRegsPerRead = 125
    private static let timeoutSeconds: TimeInterval = 5

    @objc func readRegisters(_ call: CAPPluginCall) {
        guard let hostStr = call.getString("host"), !hostStr.isEmpty else {
            call.reject("host is required")
            return
        }

        let unitId = UInt8(call.getInt("unitId") ?? 1)
        let fc = UInt8(call.getInt("functionCode") ?? 0x04)
        let startAddr = call.getInt("startAddress") ?? 0
        let endAddr = call.getInt("endAddress") ?? 0
        let totalCount = endAddr - startAddr + 1

        guard totalCount > 0 && totalCount <= 0xFFFF else {
            call.reject("Invalid address range")
            return
        }

        // Parse host:port
        let host: String
        let port: UInt16
        if hostStr.contains(":") {
            let parts = hostStr.split(separator: ":", maxSplits: 1)
            host = String(parts[0])
            port = UInt16(parts[1]) ?? 502
        } else {
            host = hostStr
            port = 502
        }

        DispatchQueue.global(qos: .userInitiated).async {
            self.performRead(host: host, port: port, unitId: unitId, fc: fc,
                           startAddr: startAddr, totalCount: totalCount, call: call)
        }
    }

    private func performRead(host: String, port: UInt16, unitId: UInt8, fc: UInt8,
                            startAddr: Int, totalCount: Int, call: CAPPluginCall) {
        let nwHost = NWEndpoint.Host(host)
        let nwPort = NWEndpoint.Port(rawValue: port)!
        let connection = NWConnection(host: nwHost, port: nwPort, using: .tcp)

        let semaphore = DispatchSemaphore(value: 0)
        var connectError: Error?

        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                semaphore.signal()
            case .failed(let error):
                connectError = error
                semaphore.signal()
            case .cancelled:
                connectError = NSError(domain: "ModbusTcp", code: -1,
                    userInfo: [NSLocalizedDescriptionKey: "Connection cancelled"])
                semaphore.signal()
            default:
                break
            }
        }

        connection.start(queue: .global(qos: .userInitiated))

        let connectResult = semaphore.wait(timeout: .now() + Self.timeoutSeconds)
        if connectResult == .timedOut {
            connection.cancel()
            call.reject("Modbus connect timeout to \(host):\(port)")
            return
        }
        if let err = connectError {
            connection.cancel()
            call.reject("Modbus connect error: \(err.localizedDescription)")
            return
        }

        var allRegisters: [Int] = []
        var offset = 0

        while offset < totalCount {
            let chunkCount = min(Self.maxRegsPerRead, totalCount - offset)
            let chunkAddr = startAddr + offset

            do {
                let regs = try readChunk(connection: connection, unitId: unitId, fc: fc,
                                        addr: chunkAddr, count: chunkCount)
                allRegisters.append(contentsOf: regs)
                offset += chunkCount
            } catch {
                connection.cancel()
                call.reject("Modbus error: \(error.localizedDescription)")
                return
            }
        }

        connection.cancel()

        var result = JSObject()
        result["registers"] = allRegisters
        call.resolve(result)
    }

    private func readChunk(connection: NWConnection, unitId: UInt8, fc: UInt8,
                          addr: Int, count: Int) throws -> [Int] {
        let transactionId = UInt16.random(in: 0...0xFFFE)

        // Build request (12 bytes)
        var request = Data(count: 12)
        request[0] = UInt8(transactionId >> 8)
        request[1] = UInt8(transactionId & 0xFF)
        request[2] = 0 // protocol ID
        request[3] = 0
        request[4] = 0 // length high
        request[5] = 6 // length low
        request[6] = unitId
        request[7] = fc
        request[8] = UInt8(addr >> 8)
        request[9] = UInt8(addr & 0xFF)
        request[10] = UInt8(count >> 8)
        request[11] = UInt8(count & 0xFF)

        // Send
        let sendSem = DispatchSemaphore(value: 0)
        var sendError: Error?
        connection.send(content: request, completion: .contentProcessed { error in
            sendError = error
            sendSem.signal()
        })
        sendSem.wait()
        if let err = sendError {
            throw err
        }

        // Receive MBAP header (7 bytes)
        let headerData = try receiveExact(connection: connection, length: 7)
        let respLength = (Int(headerData[4]) << 8) | Int(headerData[5])

        // Receive remaining (respLength - 1 for unitId)
        let pduData = try receiveExact(connection: connection, length: respLength - 1)

        let respFc = pduData[0]
        if (respFc & 0x80) != 0 {
            let errorCode = pduData[1]
            throw NSError(domain: "ModbusTcp", code: Int(errorCode),
                userInfo: [NSLocalizedDescriptionKey:
                    "Modbus exception at 0x\(String(addr, radix: 16)): FC=0x\(String(respFc, radix: 16)) code=\(errorCode)"])
        }

        let byteCount = Int(pduData[1])
        var registers: [Int] = []
        for i in stride(from: 0, to: byteCount, by: 2) {
            let val = (Int(pduData[2 + i]) << 8) | Int(pduData[2 + i + 1])
            registers.append(val)
        }

        return registers
    }

    private func receiveExact(connection: NWConnection, length: Int) throws -> Data {
        let sem = DispatchSemaphore(value: 0)
        var resultData: Data?
        var resultError: Error?

        connection.receive(minimumIncompleteLength: length, maximumLength: length) { data, _, _, error in
            resultData = data
            resultError = error
            sem.signal()
        }

        let waitResult = sem.wait(timeout: .now() + Self.timeoutSeconds)
        if waitResult == .timedOut {
            throw NSError(domain: "ModbusTcp", code: -2,
                userInfo: [NSLocalizedDescriptionKey: "Modbus read timeout"])
        }
        if let err = resultError {
            throw err
        }
        guard let data = resultData, data.count >= length else {
            throw NSError(domain: "ModbusTcp", code: -3,
                userInfo: [NSLocalizedDescriptionKey: "Incomplete Modbus response: expected \(length) bytes"])
        }
        return data
    }
}
