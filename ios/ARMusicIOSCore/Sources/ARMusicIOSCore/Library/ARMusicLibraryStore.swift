import Foundation

public struct ARMusicLibraryCatalog: Codable, Equatable, Sendable {
    public var libraryId: String
    public var deviceName: String
    public var tracks: [ARMusicSyncTrack]

    public init(
        libraryId: String = "ios-\(UUID().uuidString)",
        deviceName: String = "ARMusic iOS",
        tracks: [ARMusicSyncTrack] = []
    ) {
        self.libraryId = libraryId
        self.deviceName = deviceName
        self.tracks = tracks
    }
}

public final class ARMusicLibraryStore {
    public let rootDirectory: URL
    public let tracksDirectory: URL
    public let catalogURL: URL

    private let fileManager: FileManager
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder
    private var catalog: ARMusicLibraryCatalog

    public init(
        rootDirectory: URL? = nil,
        catalogDirectory: URL? = nil,
        deviceName: String = "ARMusic iOS",
        fileManager: FileManager = .default
    ) throws {
        self.fileManager = fileManager

        let documents = try fileManager.url(
            for: .documentDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let appSupport = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )

        self.rootDirectory = rootDirectory
            ?? documents.appendingPathComponent("ARMusic Library", isDirectory: true)
        self.tracksDirectory = self.rootDirectory.appendingPathComponent("Tracks", isDirectory: true)
        self.catalogURL = (catalogDirectory ?? appSupport.appendingPathComponent("ARMusic", isDirectory: true))
            .appendingPathComponent("library.json", isDirectory: false)

        self.encoder = JSONEncoder()
        self.encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        self.decoder = JSONDecoder()

        try fileManager.createDirectory(at: self.tracksDirectory, withIntermediateDirectories: true)
        try fileManager.createDirectory(
            at: self.catalogURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )

        if fileManager.fileExists(atPath: catalogURL.path) {
            let data = try Data(contentsOf: catalogURL)
            self.catalog = try decoder.decode(ARMusicLibraryCatalog.self, from: data)
        } else {
            self.catalog = ARMusicLibraryCatalog(deviceName: deviceName)
            try saveCatalog()
        }
    }

    public func buildManifest() -> ARMusicSyncManifest {
        ARMusicSyncManifest(
            libraryId: catalog.libraryId,
            deviceName: catalog.deviceName,
            generatedAt: ARMusicISO8601.nowString(),
            tracks: catalog.tracks.sorted { $0.relativePath.localizedStandardCompare($1.relativePath) == .orderedAscending }
        )
    }

    public func allTracks() -> [ARMusicSyncTrack] {
        catalog.tracks
    }

    public func track(syncId: String) -> ARMusicSyncTrack? {
        catalog.tracks.first { $0.syncId == syncId }
    }

    public func fileURL(for track: ARMusicSyncTrack) throws -> URL {
        let relativePath = ARMusicFileNames.safeRelativePath(
            track.relativePath,
            fallbackFileName: "\(track.syncId).mp3"
        )
        let url = tracksDirectory.appendingPathComponent(relativePath, isDirectory: false)
        guard fileManager.fileExists(atPath: url.path) else {
            throw ARMusicLibraryError.fileMissing(track.relativePath)
        }
        return url
    }

    @discardableResult
    public func importExternalFile(from sourceURL: URL) throws -> ARMusicSyncTrack {
        let fileName = ARMusicFileNames.sanitizedFileName(sourceURL.lastPathComponent)
        let destination = ARMusicFileNames.uniqueFileURL(in: tracksDirectory, preferredFileName: fileName)
        try copySecurityScopedFile(from: sourceURL, to: destination)
        return try indexLocalFile(destination, source: "ios")
    }

    @discardableResult
    public func importDownloadedFile(_ temporaryURL: URL, remoteTrack: ARMusicSyncTrack) throws -> ARMusicSyncTrack {
        let fallbackName = "\(remoteTrack.syncId).mp3"
        let safePath = ARMusicFileNames.safeRelativePath(
            remoteTrack.relativePath,
            fallbackFileName: fallbackName
        )
        let preferredDestination = tracksDirectory.appendingPathComponent(safePath, isDirectory: false)
        try fileManager.createDirectory(
            at: preferredDestination.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )

        let destination = uniqueDestination(for: preferredDestination)
        try fileManager.moveItem(at: temporaryURL, to: destination)

        var localTrack = remoteTrack
        localTrack.relativePath = tracksDirectory.relativePath(to: destination)
        localTrack.source = "ios"
        localTrack.modifiedAt = fileModificationString(destination)

        upsert(localTrack)
        try saveCatalog()
        return localTrack
    }

