package com.lalilu.lmusic.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.math.max

class ARMusicLanDiscoveryClient(
    private val json: Json,
) {
    suspend fun discover(timeoutMs: Long = 2500L): List<ARMusicDiscoveredPeer> =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            val peers = linkedMapOf<String, ARMusicDiscoveredPeer>()

            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 350

                val request = "ARMUSIC_DISCOVER_V1".toByteArray(Charsets.UTF_8)
                val packet = DatagramPacket(
                    request,
                    request.size,
                    InetAddress.getByName("255.255.255.255"),
                    DISCOVERY_PORT,
                )
                socket.send(packet)

                while (System.currentTimeMillis() < deadline) {
                    val buffer = ByteArray(2048)
                    val responsePacket = DatagramPacket(buffer, buffer.size)

                    try {
                        socket.receive(responsePacket)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }

                    val text = String(
                        responsePacket.data,
                        responsePacket.offset,
                        responsePacket.length,
                        Charsets.UTF_8,
                    )
                    val response = runCatching {
                        json.decodeFromString<DiscoveryResponse>(text)
                    }.getOrNull() ?: continue

                    if (response.kind != "armusic-sync") continue
                    val host = responsePacket.address.hostAddress ?: continue
                    val port = response.port.takeIf { it > 0 } ?: continue
                    val baseUrl = "http://$host:$port"

                    peers[baseUrl] = ARMusicDiscoveredPeer(
                        name = response.name.ifBlank { "ARMusic Desktop" },
                        baseUrl = baseUrl,
                        addresses = response.addresses,
                    )

                    val remaining = deadline - System.currentTimeMillis()
                    socket.soTimeout = max(150L, remaining.coerceAtMost(350L)).toInt()
                }
            }

            peers.values.toList()
        }

    private companion object {
        const val DISCOVERY_PORT = 49322
    }
}

data class ARMusicDiscoveredPeer(
    val name: String,
    val baseUrl: String,
    val addresses: List<String> = emptyList(),
)

@Serializable
private data class DiscoveryResponse(
    val kind: String = "",
    val name: String = "",
    val port: Int = 0,
    val addresses: List<String> = emptyList(),
)
