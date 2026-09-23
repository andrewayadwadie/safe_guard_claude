package com.safeguard.parentalcontrol.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.AlertRepository
import com.safeguard.parentalcontrol.data.repository.ContentFilterRepository
import com.safeguard.parentalcontrol.presentation.MainActivity
import com.safeguard.parentalcontrol.util.Constants
import com.safeguard.parentalcontrol.util.LocaleHelper
import com.safeguard.parentalcontrol.util.PreferencesManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.DatagramChannel
import javax.inject.Inject

/**
 * VPN service for content filtering using DNS interception
 *
 * How it works:
 * 1. All traffic is routed through the VPN
 * 2. DNS queries (UDP port 53) are intercepted
 * 3. Domain names are extracted from DNS queries
 * 4. Blocked domains receive a fake response (0.0.0.0)
 * 5. Allowed domains are forwarded to real DNS servers
 */
@AndroidEntryPoint
class ContentFilterVpnService : VpnService() {

    private fun getLocalizedString(resId: Int): String =
        LocaleHelper.localizedContext(this).getString(resId)

    @Inject
    lateinit var alertRepository: AlertRepository

    @Inject
    lateinit var contentFilterRepository: ContentFilterRepository

    @Inject
    lateinit var preferencesManager: PreferencesManager

    private var vpnInterface: ParcelFileDescriptor? = null
    private var filteringJob: Job? = null
    private var serviceJob: Job? = null
    private var _serviceScope: CoroutineScope? = null
    @Volatile private var isShuttingDown = false

    // Set in onRevoke() when Android hands the single VPN slot to another VPN app
    // (e.g. ProtonVPN). Lets onDestroy send the specific "foreign VPN" alert instead
    // of the generic "vpn_disconnected" one, and suppresses the duplicate.
    @Volatile private var revokedByForeignVpn = false

    private val serviceScope: CoroutineScope
        get() = _serviceScope ?: CoroutineScope(Dispatchers.IO + SupervisorJob().also {
            serviceJob = it
        }).also { _serviceScope = it }

    // Synchronization lock for writing to VPN output stream
    private val outputLock = Any()

    // Blocked domains (loaded from backend) - limit size to prevent memory bloat
    private val blockedDomains = mutableSetOf<String>()
    private val MAX_BLOCKED_DOMAINS = 500 // Reasonable limit for custom domains

    // Category filters
    private var blockAdult = true
    private var blockViolence = true
    private var blockGambling = true
    private var blockDrugs = true
    private var blockSocialMedia = false

    // Adult content keywords - use Set for O(1) lookup
    private val adultKeywords = setOf(
        "porn", "xxx", "adult", "nsfw", "nude", "explicit", "sex", "xnxx", "xvideos",
        "pornhub", "xhamster", "redtube", "youporn", "brazzers", "playboy", "onlyfans",
        "hentai", "rule34", "livejasmin", "chaturbate", "stripchat"
    )

    private val gamblingKeywords = setOf(
        "casino", "bet", "poker", "gambling", "slots", "lottery", "bet365", "draftkings", "fanduel"
    )

    // Use Set for O(1) lookup
    private val socialMediaDomains = setOf(
        // Facebook/Meta
        "facebook.com", "fb.com", "fbcdn.net", "fbsbx.com", "facebook.net",
        "messenger.com", "m.me",
        // Instagram
        "instagram.com", "cdninstagram.com", "ig.me",
        // Twitter/X
        "twitter.com", "x.com", "twimg.com", "t.co", "tweetdeck.com",
        // TikTok
        "tiktok.com", "tiktokcdn.com", "tiktokv.com", "musical.ly",
        "byteoversea.com", "ibytedtos.com", "tiktokcdn-us.com",
        // Snapchat
        "snapchat.com", "snap.com", "snapkit.com", "sc-cdn.net", "snapads.com",
        // Reddit
        "reddit.com", "redd.it", "redditstatic.com", "redditmedia.com",
        // Pinterest
        "pinterest.com", "pinimg.com",
        // YouTube (optional - comment out if too restrictive)
        // "youtube.com", "youtu.be", "ytimg.com", "googlevideo.com",
        // Discord
        "discord.com", "discord.gg", "discordapp.com", "discordapp.net",
        // Telegram
        "telegram.org", "telegram.me", "t.me",
        // WhatsApp (owned by Meta)
        "whatsapp.com", "whatsapp.net",
        // Tumblr
        "tumblr.com",
        // LinkedIn
        "linkedin.com", "licdn.com",
        // BeReal
        "bereal.com", "bfrnd.link",
        // Threads (Meta)
        "threads.net"
    ) // End of socialMediaDomains Set

    // Common adult domains to always block
    private val defaultBlockedAdultDomains = setOf(
        "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com",
        "youporn.com", "tube8.com", "spankbang.com", "eporner.com", "txxx.com",
        "hqporner.com", "beeg.com", "drtuber.com", "sunporno.com", "tnaflix.com",
        "porntrex.com", "4tube.com", "ixxx.com", "porn.com", "xxxbunker.com"
    )

    // DNS-over-HTTPS servers to block (forces browsers to use regular DNS which we intercept)
    private val dohServers = setOf(
        "dns.google", "dns.google.com",
        "cloudflare-dns.com", "one.one.one.one",
        "dns.quad9.net", "dns.nextdns.io",
        "doh.opendns.com", "dns.adguard.com",
        "doh.cleanbrowsing.org", "dns.mozilla.org"
    )

    // Essential domains that should NEVER be blocked
    // These are required for basic Android/app functionality
    private val essentialDomains = setOf(
        // Google services/CDN (required for many apps)
        "googleusercontent.com", "googleapis.com", "gstatic.com",
        "google.com", "google-analytics.com", "googleadservices.com",
        "googlesyndication.com", "googletagmanager.com", "googletagservices.com",
        "gvt1.com", "gvt2.com", "gvt3.com", // Google update servers
        "android.com", "android.clients.google.com",
        "play.google.com", "play-fe.googleapis.com",
        // Firebase (required for our app)
        "firebase.google.com", "firebaseio.com", "firebase.googleapis.com",
        "firebasestorage.googleapis.com", "fcm.googleapis.com",
        // Android system
        "android.googleapis.com", "mtalk.google.com",
        "connectivitycheck.gstatic.com", "connectivitycheck.android.com",
        // Apple (for cross-platform apps)
        "apple.com", "icloud.com", "apple-cloudkit.com",
        // Microsoft (for apps)
        "microsoft.com", "microsoftonline.com", "azure.com", "live.com",
        // Amazon (for AWS-based apps)
        "amazonaws.com", "cloudfront.net",
        // Common CDNs
        "akamaihd.net", "akamai.net", "cloudflare.com", "fastly.net",
        // Crash reporting / analytics (many apps need these)
        "crashlytics.com", "sentry.io", "bugsnag.com",
        // App stores
        "apkpure.com", "apkmirror.com"
    )

    // Real DNS servers to forward allowed queries
    private val realDnsServers = listOf("8.8.8.8", "8.8.4.4", "1.1.1.1")

