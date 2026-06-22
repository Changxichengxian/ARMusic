import Foundation

enum ARMusicFileNames {
    static func sanitizedFileName(_ rawName: String, fallback: String = "track.audio") -> String {
        let trimmed = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = trimmed.isEmpty ? fallback : trimmed
        let invalid = CharacterSet(charactersIn: "\\/:*?\"<>|")
            .union(.newlines)
            .union(.controlCharacters)

        let parts = name.unicodeScalars.map { scalar -> String in
            invalid.contains(scalar) ? "_" : String(scalar)
        }
        return parts.joined().trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty ?? fallback
    }

    static func safeRelativePath(_ rawPath: String, fallbackFileName: String) -> String {
        let pieces = rawPath
            .replacingOccurrences(of: "\\", with: "/")
            .split(separator: "/")
            .map { sanitizedFileName(String($0)) }
            .filter { !$0.isEmpty && $0 != "." && $0 != ".." }

        if pieces.isEmpty {
            return sanitizedFileName(fallbackFileName)
        }
        return pieces.joined(separator: "/")
    }

    static func uniqueFileURL(in directory: URL, preferredFileName: String) -> URL {
        let fileName = sanitizedFileName(preferredFileName)
        let baseName = (fileName as NSString).deletingPathExtension
        let ext = (fileName as NSString).pathExtension
        let suffix = ext.isEmpty ? "" : ".\(ext)"

        var index = 0
        while true {
            let candidateName = index == 0 ? fileName : "\(baseName) (\(index))\(suffix)"
            let candidate = directory.appendingPathComponent(candidateName, isDirectory: false)
            if !FileManager.default.fileExists(atPath: candidate.path) {
                return candidate
            }
            index += 1
        }
    }
}

extension String {
    var nonEmpty: String? {
        isEmpty ? nil : self
    }
}
