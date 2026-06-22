import CryptoKit
import Foundation

public enum ARMusicSyncIdHasher {
    public static let chunkSize = 64 * 1024

    public static func makeSyncId(fileURL: URL, fileName: String? = nil) throws -> String {
        let values = try fileURL.resourceValues(forKeys: [.fileSizeKey])
        let size = UInt64(values.fileSize ?? 0)
        let normalizedFileName = (fileName ?? fileURL.lastPathComponent).lowercased()

        var hasher = SHA256()
        hasher.update(data: Data(String(size).utf8))
        hasher.update(data: Data(normalizedFileName.utf8))

        let handle = try FileHandle(forReadingFrom: fileURL)
        defer { try? handle.close() }

        let firstLength = min(Self.chunkSize, Int(size))
        if firstLength > 0 {
            try handle.seek(toOffset: 0)
            if let first = try handle.read(upToCount: firstLength), !first.isEmpty {
                hasher.update(data: first)
            }
        }

        if size > UInt64(Self.chunkSize) {
            let lastLength = min(Self.chunkSize, Int(size))
            try handle.seek(toOffset: size - UInt64(lastLength))
            if let last = try handle.read(upToCount: lastLength), !last.isEmpty {
                hasher.update(data: last)
            }
        }

        let hex = hasher.finalize().map { String(format: "%02x", $0) }.joined()
        return "sha256-\(hex.prefix(32))"
    }
}