    // Periodic content filter refresh job
    private var refreshJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Timber.d("========== VPN SERVICE CREATED ==========")
        Timber.d("ContentFilterVpnService created")
        // Initialize service scope with a new SupervisorJob
        serviceJob = SupervisorJob()
        _serviceScope = CoroutineScope(Dispatchers.IO + serviceJob!!)
        createNotificationChannel()
    }

    /**
     * Start periodic refresh of content filter settings
     * This ensures the VPN picks up changes made by the parent without restarting
     */
    private fun startPeriodicRefresh() {
        refreshJob?.cancel()
        refreshJob = serviceScope.launch {
            while (isActive) {
                delay(90 * 1000L) // Refresh every 90s (ISSUE-023: cut filter propagation latency; no push yet)
                Timber.d("Refreshing content filter settings...")
                syncBlacklistFromBackend()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getLocalizedString(R.string.notif_vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getLocalizedString(R.string.notif_vpn_channel_desc)
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getLocalizedString(R.string.notif_vpn_title))
            .setContentText(getLocalizedString(R.string.notif_vpn_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setGroup("safeguard_vpn")
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setStyle(NotificationCompat.BigTextStyle().bigText(getLocalizedString(R.string.notif_vpn_bigtext)))
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("========== VPN SERVICE onStartCommand ==========")
        Timber.d("Intent action: ${intent?.action}")
        Timber.d("ContentFilterVpnService onStartCommand")

        if (intent?.action == ACTION_STOP) {
            Timber.d("Stopping VPN service")
            stopVpn()
            return START_NOT_STICKY
        }

        // Start as foreground service
        try {
            Timber.d("Starting as foreground service...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            Timber.d("Foreground service started successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start foreground service")
            return START_NOT_STICKY
        }

        // Consent gate: content filtering must not run until the parent has accepted
        // the in-app monitoring disclosure (Play Prominent Disclosure & Consent).
        // startForeground above satisfies the startForegroundService contract first.
        if (!preferencesManager.shouldRunMonitoring) {
            Timber.w("VPN service start blocked: monitoring consent not granted")
            stopSelf()
            return START_NOT_STICKY
        }

        // Sync blacklist from backend
        syncBlacklistFromBackend()

        // Start VPN
        startVpn()

        return START_STICKY
    }

    private fun syncBlacklistFromBackend() {
        val deviceId = preferencesManager.deviceDbId
        if (deviceId == -1) {
            Timber.w("Device not registered, using default blocklist")
            return
        }

        serviceScope.launch {
            try {
                when (val result = contentFilterRepository.getContentFilter(deviceId)) {
                    is NetworkResult.Success -> {
                        val filter = result.data
                        blockedDomains.clear()
                        // Limit blocked domains to prevent memory bloat
                        filter.blockedDomains?.take(MAX_BLOCKED_DOMAINS)?.let { blockedDomains.addAll(it) }
                        blockAdult = filter.blockAdult
                        blockViolence = filter.blockViolence
                        blockGambling = filter.blockGambling
                        blockDrugs = filter.blockDrugs
                        blockSocialMedia = filter.blockSocialMedia
                        Timber.d("Content filter synced: blockAdult=$blockAdult, blockSocialMedia=$blockSocialMedia, " +
                                "blockGambling=$blockGambling, blockedDomains=${blockedDomains.size}")
                        Timber.d("Blacklist synced: ${blockedDomains.size} custom domains, blockSocialMedia=$blockSocialMedia")
                    }
                    is NetworkResult.Error -> {
                        Timber.w("Failed to sync blacklist: ${result.message}")
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                Timber.e(e, "Error syncing blacklist")
            }
        }
    }

    private fun startVpn() {
        Timber.d("========== startVpn() called ==========")

        // Reset shutdown flag
        isShuttingDown = false

        if (vpnInterface != null) {
            Timber.d("VPN already running, skipping")
            Timber.d("VPN already running")
            return
        }

        try {
            Timber.d("Building VPN interface...")

            // Build VPN interface
            // DNS-only filtering VPN: We intercept DNS traffic to common DNS servers
            // HTTP/HTTPS traffic bypasses the VPN entirely (only DNS server IPs are routed)
            //
            // KEY INSIGHT: We do NOT set a fake DNS server. Instead, we:
            // 1. Route traffic to real DNS servers through the VPN
            // 2. Intercept those DNS queries
            // 3. Block or forward them
            // 4. Return responses that appear to come from the queried DNS server
            //
            // This way, responses have correct source IPs and Android accepts them.
            val builder = Builder()
                .addAddress("10.0.0.2", 32)
                // IPv6 address to capture IPv6 DNS traffic
                .addAddress("fd00::2", 128)

                // Route IPv4 DNS server IPs through VPN
                // Google DNS
                .addRoute("8.8.8.8", 32)
                .addRoute("8.8.4.4", 32)
                // Cloudflare DNS
                .addRoute("1.1.1.1", 32)
                .addRoute("1.0.0.1", 32)
                // Quad9 DNS
                .addRoute("9.9.9.9", 32)
                .addRoute("149.112.112.112", 32)
                // OpenDNS
                .addRoute("208.67.222.222", 32)
                .addRoute("208.67.220.220", 32)
                // Comodo Secure DNS
                .addRoute("8.26.56.26", 32)
                .addRoute("8.20.247.20", 32)
                // Level3 DNS
                .addRoute("4.2.2.1", 32)
                .addRoute("4.2.2.2", 32)
                // Verisign DNS
                .addRoute("64.6.64.6", 32)
                .addRoute("64.6.65.6", 32)
                // AdGuard DNS
                .addRoute("94.140.14.14", 32)
                .addRoute("94.140.15.15", 32)
                // CleanBrowsing DNS
                .addRoute("185.228.168.9", 32)
                .addRoute("185.228.169.9", 32)

                // Route IPv6 DNS server IPs through VPN
                // Google IPv6 DNS
                .addRoute("2001:4860:4860::8888", 128)
                .addRoute("2001:4860:4860::8844", 128)
                // Cloudflare IPv6 DNS
                .addRoute("2606:4700:4700::1111", 128)
                .addRoute("2606:4700:4700::1001", 128)
                // Quad9 IPv6 DNS
                .addRoute("2620:fe::fe", 128)
                .addRoute("2620:fe::9", 128)
                // OpenDNS IPv6 DNS
                .addRoute("2620:119:35::35", 128)
                .addRoute("2620:119:53::53", 128)

                // Route DNS-over-TLS port 853 servers (to block Private DNS bypass)
                // We'll intercept and block these connections

                // IMPORTANT: We MUST set addDnsServer() to force Android to use DNS servers
                // that we're routing through the VPN. Otherwise, Android uses the default
                // network's DNS (router/carrier) which bypasses our VPN entirely.
                // We use real DNS server IPs (not fake ones) so responses have correct source IPs.
                .addDnsServer("8.8.8.8")       // Primary: Google DNS (also routed above)
                .addDnsServer("1.1.1.1")       // Secondary: Cloudflare DNS (also routed above)

                .setSession("Haris VPN")
                .setMtu(1400) // Reduced MTU for better compatibility with mobile networks
                .setBlocking(true)
                .setConfigureIntent(createConfigIntent())

            Timber.d("VPN builder configured: IPv4=10.0.0.2, IPv6=fd00::2, DNS=8.8.8.8+1.1.1.1, routes for IPv4+IPv6 DNS servers, MTU=1400")

            // Allow bypass for our own app to prevent loops
            try {
                builder.addDisallowedApplication(packageName)
                Timber.d("Excluded package: $packageName")
            } catch (e: Exception) {
                Timber.w("Could not disallow own package: ${e.message}")
                Timber.w(e, "Could not disallow own package")
            }

            Timber.d("Calling builder.establish()...")
            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Timber.e("FAILED to establish VPN interface - establish() returned null!")
                Timber.e("This usually means VPN permission was not granted")
                Timber.e("Failed to establish VPN interface")

                // Notify UI that VPN failed to start
                broadcastVpnState(false)

                // Update preference to reflect actual state
                preferencesManager.isContentFilteringEnabled = false

                // Stop foreground service since VPN isn't actually running
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            Timber.d("SUCCESS! VPN interface established, fd=${vpnInterface?.fd}")
            Timber.d("VPN interface established successfully")
            isActive = true

            // Notify UI that VPN started successfully
            broadcastVpnState(true)

            // Start periodic content filter refresh
            startPeriodicRefresh()

            // Start packet filtering
            startFiltering()

        } catch (e: Exception) {
            Timber.e(e, "Error starting VPN")
            Timber.e(e, "Error starting VPN")
        }
    }

    private fun stopVpn() {
        Timber.d("stopVpn() called")
        isActive = false

        // Set shutdown flag to prevent new DNS forwarding coroutines
        isShuttingDown = true

        // Cancel DNS forwarding coroutines first
        dnsForwardingScope?.coroutineContext?.get(Job)?.cancel()
        dnsForwardingScope = null

        // Cancel the filtering job (this will also trigger its finally block)
        filteringJob?.cancel()
        filteringJob = null

        refreshJob?.cancel()
        refreshJob = null

        // Small delay to allow coroutines to cancel before closing interface
        Thread.sleep(150)

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Timber.e(e, "Error closing VPN interface")
        }
        vpnInterface = null

        Timber.d("VPN stopped")
        stopSelf()
    }

    // Scope for DNS forwarding coroutines - will be cancelled when filtering stops
    private var dnsForwardingScope: CoroutineScope? = null

    /**
     * Main packet filtering loop
     * Intercepts all packets and handles DNS queries specially
     * DNS forwarding is done asynchronously to avoid blocking the packet loop
     */
    private fun startFiltering() {
        Timber.d("========== startFiltering() called ==========")

        val vpnFd = vpnInterface?.fileDescriptor
        if (vpnFd == null) {
            Timber.e("Cannot start filtering - VPN file descriptor is null!")
            return
        }

        Timber.d("Got VPN file descriptor, launching filtering coroutine...")

        filteringJob = serviceScope.launch {
            var inputStream: FileInputStream? = null
            var outputStream: FileOutputStream? = null
            val buffer = ByteArray(32767)
            var packetCount = 0

            // Create a child scope for DNS forwarding that will be cancelled with this job
            val dnsJob = SupervisorJob(coroutineContext[Job])
            dnsForwardingScope = CoroutineScope(Dispatchers.IO + dnsJob)

            try {
                inputStream = FileInputStream(vpnFd)
                outputStream = FileOutputStream(vpnFd)

                Timber.d("Streams created, entering packet filtering loop")
                Timber.d("Starting packet filtering loop")

                while (isActive) {
                    val length = inputStream.read(buffer)
                    if (length > 0) {
                        packetCount++
                        if (packetCount <= 10 || packetCount % 100 == 0) {
                            Timber.d("Received packet #$packetCount, length=$length bytes")
                        }
                        try {
                            // IMPORTANT: Copy the packet data since buffer is reused
                            val packetCopy = buffer.copyOf(length)
                            // Handle packet without blocking the main loop
                            handlePacketAsync(packetCopy, length, outputStream)
                        } catch (e: Exception) {
                            Timber.w("Error handling packet: ${e.message}")
                            Timber.w(e, "Error handling packet")
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Timber.e(e, "Filtering loop error")
                    Timber.e(e, "Filtering loop error")
                }
            } finally {
                Timber.d("Filtering loop ending, total packets processed: $packetCount")
                // Cancel all DNS forwarding coroutines BEFORE closing streams
                dnsJob.cancel()
                dnsForwardingScope = null
                // Give coroutines a moment to cancel
                delay(100)
                inputStream?.closeQuietly()
                outputStream?.closeQuietly()
                Timber.d("Filtering loop ended")
            }
        }

        Timber.d("Filtering coroutine launched")
        Timber.d("Filtering started")
    }

    /**
     * Write data to VPN output stream with synchronization
     * Includes debug logging for packet inspection
     */
    private fun writeToVpn(outputStream: FileOutputStream, data: ByteArray, length: Int = data.size) {
        synchronized(outputLock) {
            try {
                // Debug logging for DNS responses
                if (length >= 28) { // Minimum IP + UDP header
                    val protocol = data[9].toInt() and 0xFF
                    if (protocol == 17) { // UDP
                        val ihl = (data[0].toInt() and 0xF) * 4
                        if (length >= ihl + 4) {
                            val srcPort = ((data[ihl].toInt() and 0xFF) shl 8) or (data[ihl + 1].toInt() and 0xFF)
                            val dstPort = ((data[ihl + 2].toInt() and 0xFF) shl 8) or (data[ihl + 3].toInt() and 0xFF)

                            // If this is a DNS response (source port 53)
                            if (srcPort == 53) {
                                logDnsResponsePacket(data, length, ihl)
                            }
                        }
                    }
                }

                outputStream.write(data, 0, length)
            } catch (e: Exception) {
                Timber.w("Error writing to VPN: ${e.message}")
            }
        }
    }

    /**
     * Log detailed information about a DNS response packet for debugging
     */
    private fun logDnsResponsePacket(packet: ByteArray, length: Int, ihl: Int) {
        try {
            Timber.d("=== DNS RESPONSE PACKET DEBUG ===")
            Timber.d("Total packet length: $length bytes")

            // IP Header info
            val version = (packet[0].toInt() shr 4) and 0xF
            val totalLength = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
            val ttl = packet[8].toInt() and 0xFF
            val srcIp = "${packet[12].toInt() and 0xFF}.${packet[13].toInt() and 0xFF}.${packet[14].toInt() and 0xFF}.${packet[15].toInt() and 0xFF}"
            val dstIp = "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}.${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"
            val ipChecksum = ((packet[10].toInt() and 0xFF) shl 8) or (packet[11].toInt() and 0xFF)

            Timber.d("IP: ver=$version, ihl=$ihl, totalLen=$totalLength, ttl=$ttl")
            Timber.d("IP: src=$srcIp -> dst=$dstIp")
            Timber.d("IP: checksum=0x${ipChecksum.toString(16).padStart(4, '0')}")

            // Verify IP checksum by summing all header bytes including checksum
            // If valid, result should be 0xFFFF (or one's complement = 0x0000)
            val verifyResult = verifyIpChecksum(packet, ihl)
            Timber.d("IP: checksum valid=$verifyResult")

            // UDP Header info
            val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
            val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
            val udpLength = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)
            val udpChecksum = ((packet[ihl + 6].toInt() and 0xFF) shl 8) or (packet[ihl + 7].toInt() and 0xFF)

            Timber.d("UDP: srcPort=$srcPort, dstPort=$dstPort, len=$udpLength, checksum=0x${udpChecksum.toString(16).padStart(4, '0')}")

            // DNS Header info (if present)
            if (length >= ihl + 8 + 12) {
                val dnsOffset = ihl + 8
                val transactionId = ((packet[dnsOffset].toInt() and 0xFF) shl 8) or (packet[dnsOffset + 1].toInt() and 0xFF)
                val flags = ((packet[dnsOffset + 2].toInt() and 0xFF) shl 8) or (packet[dnsOffset + 3].toInt() and 0xFF)
                val qdCount = ((packet[dnsOffset + 4].toInt() and 0xFF) shl 8) or (packet[dnsOffset + 5].toInt() and 0xFF)
                val anCount = ((packet[dnsOffset + 6].toInt() and 0xFF) shl 8) or (packet[dnsOffset + 7].toInt() and 0xFF)
                val nsCount = ((packet[dnsOffset + 8].toInt() and 0xFF) shl 8) or (packet[dnsOffset + 9].toInt() and 0xFF)
                val arCount = ((packet[dnsOffset + 10].toInt() and 0xFF) shl 8) or (packet[dnsOffset + 11].toInt() and 0xFF)

                val qr = (flags shr 15) and 0x1
                val opcode = (flags shr 11) and 0xF
                val aa = (flags shr 10) and 0x1
                val tc = (flags shr 9) and 0x1
                val rd = (flags shr 8) and 0x1
                val ra = (flags shr 7) and 0x1
                val rcode = flags and 0xF

                Timber.d("DNS: txId=0x${transactionId.toString(16).padStart(4, '0')}")
                Timber.d("DNS: flags=0x${flags.toString(16).padStart(4, '0')} (QR=$qr, Opcode=$opcode, AA=$aa, TC=$tc, RD=$rd, RA=$ra, RCODE=$rcode)")
                Timber.d("DNS: qdCount=$qdCount, anCount=$anCount, nsCount=$nsCount, arCount=$arCount")

                // Extract domain from question section
                if (qdCount > 0 && length > dnsOffset + 12) {
                    val domain = extractDomainFromDnsAtOffset(packet, dnsOffset + 12)
                    Timber.d("DNS: domain=$domain")
                }

                // Log first few bytes of DNS payload for debugging
                val dnsPayloadLen = minOf(32, length - dnsOffset)
                val dnsBytes = packet.slice(dnsOffset until dnsOffset + dnsPayloadLen).joinToString(" ") {
                    String.format("%02X", it.toInt() and 0xFF)
                }
                Timber.d("DNS: first bytes: $dnsBytes")
            }

            Timber.d("=== END DNS RESPONSE DEBUG ===")
        } catch (e: Exception) {
            Timber.w("Error in DNS debug logging: ${e.message}")
        }
    }

    /**
     * Verify IP checksum by summing all header bytes including the checksum field
     * If checksum is valid, the sum (after folding) should be 0xFFFF
     */
    private fun verifyIpChecksum(packet: ByteArray, ipHeaderLength: Int): Boolean {
        var sum = 0L
        var i = 0
        while (i < ipHeaderLength) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        // Fold carries
        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        // Valid checksum produces 0xFFFF
        return sum == 0xFFFFL
    }

    /**
     * Extract domain from DNS packet at given offset
     */
    private fun extractDomainFromDnsAtOffset(packet: ByteArray, offset: Int): String {
        val domain = StringBuilder()
        var pos = offset
        while (pos < packet.size) {
            val labelLen = packet[pos].toInt() and 0xFF
            if (labelLen == 0) break
            if (labelLen > 63) break // Compression pointer
            if (domain.isNotEmpty()) domain.append(".")
            pos++
            for (j in 0 until labelLen) {
                if (pos + j < packet.size) {
                    domain.append((packet[pos + j].toInt() and 0xFF).toChar())
                }
            }
            pos += labelLen
        }
        return domain.toString()
    }

    /**
     * Handle an incoming IP packet asynchronously
     * Non-DNS packets are forwarded immediately
     * DNS packets are processed in a separate coroutine to avoid blocking
     */
    private fun handlePacketAsync(buffer: ByteArray, length: Int, outputStream: FileOutputStream) {
        if (length < 20) return // Too short for IP header

        val byteBuffer = ByteBuffer.wrap(buffer, 0, length)
        byteBuffer.order(ByteOrder.BIG_ENDIAN)

        // Parse IP header
        val versionAndIhl = byteBuffer.get().toInt() and 0xFF
        val version = (versionAndIhl shr 4) and 0xF

        if (version == 6) {
            // Handle IPv6 DNS traffic
            handleIpv6Packet(buffer, length, outputStream)
            return
        } else if (version != 4) {
            // Unknown IP version - drop packet
            Timber.w("Dropping packet with unknown IP version: $version")
            return
        }

        val ihl = (versionAndIhl and 0xF) * 4
        if (length < ihl) return

        // Skip to protocol field
        byteBuffer.position(9)
        val protocol = byteBuffer.get().toInt() and 0xFF

        // Get destination IP
        byteBuffer.position(16)
        val destIpBytes = ByteArray(4)
        byteBuffer.get(destIpBytes)
        val destIp = InetAddress.getByAddress(destIpBytes)

        // Check if this is TCP (protocol 6) - block DNS-over-TLS (port 853)
        if (protocol == 6 && length >= ihl + 20) {
            byteBuffer.position(ihl)
            val tcpSrcPort = byteBuffer.short.toInt() and 0xFFFF
            val tcpDstPort = byteBuffer.short.toInt() and 0xFFFF

            // Block DNS-over-TLS (port 853) to prevent Private DNS bypass
            if (tcpDstPort == 853) {
                val dstIpStr = "${buffer[16].toInt() and 0xFF}.${buffer[17].toInt() and 0xFF}.${buffer[18].toInt() and 0xFF}.${buffer[19].toInt() and 0xFF}"
                Timber.w(">>> BLOCKING DNS-over-TLS connection to $dstIpStr:853 (Private DNS bypass prevention)")
                // Drop the packet - don't respond, connection will timeout
                return
            }

            // Any other TCP traffic to DNS servers is unexpected - drop it
            Timber.d("Dropping unexpected TCP packet to DNS server (port $tcpDstPort)")
            return
        }

        // Check if this is UDP (protocol 17)
        if (protocol == 17 && length >= ihl + 8) {
            byteBuffer.position(ihl)
            val srcPort = byteBuffer.short.toInt() and 0xFFFF
            val dstPort = byteBuffer.short.toInt() and 0xFFFF
            val udpLength = byteBuffer.short.toInt() and 0xFFFF

            // DNS query (destination port 53)
            if (dstPort == 53 && length >= ihl + 8 + 12) {
                val dnsData = ByteArray(udpLength - 8)
                byteBuffer.position(ihl + 8)
                if (byteBuffer.remaining() >= dnsData.size) {
                    byteBuffer.get(dnsData)

                    // Extract transaction ID for debugging
                    val txId = ((dnsData[0].toInt() and 0xFF) shl 8) or (dnsData[1].toInt() and 0xFF)

                    val domain = extractDomainFromDns(dnsData)
                    if (domain != null) {
                        // Log original query info with clear indication of which DNS server was queried
                        val srcIpStr = "${buffer[12].toInt() and 0xFF}.${buffer[13].toInt() and 0xFF}.${buffer[14].toInt() and 0xFF}.${buffer[15].toInt() and 0xFF}"
                        val dstIpStr = "${buffer[16].toInt() and 0xFF}.${buffer[17].toInt() and 0xFF}.${buffer[18].toInt() and 0xFF}.${buffer[19].toInt() and 0xFF}"
                        val dnsServerType = when (dstIpStr) {
                            "10.0.0.1" -> "OUR_DNS"
                            "8.8.8.8", "8.8.4.4" -> "GOOGLE_DNS"
                            "1.1.1.1", "1.0.0.1" -> "CLOUDFLARE_DNS"
                            "9.9.9.9", "149.112.112.112" -> "QUAD9_DNS"
                            else -> "OTHER_DNS($dstIpStr)"
                        }
                        Timber.d(">>> DNS QUERY [$dnsServerType]: $domain (txId=0x${txId.toString(16).padStart(4, '0')}, src=$srcIpStr:$srcPort -> dst=$dstIpStr:$dstPort)")
                        Timber.d("DNS query for: $domain")

                        if (shouldBlockDomain(domain)) {
                            Timber.w(">>> BLOCKING: $domain (txId=0x${txId.toString(16).padStart(4, '0')})")
                            Timber.d("BLOCKING DNS for: $domain")

                            // Send blocked DNS response (synchronous - fast operation)
                            val blockedResponse = createBlockedDnsResponse(buffer, length, ihl, dnsData)
                            if (blockedResponse != null) {
                                Timber.d(">>> Writing BLOCKED response to VPN (${blockedResponse.size} bytes)")
                                writeToVpn(outputStream, blockedResponse)
                                Timber.d(">>> BLOCKED response written successfully")
                            } else {
                                Timber.e(">>> FAILED to create blocked DNS response!")
                            }

                            // Send alert for content blocks (not for DoH server blocks)
                            sendBlockAlert(domain)
                            return
                        }
                    }
                }

                // Forward DNS query to real DNS server ASYNCHRONOUSLY
                // This is critical - don't block the main loop waiting for DNS response
                // Use dnsForwardingScope so coroutines are cancelled when filtering stops
                if (!isShuttingDown) {
                    dnsForwardingScope?.launch {
                        forwardDnsQueryAsync(buffer, length, ihl, outputStream)
                    } ?: Timber.w("DNS forwarding scope not available, dropping query")
                }
                return
            }
        }

        // Drop all other packets - they shouldn't reach here since we only route DNS server IPs
        // Non-DNS traffic to DNS servers is unexpected
        Timber.d("Dropping unexpected packet: protocol=$protocol")
    }

    /**
     * Handle IPv6 packets - specifically DNS queries over IPv6
     * IPv6 Header: 40 bytes fixed
     * - Version (4 bits) = 6
     * - Traffic Class (8 bits)
     * - Flow Label (20 bits)
     * - Payload Length (16 bits)
     * - Next Header (8 bits) = protocol (17 for UDP, 6 for TCP)
     * - Hop Limit (8 bits)
     * - Source Address (128 bits / 16 bytes)
     * - Destination Address (128 bits / 16 bytes)
     */
    private fun handleIpv6Packet(buffer: ByteArray, length: Int, outputStream: FileOutputStream) {
        if (length < 40) {
            Timber.w("IPv6 packet too short: $length bytes")
            return
        }

        val byteBuffer = ByteBuffer.wrap(buffer, 0, length)
        byteBuffer.order(ByteOrder.BIG_ENDIAN)

        // Skip first 4 bytes (version/class/flow label)
        byteBuffer.position(4)

        val payloadLength = byteBuffer.short.toInt() and 0xFFFF
        val nextHeader = byteBuffer.get().toInt() and 0xFF // Protocol
        val hopLimit = byteBuffer.get().toInt() and 0xFF

        // Source address (16 bytes) at offset 8
        // Destination address (16 bytes) at offset 24

        val ipv6HeaderLength = 40

        // Check for TCP (block DNS-over-TLS on port 853)
        if (nextHeader == 6 && length >= ipv6HeaderLength + 20) {
            byteBuffer.position(ipv6HeaderLength)
            val tcpSrcPort = byteBuffer.short.toInt() and 0xFFFF
            val tcpDstPort = byteBuffer.short.toInt() and 0xFFFF

            if (tcpDstPort == 853) {
                Timber.w(">>> BLOCKING IPv6 DNS-over-TLS connection on port 853")
                return
            }

            Timber.d("Dropping unexpected IPv6 TCP packet (port $tcpDstPort)")
            return
        }

        // Check for UDP DNS (port 53)
        if (nextHeader == 17 && length >= ipv6HeaderLength + 8) {
            byteBuffer.position(ipv6HeaderLength)
            val srcPort = byteBuffer.short.toInt() and 0xFFFF
            val dstPort = byteBuffer.short.toInt() and 0xFFFF
            val udpLength = byteBuffer.short.toInt() and 0xFFFF

            if (dstPort == 53 && length >= ipv6HeaderLength + 8 + 12) {
                val dnsDataLength = udpLength - 8
                if (dnsDataLength <= 0 || ipv6HeaderLength + 8 + dnsDataLength > length) {
                    Timber.w("Invalid IPv6 DNS packet length")
                    return
                }

                val dnsData = ByteArray(dnsDataLength)
                byteBuffer.position(ipv6HeaderLength + 8)
                if (byteBuffer.remaining() >= dnsData.size) {
                    byteBuffer.get(dnsData)

                    val domain = extractDomainFromDns(dnsData)
                    if (domain != null) {
                        Timber.d(">>> IPv6 DNS QUERY: $domain")

                        if (shouldBlockDomain(domain)) {
                            Timber.w(">>> BLOCKING IPv6 DNS: $domain")
                            val blockedResponse = createBlockedIpv6DnsResponse(buffer, length, dnsData)
                            if (blockedResponse != null) {
                                writeToVpn(outputStream, blockedResponse)
                            }
                            // Send alert for content blocks (not for DoH server blocks)
                            sendBlockAlert(domain)
                            return
                        }

                        // Forward IPv6 DNS query to real DNS server
                        // Use dnsForwardingScope so coroutines are cancelled when filtering stops
                        if (!isShuttingDown) {
                            dnsForwardingScope?.launch {
                                forwardIpv6DnsQueryAsync(buffer, length, outputStream)
                            } ?: Timber.w("DNS forwarding scope not available, dropping IPv6 query")
                        }
                        return
                    }
                }
            }
        }

        // Drop other IPv6 packets
        Timber.d("Dropping unexpected IPv6 packet: nextHeader=$nextHeader")
    }

    /**
     * Create a blocked DNS response for IPv6
     */
    private fun createBlockedIpv6DnsResponse(
        originalPacket: ByteArray,
        originalLength: Int,
        dnsQuery: ByteArray
    ): ByteArray? {
        try {
            val ipv6HeaderLength = 40

            // Find the end of the question section
            val questionEnd = findQuestionEnd(dnsQuery)
            if (questionEnd < 0) return null

            // Extract QTYPE from the question section
            val qtypeOffset = questionEnd - 4
            val qtype = ((dnsQuery[qtypeOffset].toInt() and 0xFF) shl 8) or (dnsQuery[qtypeOffset + 1].toInt() and 0xFF)

            // Determine response data based on query type
            val isAAAA = (qtype == 28)
            val rdataLength = if (isAAAA) 16 else 4

            // DNS Response: Header + Question + Answer
            val answerSize = 2 + 2 + 2 + 4 + 2 + rdataLength
            val dnsResponseSize = questionEnd + answerSize
            val dnsResponse = ByteArray(dnsResponseSize)

            // Copy header and question from query
            System.arraycopy(dnsQuery, 0, dnsResponse, 0, questionEnd)

            // Set response flags
            dnsResponse[2] = 0x81.toByte()
            dnsResponse[3] = 0x80.toByte()

            // Set answer count to 1
            dnsResponse[6] = 0
            dnsResponse[7] = 1

            // Answer section
            var offset = questionEnd
            dnsResponse[offset++] = 0xC0.toByte() // Pointer to name
            dnsResponse[offset++] = 0x0C.toByte()
            dnsResponse[offset++] = ((qtype shr 8) and 0xFF).toByte() // Type: match query
            dnsResponse[offset++] = (qtype and 0xFF).toByte()
            dnsResponse[offset++] = 0 // Class IN
            dnsResponse[offset++] = 1
            dnsResponse[offset++] = 0 // TTL
            dnsResponse[offset++] = 0
            dnsResponse[offset++] = 0
            dnsResponse[offset++] = 60
            dnsResponse[offset++] = ((rdataLength shr 8) and 0xFF).toByte() // RDLength
            dnsResponse[offset++] = (rdataLength and 0xFF).toByte()
            // RData: 0.0.0.0 or :: (all zeros)
            for (i in 0 until rdataLength) {
                dnsResponse[offset++] = 0
            }

            // Build IPv6 response packet
            val responseLength = ipv6HeaderLength + 8 + dnsResponse.size
            val responsePacket = ByteArray(responseLength)

            // Copy IPv6 header
            System.arraycopy(originalPacket, 0, responsePacket, 0, ipv6HeaderLength)

            // Swap source and destination addresses (each 16 bytes)
            System.arraycopy(originalPacket, 8, responsePacket, 24, 16)  // src -> dst
            System.arraycopy(originalPacket, 24, responsePacket, 8, 16)  // dst -> src

            // Update payload length
            val newPayloadLength = 8 + dnsResponse.size
            responsePacket[4] = ((newPayloadLength shr 8) and 0xFF).toByte()
            responsePacket[5] = (newPayloadLength and 0xFF).toByte()

            // UDP header - swap ports
            val udpOffset = ipv6HeaderLength
            System.arraycopy(originalPacket, udpOffset + 2, responsePacket, udpOffset, 2)
            System.arraycopy(originalPacket, udpOffset, responsePacket, udpOffset + 2, 2)

            // UDP length
            val udpLen = 8 + dnsResponse.size
            responsePacket[udpOffset + 4] = ((udpLen shr 8) and 0xFF).toByte()
            responsePacket[udpOffset + 5] = (udpLen and 0xFF).toByte()
            responsePacket[udpOffset + 6] = 0 // Checksum (optional in IPv6)
            responsePacket[udpOffset + 7] = 0

            // DNS response
            System.arraycopy(dnsResponse, 0, responsePacket, udpOffset + 8, dnsResponse.size)

            Timber.d("IPv6 BLOCKED DNS response created: ${responsePacket.size} bytes")
            return responsePacket
        } catch (e: Exception) {
            Timber.e(e, "Error creating IPv6 blocked DNS response")
            return null
        }
    }

    /**
     * Forward IPv6 DNS query to real DNS server
     */
    private fun forwardIpv6DnsQueryAsync(
        originalPacket: ByteArray,
        originalLength: Int,
        outputStream: FileOutputStream
    ) {
        try {
            val ipv6HeaderLength = 40
            val udpOffset = ipv6HeaderLength

            val udpLength = ((originalPacket[udpOffset + 4].toInt() and 0xFF) shl 8) or
                    (originalPacket[udpOffset + 5].toInt() and 0xFF)
            val dnsDataLength = udpLength - 8

            if (dnsDataLength <= 0) return

            val dnsQuery = ByteArray(dnsDataLength)
            System.arraycopy(originalPacket, udpOffset + 8, dnsQuery, 0, dnsDataLength)

            val domain = extractDomainFromDns(dnsQuery) ?: "unknown"

            // Forward to real DNS using IPv4 (simpler and more reliable)
            var dnsResponse: ByteArray? = null
            for (dnsServer in realDnsServers) {
                var channel: DatagramChannel? = null
                try {
                    channel = DatagramChannel.open()
                    channel.configureBlocking(true)
                    val socket = channel.socket()
                    if (!protect(socket)) continue

                    socket.bind(null)
                    socket.soTimeout = 3000

                    channel.connect(InetSocketAddress(dnsServer, 53))
                    channel.write(ByteBuffer.wrap(dnsQuery))

                    val receiveBuffer = ByteBuffer.allocate(1024)
                    val bytesRead = channel.read(receiveBuffer)

                    if (bytesRead > 0) {
                        receiveBuffer.flip()
                        dnsResponse = ByteArray(bytesRead)
                        receiveBuffer.get(dnsResponse)
                        Timber.d("IPv6 DNS forwarded via IPv4 to $dnsServer for $domain")
                        break
                    }
                } catch (e: Exception) {
                    Timber.w("DNS server $dnsServer failed for IPv6 query: ${e.message}")
                } finally {
                    try { channel?.close() } catch (_: Exception) {}
                }
            }

            if (dnsResponse != null) {
                // Build IPv6 response packet
                val responseLength = ipv6HeaderLength + 8 + dnsResponse.size
                val responsePacket = ByteArray(responseLength)

                // Copy IPv6 header
                System.arraycopy(originalPacket, 0, responsePacket, 0, ipv6HeaderLength)

                // Swap addresses
                System.arraycopy(originalPacket, 8, responsePacket, 24, 16)
                System.arraycopy(originalPacket, 24, responsePacket, 8, 16)

                // Update payload length
                val newPayloadLength = 8 + dnsResponse.size
                responsePacket[4] = ((newPayloadLength shr 8) and 0xFF).toByte()
                responsePacket[5] = (newPayloadLength and 0xFF).toByte()

                // UDP header - swap ports
                System.arraycopy(originalPacket, udpOffset + 2, responsePacket, udpOffset, 2)
                System.arraycopy(originalPacket, udpOffset, responsePacket, udpOffset + 2, 2)

                val newUdpLen = 8 + dnsResponse.size
                responsePacket[udpOffset + 4] = ((newUdpLen shr 8) and 0xFF).toByte()
                responsePacket[udpOffset + 5] = (newUdpLen and 0xFF).toByte()
                responsePacket[udpOffset + 6] = 0
                responsePacket[udpOffset + 7] = 0

                System.arraycopy(dnsResponse, 0, responsePacket, udpOffset + 8, dnsResponse.size)

                writeToVpn(outputStream, responsePacket)
                Timber.d(">>> IPv6 DNS FORWARDED response written")
            } else {
                Timber.e("All DNS servers failed for IPv6 query: $domain")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error forwarding IPv6 DNS query")
        }
    }

    /**
     * Extract domain name from DNS query
     */
    private fun extractDomainFromDns(dnsData: ByteArray): String? {
        if (dnsData.size < 12) return null

        try {
            val buffer = ByteBuffer.wrap(dnsData)
            buffer.order(ByteOrder.BIG_ENDIAN)

            // Skip header (12 bytes)
            buffer.position(12)

            // Read domain name (sequence of labels)
            val domain = StringBuilder()
            while (buffer.hasRemaining()) {
                val labelLength = buffer.get().toInt() and 0xFF
                if (labelLength == 0) break
                if (labelLength > 63) break // Compression pointer, stop

                if (domain.isNotEmpty()) domain.append(".")

                val label = ByteArray(labelLength)
                if (buffer.remaining() < labelLength) break
                buffer.get(label)
                domain.append(String(label, Charsets.US_ASCII))
            }

            return if (domain.isNotEmpty()) domain.toString() else null
        } catch (e: Exception) {
            Timber.w(e, "Error parsing DNS query")
            return null
        }
    }

    /**
     * Check if domain should be blocked
     */
    private fun shouldBlockDomain(domain: String): Boolean {
        val domainLower = domain.lowercase()

        // FIRST: Check essential domains whitelist - NEVER block these
        // This must be checked BEFORE any other blocking logic
        if (essentialDomains.any { domainLower.endsWith(it) || domainLower == it }) {
            // Exception: still block DOH servers even if on essential list
            if (!dohServers.any { domainLower.contains(it) }) {
                Timber.d("ALLOWING essential domain: $domain")
                return false
            }
        }

        // Block DNS-over-HTTPS servers to force browsers to use regular DNS
        // This ensures our DNS filtering works even with modern browsers
        if (dohServers.any { domainLower.contains(it) }) {
            Timber.d("Blocking DoH server: $domain")
            return true
        }

        // Check custom blocklist (explicit parent blocks override essentials except DOH)
        if (blockedDomains.any { blocked ->
                val blockedLower = blocked.lowercase()
                domainLower.endsWith(blockedLower) || domainLower == blockedLower
            }) {
            return true
        }

        // Check default adult domains (exact domain match)
        if (blockAdult && defaultBlockedAdultDomains.any { domainLower.endsWith(it) || domainLower == it }) {
            return true
        }

        // Check adult keywords (in domain parts, not substring)
        // This prevents "googleusercontent.com" from matching "adult"
        if (blockAdult) {
            val domainParts = domainLower.split(".")
            if (domainParts.any { part -> adultKeywords.contains(part) }) {
                return true
            }
        }

        // Check gambling keywords (in domain parts, not substring)
        if (blockGambling) {
            val domainParts = domainLower.split(".")
            if (domainParts.any { part -> gamblingKeywords.contains(part) }) {
                return true
            }
        }

        // Check social media - use blockedDomains for individual platform control
        // When blockSocialMedia is true AND there are blocked domains, use those specific domains
        // This allows individual platform control from the parent dashboard
        if (blockSocialMedia && blockedDomains.isNotEmpty()) {
            // Already checked above in blockedDomains check
            // No additional check needed since individual platforms are in blockedDomains
        } else if (blockSocialMedia) {
            // Fallback: if blockSocialMedia is true but no specific domains are set,
            // block all social media using the default list
            val matchedSocialMedia = socialMediaDomains.find { domainLower.endsWith(it) || domainLower == it }
            if (matchedSocialMedia != null) {
                Timber.d("BLOCKING social media domain (all): $domain (matched: $matchedSocialMedia)")
                return true
            }
        }

        return false
    }

    /**
     * Check if domain is a DNS-over-HTTPS server (infrastructure block, not content)
     * We don't send alerts for these as they're just to prevent filter bypass
     */
    private fun isDohServer(domain: String): Boolean {
        val domainLower = domain.lowercase()
        return dohServers.any { domainLower.contains(it) }
    }

    /**
     * Send alert to parent for blocked content (not for DoH server blocks or essential domains)
     */
    private fun sendBlockAlert(domain: String) {
        val domainLower = domain.lowercase()

        // Don't send alerts for DoH server blocks - those are infrastructure blocks
        if (isDohServer(domain)) {
            return
        }

        // Don't send alerts for essential domains (in case they were blocked erroneously)
        if (essentialDomains.any { domainLower.endsWith(it) || domainLower == it }) {
            Timber.w("Skipping alert for essential domain: $domain")
            return
        }

        serviceScope.launch {
            try {
                alertRepository.createContentBlockAlert(
                    domain = domain,
                    reason = "Blocked by content filter"
                )
            } catch (e: Exception) {
                Timber.e(e, "Error sending block alert")
            }
        }
    }

    /**
     * Calculate IP header checksum
     * The checksum is the 16-bit one's complement of the one's complement sum
     * of all 16-bit words in the header (with checksum field set to zero)
     * Returns the checksum as an Int (0-65535) to avoid sign issues
     */
    private fun calculateIpChecksum(packet: ByteArray, ipHeaderLength: Int): Int {
        var sum = 0L

        // Sum all 16-bit words in the IP header
        var i = 0
        while (i < ipHeaderLength) {
            // Skip the checksum field (bytes 10-11)
            if (i == 10) {
                i += 2
                continue
            }

            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }

        // Add carry bits (fold 32-bit sum into 16-bit)
        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        // One's complement - return as unsigned Int
        return (sum.inv() and 0xFFFF).toInt()
    }

    /**
     * Set IP header checksum in packet
     */
    private fun setIpChecksum(packet: ByteArray, ipHeaderLength: Int) {
        // Clear existing checksum
        packet[10] = 0
        packet[11] = 0

        // Calculate new checksum (returned as 0-65535)
        val checksum = calculateIpChecksum(packet, ipHeaderLength)

        // Set checksum (big-endian) - no sign issues since checksum is Int
        packet[10] = ((checksum shr 8) and 0xFF).toByte()
        packet[11] = (checksum and 0xFF).toByte()

        Timber.d("IP checksum set to 0x${checksum.toString(16).padStart(4, '0')}")
    }

    /**
     * Create a DNS response that blocks the domain (returns 0.0.0.0 for A, :: for AAAA)
     * Properly handles both IPv4 (A) and IPv6 (AAAA) queries
     */
    private fun createBlockedDnsResponse(
        originalPacket: ByteArray,
        originalLength: Int,
        ipHeaderLength: Int,
        dnsQuery: ByteArray
    ): ByteArray? {
        try {
            // Log the incoming query for debugging
            val queryTxId = ((dnsQuery[0].toInt() and 0xFF) shl 8) or (dnsQuery[1].toInt() and 0xFF)
            Timber.d("Creating BLOCKED DNS response for txId=0x${queryTxId.toString(16).padStart(4, '0')}")

            // Find the end of the question section
            val questionEnd = findQuestionEnd(dnsQuery)
            if (questionEnd < 0) {
                Timber.e("Failed to find question end in DNS query")
                return null
            }

            // Extract QTYPE from the question section (2 bytes before QCLASS, which is 2 bytes before questionEnd)
            // Question format: QNAME (variable) + QTYPE (2 bytes) + QCLASS (2 bytes)
            val qtypeOffset = questionEnd - 4
            val qtype = ((dnsQuery[qtypeOffset].toInt() and 0xFF) shl 8) or (dnsQuery[qtypeOffset + 1].toInt() and 0xFF)

            // Determine response data based on query type
            val isAAAA = (qtype == 28) // AAAA record type
            val rdataLength = if (isAAAA) 16 else 4 // IPv6 = 16 bytes, IPv4 = 4 bytes

            Timber.d("Query type: ${if (isAAAA) "AAAA (IPv6)" else "A (IPv4)"}, QTYPE=$qtype")

            // DNS Response structure:
            // Header (12 bytes) + Question Section + Answer Section
            // Answer: Name pointer (2) + Type (2) + Class IN (2) + TTL (4) + RDLength (2) + RData (4 or 16)
            val answerSize = 2 + 2 + 2 + 4 + 2 + rdataLength // 12 + rdataLength
            val dnsResponseSize = questionEnd + answerSize
            val dnsResponse = ByteArray(dnsResponseSize)

            // Copy header and question from query (preserves transaction ID!)
            System.arraycopy(dnsQuery, 0, dnsResponse, 0, questionEnd)

            // Set response flags
            dnsResponse[2] = 0x81.toByte() // QR=1 (response), Opcode=0, AA=0, TC=0, RD=1
            dnsResponse[3] = 0x80.toByte() // RA=1, Z=0, RCODE=0 (No error)

            // Set answer count to 1 (bytes 6-7)
            dnsResponse[6] = 0
            dnsResponse[7] = 1

            // Build answer section starting after question
            var offset = questionEnd

            // Name: compression pointer to offset 12 (0xC00C points to question name)
            dnsResponse[offset++] = 0xC0.toByte()
            dnsResponse[offset++] = 0x0C.toByte()

            // Type: A (1) or AAAA (28) - match the query type!
            dnsResponse[offset++] = ((qtype shr 8) and 0xFF).toByte()
            dnsResponse[offset++] = (qtype and 0xFF).toByte()

            // Class: IN (1)
            dnsResponse[offset++] = 0
            dnsResponse[offset++] = 1

            // TTL: 60 seconds (short TTL for blocked domains)
            dnsResponse[offset++] = 0
            dnsResponse[offset++] = 0
            dnsResponse[offset++] = 0
            dnsResponse[offset++] = 60

            // RDLength: 4 bytes (IPv4) or 16 bytes (IPv6)
            dnsResponse[offset++] = ((rdataLength shr 8) and 0xFF).toByte()
            dnsResponse[offset++] = (rdataLength and 0xFF).toByte()

            // RData: 0.0.0.0 (blocked IPv4) or :: (blocked IPv6)
            for (i in 0 until rdataLength) {
                dnsResponse[offset++] = 0
            }

            // Build complete IP packet
            val udpOffset = ipHeaderLength
            val responseLength = ipHeaderLength + 8 + dnsResponse.size
            val responsePacket = ByteArray(responseLength)

            // Copy IP header from original
            System.arraycopy(originalPacket, 0, responsePacket, 0, ipHeaderLength)

            // Swap source and destination IP addresses
            System.arraycopy(originalPacket, 12, responsePacket, 16, 4) // original src -> response dst
            System.arraycopy(originalPacket, 16, responsePacket, 12, 4) // original dst -> response src

            // Update IP total length field
            responsePacket[2] = ((responseLength shr 8) and 0xFF).toByte()
            responsePacket[3] = (responseLength and 0xFF).toByte()

            // Clear IP identification (use new ID for response)
            responsePacket[4] = 0
            responsePacket[5] = 0

            // Clear fragment flags and offset (for clean response)
            responsePacket[6] = 0x40.toByte() // Don't Fragment flag set, fragment offset = 0
            responsePacket[7] = 0

            // Set reasonable TTL for response
            responsePacket[8] = 64.toByte()

            // CRITICAL: Recalculate IP header checksum after modifications
            setIpChecksum(responsePacket, ipHeaderLength)

            // UDP header - swap source and destination ports
            System.arraycopy(originalPacket, udpOffset + 2, responsePacket, udpOffset, 2) // dst port -> src port
            System.arraycopy(originalPacket, udpOffset, responsePacket, udpOffset + 2, 2) // src port -> dst port

            // UDP length
            val udpLen = 8 + dnsResponse.size
            responsePacket[udpOffset + 4] = ((udpLen shr 8) and 0xFF).toByte()
            responsePacket[udpOffset + 5] = (udpLen and 0xFF).toByte()

            // UDP checksum (0 = disabled, valid for IPv4)
            responsePacket[udpOffset + 6] = 0
            responsePacket[udpOffset + 7] = 0

            // Copy DNS response data
            System.arraycopy(dnsResponse, 0, responsePacket, udpOffset + 8, dnsResponse.size)

            Timber.d("BLOCKED DNS response created: ${responsePacket.size} bytes, DNS size=${dnsResponse.size}")

            return responsePacket
        } catch (e: Exception) {
            Timber.e(e, "Error creating blocked DNS response")
            Timber.w(e, "Error creating blocked DNS response")
            return null
        }
    }

    /**
     * Forward DNS query to real DNS server and return response (ASYNC version)
     * This runs in a separate coroutine to avoid blocking the main packet loop
     * Uses DatagramChannel for better socket handling and protection
     */
    private fun forwardDnsQueryAsync(
        originalPacket: ByteArray,
        originalLength: Int,
        ipHeaderLength: Int,
        outputStream: FileOutputStream
    ) {
        try {
            val udpOffset = ipHeaderLength
            val udpLength = ((originalPacket[udpOffset + 4].toInt() and 0xFF) shl 8) or
                    (originalPacket[udpOffset + 5].toInt() and 0xFF)
            val dnsDataLength = udpLength - 8

            if (dnsDataLength <= 0 || udpOffset + 8 + dnsDataLength > originalLength) {
                writeToVpn(outputStream, originalPacket, originalLength)
                return
            }

            val dnsQuery = ByteArray(dnsDataLength)
            System.arraycopy(originalPacket, udpOffset + 8, dnsQuery, 0, dnsDataLength)

            // Extract domain for logging
            val domain = extractDomainFromDns(dnsQuery) ?: "unknown"

            // Forward to real DNS using DatagramChannel (more reliable protection)
            var dnsResponse: ByteArray? = null
            for (dnsServer in realDnsServers) {
                var channel: DatagramChannel? = null
                try {
                    // Create DatagramChannel
                    channel = DatagramChannel.open()
                    channel.configureBlocking(true)

                    // Get the underlying socket and protect it FIRST
                    val socket = channel.socket()

                    // CRITICAL: Protect the socket BEFORE binding or connecting
                    val protected = protect(socket)
                    if (!protected) {
                        Timber.w("Failed to protect channel socket for DNS server $dnsServer")
                        channel.close()
                        continue
                    }

                    Timber.d("Socket protected for $dnsServer, forwarding query for $domain")

                    // Now bind and set timeout
                    socket.bind(null) // Bind to any available local port
                    socket.soTimeout = 3000 // 3 second timeout (reduced for faster fallback)

                    // Connect to DNS server
                    val serverAddress = InetSocketAddress(dnsServer, 53)
                    channel.connect(serverAddress)

                    // Send DNS query
                    val sendBuffer = ByteBuffer.wrap(dnsQuery)
                    channel.write(sendBuffer)

                    // Receive DNS response
                    val receiveBuffer = ByteBuffer.allocate(1024)
                    val bytesRead = channel.read(receiveBuffer)

                    if (bytesRead > 0) {
                        receiveBuffer.flip()
                        dnsResponse = ByteArray(bytesRead)
                        receiveBuffer.get(dnsResponse)
                        Timber.d("DNS response received from $dnsServer for $domain, $bytesRead bytes")
                        break
                    }
                } catch (e: Exception) {
                    Timber.w("DNS server $dnsServer failed for $domain: ${e.message}")
                    Timber.w("DNS server $dnsServer failed: ${e.message}")
                } finally {
                    try {
                        channel?.close()
                    } catch (e: Exception) {
                        // Ignore close errors
                    }
                }
            }

            if (dnsResponse != null) {
                // Log the real DNS response for debugging
                val responseTxId = ((dnsResponse[0].toInt() and 0xFF) shl 8) or (dnsResponse[1].toInt() and 0xFF)
                Timber.d("Building FORWARDED response packet, DNS txId=0x${responseTxId.toString(16).padStart(4, '0')}")

                // Build response packet
                val responseLength = ipHeaderLength + 8 + dnsResponse.size
                val responsePacket = ByteArray(responseLength)

                // Copy and modify IP header
                System.arraycopy(originalPacket, 0, responsePacket, 0, ipHeaderLength)

                // Swap source and destination IP
                System.arraycopy(originalPacket, 12, responsePacket, 16, 4) // original src -> response dst
                System.arraycopy(originalPacket, 16, responsePacket, 12, 4) // original dst -> response src

                // Update total length
                responsePacket[2] = ((responseLength shr 8) and 0xFF).toByte()
                responsePacket[3] = (responseLength and 0xFF).toByte()

                // Clear fragment flags and offset (for clean response)
                responsePacket[6] = 0x40.toByte() // Don't Fragment flag set, fragment offset = 0
                responsePacket[7] = 0

                // Set reasonable TTL for response
                responsePacket[8] = 64.toByte()

                // CRITICAL: Recalculate IP header checksum after modifications
                setIpChecksum(responsePacket, ipHeaderLength)

                // UDP header - swap ports
                System.arraycopy(originalPacket, udpOffset + 2, responsePacket, udpOffset, 2) // dst port -> src port
                System.arraycopy(originalPacket, udpOffset, responsePacket, udpOffset + 2, 2) // src port -> dst port

                val newUdpLen = 8 + dnsResponse.size
                responsePacket[udpOffset + 4] = ((newUdpLen shr 8) and 0xFF).toByte()
                responsePacket[udpOffset + 5] = (newUdpLen and 0xFF).toByte()
                responsePacket[udpOffset + 6] = 0
                responsePacket[udpOffset + 7] = 0

                // DNS response data
                System.arraycopy(dnsResponse, 0, responsePacket, udpOffset + 8, dnsResponse.size)

                Timber.d(">>> Writing FORWARDED response to VPN (${responsePacket.size} bytes)")
                writeToVpn(outputStream, responsePacket)
                Timber.d(">>> FORWARDED response written successfully")
            } else {
                // All DNS servers failed - try system DNS resolution as fallback
                Timber.w("All DNS servers failed for $domain, trying system resolver fallback")
                val fallbackResponse = trySystemDnsResolver(domain, dnsQuery, originalPacket, originalLength, ipHeaderLength)
                if (fallbackResponse != null) {
                    writeToVpn(outputStream, fallbackResponse)
                } else {
                    // Return SERVFAIL response
                    Timber.e("System DNS fallback also failed for $domain")
                    val servFailResponse = createServFailDnsResponse(originalPacket, originalLength, ipHeaderLength, dnsQuery)
                    if (servFailResponse != null) {
                        writeToVpn(outputStream, servFailResponse)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error forwarding DNS query")
            Timber.w(e, "Error forwarding DNS query")
        }
    }

    /**
     * Fallback: Use Android's system DNS resolver
     * This uses InetAddress.getByName() which goes through the system's DNS
     */
    private fun trySystemDnsResolver(
        domain: String,
        originalDnsQuery: ByteArray,
        originalPacket: ByteArray,
        originalLength: Int,
        ipHeaderLength: Int
    ): ByteArray? {
        return try {
            // Use system resolver to get the IP address
            val addresses = InetAddress.getAllByName(domain)
            if (addresses.isEmpty()) {
                return null
            }

            // Get the first IPv4 address
            val ipv4Address = addresses.firstOrNull { it.address.size == 4 }
                ?: return null

            Timber.d("System DNS resolved $domain to ${ipv4Address.hostAddress}")

            // Build a synthetic DNS response with the resolved IP
            createSyntheticDnsResponse(
                originalPacket,
                originalLength,
                ipHeaderLength,
                originalDnsQuery,
                ipv4Address.address
            )
        } catch (e: Exception) {
            Timber.w("System DNS fallback failed for $domain: ${e.message}")
            null
        }
    }

    /**
     * Create a synthetic DNS response with a given IP address
     */
    private fun createSyntheticDnsResponse(
        originalPacket: ByteArray,
        originalLength: Int,
        ipHeaderLength: Int,
        dnsQuery: ByteArray,
        ipAddress: ByteArray
    ): ByteArray? {
        try {
            // Build DNS response with answer
            // Response = Header (12) + Question Section + Answer Section
            val questionEnd = findQuestionEnd(dnsQuery)
            if (questionEnd < 0) return null

            // Answer section: Name pointer (2) + Type (2) + Class (2) + TTL (4) + RDLength (2) + RData (4) = 16 bytes
            val dnsResponseSize = questionEnd + 16
            val dnsResponse = ByteArray(dnsResponseSize)

            // Copy header and question from query
            System.arraycopy(dnsQuery, 0, dnsResponse, 0, questionEnd)

            // Set response flags
            dnsResponse[2] = 0x81.toByte() // QR=1, Opcode=0, AA=0, TC=0, RD=1
            dnsResponse[3] = 0x80.toByte() // RA=1, Z=0, RCODE=0 (No error)

            // Set answer count to 1
            dnsResponse[6] = 0
            dnsResponse[7] = 1

            // Answer section starts after question
            var offset = questionEnd

            // Name: pointer to offset 12 (0xC00C)
            dnsResponse[offset++] = 0xC0.toByte()
            dnsResponse[offset++] = 0x0C.toByte()

            // Type: A (1)
            dnsResponse[offset++] = 0
            dnsResponse[offset++] = 1

            // Class: IN (1)
            dnsResponse[offset++] = 0
            dnsResponse[offset++] = 1

            // TTL: 300 seconds (5 minutes)
            dnsResponse[offset++] = 0
            dnsResponse[offset++] = 0
            dnsResponse[offset++] = 0x01
            dnsResponse[offset++] = 0x2C.toByte()

            // RDLength: 4 (IPv4 address)
            dnsResponse[offset++] = 0
            dnsResponse[offset++] = 4

            // RData: IP address
            System.arraycopy(ipAddress, 0, dnsResponse, offset, 4)

            // Build complete response packet
            val udpOffset = ipHeaderLength
            val responseLength = ipHeaderLength + 8 + dnsResponse.size
            val responsePacket = ByteArray(responseLength)

            // Copy IP header
            System.arraycopy(originalPacket, 0, responsePacket, 0, ipHeaderLength)

            // Swap source and destination IP
            System.arraycopy(originalPacket, 12, responsePacket, 16, 4)
            System.arraycopy(originalPacket, 16, responsePacket, 12, 4)

            // Update IP total length
            responsePacket[2] = ((responseLength shr 8) and 0xFF).toByte()
            responsePacket[3] = (responseLength and 0xFF).toByte()

            // Clear fragment flags and offset
            responsePacket[6] = 0x40.toByte() // Don't Fragment
            responsePacket[7] = 0

            // Set reasonable TTL
            responsePacket[8] = 64.toByte()

            // CRITICAL: Recalculate IP header checksum after modifications
            setIpChecksum(responsePacket, ipHeaderLength)

            // UDP header - swap ports
            System.arraycopy(originalPacket, udpOffset + 2, responsePacket, udpOffset, 2)
            System.arraycopy(originalPacket, udpOffset, responsePacket, udpOffset + 2, 2)

            // UDP length
            val udpLen = 8 + dnsResponse.size
            responsePacket[udpOffset + 4] = ((udpLen shr 8) and 0xFF).toByte()
            responsePacket[udpOffset + 5] = (udpLen and 0xFF).toByte()
            responsePacket[udpOffset + 6] = 0
            responsePacket[udpOffset + 7] = 0

            // DNS response data
            System.arraycopy(dnsResponse, 0, responsePacket, udpOffset + 8, dnsResponse.size)

            Timber.d("Synthetic DNS response created: ${responsePacket.size} bytes")
            return responsePacket
        } catch (e: Exception) {
            Timber.w(e, "Error creating synthetic DNS response")
            return null
        }
    }

    /**
     * Find the end of the DNS question section
     */
    private fun findQuestionEnd(dnsQuery: ByteArray): Int {
        if (dnsQuery.size < 12) return -1

        // Start after header (12 bytes)
        var offset = 12

        // Skip question name
        while (offset < dnsQuery.size) {
            val labelLen = dnsQuery[offset].toInt() and 0xFF
            if (labelLen == 0) {
                offset++ // Skip null terminator
                break
            }
            offset += labelLen + 1
        }

        // Skip QTYPE (2) and QCLASS (2)
        offset += 4

        return if (offset <= dnsQuery.size) offset else -1
    }

    /**
     * Create a DNS SERVFAIL response when all DNS servers fail
     */
    private fun createServFailDnsResponse(
        originalPacket: ByteArray,
        originalLength: Int,
        ipHeaderLength: Int,
        dnsQuery: ByteArray
    ): ByteArray? {
        try {
            // Create DNS response with SERVFAIL
            val dnsResponse = dnsQuery.copyOf()

            // Set response flags: QR=1 (response), RCODE=2 (SERVFAIL)
            dnsResponse[2] = 0x81.toByte() // Response + Recursion Desired
            dnsResponse[3] = 0x82.toByte() // Recursion Available + SERVFAIL

            // Build complete response packet
            val udpOffset = ipHeaderLength
            val responseLength = ipHeaderLength + 8 + dnsResponse.size
            val responsePacket = ByteArray(responseLength)

            // Copy IP header and swap src/dst
            System.arraycopy(originalPacket, 0, responsePacket, 0, ipHeaderLength)

            // Swap source and destination IP
            System.arraycopy(originalPacket, 12, responsePacket, 16, 4) // dst -> src
            System.arraycopy(originalPacket, 16, responsePacket, 12, 4) // src -> dst

            // Update IP total length
            responsePacket[2] = ((responseLength shr 8) and 0xFF).toByte()
            responsePacket[3] = (responseLength and 0xFF).toByte()

            // Clear fragment flags and offset
            responsePacket[6] = 0x40.toByte() // Don't Fragment
            responsePacket[7] = 0

            // Set reasonable TTL
            responsePacket[8] = 64.toByte()

            // CRITICAL: Recalculate IP header checksum after modifications
            setIpChecksum(responsePacket, ipHeaderLength)

            // UDP header - swap ports
            System.arraycopy(originalPacket, udpOffset + 2, responsePacket, udpOffset, 2)
            System.arraycopy(originalPacket, udpOffset, responsePacket, udpOffset + 2, 2)

            // UDP length
            val udpLen = 8 + dnsResponse.size
            responsePacket[udpOffset + 4] = ((udpLen shr 8) and 0xFF).toByte()
            responsePacket[udpOffset + 5] = (udpLen and 0xFF).toByte()
            responsePacket[udpOffset + 6] = 0
            responsePacket[udpOffset + 7] = 0

            // DNS response
            System.arraycopy(dnsResponse, 0, responsePacket, udpOffset + 8, dnsResponse.size)

            Timber.d("SERVFAIL DNS response created: ${responsePacket.size} bytes")
            return responsePacket
        } catch (e: Exception) {
            Timber.w(e, "Error creating SERVFAIL DNS response")
            return null
        }
    }

    private fun createConfigIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun Closeable.closeQuietly() {
        try {
            close()
        } catch (e: Exception) {
            Timber.w(e, "Error closing stream")
        }
    }

    /**
     * Android calls this when another VPN app is prepared and takes over the single
     * system VPN slot (e.g. the child launches ProtonVPN). Our tunnel is torn down and
     * content filtering silently stops. Flag it so onDestroy sends the specific
     * "another VPN app" alert, then stop ourselves - we cannot reclaim the slot while
     * the other VPN holds it.
     */
    /**
     * Defensive handler for the platform foreground-service timeout (API 35+).
     *
     * This service is declared specialUse and so is not subject to the dataSync
     * 6-hour cumulative cap. The override exists so that, if the platform ever does time
     * it out, content filtering does not stop silently.
     *
     * Logs and requests a restart. Touches none of the packet, DNS or blocklist logic.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Timber.w(
            "ContentFilterVpnService received foreground-service timeout " +
                "(startId=$startId, fgsType=$fgsType). Attempting graceful restart."
        )
        try {
            val restartIntent = Intent(applicationContext, ContentFilterVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(restartIntent)
            } else {
                applicationContext.startService(restartIntent)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to restart ContentFilterVpnService after timeout")
        }
        super.onTimeout(startId, fgsType)
    }

    override fun onRevoke() {
        Timber.w("onRevoke(): VPN slot taken by another app - content filter displaced")
        revokedByForeignVpn = true
        isActive = false
        broadcastVpnState(false)

        val wasSupposedToBeRunning = try {
            preferencesManager.isContentFilteringEnabled && !preferencesManager.isParent
        } catch (e: Exception) {
            false
        }
        if (wasSupposedToBeRunning) {
            val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastAlert = prefs.getLong(KEY_LAST_VPN_DISCONNECT_ALERT, 0L)
            if (now - lastAlert > VPN_DISCONNECT_ALERT_COOLDOWN_MS) {
                prefs.edit().putLong(KEY_LAST_VPN_DISCONNECT_ALERT, now).apply()
                sendForeignVpnAlert()
            }
        }

        super.onRevoke()
        stopVpn()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Check if VPN was supposed to be running (user-initiated disconnect detection)
        // If content filtering is still enabled in preferences but service is being destroyed,
        // it means the user manually disconnected the VPN
        val wasSupposedToBeRunning = try {
            preferencesManager.isContentFilteringEnabled && !preferencesManager.isParent
        } catch (e: Exception) {
            false
        }

        refreshJob?.cancel()
        refreshJob = null
        stopVpn()

        // Send alert if VPN was disconnected unexpectedly (likely by user) - but throttled.
        // onDestroy also fires on routine START_STICKY recycles (memory pressure, config
        // change), so alerting on every destroy spammed the parent with "VPN Content Filter
        // Disabled" (15 in 5 days here). Gate it behind a cooldown; a real user-disable still
        // surfaces, while OS churn is collapsed to at most one alert per window.
        if (wasSupposedToBeRunning && !revokedByForeignVpn) {
            val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastAlert = prefs.getLong(KEY_LAST_VPN_DISCONNECT_ALERT, 0L)
            if (now - lastAlert > VPN_DISCONNECT_ALERT_COOLDOWN_MS) {
                Timber.w("VPN disconnected while content filtering was enabled - sending tamper alert")
                prefs.edit().putLong(KEY_LAST_VPN_DISCONNECT_ALERT, now).apply()
                sendVpnDisconnectAlert()
            } else {
                Timber.w("VPN disconnect alert suppressed (within ${VPN_DISCONNECT_ALERT_COOLDOWN_MS / 60000}min cooldown - likely an OS service recycle)")
            }
        }

        serviceJob?.cancel()
        serviceJob = null
        _serviceScope = null
        Timber.d("ContentFilterVpnService destroyed")
    }

    /**
     * Send alert to parent when VPN is disconnected unexpectedly.
     * Uses WorkManager since we're in onDestroy and can't do long-running work.
     */
    private fun sendVpnDisconnectAlert() {
        try {
            val workData = androidx.work.workDataOf(
                com.safeguard.parentalcontrol.receiver.TamperDetectionReceiver.KEY_TAMPER_TYPE to "vpn_disconnected",
                com.safeguard.parentalcontrol.receiver.TamperDetectionReceiver.KEY_TAMPER_DETAILS to "VPN content filtering was disconnected"
            )

            val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.safeguard.parentalcontrol.worker.TamperAlertWorker>()
                .setInputData(workData)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build()
                )
                .addTag(Constants.WORK_TAG_TAMPER_ALERT)
                .build()

            androidx.work.WorkManager.getInstance(this).enqueue(workRequest)
            Timber.i("VPN disconnect alert work enqueued")
        } catch (e: Exception) {
            Timber.e(e, "Failed to enqueue VPN disconnect alert")
        }
    }

    /**
     * Send alert to parent when another VPN app displaced our content filter (onRevoke).
     * Distinct from sendVpnDisconnectAlert() so the parent knows a third-party VPN is the
     * cause, not that they turned filtering off themselves.
     */
    private fun sendForeignVpnAlert() {
        try {
            val workData = androidx.work.workDataOf(
                com.safeguard.parentalcontrol.receiver.TamperDetectionReceiver.KEY_TAMPER_TYPE to "foreign_vpn",
                com.safeguard.parentalcontrol.receiver.TamperDetectionReceiver.KEY_TAMPER_DETAILS to "Another VPN app took over the VPN slot; content filter stopped"
            )

            val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.safeguard.parentalcontrol.worker.TamperAlertWorker>()
                .setInputData(workData)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build()
                )
                .addTag(Constants.WORK_TAG_TAMPER_ALERT)
                .build()

            androidx.work.WorkManager.getInstance(this).enqueue(workRequest)
            Timber.i("Foreign-VPN alert work enqueued")
        } catch (e: Exception) {
            Timber.e(e, "Failed to enqueue foreign-VPN alert")
        }
    }

    /**
     * Broadcast VPN state change to UI
     */
    private fun broadcastVpnState(isRunning: Boolean) {
        val intent = Intent(ACTION_VPN_STATE_CHANGED).apply {
            putExtra(EXTRA_VPN_RUNNING, isRunning)
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Timber.d("Broadcast VPN state: isRunning=$isRunning")
    }

    companion object {
        const val ACTION_STOP = "com.safeguard.parentalcontrol.STOP_VPN"
        const val ACTION_VPN_STATE_CHANGED = "com.safeguard.parentalcontrol.VPN_STATE_CHANGED"
        const val EXTRA_VPN_RUNNING = "vpn_running"
        const val CHANNEL_ID = "safeguard_vpn_channel"
        const val NOTIFICATION_ID = 1002

        // Throttle for the onDestroy "VPN disconnected" tamper alert (see onDestroy).
        private const val VPN_DISCONNECT_ALERT_COOLDOWN_MS = 15 * 60 * 1000L
        private const val KEY_LAST_VPN_DISCONNECT_ALERT = "vpn_last_disconnect_alert"

        /**
         * True only while OUR content-filter tunnel is actually established (set after
         * builder.establish() succeeds, cleared on teardown). This is the real liveness
         * signal that ProtectionStatusHelper.isVpnConnected() reads - unlike
         * VpnService.prepare()==null, which only reports whether we still hold VPN consent
         * (it stays "granted" even when the tunnel is down), so it both missed genuine drops
         * and flipped false on benign consent re-evaluation, minting false "VPN stopped" alerts.
         */
        @Volatile
        var isActive: Boolean = false
            internal set

        /**
         * Check if VPN is currently running using ConnectivityManager
         */
        fun isVpnRunning(context: Context): Boolean {
            return try {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                        as android.net.ConnectivityManager
                connectivityManager.allNetworks.any { network ->
                    val capabilities = connectivityManager.getNetworkCapabilities(network)
                    capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
                }
            } catch (e: Exception) {
                Timber.e(e, "Error checking VPN state")
                false
            }
        }
    }
}
