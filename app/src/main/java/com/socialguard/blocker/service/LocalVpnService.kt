package com.quell.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.quell.app.MainActivity
import com.quell.app.R
import com.quell.app.data.repository.BlockingRepository
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Local VPN service that intercepts DNS queries and returns NXDOMAIN for
 * Instagram / Facebook domains when those apps are blocked.
 *
 * Architecture:
 *   - VPN only routes traffic to the fake DNS server IP (198.51.100.1).
 *     All other traffic goes through the normal network — no performance hit.
 *   - A DNS proxy running in a coroutine reads UDP packets from the tun interface,
 *     checks if the queried domain belongs to Instagram/Facebook, and either:
 *       a) Returns NXDOMAIN (blocked) — app gets "host not found" and can't connect.
 *       b) Forwards to 8.8.8.8 and relays the real answer back (not blocked).
 *
 * Why no banking warnings: VpnService does not appear in
 * AccessibilityManager.getEnabledAccessibilityServiceList(). Most banking apps
 * only check for accessibility services, not VPN connections.
 *
 * Note: Instagram/Facebook may use DNS-over-HTTPS (DoH) for some traffic.
 * UDP DNS blocking covers the majority of app startup/API requests.
 * Pair with MonitoringForegroundService overlay for clear UX feedback.
 */
class LocalVpnService : VpnService() {

    companion object {
        private const val TAG = "QuellVPN"
        private const val NOTIF_ID = 1002
        private const val CHANNEL_ID = "quell_vpn"

        // RFC 5737 TEST-NET-3 — safe to use as a local-only address.
        // We route only this single IP through the VPN tun so all other traffic is unaffected.
        private const val FAKE_DNS_IP = "198.51.100.1"
        private const val UPSTREAM_DNS = "8.8.8.8"
        private const val VPN_ADDRESS = "10.88.0.2"
        private const val VPN_PREFIX = 30  // /30 = 2 usable addresses (10.88.0.1 / 10.88.0.2)

        private val FAKE_DNS_BYTES = byteArrayOf(198.toByte(), 51, 100.toByte(), 1)

        // Domains to intercept. Subdomains are matched automatically (e.g. *.instagram.com).
        private val INSTAGRAM_ROOTS = setOf("instagram.com", "cdninstagram.com")
        private val FACEBOOK_ROOTS  = setOf("facebook.com", "fbcdn.net", "facebook.net",
                                            "fbsbx.com", "fb.com")

        const val ACTION_STOP = "com.quell.app.VPN_STOP"

        @Volatile var instance: LocalVpnService? = null
            private set
    }