    @discardableResult
    public func reindexLibrary() throws -> [ARMusicSyncTrack] {
        let urls = try fileManager
            .subpathsOfDirectory(atPath: tracksDirectory.path)
            .map { tracksDirectory.appendingPathComponent($0, isDirectory: false) }
            .filter { isAudioFile($0) }

        let tracks = try urls.map { try makeTrack(for: $0, source: "ios", syncId: nil) }
        catalog.tracks = tracks
        try saveCatalog()
        return tracks
    }

    public func setDeviceName(_ deviceName: String) throws {
        catalog.deviceName = deviceName.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty ?? "ARMusic iOS"
        try saveCatalog()
    }

    private func copySecurityScopedFile(from sourceURL: URL, to destinationURL: URL) throws {
        let didAccess = sourceURL.startAccessingSecurityScopedResource()
        defer {
            if didAccess {
                sourceURL.stopAccessingSecurityScopedResource()
            }
        }

        var coordinationError: NSError?
        var copyResult: Result<Void, Error> = .success(())
        let coordinator = NSFileCoordinator(filePresenter: nil)
        coordinator.coordinate(readingItemAt: sourceURL, options: [], error: &coordinationError) { readableURL in
            do {
                try fileManager.copyItem(at: readableURL, to: destinationURL)
            } catch {
                copyResult = .failure(error)
            }
        }

        if let coordinationError {
            throw coordinationError
        }
        try copyResult.get()
    }

    private func indexLocalFile(_ fileURL: URL, source: String) throws -> ARMusicSyncTrack {
        let track = try makeTrack(for: fileURL, source: source, syncId: nil)
        upsert(track)
        try saveCatalog()
        return track
    }

    private func makeTrack(for fileURL: URL, source: String, syncId: String?) throws -> ARMusicSyncTrack {
        let metadata = ARMusicAudioMetadataReader.read(fileURL: fileURL)
        let values = try fileURL.resourceValues(forKeys: [.fileSizeKey])
        let relativePath = tracksDirectory.relativePath(to: fileURL)
        let fileStem = fileURL.deletingPathExtension().lastPathComponent

        return ARMusicSyncTrack(
            syncId: syncId ?? ARMusicSyncIdHasher.makeSyncId(fileURL: fileURL),
            title: metadata.title ?? fileStem,
            artist: metadata.artist ?? "未知歌手",
            album: metadata.album ?? "本地音乐",
            durationSeconds: metadata.durationSeconds,
            sizeBytes: Int64(values.fileSize ?? 0),
            relativePath: relativePath,
            modifiedAt: fileModificationString(fileURL),
            source: source
        )
    }

    private func upsert(_ track: ARMusicSyncTrack) {
        catalog.tracks.removeAll { $0.syncId == track.syncId }
        catalog.tracks.append(track)
    }

    private func saveCatalog() throws {
        let data = try encoder.encode(catalog)
        try data.write(to: catalogURL, options: [.atomic])
    }

    private func uniqueDestination(for preferredURL: URL) -> URL {
        if !fileManager.fileExists(atPath: preferredURL.path) {
            return preferredURL
        }
        return ARMusicFileNames.uniqueFileURL(
            in: preferredURL.deletingLastPathComponent(),
            preferredFileName: preferredURL.lastPathComponent
        )
    }

    private func fileModificationString(_ fileURL: URL) -> String? {
        guard let values = try? fileURL.resourceValues(forKeys: [.contentModificationDateKey]),
              let date = values.contentModificationDate else {
            return nil
        }
        return ARMusicISO8601.string(from: date)
    }

    private func isAudioFile(_ url: URL) -> Bool {
        let ext = url.pathExtension.lowercased()
        return ["mp3", "m4a", "aac", "flac", "wav", "aiff", "aif", "caf"].contains(ext)
    }
}

public enum ARMusicLibraryError: LocalizedError {
    case fileMissing(String)

    public var errorDescription: String? {
        switch self {
        case .fileMissing(let path):
            return "iOS 本地音乐文件不存在：\(path)"
        }
    }
}

private extension URL {
    func relativePath(to child: URL) -> String {
        let basePath = standardizedFileURL.path
        let childPath = child.standardizedFileURL.path
        guard childPath.hasPrefix(basePath) else {
            return child.lastPathComponent
        }
        let start = childPath.index(childPath.startIndex, offsetBy: basePath.count)
        return String(childPath[start...])
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            .replacingOccurrences(of: "\\", with: "/")
    }
}
