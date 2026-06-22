import Darwin
import Foundation

public final class ARMusicLanDiscoveryClient {
    private let decoder: JSONDecoder

    public init(decoder: JSONDecoder = JSONDecoder()) {
        self.decoder = decoder
    }

    public func discover(timeout: TimeInterval = 2.5) async -> [ARMusicDiscoveredPeer] {
        await Task.detached(priority: .utility) {
            self.discoverBlocking(timeout: timeout)
        }.value
    }

    private func discoverBlocking(timeout: TimeInterval) -> [ARMusicDiscoveredPeer] {
        let socketFD = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard socketFD >= 0 else {
            return []
        }
        defer { Darwin.close(socketFD) }

        var enabled: Int32 = 1
        setsockopt(
            socketFD,
            SOL_SOCKET,
            SO_BROADCAST,
            &enabled,
            socklen_t(MemoryLayout<Int32>.size)
        )

        var local = sockaddr_in()
        local.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        local.sin_family = sa_family_t(AF_INET)
        local.sin_port = 0
        local.sin_addr = in_addr(s_addr: INADDR_ANY)

        let bindResult = withUnsafePointer(to: &local) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { socketAddress in
                Darwin.bind(socketFD, socketAddress, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bindResult == 0 else {
            return []
        }

        guard sendDiscoveryRequest(socketFD: socketFD) else {
            return []
        }

        let deadline = Date().addingTimeInterval(timeout)
        var peers: [String: ARMusicDiscoveredPeer] = [:]

        while Date() < deadline {
            let remainingMs = max(100, Int(deadline.timeIntervalSinceNow * 1000))
            var pollInfo = pollfd(fd: socketFD, events: Int16(POLLIN), revents: 0)
            let pollResult = Darwin.poll(&pollInfo, 1, Int32(min(remainingMs, 350)))

            guard pollResult > 0, (pollInfo.revents & Int16(POLLIN)) != 0 else {
                continue
            }

            guard let packet = receivePacket(socketFD: socketFD),
                  let response = try? decoder.decode(DiscoveryResponse.self, from: packet.data),
                  response.kind == "armusic-sync",
                  response.port > 0 else {
                continue
            }

            let baseUrl = "http://\(packet.host):\(response.port)"
            peers[baseUrl] = ARMusicDiscoveredPeer(
                name: response.name.nonEmpty ?? "ARMusic Desktop",
                baseUrl: baseUrl,
                addresses: response.addresses
            )
        }

        return peers.values.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    private func sendDiscoveryRequest(socketFD: Int32) -> Bool {
        var target = sockaddr_in()
        target.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        target.sin_family = sa_family_t(AF_INET)
        target.sin_port = in_port_t(Self.discoveryPort).bigEndian
        inet_pton(AF_INET, Self.broadcastAddress, &target.sin_addr)

        let payload = Data(Self.discoveryRequest.utf8)
        let sent = payload.withUnsafeBytes { rawBuffer in
            withUnsafePointer(to: &target) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { socketAddress in
                    sendto(
                        socketFD,
                        rawBuffer.baseAddress,
                        rawBuffer.count,
                        0,
                        socketAddress,
                        socklen_t(MemoryLayout<sockaddr_in>.size)
                    )
                }
            }
        }

        return sent == payload.count
    }

    private func receivePacket(socketFD: Int32) -> (data: Data, host: String)? {
        var buffer = [UInt8](repeating: 0, count: 2048)
        var from = sockaddr_in()
        var fromLength = socklen_t(MemoryLayout<sockaddr_in>.size)

        let count = buffer.withUnsafeMutableBytes { rawBuffer in
            withUnsafeMutablePointer(to: &from) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { socketAddress in
                    recvfrom(
                        socketFD,
                        rawBuffer.baseAddress,
                        rawBuffer.count,
                        0,
                        socketAddress,
                        &fromLength
                    )
                }
            }
        }

        guard count > 0 else {
            return nil
        }

        var address = from.sin_addr
        var hostBuffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        let host = withUnsafePointer(to: &address) { pointer -> String? in
            guard inet_ntop(AF_INET, pointer, &hostBuffer, socklen_t(INET_ADDRSTRLEN)) != nil else {
                return nil
            }
            return String(cString: hostBuffer)
        }

        guard let host else {
            return nil
        }

        return (Data(buffer.prefix(count)), host)
    }

    private static let discoveryPort: UInt16 = 49322
    private static let broadcastAddress = "255.255.255.255"
    private static let discoveryRequest = "ARMUSIC_DISCOVER_V1"
}

private struct DiscoveryResponse: Decodable {
    var kind: String = ""
    var name: String = ""
    var port: Int = 0
    var addresses: [String] = []
}