    private var tun: ParcelFileDescriptor? = null
    private var isRunning = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, t -> Log.e(TAG, "Coroutine error", t) })

    override fun onCreate() { super.onCreate(); instance = this }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopVpn(); stopSelf(); return START_NOT_STICKY }
        if (!isRunning) startVpn()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
        scope.cancel()
        tun?.close()
    }

    // -------------------------------------------------------------------------
    // VPN lifecycle
    // -------------------------------------------------------------------------

    private fun startVpn() {
        startForeground(NOTIF_ID, buildNotification())
        try {
            tun = Builder()
                .addAddress(VPN_ADDRESS, VPN_PREFIX)
                .addDnsServer(FAKE_DNS_IP)
                .addRoute(FAKE_DNS_IP, 32)     // Only this one IP goes through the tunnel
                .setSession("Quell")
                .setBlocking(false)
                .establish()
                ?: run { Log.e(TAG, "establish() returned null"); return }

            isRunning = true
            scope.launch { runProxy() }
            Log.i(TAG, "VPN started — DNS proxy active")
        } catch (e: Exception) {
            Log.e(TAG, "startVpn failed", e)
        }
    }

    private fun stopVpn() {
        isRunning = false
        tun?.close(); tun = null
        Log.i(TAG, "VPN stopped")
    }

    // -------------------------------------------------------------------------
    // DNS proxy loop
    // -------------------------------------------------------------------------

    private suspend fun runProxy() {
        val vpnTun = tun ?: return
        val inStream  = FileInputStream(vpnTun.fileDescriptor)
        val outStream = FileOutputStream(vpnTun.fileDescriptor)

        // Upstream socket is protected from the VPN so it uses the real network.
        val upstream = DatagramSocket().also { protect(it) }
        upstream.soTimeout = 3_000

        val buf = ByteArray(2048)
        val repo = BlockingRepository.getInstance(applicationContext)

        while (isRunning && currentCoroutineContext().isActive) {
            try {
                val len = withContext(Dispatchers.IO) { inStream.read(buf) }
                if (len < 28) continue           // need at least IP(20) + UDP(8) headers

                // --- Parse IPv4 header ---
                if ((buf[0].toInt() and 0xF0 shr 4) != 4) continue  // IPv4 only
                val ipHdrLen = (buf[0].toInt() and 0x0F) * 4
                if (buf[9].toInt() and 0xFF != 17) continue          // UDP protocol only
                if (ipHdrLen + 8 > len) continue

                // --- Parse UDP header ---
                val dstPort = u16(buf, ipHdrLen + 2)
                val srcPort = u16(buf, ipHdrLen + 0)
                if (dstPort != 53) continue                          // DNS only

                val dnsOff = ipHdrLen + 8
                if (len - dnsOff < 12) continue                      // minimum DNS header

                val dns = buf.copyOfRange(dnsOff, len)
                val domain = parseDomain(dns) ?: continue

                val settings = withContext(Dispatchers.IO) { repo.getSettings() }
                val blocked = when {
                    settings.blockInstagram && isDomainBlocked(domain, INSTAGRAM_ROOTS) -> true
                    settings.blockFacebook  && isDomainBlocked(domain, FACEBOOK_ROOTS)  -> true
                    else -> false
                }

                val srcIp = buf.copyOfRange(12, 16)  // original source IP (our VPN address)

                val responsePayload: ByteArray = if (blocked) {
                    Log.d(TAG, "NXDOMAIN: $domain")
                    nxDomain(dns)
                } else {
                    // Forward to real DNS and relay the answer
                    val fwd = DatagramPacket(dns, dns.size, InetSocketAddress(UPSTREAM_DNS, 53))
                    withContext(Dispatchers.IO) { upstream.send(fwd) }
                    val resp = ByteArray(2048)
                    val respPkt = DatagramPacket(resp, resp.size)
                    try {
                        withContext(Dispatchers.IO) { upstream.receive(respPkt) }
                        resp.copyOf(respPkt.length)
                    } catch (_: Exception) { continue }  // upstream timeout
                }

                // Build IP/UDP response: fake DNS → our VPN address
                val ipUdp = buildIpUdp(
                    srcIp  = FAKE_DNS_BYTES,
                    dstIp  = srcIp,
                    srcPort = 53,
                    dstPort = srcPort,
                    payload = responsePayload
                )
                withContext(Dispatchers.IO) { outStream.write(ipUdp) }

            } catch (e: Exception) {
                if (isRunning) Log.w(TAG, "proxy loop error: ${e.message}")
            }
        }
        upstream.close()
    }

    // -------------------------------------------------------------------------
    // DNS helpers
    // -------------------------------------------------------------------------

    /** Extract the query domain name from a raw DNS packet. */
    private fun parseDomain(dns: ByteArray): String? {
        if (dns.size < 13) return null
        return try {
            val sb = StringBuilder()
            var pos = 12  // skip 12-byte header
            while (pos < dns.size) {
                val labelLen = dns[pos].toInt() and 0xFF
                if (labelLen == 0) break
                if (pos + labelLen + 1 > dns.size) return null
                if (sb.isNotEmpty()) sb.append('.')
                repeat(labelLen) { sb.append((dns[pos + 1 + it].toInt() and 0xFF).toChar()) }
                pos += labelLen + 1
            }
            sb.toString().lowercase().trimEnd('.')
        } catch (_: Exception) { null }
    }

    /** True if [domain] matches any root in [roots] (exact or subdomain). */
    private fun isDomainBlocked(domain: String, roots: Set<String>): Boolean =
        roots.any { root -> domain == root || domain.endsWith(".$root") }

    /** Build a NXDOMAIN (RCODE=3) response from a query packet. */
    private fun nxDomain(query: ByteArray): ByteArray {
        val r = query.copyOf()
        r[2] = 0x81.toByte()   // QR=1, RD=1
        r[3] = 0x83.toByte()   // RA=1, RCODE=3 (NXDOMAIN)
        r[6] = 0; r[7]  = 0    // ANCOUNT = 0
        r[8] = 0; r[9]  = 0    // NSCOUNT = 0
        r[10] = 0; r[11] = 0   // ARCOUNT = 0
        return r
    }

    // -------------------------------------------------------------------------
    // Packet construction
    // -------------------------------------------------------------------------

    /** Build a minimal IPv4 / UDP packet carrying [payload]. UDP checksum is optional (0). */
    private fun buildIpUdp(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen   = 8 + payload.size
        val totalLen = 20 + udpLen
        val b = ByteArray(totalLen)

        // IP header
        b[0]  = 0x45.toByte()                    // ver=4 IHL=5
        b[2]  = (totalLen shr 8).toByte()
        b[3]  = (totalLen and 0xFF).toByte()
        b[6]  = 0x40.toByte()                    // don't fragment
        b[8]  = 64                               // TTL
        b[9]  = 17                               // protocol = UDP
        srcIp.copyInto(b, 12)
        dstIp.copyInto(b, 16)
        val csum = ipChecksum(b, 0, 20)
        b[10] = (csum shr 8).toByte()
        b[11] = (csum and 0xFF).toByte()

        // UDP header
        b[20] = (srcPort shr 8).toByte(); b[21] = (srcPort and 0xFF).toByte()
        b[22] = (dstPort shr 8).toByte(); b[23] = (dstPort and 0xFF).toByte()
        b[24] = (udpLen  shr 8).toByte(); b[25] = (udpLen  and 0xFF).toByte()
        // checksum = 0 (optional for IPv4)

        payload.copyInto(b, 28)
        return b
    }

    /** One's complement 16-bit checksum of [len] bytes starting at [off]. */
    private fun ipChecksum(b: ByteArray, off: Int, len: Int): Int {
        var s = 0; var i = off
        while (i < off + len - 1) { s += (b[i].toInt() and 0xFF shl 8) or (b[i+1].toInt() and 0xFF); i += 2 }
        if (len and 1 != 0) s += b[off + len - 1].toInt() and 0xFF shl 8
        while (s shr 16 != 0) s = (s and 0xFFFF) + (s shr 16)
        return s.inv() and 0xFFFF
    }

    private fun u16(b: ByteArray, off: Int) =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun buildNotification(): Notification {
        NotificationManager::class.java.let { nm ->
            getSystemService(nm)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Quell VPN", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Quell VPN Active")
            .setContentText("Blocking Instagram & Facebook at network level")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(
                PendingIntent.getActivity(this, 0,
                    Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).setSilent(true)
            .build()
    }
}
