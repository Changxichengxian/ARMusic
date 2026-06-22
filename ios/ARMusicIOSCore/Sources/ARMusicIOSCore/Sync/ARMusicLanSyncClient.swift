import Foundation

public final class ARMusicLanSyncClient {
    private let session: URLSession
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    public init(
        session: URLSession = .shared,
        encoder: JSONEncoder = JSONEncoder(),
        decoder: JSONDecoder = JSONDecoder()
    ) {
        self.session = session
        self.encoder = encoder
        self.decoder = decoder
    }

    public func fetchHealth(baseUrl: String) async throws -> ARMusicSyncHealth {
        try await fetchJSON(baseUrl: baseUrl, pathSegments: ["health"])
    }

    public func fetchManifest(baseUrl: String) async throws -> ARMusicSyncManifest {
        try await fetchJSON(baseUrl: baseUrl, pathSegments: ["manifest"])
    }

    public func downloadTrack(baseUrl: String, syncId: String) async throws -> URL {
        let request = URLRequest(url: try buildURL(baseUrl: baseUrl, pathSegments: ["tracks", syncId]))
        let (temporaryURL, response) = try await session.download(for: request)
        try validate(response: response)
        return temporaryURL
    }

    public func uploadTrack(baseUrl: String, track: ARMusicSyncTrack, fileURL: URL) async throws {
        var request = URLRequest(url: try buildURL(baseUrl: baseUrl, pathSegments: ["tracks", track.syncId]))
        request.httpMethod = "POST"
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")

        let metadata = try encoder.encode(track).base64EncodedString()
        request.setValue(metadata, forHTTPHeaderField: "X-ARMusic-Track")

        let (_, response) = try await session.upload(for: request, fromFile: fileURL)
        try validate(response: response)
    }

    private func fetchJSON<T: Decodable>(baseUrl: String, pathSegments: [String]) async throws -> T {
        let request = URLRequest(url: try buildURL(baseUrl: baseUrl, pathSegments: pathSegments))
        let (data, response) = try await session.data(for: request)
        try validate(response: response)
        return try decoder.decode(T.self, from: data)
    }

    private func buildURL(baseUrl: String, pathSegments: [String]) throws -> URL {
        let trimmed = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalized = trimmed.hasPrefix("http://") || trimmed.hasPrefix("https://")
            ? trimmed
            : "http://\(trimmed)"

        guard var components = URLComponents(string: normalized) else {
            throw ARMusicSyncError.invalidBaseUrl(baseUrl)
        }

        var path = components.percentEncodedPath
        for segment in pathSegments {
            if !path.hasSuffix("/") {
                path += "/"
            }
            path += segment.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? segment
        }
        components.percentEncodedPath = path

        guard let url = components.url else {
            throw ARMusicSyncError.invalidBaseUrl(baseUrl)
        }
        return url
    }

    private func validate(response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else {
            throw ARMusicSyncError.invalidResponse
        }
        guard (200..<300).contains(http.statusCode) else {
            throw ARMusicSyncError.httpStatus(http.statusCode)
        }
    }
}

public enum ARMusicSyncError: LocalizedError {
    case invalidBaseUrl(String)
    case invalidResponse
    case httpStatus(Int)

    public var errorDescription: String? {
        switch self {
        case .invalidBaseUrl(let value):
            return "同步地址格式不正确：\(value)"
        case .invalidResponse:
            return "同步服务没有返回有效 HTTP 响应"
        case .httpStatus(let status):
            return "同步服务返回错误：HTTP \(status)"
        }
    }
}
