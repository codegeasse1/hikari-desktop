package com.hikari.app.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object Http {

    /** Desktop Chrome UA, matching CloudStream's own USER_AGENT. The WAFs that
     *  guard the TamilBlasters/StreamHG/luluvdo family serve their player pages
     *  and HLS CDNs to desktop browsers (the plugins' own requests even use a
     *  desktop Chrome 149); a mobile "… Mobile Safari" UA stands out to those
     *  WAFs and some answer 403 before ever checking the token. */
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** Same current-Chrome fingerprint the WebView uses so probes and the site
     *  agree on what browser is "visiting" (Cloudflare checks consistency). */
    const val WEBVIEW_UA = UA

    private lateinit var client: OkHttpClient

    fun init() {
        // Align with the browser: on networks where the OS resolver is filtered
        // (or a local proxy/VPN must be used), the system ProxySelector and the
        // DoH-first resolver below let the app reach what the user's browser can.
        System.setProperty("java.net.useSystemProxies", "true")
        client = applyConscryptTls(OkHttpClient.Builder())
            .proxySelector(java.net.ProxySelector.getDefault())
            .dns(HikariDns)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Explicitly pins every client to Conscrypt (BoringSSL). This is critical:
     *  the JVM's lazy `sun.security.ssl.SSLSessionImpl` class-init can fail
     *  fatally on Windows (NoClassDefFoundError) once Conscrypt is installed as
     *  the default provider, so the JDK SSL stack must never be touched at all —
     *  a plain `.sslSocketFactory` from the JDK default context is a landmine. */
    fun applyConscryptTls(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        try {
            if (java.security.Security.getProvider("Conscrypt") == null) {
                java.security.Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1)
            }
            // The verifier is OURS: the anchors are handed over explicitly
            // (left to itself Conscrypt takes them from the JDK's
            // `lib/security/cacerts` snapshot — see Conscrypt's
            // Platform.getDefaultCertKeyStore() — which is what made every
            // GitHub-family host unreachable on Windows), AND the path building
            // is ours too, because Conscrypt's refuses any chain containing a
            // SHA-1-signed certificate. See [HikariTrustManager].
            val trust = trustManager()
            val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(trust), null)
            return builder.sslSocketFactory(ctx.socketFactory, trust)
        } catch (t: Throwable) {
            System.err.println("applyConscryptTls failed: $t")
            return builder
        }
    }

    // ── trust anchors ───────────────────────────────────────────────────────

    /**
     * The certificate authorities this app verifies HTTPS against.
     *
     * Why this exists at all: Conscrypt (which every client in this object is
     * pinned to) derives its trust anchors from the JDK's
     * `lib/security/cacerts` snapshot — see Conscrypt's
     * `Platform.getDefaultCertKeyStore()`, which asks the JDK's PKIX
     * TrustManagerFactory for its accepted issuers. That snapshot no longer
     * contains the legacy Comodo/Sectigo **AAA Certificate Services** root
     * (Temurin 17.0.20 and 21.0.12 both ship USERTrust RSA and the Sectigo R46
     * root, neither ships AAA), while GitHub's CDN still serves chains that end
     * at a Sectigo root cross-signed by AAA. So on a user's Windows machine the
     * browser loaded the repo and the app answered
     *
     *     Unacceptable certificate: CN=AAA Certificate Services, O=Comodo CA Limited …
     *
     * for every repo, extension download and mirror — which looked exactly like
     * "this network is blocking GitHub".
     *
     * The anchors are therefore assembled explicitly, from three sources, once
     * per process:
     *
     *  1. the JDK's own store (what we would have had anyway),
     *  2. the **Windows certificate store** — precisely what Chrome/Edge trust
     *     on this machine, which is the literal meaning of "but my browser can
     *     open it", and which also picks up corporate/AV roots the JDK never
     *     sees,
     *  3. `cacerts-extra.pem` from this app's resources: public roots that modern
     *     JDKs stopped shipping but that real servers still require (currently
     *     AAA Certificate Services — see the header of that file).
     *
     * Nothing is trusted blindly: every anchor comes from a public CA program
     * (Mozilla/Microsoft/JDK), and certificate *validation* is unchanged.
     */
    private val trustStoreLock = Any()

    @Volatile
    private var trustStoreCache: java.security.KeyStore? = null

    @Volatile
    private var trustStoreSummary = "not built yet"

    /** Anchor counts from the last [trustStore] build, for the log and the tests. */
    fun trustStoreReport(): String = trustStoreSummary

    /** The merged trust store (see [trustStoreReport] for what it holds). */
    fun trustStore(): java.security.KeyStore {
        trustStoreCache?.let { return it }
        synchronized(trustStoreLock) {
            trustStoreCache?.let { return it }
            val ks = java.security.KeyStore.getInstance("JKS")
            ks.load(null, null)
            var added = 0
            val jdk = addCertificates(ks, jdkCacerts(ks), added)
            added += jdk
            val windows = addCertificates(ks, windowsRootStore(ks), added)
            added += windows
            val extra = addPemCertificates(ks, "cacerts-extra.pem", added)
            added += extra
            val accepted = addCertificates(ks, extraTrustedKeyStore(), added)
            added += accepted
            trustStoreSummary =
                "anchors=" + ks.size() + " (jdk=" + jdk + " windows=" + windows + " extra=" + extra +
                    " accepted=" + accepted + ")"
            System.err.println("tls-trust: " + trustStoreSummary)
            if (ks.size() == 0) {
                // Nothing loaded at all (no JDK store on disk, no resources):
                // returning an EMPTY store would fail every request, so fall
                // back to letting the platform decide (the old behaviour).
                System.err.println("tls-trust: no anchors loaded — using the platform default store")
                trustStoreSummary = "anchors=none (platform default)"
                throw IllegalStateException("no trust anchors could be loaded")
            }
            trustStoreCache = ks
            return ks
        }
    }

    /** SHA-256 of every anchor in [trustStore] (how the tests recognise one). */
    fun trustStoreFingerprints(): Set<String> = runCatching {
        val ks = trustStore()
        val out = HashSet<String>()
        val aliases = ks.aliases()
        val md = java.security.MessageDigest.getInstance("SHA-256")
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            val cert = runCatching { ks.getCertificate(alias) }.getOrNull() ?: continue
            out.add(md.digest(cert.encoded).joinToString("") { "%02x".format(it) })
        }
        out
    }.getOrDefault(emptySet())

    /** Every certificate in a KeyStore (the anchors a verifier is built from). */
    private fun certificatesOf(ks: java.security.KeyStore): List<java.security.cert.X509Certificate> {
        val out = ArrayList<java.security.cert.X509Certificate>(ks.size())
        val aliases = runCatching { ks.aliases() }.getOrNull() ?: return out
        while (aliases.hasMoreElements()) {
            val cert = runCatching { ks.getCertificate(aliases.nextElement()) }.getOrNull() ?: continue
            if (cert is java.security.cert.X509Certificate) out.add(cert)
        }
        return out
    }

    @Volatile
    private var verifierCache: javax.net.ssl.X509TrustManager? = null

    /**
     * The verifier every client in this object uses.
     *
     * It is [HikariTrustManager] unless that cannot be built at all, in which
     * case the platform verifier is used (never a worse failure mode than the
     * old behaviour).
     */
    fun trustManager(): javax.net.ssl.X509TrustManager {
        verifierCache?.let { return it }
        synchronized(trustStoreLock) {
            verifierCache?.let { return it }
            val made = runCatching {
                val anchors = runCatching { certificatesOf(trustStore()) }.getOrDefault(emptyList())
                if (anchors.isEmpty()) throw IllegalStateException("no anchors could be loaded")
                System.err.println("tls-trust: verifying with " + anchors.size + " anchors (own PKIX verifier)")
                HikariTrustManager(anchors) as javax.net.ssl.X509TrustManager
            }.getOrElse { e ->
                System.err.println(
                    "tls-trust: own verifier unavailable (" + (e.message ?: e.javaClass.simpleName) +
                        ") — using the platform verifier",
                )
                platformTrustManager()
            }
            verifierCache = made
            return made
        }
    }

    /** The stock verifier (Conscrypt's, since it is the first provider). */
    private fun platformTrustManager(): javax.net.ssl.X509TrustManager = runCatching {
        val tmf = javax.net.ssl.TrustManagerFactory.getInstance("X509")
        val anchors = runCatching { trustStore() }.getOrNull()
        if (anchors != null) tmf.init(anchors) else tmf.init(null as java.security.KeyStore?)
        tmf.trustManagers[0] as javax.net.ssl.X509TrustManager
    }.getOrElse {
        val tmf = javax.net.ssl.TrustManagerFactory.getInstance("X509")
        tmf.init(null as java.security.KeyStore?)
        tmf.trustManagers[0] as javax.net.ssl.X509TrustManager
    }

    /** The user-accepted anchors as a KeyStore, merged into [trustStore] like
     *  any other anchor source. */
    private fun extraTrustedKeyStore(): java.security.KeyStore? {
        val certs = extraTrustedAnchors()
        if (certs.isEmpty()) return null
        return runCatching {
            java.security.KeyStore.getInstance("JKS").apply {
                load(null, null)
                var i = 0
                for (c in certs) runCatching { setCertificateEntry("u" + i++, c) }
            }
        }.getOrNull()
    }

    /** Drops the built store/verifier so the next fetch rebuilds them (the
     *  anchor set changed — see [trustCertificates]). */
    private fun forgetAnchors() {
        synchronized(trustStoreLock) {
            trustStoreCache = null
            verifierCache = null
            extraTrustedCache = null
        }
    }

    // ── anchors the user accepted by hand ────────────────────────────────────

    @Volatile
    private var extraTrustedCache: List<java.security.cert.X509Certificate>? = null

    /** Where the user's accepted extra CAs live. */
    fun extraTrustedFile(): java.io.File = java.io.File(
        java.io.File(System.getProperty("user.home"), ".hikari").apply { mkdirs() },
        "extra-trusted.pem",
    )

    /** The certificates the user has accepted ("Trust this network's
     *  certificate…" on a repo that will not load). */
    fun extraTrustedAnchors(): List<java.security.cert.X509Certificate> {
        extraTrustedCache?.let { return it }
        val f = runCatching { extraTrustedFile() }.getOrNull()
        val list = if (f == null || !f.isFile) emptyList() else parsePemText(runCatching { f.readText() }.getOrDefault(""))
        extraTrustedCache = list
        return list
    }

    fun extraTrustedCount(): Int = extraTrustedAnchors().size

    /**
     * Accepts [certs] as extra trust anchors, for this machine only.
     *
     * This is the last resort, and it is deliberately explicit: a network that
     * rewrites TLS (an ISP filter, a corporate/AV inspector) presents a chain no
     * store here can verify, and the alternative to accepting it is that the
     * network is simply unusable. The user sees the certificate (subject +
     * SHA-256) before it is added, and the file it lands in is theirs to delete.
     */
    fun trustCertificates(certs: Collection<java.security.cert.X509Certificate>): Int {
        if (certs.isEmpty()) return 0
        val f = runCatching { extraTrustedFile() }.getOrNull() ?: return 0
        val pem = java.util.Base64.getMimeEncoder(64, "\n".toByteArray())
        val text = buildString {
            if (!f.isFile || f.length() == 0L) {
                append("# Hikari Desktop — extra TLS trust anchors accepted by the user.\n")
                append("# Delete this file to withdraw them. One entry per certificate.\n")
            }
            for (c in certs) {
                append("\n# ").append(c.subjectX500Principal.name).append("\n")
                append("# sha256 ").append(sha256HexOf(c.encoded)).append("\n")
                append("-----BEGIN CERTIFICATE-----\n")
                append(pem.encodeToString(c.encoded)).append("\n")
                append("-----END CERTIFICATE-----\n")
            }
        }
        runCatching {
            val existing = if (f.isFile) f.readText() else ""
            f.writeText(existing + text)
        }.onFailure { return 0 }
        forgetAnchors()
        System.err.println("tls-trust: accepted " + certs.size + " extra anchor(s) -> " + f.absolutePath)
        return certs.size
    }

    private fun sha256HexOf(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Verifies a server's certificate chain against the anchors this app
     * collected (see [trustStore], plus anything the user accepted by hand).
     *
     * Why not Conscrypt's own TrustManagerImpl, which is what every client here
     * used to get: its path builder refuses to use ANY certificate in the
     * server's chain whose own signature is SHA-1 (Conscrypt's
     * ChainStrengthAnalyzer blacklists md2, md4, md5 and sha1 signature OIDs) —
     * even when that certificate's issuer is a trusted anchor of ours, and even
     * when a perfectly valid path exists through it. GitHub's CDN still serves
     * chains containing the legacy Comodo/Sectigo **AAA Certificate Services**
     * root (self-signed with SHA-1) on some networks and edges, and every such
     * connection died with
     *
     *     Unacceptable certificate: CN=AAA Certificate Services, O=Comodo CA Limited …
     *
     * which the app then reported as "this machine's certificate store doesn't
     * trust the site's CA chain" — a wrong name for a rule inside the verifier.
     * The JDK's PKIX validator has no such rule (Java 17 only disables SHA-1 for
     * *signed JARs*), so it builds the path through those cross-signed
     * certificates and — when it really cannot — says exactly which certificate
     * and why, which is what the user and the [diagnoseServer] report need.
     *
     * Two strategies, in order:
     *  1. validate the chain exactly as the server sent it; if the server sent
     *     the root (or a cross-signed variant of one we have), this is enough;
     *  2. otherwise BUILD a path from the leaf to any of our anchors using the
     *     server's certificates as the intermediate pool — the repair a browser
     *     silently performs for a server that reports its chain incompletely.
     */
    private class HikariTrustManager(
        anchors: List<java.security.cert.X509Certificate>,
    ) : javax.net.ssl.X509TrustManager {

        private val cam: java.security.cert.CertificateFactory =
            java.security.cert.CertificateFactory.getInstance("X.509")

        private val trustAnchors: Set<java.security.cert.TrustAnchor> =
            anchors.map { java.security.cert.TrustAnchor(it, null) }.toSet()

        /** Chains already verified in this session (TLS reconnects are frequent
         *  and the answer cannot change while the anchor set is the same). */
        private val accepted = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
            trustAnchors.map { it.trustedCert }.toTypedArray()

        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) =
            verify(chain)

        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) =
            verify(chain)

        private fun verify(chain: Array<out java.security.cert.X509Certificate>?) {
            if (chain == null || chain.isEmpty()) {
                throw java.security.cert.CertificateException("the server sent no certificate")
            }
            val list = chain.toList()
            val key = runCatching {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                list.joinToString("/") { sha256(md, it.encoded) }
            }.getOrNull()
            if (key != null && accepted.containsKey(key)) return
            val given = runCatching { validateGiven(list) }.exceptionOrNull()
            if (given == null) {
                if (key != null) accepted[key] = true
                return
            }
            val built = runCatching { build(list) }.exceptionOrNull()
            if (built == null) {
                if (key != null) accepted[key] = true
                return
            }
            val chosen = if (built is java.security.cert.CertificateException) built else given
            throw java.security.cert.CertificateException(describeFailure(chosen, list), chosen)
        }

        /** Strategy 1: the chain as received, validated as a complete path. */
        private fun validateGiven(chain: List<java.security.cert.X509Certificate>) {
            val params = java.security.cert.PKIXParameters(trustAnchors)
            params.isRevocationEnabled = false
            java.security.cert.CertPathValidator.getInstance("PKIX")
                .validate(cam.generateCertPath(chain), params)
        }

        /** Strategy 2: build a path from the leaf to one of our anchors. */
        private fun build(chain: List<java.security.cert.X509Certificate>) {
            val selector = java.security.cert.X509CertSelector().apply { certificate = chain.first() }
            val params = java.security.cert.PKIXBuilderParameters(trustAnchors, selector)
            params.isRevocationEnabled = false
            params.addCertStore(
                java.security.cert.CertStore.getInstance(
                    "Collection",
                    java.security.cert.CollectionCertStoreParameters(
                        HashSet<java.security.cert.Certificate>(chain),
                    ),
                ),
            )
            java.security.cert.CertPathBuilder.getInstance("PKIX").build(params)
        }

        private fun sha256(md: java.security.MessageDigest, bytes: ByteArray): String =
            md.digest(bytes).joinToString("") { "%02x".format(it) }

        /**
         * Turns a path-validation failure into something worth reading.
         *
         * "validity check failed" is the JDK's wording for a certificate that is
         * outside its validity window **for this machine's clock**, and neither of
         * the two possible causes is visible in it:
         *
         *  - the network served a chain whose certificate has expired (a mirror or
         *    a middlebox that never rotated its intermediate), or
         *  - this machine's clock is wrong.
         *
         * Both are named here, with the dates, because the difference decides
         * whether the user has to fix their clock or their network.
         */
        private fun describeFailure(
            t: Throwable,
            chain: List<java.security.cert.X509Certificate>,
        ): String {
            val base = (t.message ?: "").trim()
            if (t is java.security.cert.CertPathValidatorException && t.index >= 0) {
                val cert = chain.getOrNull(t.index)
                if (cert != null) {
                    val now = java.util.Date()
                    val clock = java.time.ZonedDateTime.now().format(CLOCK_FORMAT)
                    if (now.after(cert.notAfter)) {
                        return "the certificate \"" + cert.subjectX500Principal.name +
                            "\" is EXPIRED (" + stamp(cert.notAfter) + ") and this machine's clock reads " + clock
                    }
                    if (now.before(cert.notBefore)) {
                        return "the certificate \"" + cert.subjectX500Principal.name +
                            "\" is not valid until " + stamp(cert.notBefore) +
                            " and this machine's clock reads " + clock
                    }
                    return "the certificate \"" + cert.subjectX500Principal.name +
                        "\" was rejected" + (if (base.isBlank()) "" else ": " + base.take(160))
                }
            }
            return base.take(400).ifBlank { "the certificate chain could not be verified" }
        }

        private fun stamp(d: java.util.Date): String = DATE_FORMAT.format(d.toInstant())

        private val CLOCK_FORMAT: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")

        private val DATE_FORMAT: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
                .withZone(java.time.ZoneOffset.UTC)
    }

    private fun addCertificates(dest: java.security.KeyStore, src: java.security.KeyStore?, already: Int): Int {
        if (src == null) return 0
        var n = 0
        val aliases = runCatching { src.aliases() }.getOrNull() ?: return 0
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            val cert = runCatching {
                if (src.isCertificateEntry(alias)) src.getCertificate(alias) else null
            }.getOrNull() ?: continue
            if (cert !is java.security.cert.X509Certificate) continue
            runCatching { dest.setCertificateEntry("a" + (already + n), cert) }.onSuccess { n++ }
        }
        return n
    }

    /** The JDK's own `lib/security/cacerts`, read from disk (never through
     *  `sun.security.ssl`, which can be a landmine once Conscrypt is the
     *  default provider — see [applyConscryptTls]). */
    private fun jdkCacerts(dest: java.security.KeyStore): java.security.KeyStore? {
        val home = System.getProperty("java.home") ?: return null
        val file = java.io.File(java.io.File(home, "lib/security"), "cacerts")
        if (!file.isFile || !file.canRead()) return null
        return loadKeyStore(file, "changeit")
    }

    private fun loadKeyStore(file: java.io.File, password: String): java.security.KeyStore? {
        // JKS/JCEKS files carry the 0xFEEDFEED magic; everything else modern is
        // a PKCS#12 (the JDK's own cacerts has been PKCS#12 since JDK 9).
        val magic = runCatching { file.inputStream().use { it.readNBytes(4) } }.getOrNull()
        val looksJks = magic != null && magic.size == 4 && (magic[0].toInt() and 0xFF) == 0xFE &&
            (magic[1].toInt() and 0xFF) == 0xED
        val types = if (looksJks) listOf("JKS", "PKCS12") else listOf("PKCS12", "JKS")
        for (t in types) {
            val ks = runCatching {
                val k = java.security.KeyStore.getInstance(t)
                file.inputStream().use { k.load(it, password.toCharArray()) }
                k
            }.getOrNull()
            if (ks != null) return ks
        }
        return null
    }

    /** The OS certificate store. On Windows this is what Chrome/Edge use, so a
     *  site the user's browser trusts is trusted here too — including the roots
     *  of a corporate/AV TLS inspection proxy, which no JDK update will ever
     *  contain. Absent (or unreadable) on other platforms: that is fine, the
     *  other two sources still apply. */
    private fun windowsRootStore(dest: java.security.KeyStore): java.security.KeyStore? =
        runCatching {
            val ks = java.security.KeyStore.getInstance("Windows-ROOT")
            ks.load(null, null)
            ks
        }.getOrElse {
            System.err.println("tls-trust: no OS root store (" + (it.message ?: it.javaClass.simpleName) + ")")
            null
        }

    /** Anchors shipped with the app (see the header of `cacerts-extra.pem`). */
    private fun addPemCertificates(dest: java.security.KeyStore, resource: String, already: Int): Int {
        var n = 0
        for (cert in extraAnchors(resource)) {
            runCatching { dest.setCertificateEntry("x" + (already + n), cert) }.onSuccess { n++ }
        }
        if (n == 0) System.err.println("tls-trust: no anchors parsed from " + resource)
        return n
    }

    /** The certificates in a PEM resource of this jar, parsed once. */
    private fun extraAnchors(resource: String): List<java.security.cert.X509Certificate> =
        certCache.computeIfAbsent(resource) { parsePemResource(it) }

    private val certCache =
        java.util.concurrent.ConcurrentHashMap<String, List<java.security.cert.X509Certificate>>()

    /**
     * Reads every `-----BEGIN CERTIFICATE-----` block out of a PEM resource.
     *
     * Not `CertificateFactory.generateCertificates(stream)`: that call yields
     * NOTHING at all — silently — for a stream holding text it does not
     * understand, and this file deliberately carries a comment header (why it
     * exists, where each root came from, how to regenerate it). The first
     * version of this shipped exactly that way: the store logged `extra=0`,
     * the build passed because the Windows store happened to hold the same
     * root, and the shipped anchor was not actually being loaded at all.
     */
    private fun parsePemResource(resource: String): List<java.security.cert.X509Certificate> {
        val text = runCatching {
            val stream = javaClass.getResourceAsStream("/" + resource)
                ?: javaClass.classLoader?.getResourceAsStream(resource)
                ?: return emptyList()
            stream.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return emptyList()
        val out = parsePemText(text)
        if (out.isEmpty()) System.err.println("tls-trust: nothing parsed from " + resource)
        return out
    }

    /** Every `-----BEGIN CERTIFICATE-----` block in [text], comments ignored. */
    private fun parsePemText(text: String): List<java.security.cert.X509Certificate> {
        val factory = runCatching {
            java.security.cert.CertificateFactory.getInstance("X.509")
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<java.security.cert.X509Certificate>()
        val block = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----")
        for (match in block.findAll(text)) {
            val body = match.value.replace(Regex("-----[^-]+-----"), "").trim()
            val der = runCatching { java.util.Base64.getMimeDecoder().decode(body) }.getOrNull() ?: continue
            val cert = runCatching { factory.generateCertificate(der.inputStream()) }.getOrNull() ?: continue
            if (cert is java.security.cert.X509Certificate) out.add(cert)
        }
        return out
    }

    /** How many anchors the shipped `cacerts-extra.pem` holds (0 = missing or
     *  unparsable — the state the trust self-test refuses to accept). */
    fun extraAnchorCount(): Int = extraAnchors("cacerts-extra.pem").size

    /** SHA-256 of every anchor in the shipped file (how the tests recognise
     *  that a root came from the file and not from the OS store). */
    fun extraAnchorFingerprints(): Set<String> {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return extraAnchors("cacerts-extra.pem")
            .map { md.digest(it.encoded).joinToString("") { b -> "%02x".format(b) } }
            .toSet()
    }

    /**
     * The subject names of the certificate chain a host actually serves, read
     * from a real handshake with this machine's stack. Purely diagnostic: it is
     * what turns "the TLS handshake is being blocked" into a name the user (and
     * the log) can act on, and the trust self-test prints it.
     */
    fun serverChainSubjects(url: String): List<String> = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(request).execute().use { resp ->
            resp.handshake?.peerCertificates?.mapNotNull { c ->
                (c as? java.security.cert.X509Certificate)?.subjectX500Principal?.name
            }.orEmpty()
        }
    }.getOrDefault(emptyList())

    // ── diagnostics ─────────────────────────────────────────────────────────

    /**
     * A plain-text report on why a host cannot be reached from this machine —
     * the trust anchors that were loaded, every stack's exact error, and the
     * certificate chain the network ACTUALLY serves (read with a trust-all
     * socket, so it is available even when verification fails: that is the whole
     * point).
     *
     * It exists because "the TLS handshake is being blocked by this network" was
     * for months the app's answer to a certificate problem nobody could see the
     * details of. Everything here is written to
     * `~/.hikari/network-diagnosis.txt` by the Extensions screen, which is also
     * where the button that produces it lives.
     */
    fun diagnoseServer(rawUrl: String): String {
        val url = sanitizeStreamUrl(rawUrl).ifBlank { rawUrl }
        val sb = StringBuilder()
        val now = java.time.ZonedDateTime.now()
        sb.append("Hikari network diagnosis\n")
        sb.append("generated: ").append(java.time.Instant.now()).append("\n")
        sb.append("system clock: ").append(now).append("  (unix ms ").append(System.currentTimeMillis()).append(")\n")
        sb.append("java: ").append(System.getProperty("java.version")).append(" / ")
            .append(System.getProperty("java.vendor")).append("\n")
        sb.append("java.home: ").append(System.getProperty("java.home")).append("\n")
        sb.append("os: ").append(System.getProperty("os.name")).append(" ")
            .append(System.getProperty("os.version")).append(" ").append(System.getProperty("os.arch")).append("\n")
        sb.append("url: ").append(url).append("\n")
        sb.append("trust store: ").append(trustStoreReport()).append("\n")
        sb.append("accepted by the user: ").append(extraTrustedCount()).append("\n")
        sb.append("system proxy configured: ").append(systemProxyInUse()).append("\n")
        sb.append("verifier: ").append(runCatching { trustManager().javaClass.name }.getOrDefault("?")).append("\n")

        // 1. every stack, and exactly what it said
        sb.append("\n── each compatibility pass ─────────────────────────────\n")
        for (pass in passes()) {
            val t0 = System.currentTimeMillis()
            // getStringStrictOn already returns a Result, so it must NOT be
            // wrapped in another runCatching (that Double-Result swallowed the
            // text type and the report could not print its length).
            val r: Result<String> = try {
                getStringStrictOn(passClient(pass), url, emptyMap())
            } catch (t: Throwable) {
                Result.failure(t)
            }
            val ms = System.currentTimeMillis() - t0
            val err = r.exceptionOrNull()
            if (err == null) {
                val text: String = r.getOrNull() ?: ""
                sb.append(pass.key).append(": OK in ").append(ms).append("ms (").append(text.length).append(" chars)\n")
            } else {
                sb.append(pass.key).append(": FAILED in ").append(ms).append("ms\n")
                appendCauseChain(sb, err, "  ")
            }
        }

        // 2. the chain the network serves, trust on or off
        sb.append("\n── certificate chain the server actually sent ─────────\n")
        val chain = servedChain(url)
        if (chain.isEmpty()) {
            sb.append("(no handshake could be completed at all)\n")
        } else {
            val seen = trustStoreFingerprints()
            for ((i, cert) in chain.withIndex()) {
                val fp = sha256HexOf(cert.encoded)
                val self = cert.subjectX500Principal == cert.issuerX500Principal
                sb.append("[").append(i).append("] ").append(cert.subjectX500Principal.name).append("\n")
                sb.append("    issuer: ").append(cert.issuerX500Principal.name).append("\n")
                sb.append("    signature: ").append(cert.sigAlgName).append(" (").append(cert.sigAlgOID).append(")")
                    .append(sha1Like(cert) ?: "").append("\n")
                sb.append("    key: ").append(cert.publicKey.algorithm).append(" ").append(keyBits(cert)).append("\n")
                sb.append("    valid: ").append(cert.notBefore).append(" → ").append(cert.notAfter)
                .append(validityNote(cert)).append("\n")
                sb.append("    sha256: ").append(fp)
                    .append(if (fp in seen) "   [it IS one of our anchors]" else "   [not in our trust store]")
                    .append(if (self) "   [self-signed]" else "").append("\n")
                if (!self) {
                    val fits = runCatching {
                        trustManager().acceptedIssuers.count {
                            it.subjectX500Principal == cert.issuerX500Principal
                        }
                    }.getOrDefault(0)
                    sb.append("    our anchors with that issuer name: ").append(fits).append("\n")
                }
            }
        }
        sb.append("\nThe two lines that matter: the reason under each pass, and whether the\n")
        sb.append("bottom certificate of the served chain is in our trust store.\n")
        return sb.toString()
    }

    /** Marks a SHA-1 signature — the signature Conscrypt's verifier refuses. */
    private fun sha1Like(cert: java.security.cert.X509Certificate): String? =
        if (cert.sigAlgOID == "1.2.840.113549.1.1.5" || cert.sigAlgOID == "1.2.840.10045.4.1") {
            "   <-- SHA-1 SIGNATURE (Conscrypt refuses to build a path through this)"
        } else null

    private fun keyBits(cert: java.security.cert.X509Certificate): String = runCatching {
        when (val k = cert.publicKey) {
            is java.security.interfaces.RSAPublicKey -> k.modulus.bitLength().toString() + " bit"
            is java.security.interfaces.ECPublicKey -> k.params.curve.field.fieldSize.toString() + " bit"
            else -> ""
        }
    }.getOrDefault("")

    private fun validityNote(cert: java.security.cert.X509Certificate): String {
        val now = java.util.Date()
        return when {
            now.before(cert.notBefore) -> "   <-- NOT VALID YET for this machine's clock"
            now.after(cert.notAfter) -> "   <-- EXPIRED for this machine's clock"
            else -> ""
        }
    }

    private fun appendCauseChain(sb: StringBuilder, t: Throwable?, indent: String) {
        var c = t
        var depth = 0
        while (c != null && depth < 8) {
            sb.append(indent).append(c.javaClass.name).append(": ").append(c.message?.take(400) ?: "").append("\n")
            c = c.cause
            depth++
        }
    }

    /**
     * The certificate chain a host hands over, read through a socket that
     * accepts anything — the only way to SEE the chain when verification is the
     * thing that failed. Used by [diagnoseServer] and by the "trust this
     * network's certificate" action; never used for real requests.
     */
    fun servedChain(rawUrl: String): List<java.security.cert.X509Certificate> = runCatching {
        val uri = java.net.URI(rawUrl.trim())
        val host = uri.host ?: return emptyList()
        val port = if (uri.port > 0) uri.port else if (uri.scheme.equals("http", true)) 80 else 443
        if (port == 80) return emptyList()
        val trustAll = object : javax.net.ssl.X509TrustManager {
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
            override fun checkClientTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) = Unit
            override fun checkServerTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) = Unit
        }
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(trustAll), null)
        (ctx.socketFactory.createSocket() as javax.net.ssl.SSLSocket).use { s ->
            s.connect(java.net.InetSocketAddress(host, port), 8_000)
            runCatching {
                val params = s.sslParameters
                params.serverNames = listOf(javax.net.ssl.SNIHostName(host))
                s.sslParameters = params
            }
            s.soTimeout = 8_000
            s.startHandshake()
            s.session.peerCertificates.mapNotNull { it as? java.security.cert.X509Certificate }
        }
    }.getOrElse { e ->
        System.err.println("tls-diagnose: reading the served chain failed: " + (e.message ?: e.javaClass.simpleName))
        emptyList()
    }

    /** The user's accepted anchors that would let [url] verify — the certificate
     *  offered by "Trust this network's certificate…". */
    fun certsToAccept(url: String): List<java.security.cert.X509Certificate> {
        val chain = servedChain(url)
        if (chain.isEmpty()) return emptyList()
        val known = trustStoreFingerprints()
        // Everything in the chain we do NOT already trust: accepting the root
        // alone would not help a chain that is also missing an intermediate, and
        // an intermediate is a perfectly valid PKIX anchor. If nothing is new,
        // offer the top-most certificate (the one a re-issue would have changed).
        val fresh = LinkedHashMap<String, java.security.cert.X509Certificate>()
        for (c in chain) {
            val fp = sha256HexOf(c.encoded)
            if (fp !in known) fresh[fp] = c
        }
        return if (fresh.isEmpty()) listOfNotNull(chain.lastOrNull()) else fresh.values.toList()
    }

    // ── the compatibility ladder ────────────────────────────────────────────

    /**
     * One way of talking to the network. A machine can be unable to reach the
     * GitHub family (and *only* it) for reasons that have nothing to do with
     * the site — Conscrypt's TLS 1.3 handshake dying inside a network filter
     * ("Read error: Failure in SSL library, usually a protocol error"), or a
     * leftover OS proxy (an uninstalled VPN/Clash/Psiphon entry) answering
     * every request with garbage. Both are invisible to the user and both look
     * exactly like "this repo is unreachable".
     *
     * So instead of betting on one stack, every fetch walks a ladder of them,
     * and the one that worked is remembered in `~/.hikari/cache/net.json`, so
     * the next launch goes straight to it instead of paying for the dead stack
     * again.
     */
    private data class NetPass(val tls12: Boolean, val noProxy: Boolean) {
        val key: String get() = (if (tls12) "tls12" else "tls13") + (if (noProxy) "-noproxy" else "")
    }

    /** Remembers which pass got through, so the ladder is walked in the order
     *  this machine needs. */
    private object NetMemory {

        private val file: java.io.File by lazy {
            val f = java.io.File(
                java.io.File(System.getProperty("user.home"), ".hikari/cache"),
                "net.json",
            )
            runCatching { f.parentFile?.mkdirs() }
            f
        }

        @Volatile private var loaded = false
        @Volatile private var tls12 = false
        @Volatile private var noProxy = false

        private fun load() {
            if (loaded) return
            synchronized(this) {
                if (loaded) return
                loaded = true
                val text = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull() ?: return
                val root = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return
                tls12 = root.optBoolean("tls12", false)
                noProxy = root.optBoolean("noProxy", false)
            }
        }

        /** The pass that last worked, or null when nothing has been learned
         *  (a machine whose normal stack works learns nothing, which is the
         *  point — there is nothing to change). */
        fun pass(): NetPass? {
            load()
            return if (tls12 || noProxy) NetPass(tls12, noProxy) else null
        }

        fun remember(pass: NetPass) {
            load()
            if (tls12 == pass.tls12 && noProxy == pass.noProxy) return
            tls12 = pass.tls12
            noProxy = pass.noProxy
            val json = runCatching {
                org.json.JSONObject().put("tls12", tls12).put("noProxy", noProxy).toString()
            }.getOrElse { """{"tls12":$tls12,"noProxy":$noProxy}""" }
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(json)
            }.onFailure {
                // Never silent: a machine that cannot remember the rescue pays
                // for the dead stack on every launch, and the only way to find
                // that out is in the log.
                System.err.println("NetMemory: cannot persist the rescue pass to " + file.absolutePath + ": " + it)
            }
        }
    }

    /** Which pass this machine has learned (for the self-test and the logs). */
    fun learnedPassKey(): String? = NetMemory.pass()?.key

    /** Which pass served the most recent robust fetch, or null when the fetch
     *  went through on the ordinary stack (the only non-newsworthy outcome). */
    @Volatile private var lastWinPassKey: String? = null

    fun lastWinningPassKey(): String? = lastWinPassKey

    private val clientCache = ConcurrentHashMap<String, OkHttpClient>()

    /** TLS pinned to 1.2: everything a modern site accepts, and none of the
     *  TLS 1.3 machinery a broken network filter chokes on. */
    private val tls12Spec: okhttp3.ConnectionSpec by lazy {
        okhttp3.ConnectionSpec.Builder(okhttp3.ConnectionSpec.MODERN_TLS)
            .tlsVersions(okhttp3.TlsVersion.TLS_1_2)
            .build()
    }

    private fun buildClient(tls12: Boolean, noProxy: Boolean, connectMs: Long, readMs: Long): OkHttpClient {
        val b = OkHttpClient.Builder()
        applyConscryptTls(b)
        // CLEARTEXT is included on purpose: a spec list holding only a TLS spec
        // makes OkHttp refuse every http:// URL ("cleartext communication …
        // not permitted by network security policy"), which silently disabled
        // the TLS-1.2 and no-proxy rescue passes for plain-HTTP repos/streams —
        // the NetworkSelfTest caught exactly that.
        if (tls12) b.connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT, tls12Spec))
        if (noProxy) b.proxy(java.net.Proxy.NO_PROXY) else b.proxySelector(java.net.ProxySelector.getDefault())
        return b.dns(HikariDns)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(readMs, TimeUnit.MILLISECONDS)
            .build()
    }

    /** A short-timeout client for one pass: the timeouts are what keep a
     *  black-holed host cheap. */
    private fun passClient(pass: NetPass): OkHttpClient =
        cached("p-" + pass.key) { buildClient(pass.tls12, pass.noProxy, 6_000L, 10_000L) }

    /** The same pass with full timeouts, for hosts that are merely slow. */
    private fun slowClient(pass: NetPass): OkHttpClient =
        cached("s-" + pass.key) { buildClient(pass.tls12, pass.noProxy, 20_000L, 30_000L) }

    /** One client per (pass, timeout) pair, built once. */
    private fun cached(key: String, build: () -> OkHttpClient): OkHttpClient {
        clientCache[key]?.let { return it }
        val made = runCatching { build() }.getOrElse { client }
        return clientCache.putIfAbsent(key, made) ?: made
    }

    /** The ladder, most-likely-to-work first: what this machine learned last
     *  time, then the normal stack, then TLS 1.2, then TLS 1.2 with the OS
     *  proxy bypassed (only when one is configured — otherwise it is the same
     *  client twice). */
    private fun passes(): List<NetPass> {
        val out = LinkedHashSet<NetPass>()
        NetMemory.pass()?.let { out.add(it) }
        out.add(NetPass(tls12 = false, noProxy = false))
        out.add(NetPass(tls12 = true, noProxy = false))
        if (systemProxyInUse()) out.add(NetPass(tls12 = true, noProxy = true))
        System.err.println(
            "net-ladder: systemProxy=" + systemProxyInUse() + " learned=" + (NetMemory.pass()?.key ?: "-") +
                " ladder=" + out.joinToString(",") { it.key },
        )
        return out.toList()
    }

    /** True when the failure is the TLS stack unable to talk to the host at all
     *  — the signature of a network filter or a broken TLS 1.3 path, not of a
     *  dead host. The ladder handles it; the host must not be blacklisted for
     *  it. */
    fun isTlsStackFailure(t: Throwable?): Boolean {
        val text = (t?.message ?: "").lowercase()
        if (text.isBlank()) return false
        return text.contains("failure in ssl library") ||
            text.contains("ssl routines") ||
            text.contains("wrong_version_number") ||
            text.contains("unsupported protocol") ||
            text.contains("tlsv1 alert") ||
            text.contains("handshake_failure")
    }

    /**
     * True when the connection died because the certificate chain could not be
     * verified — "Unacceptable certificate: …", "unable to find valid
     * certification path", "PKIX path building failed".
     *
     * This is a property of THIS MACHINE's trust store and does not depend on
     * the TLS version, the proxy or the mirror, so the ladder treats it as
     * final (see [fetchStringRobust]): re-trying the same host on the TLS-1.2
     * and no-proxy passes is what turned one rejected certificate into a
     * minute-long "loading" spinner, and "the TLS handshake is being blocked by
     * this network" was exactly the wrong thing to tell the user about it.
     */
    fun isCertTrustFailure(t: Throwable?): Boolean {
        // The TYPE is the reliable signal: whatever text a JDK version puts in
        // the message, a chain that could not be built or validated is a trust
        // decision. ("Path does not chain with any of the trust anchors" is what
        // our own verifier says, and nothing in the old message list matched it.)
        var cause: Throwable? = t
        var depth = 0
        while (cause != null && depth < 8) {
            if (cause is java.security.cert.CertificateException) return true
            if (cause is java.security.cert.CertPathValidatorException) return true
            if (cause is java.security.cert.CertPathBuilderException) return true
            cause = cause.cause
            depth++
        }
        val text = ((t?.message ?: "") + " | " + (t?.cause?.message ?: "")).lowercase()
        if (text.isBlank()) return false
        return text.contains("unacceptable certificate") ||
            text.contains("unable to find valid certification path") ||
            text.contains("trust anchor for certification path not found") ||
            text.contains("trustanchornotfound") ||
            text.contains("does not chain with any of the trust") ||
            text.contains("certification path") ||
            text.contains("pkix path building failed") ||
            text.contains("no trusted certificate") ||
            text.contains("certpath") ||
            text.contains("certificate verify failed") ||
            text.contains("self signed certificate") ||
            text.contains("certificateexception")
    }

    /** True when a fetch failed because WE cancelled it (a racing mirror lost
     *  the race and its pool was shut down), not because the host did anything.
     *  Reported as a cause these entries only ever buried the real reason —
     *  "(3) InterruptedException" next to "(2) Unacceptable certificate" — so
     *  they are left out of the summary. */
    private fun isCancellation(t: Throwable?): Boolean = when (t) {
        null -> false
        is InterruptedException -> true
        // SocketTimeoutException extends InterruptedIOException but IS a real
        // answer about the host; only the plain interrupt is ours.
        is java.io.InterruptedIOException -> t !is java.net.SocketTimeoutException
        else -> {
            val m = (t.message ?: "").lowercase()
            m.contains("interrupted") || m.contains("canceled") || m.contains("cancelled") ||
                isCancellation(t.cause)
        }
    }

    /** What a certificate-trust failure deserves: it is neither the user's
     *  network nor the site, and it cannot be retried away, so the message says
     *  whose store is missing what. */
    private const val CERT_TRUST_MESSAGE =
        "this machine's certificate store doesn't trust the site's CA chain"

    /**
     * The reason line inside a failed verification — the peer certificate's own
     * name ("Unacceptable certificate: CN=AAA Certificate Services, …") or the
     * path error ("unable to find valid certification path to requested
     * target"). Kept in the message on purpose: it used to be replaced by the
     * friendly sentence alone, which made every trust problem look identical in
     * the user's screenshot and left nothing to act on.
     */
    private fun certDetail(t: Throwable?): String {
        var c = t
        var depth = 0
        while (c != null && depth < 6) {
            val m = c.message?.trim().orEmpty()
            if (m.isNotBlank()) {
                val line = m.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                if (line.isNotBlank()) return line.take(280)
            }
            c = c.cause
            depth++
        }
        return ""
    }

    /** [CERT_TRUST_MESSAGE] with the verifier's own words appended. */
    fun certTrustMessage(t: Throwable?): String {
        val d = certDetail(t)
        return if (d.isBlank() || d.equals(CERT_TRUST_MESSAGE, ignoreCase = true)) CERT_TRUST_MESSAGE
        else CERT_TRUST_MESSAGE + ": " + d
    }

    /**
     * A short, honest reason for a failed fetch across many mirrors: the most
     * common distinct causes with how many hosts reported them, so the user
     * reads "TLS handshake blocked by this network (8)" instead of whichever
     * attempt happened to fail last.
     */
    fun summariseFailures(failures: Collection<Throwable>, hosts: Collection<String> = emptyList()): String {
        val real = failures.filterNot { isCancellation(it) }
        if (real.isEmpty()) return if (failures.isEmpty()) "no server answered" else "the fetch was cancelled"
        val groups = LinkedHashMap<String, Int>()
        for (t in real) {
            val label = when {
                isCertTrustFailure(t) -> certTrustMessage(t)
                isTlsStackFailure(t) -> "the TLS handshake is being blocked by this network"
                t is UnknownHostException -> "DNS lookup failed"
                t is java.net.ConnectException -> "connection refused"
                t is java.net.SocketTimeoutException -> "timed out"
                t is java.io.InterruptedIOException -> "timed out"
                else -> humanMessage(t).take(90)
            }
            groups[label] = (groups[label] ?: 0) + 1
        }
        val detail = groups.entries.sortedByDescending { it.value }.take(3)
            .joinToString("; ") { it.key + " (" + it.value + ")" } +
            // Naming the hosts is what makes a screenshot enough to diagnose:
            // "raw.githubusercontent.com" failing is a different problem from a
            // proxy frontdoor failing, and the reason above is only the most
            // common one.
            if (hosts.isNotEmpty() && hosts.size <= 4) "  [asked: " + hosts.joinToString(", ") + "]" else ""
        // Say what it means, not just what the network stack said: this string
        // ends up on screen under "Couldn't load this repo", and it is the one
        // place the user can judge whether to retry, change network, or stop
        // caring — the extensions they already have keep working either way.
        return "No server answered from this network — $detail.\n" +
            "Extensions you already installed keep working. Try again in a minute, or switch " +
            "network/VPN if this keeps happening."
    }

    /** Runs the call. No JDK-TLS retry: the Conscrypt stack above is the one and
     *  only TLS path — a broken alternative that touches sun.security.ssl can
     *  poison the JVM (NoClassDefFoundError: SSLSessionImpl). */
    private fun execute(client: OkHttpClient, request: Request): Response =
        client.newCall(request).execute()

    /**
     * Runs [task] for every item on a small thread pool and returns the first
     * result that isn't null, with the item that produced it.
     *
     * Every "try one host after another" path in this object (repo lookups,
     * extension downloads) used to walk its candidates *serially*, so a single
     * blocked or throttled host cost a full connect timeout (20s) before the
     * next one was even attempted — which is why adding a repo or installing an
     * extension could sit there for a minute. Racing the candidates makes the
     * wall-clock cost the FASTEST host instead of the sum of the dead ones.
     *
     * Losers are left to finish (or be interrupted by the pool shutdown) in the
     * background; nothing outside [task] is mutated by them.
     */
    private fun <T : Any> raceFirst(
        items: List<String>,
        parallelism: Int = 4,
        windowMs: Long = 45_000L,
        task: (String) -> T?,
    ): Pair<String, T>? {
        if (items.isEmpty()) return null
        val threads = minOf(parallelism, items.size)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads) { r ->
            Thread(r, "hikari-fetch").apply { isDaemon = true }
        }
        try {
            val done = java.util.concurrent.LinkedBlockingQueue<Pair<String, T?>>()
            items.forEach { item ->
                pool.execute {
                    val value = runCatching { task(item) }.getOrNull()
                    // The pool is shut down (interrupting the losers) as soon as
                    // one candidate answers, so this hand-off can be interrupted
                    // too — swallowing that keeps the console free of
                    // "Uncaught ... InterruptedException" noise on every fetch.
                    runCatching { done.put(item to value) }
                }
            }
            val rounds = (items.size + threads - 1) / threads
            val deadline = System.currentTimeMillis() + windowMs * rounds
            var received = 0
            while (received < items.size) {
                val wait = deadline - System.currentTimeMillis()
                if (wait <= 0) break
                val res = done.poll(wait, TimeUnit.MILLISECONDS) ?: break
                received++
                val value = res.second
                if (value != null) return res.first to value
            }
            return null
        } catch (e: InterruptedException) {
            return null
        } finally {
            // Interrupts whatever is still running: for a fetch that's free, and
            // the download path uses per-attempt temp files so nothing is lost.
            pool.shutdownNow()
        }
    }

    /** How long the authoritative URLs of a file get before the CDN/proxy
     *  mirrors are allowed to answer. A blocked origin usually fails in
     *  milliseconds (connection reset / TLS refusal), so this window is only
     *  ever fully spent on a black-holed host. */
    private const val ORIGIN_WINDOW_MS = 9_000L

    /** How long the mirror wave gets per network pass. */
    private const val MIRROR_WINDOW_MS = 15_000L

    /** How many mirror candidates are raced at once. Four covers the realistic
     *  "one CDN + one proxy frontdoor + the origin" spread without hammering a
     *  slow network with eight parallel sockets. */
    private const val RACE_PARALLELISM = 4

    /**
     * How many candidate URLs may be in flight at once.
     *
     * [raceFirst] starts one worker per item up to the parallelism it is given,
     * and a worker only frees up when its request finishes. With the old limit
     * of four, the mirrors that actually work were queued BEHIND the blocked
     * ones: four dead hosts held every worker for a full connect timeout (20s)
     * before the fifth URL — the proxy frontdoor or jsDelivr that answers in a
     * second — was even attempted. That is what made an install or a repo add
     * sit for 30-40s while the actual download took under a second.
     *
     * The mirror list is bounded (8-16 URLs of a few hundred KB), so starting
     * them all costs nothing worth saving.
     */
    private const val FANOUT = 20

    /**
     * What this machine's network can actually reach, learned as it goes.
     *
     * Two halves: hosts whose connections fail (a blocked CDN, a dead proxy) are
     * remembered for ten minutes and moved to the BACK of every future candidate
     * list, and the exact mirror URL that served a given file is remembered so
     * the next request for it starts there. Both are persisted in
     * `~/.hikari/cache/mirrors.json`, so the second launch of the app — and
     * every install after the first — skips the dead hosts entirely instead of
     * rediscovering them at 20 seconds a piece.
     */
    private object MirrorMemory {

        private const val DEAD_TTL_MS = 10 * 60 * 1000L
        private const val MAX_PREFERRED = 400

        private val file: java.io.File by lazy {
            val f = java.io.File(
                java.io.File(System.getProperty("user.home"), ".hikari/cache"),
                "mirrors.json",
            )
            runCatching { f.parentFile?.mkdirs() }
            f
        }

        private val dead = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private val preferred = java.util.concurrent.ConcurrentHashMap<String, String>()

        @Volatile
        private var loaded = false

        @Volatile
        private var dirty = false

        private fun load() {
            if (loaded) return
            synchronized(this) {
                if (loaded) return
                loaded = true
                runCatching {
                    val text = file.takeIf { it.isFile }?.readText() ?: return@runCatching
                    val root = org.json.JSONObject(text)
                    root.optJSONObject("dead")?.let { d ->
                        for (k in d.keys()) dead[k] = d.optLong(k)
                    }
                    root.optJSONObject("preferred")?.let { p ->
                        for (k in p.keys()) preferred[k] = p.optString(k)
                    }
                }
            }
        }

        private fun save() {
            if (!dirty) return
            dirty = false
            runCatching {
                val d = org.json.JSONObject()
                val now = System.currentTimeMillis()
                for ((k, v) in dead) if (now - v < DEAD_TTL_MS) d.put(k, v)
                val p = org.json.JSONObject()
                for ((k, v) in preferred.entries.take(MAX_PREFERRED)) p.put(k, v)
                val root = org.json.JSONObject()
                root.put("dead", d)
                root.put("preferred", p)
                file.writeText(root.toString())
            }
        }

        fun hostOf(url: String): String =
            runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

        fun isDead(url: String): Boolean {
            load()
            val at = dead[hostOf(url)] ?: return false
            if (System.currentTimeMillis() - at > DEAD_TTL_MS) {
                dead.remove(hostOf(url))
                return false
            }
            return true
        }

        fun markDead(url: String) {
            load()
            dead[hostOf(url)] = System.currentTimeMillis()
            dirty = true
            save()
        }

        fun markAlive(url: String) {
            load()
            if (dead.remove(hostOf(url)) != null) {
                dirty = true
                save()
            }
        }

        /** The mirror that last served [url], if it is still a candidate. */
        fun winnerFor(url: String): String? {
            load()
            return preferred[url]
        }

        fun rememberWinner(url: String, winner: String) {
            load()
            if (preferred[url] != winner) {
                preferred[url] = winner
                dirty = true
                save()
            }
        }

        /** Puts [winner] first and sinks hosts known to be dead, keeping the
         *  caller's preference order within each group. */
        fun order(url: String, variants: List<String>): List<String> {
            load()
            val winner = winnerFor(url)
            val head = if (winner != null && variants.contains(winner)) listOf(winner) else emptyList()
            val rest = variants.filter { it != winner }
            return head + rest.sortedBy { if (isDead(it)) 1 else 0 }
        }
    }

    /** True for failures that say something about the HOST (blocked, refused,
     *  TLS-broken, unroutable) as opposed to the specific file — only those are
     *  worth remembering. An HTTP 404 from a live host must not blacklist it. */
    private fun isHostFailure(t: Throwable?): Boolean = when (t) {
        null -> false
        is java.net.UnknownHostException -> true
        is java.net.ConnectException -> true
        is java.net.SocketTimeoutException -> true
        is java.net.NoRouteToHostException -> true
        // A TLS-stack failure is this machine's problem, not the host's (see
        // [isTlsStackFailure]) — the ladder retries it on another stack, and the
        // host must not be blacklisted for it. The same goes for a CERTIFICATE
        // rejection: whether this machine trusts the chain says nothing about
        // the host, and blacklisting it for 10 minutes is what left manual
        // retries with a single candidate to try — so every retry failed the
        // same way and looked like "this network can never load that repo".
        is javax.net.ssl.SSLException -> !isTlsStackFailure(t) && !isCertTrustFailure(t)
        is java.io.InterruptedIOException -> true
        else -> isHostFailure(t.cause)
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): Response {
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return execute(client, builder.build())
    }

    fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/json; charset=utf-8",
    ): Response {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .post(body.toRequestBody(contentType.toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return execute(client, builder.build())
    }

    fun postString(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/json; charset=utf-8",
    ): String? = try {
        post(url, body, headers, contentType).use { if (it.isSuccessful) it.body?.string() else null }
    } catch (e: Exception) {
        null
    }

    /** Adds https:// when a scheme is missing and trims stray quotes. */
    fun normalizeUrl(raw: String): String {
        var u = raw.trim().trim('"', '\'')
        if (u.isBlank()) return u
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        return u
    }

    private fun ghRaw(out: MutableSet<String>, user: String, repo: String, branch: String) {
        // Desktop first: the desktop app runs JVM .jar extensions, and the
        // official repos publish repo-desktop.json with jar URLs. Fall back to
        // the dex (.hiki) repo.json only when no desktop variant exists.
        out.add("https://raw.githubusercontent.com/$user/$repo/$branch/repo-desktop.json")
        out.add("https://cdn.jsdelivr.net/gh/$user/$repo@$branch/repo-desktop.json")
        out.add("https://raw.githubusercontent.com/$user/$repo/$branch/repo.json")
        out.add("https://cdn.jsdelivr.net/gh/$user/$repo@$branch/repo.json")
    }

    /**
     * Candidate URLs for a repo.json. Accepts a direct repo.json URL, a raw
     * GitHub URL (with or without the full path) or a github.com repo page —
     * trying main/master and a jsDelivr mirror — so pasting the repo's GitHub
     * page just works instead of 404ing.
     */
    fun repoJsonCandidates(raw: String): List<String> {
        val url = normalizeUrl(raw)
        val out = linkedSetOf(url)
        Regex("^https?://github\\.com/([^/]+)/([^/]+?)(?:/tree/([^/]+))?(?:/.*)?$")
            .matchEntire(url)?.let { m ->
                val user = m.groupValues[1]
                val repo = m.groupValues[2]
                val branch = m.groupValues[3].ifBlank { "main" }
                ghRaw(out, user, repo, branch)
                if (branch != "main") ghRaw(out, user, repo, "main")
                ghRaw(out, user, repo, "master")
                // CloudStream convention: the repo manifest lives on the builds
                // branch (builds/repo.json), so pasting a CloudStream repo page
                // should find it too.
                ghRaw(out, user, repo, "builds")
            }
        Regex("^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)(?:/([^/]+))?(?:/(.*))?$")
            .matchEntire(url)?.let { m ->
                val user = m.groupValues[1]
                val repo = m.groupValues[2]
                var branch = m.groupValues[3]
                var path = m.groupValues[4]
                // Fully-qualified refs (…/u/r/refs/heads/<branch>/p): normalize
                // so the generated mirror URLs carry a bare branch.
                if (branch == "refs" && (path.startsWith("heads/") || path.startsWith("tags/"))) {
                    val rest = path.substringAfter('/')
                    val cut = rest.indexOf('/')
                    if (cut > 0) {
                        branch = rest.substring(0, cut)
                        path = rest.substring(cut + 1)
                    }
                }
                if (branch.isNotBlank()) {
                    if (path.isBlank()) {
                        ghRaw(out, user, repo, branch)
                    } else {
                        out.add("https://cdn.jsdelivr.net/gh/$user/$repo@$branch/$path")
                        if (!path.endsWith(".json")) ghRaw(out, user, repo, branch)
                        else if (path.contains("repo.json") && !path.contains("repo-desktop.json")) {
                            // A repo.json path on any branch (e.g. builds/repo.json)
                            // is usually the dex-only Android repo. Also offer the
                            // desktop repo-desktop.json on the same branch and on
                            // main, so the desktop app finds the .jar build
                            // instead of dead .hiki URLs.
                            ghRaw(out, user, repo, branch)
                            ghRaw(out, user, repo, "main")
                            // CloudStream convention: the manifest lives on the
                            // builds branch even when the pasted link says main.
                            if (branch != "builds") ghRaw(out, user, repo, "builds")
                        }
                    }
                } else {
                    ghRaw(out, user, repo, "main")
                    ghRaw(out, user, repo, "master")
                }
            }
        return out.toList()
    }

    /** Mirrors of official repos on a CDN that works even where GitHub is slow
     *  or blocked. Regenerate whenever the source repo.json changes. */
    private const val HIKARI_REPO_MIRROR = "https://user.uploads.dev/file/160d14f91512b838449f155070cb0c58.json"
    private const val CLOUDSTREAM_REPO_MIRROR = "https://user.uploads.dev/file/42f88079718447227d8bf7ccc5a5e286.txt"

    /** Hard cap for a repo fetch so a slow/blocked network fails with a clear
     *  error instead of leaving the UI stuck on "Checking…" for minutes. */
    private const val REPO_FETCH_DEADLINE_MS = 30_000L

    /** Overall budget for one extension download (all mirrors + retries). */
    private const val DOWNLOAD_BUDGET_MS = 60_000L

    /** Fetches a repo.json, trying every candidate URL. Only accepts a response
     *  that is actually a JSON object with a "plugins" key — or a CloudStream v2
     *  manifest whose "pluginLists" files hold the plugins array — a github.com
     *  HTML page or a CDN error body is skipped instead of being passed to the UI.
     *
     *  repo-desktop.json candidates are tried before repo.json ones: the desktop
     *  app runs JVM jars, and the desktop repos publish repo-desktop.json with
     *  .jar URLs — plain repo.json is the Android dex repo. [onStep] reports
     *  progress so the UI can show which candidate is being tried. */
    fun fetchRepoJson(raw: String, onStep: ((String) -> Unit)? = null): Result<Pair<String, String>> {
        val candidates = repoJsonCandidates(raw)
        val ordered = candidates.filter { it.contains("repo-desktop.json") } +
            candidates.filter { !it.contains("repo-desktop.json") }
        val list = if (ordered.any { it.contains("codegeasse1/hikari-extensions") }) {
            // Try the LIVE repo candidates FIRST so newly published extensions
            // show up immediately. The CDN mirror is only a LAST-RESORT
            // fallback for networks where GitHub (github.com + raw + jsDelivr)
            // is blocked or refused by the app's HTTP stack — it is a stale
            // manual snapshot, so it must never shadow the real repo.
            ordered + listOf(HIKARI_REPO_MIRROR)
        } else if (ordered.any { it.contains("codegeasse1/codegeasse-cloudstream-repos") }) {
            ordered + listOf(CLOUDSTREAM_REPO_MIRROR)
        } else {
            ordered
        }
        val deadline = System.currentTimeMillis() + REPO_FETCH_DEADLINE_MS
        val walk = Walk()

        // The stale snapshot mirrors get their own phase, so a CDN copy of the
        // repo can never answer before the live one.
        val snapshot = list.filter { it == HIKARI_REPO_MIRROR || it == CLOUDSTREAM_REPO_MIRROR }
        val live = list.filter { it !in snapshot }

        // One candidate URL is not one request: each expands to its own mirror
        // variants (jsDelivr, statically.io, the proxy frontdoors…). Flattening
        // every candidate into a single stream of concrete URLs is what makes a
        // repo add fast. The old code raced four CANDIDATES per wave and let each
        // candidate walk its own mirror list serially inside the race, so a
        // blocked raw.githubusercontent.com sitting in front of a working
        // jsDelivr cost a full connect timeout before the good URL was even
        // tried — several waves of that is the 30-40s wait.
        // The live candidates split into the AUTHORITATIVE URLs (which always
        // reflect the branch as it is now) and the CDN/proxy copies, which can
        // be days stale. A stale repo.json is not a crash: it is the repo
        // silently missing its newest extensions, which is why the origins are
        // raced on their own before any mirror gets a chance (see
        // [mirrorVariants]).
        val liveOrigins = LinkedHashSet<String>()
        val liveMirrors = LinkedHashSet<String>()
        for (c in live) {
            liveOrigins.addAll(originVariants(c))
            liveMirrors.addAll(mirrorVariants(c))
        }
        val snapshotUrls = LinkedHashSet<String>()
        for (c in snapshot) snapshotUrls.addAll(urlVariants(c))

        fun tryOne(c: OkHttpClient, u: String, throughProxy: Boolean): String? {
            val r = getStringStrictOn(c, u)
            val text = r.getOrNull()
            if (text == null) {
                val cause = r.exceptionOrNull()
                walk.record(u, cause, throughProxy)
                if (isHostFailure(cause)) MirrorMemory.markDead(u)
                return null
            }
            // A repo file is JSON, and its SHAPE says which kind it is: an object
            // with `plugins`/`pluginLists` (CloudStream, Hikari) or `scrapers`
            // (Nuvio/Stremio), or a bare ARRAY (an Aniyomi/Mihon `index.json`).
            val root = runCatching { org.json.JSONObject(text) }.getOrNull()
            val array = if (root == null) runCatching { org.json.JSONArray(text) }.getOrNull() else null
            if (root == null && array == null) {
                // A silent null here was the whole problem: the file came back, was
                // not a repo manifest, and the reason the USER was shown was
                // whichever OTHER candidate happened to fail loudly — a mirror's
                // certificate error, which has nothing to do with this repo at all.
                walk.record(u, Exception(notAManifest(text)), throughProxy)
                MirrorMemory.markDead(u)
                return null
            }
            // CloudStream v2 manifests (manifestVersion + pluginLists): fetch the
            // pluginLists file and merge its plugins array in, so callers keep
            // seeing a plain "plugins" key.
            if (root != null && root.has("pluginLists")) {
                MirrorMemory.markAlive(u)
                MirrorMemory.rememberWinner(raw, u)
                return resolvePluginLists(root, u)
            }
            if (root != null && (root.has("plugins") || root.has("scrapers"))) {
                // `scrapers` is a NUVIO repo (a Stremio-style manifest) — a repo
                // file like any other, read by the screen exactly as it arrives
                // (ExtensionsScreen.parsePlugins). It used to fall through to the
                // null below: fetched perfectly, then thrown away, so a repo that
                // HAD loaded was reported as unloadable.
                MirrorMemory.markAlive(u)
                MirrorMemory.rememberWinner(raw, u)
                return text
            }
            if (array != null && array.length() > 0) {
                // An Aniyomi/Mihon index: a bare array of extensions.
                MirrorMemory.markAlive(u)
                MirrorMemory.rememberWinner(raw, u)
                return text
            }
            walk.record(
                u,
                Exception("a JSON file that is not a repo manifest (no plugins, pluginLists, scrapers or index array)"),
                throughProxy,
            )
            MirrorMemory.markDead(u)
            return null
        }

        onStep?.invoke("Fetching repo… (racing ${liveOrigins.size + liveMirrors.size} mirrors)")
        val ladder = passes()

        // The live candidates are raced once per network pass (see [passes]): a
        // machine that cannot complete a TLS 1.3 handshake to the GitHub family
        // still loads its repos, just on the second pass. Within a pass the
        // authoritative URLs go first, the CDN copies only after they fail.
        for ((index, pass) in ladder.withIndex()) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 1_000L) break
            // Every stack that is still to come gets an EQUAL share of the time
            // that is left, rather than the first one helping itself to half of
            // the budget. A network whose TLS 1.3 handshake to the GitHub family
            // dies needs the LATER passes to actually run — a first pass that ate
            // the whole deadline is how a repo reported "unreachable" while the
            // very next stack would have loaded it.
            val share = left / (ladder.size - index)
            val window = minOf(if (index == 0) 15_000L else 12_000L, maxOf(6_000L, share))
            walk.startPass(pass.noProxy)
            for ((wave, ms) in listOf(
                walk.live(MirrorMemory.order(raw, liveOrigins.toList()), pass.noProxy) to ORIGIN_WINDOW_MS,
                walk.live(MirrorMemory.order(raw, liveMirrors.toList()), pass.noProxy) to window,
            )) {
                if (wave.isEmpty()) continue
                if (System.currentTimeMillis() >= deadline) break
                val got = raceFirst(wave, FANOUT, ms) { tryOne(passClient(pass), it, pass.noProxy) }
                if (got != null) {
                    System.err.println(
                        "net-ladder(repo): served by pass '" + pass.key + "' (" + got.first + ") in " +
                            (System.currentTimeMillis() - (deadline - REPO_FETCH_DEADLINE_MS)) + "ms",
                    )
                    lastWinPassKey = pass.key
                    NetMemory.remember(pass)
                    return Result.success(got.first to got.second)
                }
            }
            if (walk.hopeless()) {
                System.err.println("net-ladder(repo): every candidate failed certificate verification — no other pass can change a trust decision")
                break
            }
            System.err.println("net-ladder(repo): pass '" + pass.key + "' exhausted every candidate")
        }

        if (snapshotUrls.isNotEmpty() && System.currentTimeMillis() < deadline) {
            onStep?.invoke("Fetching repo… (last-resort mirror)")
            for (pass in ladder) {
                if (System.currentTimeMillis() >= deadline) break
                raceFirst(walk.live(snapshotUrls.toList(), pass.noProxy), FANOUT, 15_000L) {
                    tryOne(passClient(pass), it, pass.noProxy)
                }?.let {
                    NetMemory.remember(pass)
                    return Result.success(it.first to it.second)
                }
            }
        }
        // The in-JVM ladder is exhausted, but the OS's own HTTP client has a
        // different trust store and a different TLS stack (see [osHttpClient]),
        // so it gets the same candidate list before this repo is called
        // unreachable.
        if (osHttpClient() != null) {
            val all = (liveOrigins + liveMirrors + snapshotUrls).toList()
            val got = raceFirst(all, OS_PARALLELISM, OS_WINDOW_MS) { u ->
                val text = osFetchString(u) ?: return@raceFirst null
                if (!looksLikeManifest(text)) {
                    walk.record(u, Exception(notAManifest(text)), false)
                    return@raceFirst null
                }
                // A CloudStream v2 manifest keeps its plugins in separate
                // pluginLists files; merge them in exactly as tryOne does, or the
                // caller sees a manifest with no plugins in it.
                val root = runCatching { org.json.JSONObject(text) }.getOrNull()
                if (root != null && root.has("pluginLists")) resolvePluginLists(root, u) ?: text else text
            }
            if (got != null) {
                System.err.println("os-http(repo): served by " + got.first)
                return Result.success(got.first to got.second)
            }
        }
        return Result.failure(Exception(summariseFailures(walk.failures, walk.hostsWithFailures())))
    }

    /** Resolves [url] against [baseUrl] when it is relative. The CloudStream
     *  repo spec allows plugin lists ("plugins.json") and plugin URLs
     *  ("builds/X.cs3", "X.cs3") relative to the file that referenced them —
     *  the Android client resolves them that way, and a plain
     *  "https://plugins.json" guess is DNS-dead, so resolving correctly is
     *  what makes template-style v2 repos work at all. NB: a relative FILE
     *  name contains dots ("plugins.json"), so a dot alone must NEVER be
     *  taken as "this looks like a hostname" — anything without a scheme is
     *  resolved against [baseUrl]. */
    fun resolveRelativeTo(url: String, baseUrl: String): String {
        val u = url.trim()
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (baseUrl.isBlank()) return normalizeUrl(u)
        return runCatching { java.net.URI(baseUrl.trim()).resolve(u).toString() }
            .getOrDefault(normalizeUrl(u))
    }

    /** Fetches a CloudStream v2 manifest's `pluginLists` files and returns the
     *  manifest text with every usable plugins array merged under a "plugins"
     *  key (null when no list URL serves plugins). List URLs and plugin URLs
     *  may be relative to the repo.json / list file — both are resolved. */
    private fun resolvePluginLists(root: org.json.JSONObject, repoUrl: String): String? {
        val lists = root.optJSONArray("pluginLists") ?: return null
        val merged = org.json.JSONArray()
        val seen = HashSet<String>()
        for (i in 0 until lists.length()) {
            val raw = lists.optString(i)
            if (raw.isBlank()) continue
            val listUrl = resolveRelativeTo(raw, repoUrl)
            val pr = fetchStringRobust(listUrl)
            if (!pr.isSuccess) continue
            val text = pr.getOrThrow()
            val plugins: org.json.JSONArray? = runCatching { org.json.JSONArray(text) }.getOrNull()
                ?: runCatching {
                    val o = org.json.JSONObject(text)
                    when (val p = o.opt("plugins")) {
                        null -> null
                        is org.json.JSONArray -> p
                        else -> org.json.JSONArray(p)
                    }
                }.getOrNull()
            if (plugins == null) continue
            for (j in 0 until plugins.length()) {
                val p = plugins.optJSONObject(j) ?: continue
                val u = p.optString("url")
                if (u.isBlank()) continue
                // Relative plugin URLs resolve against the plugins-list file.
                val abs = resolveRelativeTo(u, listUrl)
                if (!seen.add(abs)) continue
                if (abs != u) p.put("url", abs)
                merged.put(p)
            }
        }
        if (merged.length() == 0) return null
        return runCatching { root.put("plugins", merged).toString() }.getOrNull()
    }

    /** Short human-readable reason for a failed network call: collapses the
     *  multi-line Conscrypt/BoringSSL TLS noise into a single line. */
    fun humanMessage(t: Throwable?): String {
        // The two desktop failures that deserve a sentence a user can act on,
        // not an OpenSSL/BoringSSL routine dump.
        if (isCertTrustFailure(t)) return certTrustMessage(t)
        if (isTlsStackFailure(t)) return "the TLS handshake is being blocked by this network"
        val raw = t?.message?.trim().orEmpty().ifBlank { t?.javaClass?.simpleName ?: "unknown network error" }
        val firstLine = raw.lineSequence().firstOrNull { it.isNotBlank() } ?: raw
        return firstLine
            .replace(Regex("ssl=[0-9A-Fa-f]+: "), "")
            .replace("error:10000089:SSL routines:OPENSSL_internal:DECODE_ERROR", "TLS handshake failed")
            .trim()
            .ifBlank { "unknown network error" }
    }

    /** Fixes stream URLs that some providers hand back half-escaped or relative:
     *  JSON `\/`/`\uXXXX` escapes, stray quotes/whitespace, and chaturbate's
     *  signed LL-HLS path — which arrives scheme-less with backslash separators,
     *  an escaped root-relative path, or a bare host prefix with no scheme.
     *  Returns the clean, playable URL (an unrewriteable value passes through). */
    fun sanitizeStreamUrl(raw: String): String {
        var u = raw.trim().trim('"', '\'')
        if (u.isBlank()) return u
        // JSON escapes a JS-embedded URL commonly carries (before \\ → \\ so the
        // \\uXXXX patterns still match).
        u = u
            // Decode EVERY \uXXXX escape (chaturbate's dossier escapes quotes
            // as \u0022 and may escape any other char too) BEFORE collapsing
            // the JSON backslash escapes.
            .replace(Regex("\\\\u([0-9a-fA-F]{4})")) { m ->
                m.groupValues[1].toInt(16).toChar().toString()
            }
            .replace("\\/", "/")
            .replace("\\\\", "\\")
            .replace("\\\"", "\"")
        // RFC 3986 URL characters. EVERYTHING else (real backslashes, escaped
        // slashes, or the lookalike Unicode separators chaturbate's dossier can
        // slip in) is treated as a path separator.
        val safe = "[^0-9A-Za-z._~:/?#\\[\\]@!'()*+,;=%&-]"
        // A chaturbate edge stream, found wherever it hides in the string and
        // whatever junk/separator precedes it — match ANY non-URL character
        // between the path segments, then rebuild it on the edge host.
        val edgeMatch = Regex("v1$safe+edge$safe+streams$safe+(.+)$").find(u)
        if (edgeMatch != null) {
            val host = run {
                var h = u.substringBefore(edgeMatch.value).trim().trimStart('/')
                if (h.startsWith("https://")) h = h.removePrefix("https://")
                else if (h.startsWith("http://")) h = h.removePrefix("http://")
                h = h.substringBefore('/').substringBefore('?').substringBefore('#')
                h.takeIf {
                    it.isNotBlank() && it.contains('.') &&
                        it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' }
                }
            } ?: "edge-hls.chaturbate.com"
            val cleanPath = edgeMatch.value.replace(Regex(safe), "/").replace(Regex("/{2,}"), "/")
            return "https://$host/edge-hls/$cleanPath"
        }
        if (u.startsWith("http://") || u.startsWith("https://")) {
            // Separators that are never legal in a URL (backslashes, escaped
            // slashes, lookalikes) normalize to `/` so mpv never sees them.
            val hostEnd = u.indexOf('/', u.indexOf("://") + 3)
            return if (hostEnd > 0) {
                u.substring(0, hostEnd + 1) + u.substring(hostEnd + 1).replace(Regex(safe), "/")
            } else {
                u.replace(Regex(safe), "/")
            }
        }
        return u
    }

    /** Verdict on a stream URL before the player commits to it. mpv answers a
     *  source whose server returns a web page, a JSON error body, or a
     *  browser-only blob:/data: URL with a cryptic "file format not supported"
     *  — so probe the first bytes and classify. */
    enum class StreamProbe { HLS, VIDEO, DASH, HTML, JSON, UNKNOWN }

    /**
     * Signed, single-use stream links (chaturbate's `mmcdn.com` / `edge-hls`
     * hosts): their token is valid for one request and expires in seconds, so
     * NOTHING may touch the URL before the player does — a probe would burn the
     * token and the player's own request would 403. Used by the speed race
     * ([streamLatencyMs]) and by the player's launch path.
     */
    fun isSignedStreamUrl(url: String): Boolean =
        url.contains("/v1/edge/streams/") || url.contains("mmcdn.com") ||
            url.contains("edge-hls.chaturbate.com")

    /** Fast classifier client: short timeouts, system resolver (no DoH) so a
     *  filtered network fails fast as UNKNOWN and the player's own DoH proxy
     *  path takes over instead of stalling playback. */
    private val probeClient: OkHttpClient? by lazy {
        try {
            applyConscryptTls(OkHttpClient.Builder())
                .proxySelector(java.net.ProxySelector.getDefault())
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .build()
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * The player's "play straight away" server race: how many milliseconds the
     * server needed to ANSWER (headers in, first bytes back), or null when it
     * did not answer inside [budgetMs].
     *
     * This is the only honest way to pick "the fastest server": the provider's
     * ordering is about quality and the source list, not about which host is
     * actually reachable from this machine right now — and a host that takes 9
     * seconds to say hello is the reason a video "takes ages to start".
     *
     * The probe is a 2-byte ranged GET with the provider's own headers, so it is
     * as cheap as a player's own first request and cannot download anything.
     * Signed, single-use links must never be probed (the request would burn the
     * token and the player's own request would 403) — callers skip those.
     */
    fun streamLatencyMs(
        url: String,
        headers: Map<String, String> = emptyMap(),
        budgetMs: Long = 2_500L,
    ): Long? {
        val clean = sanitizeStreamUrl(url)
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return null
        val started = System.currentTimeMillis()
        return try {
            val b = Request.Builder().url(clean)
                .header("User-Agent", UA)
                .header("Range", "bytes=0-1")
            headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) b.header(k, v) }
            val call = latencyClient.newCall(b.build())
            runCatching { call.timeout().timeout(budgetMs.coerceIn(500L, 10_000L), TimeUnit.MILLISECONDS) }
            call.execute().use { resp ->
                if (!resp.isSuccessful) return null
                System.currentTimeMillis() - started
            }
        } catch (e: Exception) {
            null
        }
    }

    /** The client [streamLatencyMs] uses: one, with the probe budget baked in. */
    private val latencyClient: OkHttpClient by lazy {
        applyConscryptTls(OkHttpClient.Builder())
            .proxySelector(java.net.ProxySelector.getDefault())
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    /** Fetches the first bytes of a stream URL (with the provider's Referer /
     *  Cookie / UA headers) and classifies the response. Only definite answers
     *  are trusted — an HTTP error, a non-2xx, or anything ambiguous returns
     *  UNKNOWN, meaning "let the player try" (the player often succeeds where a
     *  bare probe is refused). */
    fun probeStreamUrl(raw: String, headers: Map<String, String> = emptyMap()): StreamProbe {
        val url = raw.trim().trim('"', '\'')
        if (url.startsWith("blob:") || url.startsWith("data:")) return StreamProbe.HTML
        if (!url.startsWith("http://") && !url.startsWith("https://")) return StreamProbe.UNKNOWN
        val pc = probeClient ?: return StreamProbe.UNKNOWN
        return try {
            val b = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Range", "bytes=0-2047")
            headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) b.header(k, v) }
            pc.newCall(b.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // A 403 with an HTML body is a WAF/geo/login page — classify
                    // it so the player shows a clean message instead of raw
                    // [curl]/[ytdl_hook] error noise.
                    if (resp.code == 403) {
                        val body = runCatching { resp.body?.bytes() }.getOrNull() ?: ByteArray(0)
                        val head = String(body, 0, minOf(body.size, 2048), Charsets.UTF_8)
                            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
                        if (head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<head")) {
                            return StreamProbe.HTML
                        }
                    }
                    return StreamProbe.UNKNOWN
                }
                val ct = (resp.header("Content-Type") ?: "").lowercase()
                if (ct.contains("mpegurl") || ct.contains("hls")) return StreamProbe.HLS
                if (ct.contains("dash+xml") || ct.endsWith("/mpd")) return StreamProbe.DASH
                if (ct.startsWith("text/html") || ct.contains("html")) return StreamProbe.HTML
                if (ct.contains("json")) return StreamProbe.JSON
                if (ct.startsWith("video/") || ct.startsWith("audio/")) return StreamProbe.VIDEO
                val body = runCatching { resp.body?.bytes() }.getOrNull() ?: ByteArray(0)
                val head = String(body, 0, minOf(body.size, 2048), Charsets.UTF_8)
                    .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
                if (head.startsWith("#EXTM3U")) return StreamProbe.HLS
                if (head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<head")) return StreamProbe.HTML
                if (head.startsWith("<?xml") && head.contains("<MPD")) return StreamProbe.DASH
                if (head.startsWith("{") || head.startsWith("[")) return StreamProbe.JSON
                StreamProbe.UNKNOWN
            }
        } catch (e: Exception) {
            StreamProbe.UNKNOWN
        }
    }

    /** Human-friendly repo name from a URL: "codegeasse1/hikari-extensions"
     *  for github/raw URLs, the host otherwise. */
    fun repoDisplayName(url: String): String {
        val clean = normalizeUrl(url).removePrefix("https://").removePrefix("http://").removePrefix("www.")
        val parts = clean.split("/").filter { it.isNotBlank() }
        return if (parts.size >= 3 && (parts[0] == "github.com" || parts[0] == "raw.githubusercontent.com")) {
            parts[1] + "/" + parts[2]
        } else {
            parts.firstOrNull() ?: clean
        }
    }

    fun getString(url: String, headers: Map<String, String> = emptyMap()): String? =
        try {
            get(url, headers).use { if (it.isSuccessful) it.body?.string() else null }
        } catch (e: Exception) {
            null
        }

    fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray? =
        try {
            get(url, headers).use { if (it.isSuccessful) it.body?.bytes() else null }
        } catch (e: Exception) {
            null
        }

    /**
     * Streams a download to [dest], reporting (downloadedBytes, totalBytes) through
     * [onProgress] on each chunk. totalBytes is -1 when unknown. Returns true on success.
     */
    fun downloadTo(
        url: String,
        dest: java.io.File,
        headers: Map<String, String> = emptyMap(),
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Boolean = downloadToReason(url, dest, headers, onProgress) == null

    /** [downloadTo] that reports WHY it failed: null on success, a short
     *  human-readable reason ("HTTP 404", "UnknownHostException: …") on failure. */
    private fun downloadToReason(
        url: String,
        dest: java.io.File,
        headers: Map<String, String> = emptyMap(),
        onProgress: ((Long, Long) -> Unit)? = null,
        via: OkHttpClient? = null,
    ): String? = try {
        getOn(via ?: client, url, headers).use { resp ->
            if (!resp.isSuccessful) {
                "HTTP ${resp.code}"
            } else {
                val body = resp.body ?: return@use "empty response body"
                val total = body.contentLength()
                dest.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            onProgress?.invoke(done, total)
                        }
                    }
                }
                null
            }
        }
    } catch (e: Exception) {
        runCatching { dest.delete() }
        humanMessage(e)
    }

    /** A single GET with the stack this machine has been found to need (see
     *  [NetMemory]) — a machine whose TLS 1.3 is blocked must not have every
     *  catalog call fail just because the repo path learned the workaround. */
    fun getStringStrict(url: String, headers: Map<String, String> = emptyMap()): Result<String> {
        val learned = NetMemory.pass() ?: return getStringStrictOn(client, url, headers)
        return getStringStrictOn(slowClient(learned), url, headers)
    }

    private fun getStringStrictOn(c: OkHttpClient, url: String, headers: Map<String, String> = emptyMap()): Result<String> =
        try {
            getOn(c, url, headers).use { r ->
                if (r.isSuccessful) Result.success(r.body?.string() ?: "")
                else Result.failure(Exception("HTTP ${r.code} for $url"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** GET on an explicit client (see [noProxyClient]). */
    private fun getOn(c: OkHttpClient, url: String, headers: Map<String, String> = emptyMap()): Response {
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return execute(c, builder.build())
    }

    /**
     * An honest name for an answer that is not the file we asked for.
     *
     * "The host answered with something else" and "the host is unreachable" are
     * different problems with different fixes, and a silent null turned the first
     * into the second — the user was shown a mirror's certificate error as the
     * reason a repo would not load, when the repo file itself had come back fine
     * and been thrown away (see [fetchRepoJson]).
     */
    private fun notAManifest(text: String): String {
        val t = text.trim()
        return when {
            t.isEmpty() -> "an empty answer"
            isWebPage(t.toByteArray()) -> "a web page instead of the repo file (the host is blocking or broken)"
            else -> "an answer that is not JSON (" + t.replace(Regex("\\s+"), " ").take(48) + ")"
        }
    }

    // ── GitHub file URLs, in EVERY published form ───────────────────────────
    //
    // One repository file has several URLs that serve identical bytes: raw's
    // canonical form, github.com's own `/raw/` path, the jsDelivr edges, githack
    // and the proxy frontdoors. A repo may hand out ANY of them as a plugin's
    // URL — and usually does: whichever mirror answered the manifest fetch
    // becomes the base that the manifest's RELATIVE plugin paths are joined onto.
    // So the URL that reaches the installer is very often a `cdn.jsdelivr.net`
    // one (see the All-in-One-Nuvio repo, whose manifest is a jsDelivr/raw race).
    //
    // Before this, only raw.githubusercontent.com and github.com counted as
    // "GitHub", so a jsDelivr plugin URL got ONE candidate and NO fallback at
    // all: on a network that cannot reach jsDelivr (a very common case — it is
    // blocked or throttled in whole regions) every extension of such a repo
    // failed with "Download failed — check the URL", while the same file was one
    // URL rewrite away on raw or through a frontdoor. That is the same
    // structural hole as the release-asset one, for a different set of hosts.

    private val RE_GH_RAW_HOST =
        Regex("^https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/(.+)$")
    private val RE_GH_RAW_PATH =
        Regex("^https?://(?:www\\.)?github\\.com/([^/]+)/([^/]+)/(?:raw|blob)/(.+)$")
    private val RE_GH_JSDELIVR =
        Regex("^https://(?:cdn|fastly|gcore|testingcf)\\.jsdelivr\\.net/gh/([^/]+)/([^/@]+)@([^/]+)(?:/(.*))?$")
    private val RE_GH_JSDELIVR_BCDN =
        Regex("^https://jsdelivr\\.b-cdn\\.net/gh/([^/]+)/([^/@]+)@([^/]+)(?:/(.*))?$")
    private val RE_GH_GITHACK =
        Regex("^https://(?:rawcdn\\.|raw\\.)?githack\\.com/([^/]+)/([^/]+)/([^/]+)(?:/(.*))?$")

    /** A GitHub file URL split into user/repo/ref/path, whatever form it wore. */
    private data class GhTarget(val user: String, val repo: String, val ref: String, val path: String)

    /** Strips a proxy frontdoor prefix (`ghfast.top/https://github.com/…`) so what
     *  it wraps can be recognized for what it is. */
    private fun stripFrontdoor(url: String): String {
        for (h in FRONTDOOR_HOSTS) {
            val p = "https://$h/"
            if (url.startsWith(p)) {
                val rest = url.substring(p.length)
                if (rest.startsWith("http://") || rest.startsWith("https://")) return rest
            }
        }
        return url
    }

    /**
     * Parses any published form of a GitHub file URL. Modern repos hand out the
     * fully-qualified form `…/u/r/refs/heads/<branch>/p`, which the naive split
     * reads as ref="refs" + path="heads/<branch>/p" — every mirror URL built
     * from that 404s — so the ref is normalized ("refs/heads/builds" →
     * "builds"). jsDelivr/githack forms put the ref after `@` or in its own
     * segment; all of them end up as the same (user, repo, ref, path).
     */
    private fun parseGhTarget(url0: String): GhTarget? {
        val url = stripFrontdoor(url0.trim())
        // groupValues (not destructured): the CDN forms end in an OPTIONAL path
        // group ("a bare base URL"), and a non-participating group is a null
        // String in the destructured form — an NPE waiting for the one repo that
        // publishes a base URL instead of a file URL.
        RE_GH_RAW_HOST.matchEntire(url)?.let { m ->
            return splitRefPath(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        RE_GH_RAW_PATH.matchEntire(url)?.let { m ->
            return splitRefPath(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        RE_GH_JSDELIVR.matchEntire(url)?.let { m ->
            return GhTarget(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4].substringBefore('?'))
        }
        RE_GH_JSDELIVR_BCDN.matchEntire(url)?.let { m ->
            return GhTarget(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4].substringBefore('?'))
        }
        RE_GH_GITHACK.matchEntire(url)?.let { m ->
            return GhTarget(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4].substringBefore('?'))
        }
        return null
    }

    /** `ref/path…` → (ref, path), with a fully-qualified ref normalized to the
     *  bare branch/tag the CDN and frontdoor forms expect. */
    private fun splitRefPath(user: String, repo: String, rest0: String): GhTarget {
        val rest = rest0.substringBefore('?')
        var ref = rest.substringBefore('/')
        var path = rest.substringAfter('/', "")
        if (ref == "refs" && (path.startsWith("heads/") || path.startsWith("tags/"))) {
            val tail = path.substringAfter('/')
            val cut = tail.indexOf('/')
            if (cut > 0) {
                ref = tail.substring(0, cut)
                path = tail.substring(cut + 1)
            } else {
                ref = tail
                path = ""
            }
        }
        return GhTarget(user, repo, ref, path)
    }

    /** raw.githubusercontent's canonical form for [g] — the freshest URL there is,
     *  and the one every other form can be rewritten to. */
    private fun ghRawUrl(g: GhTarget): String =
        "https://raw.githubusercontent.com/${g.user}/${g.repo}/${g.ref}" +
            (if (g.path.isBlank()) "" else "/${g.path}")

    /** github.com's own raw path for [g] — it follows the repo's canonical case
     *  and answers for a renamed owner, so it is the more forgiving twin. */
    private fun ghRawPathUrl(g: GhTarget): String =
        "https://github.com/${g.user}/${g.repo}/raw/${g.ref}" +
            (if (g.path.isBlank()) "" else "/${g.path}")

    /** The CDN copies of [g] — the jsDelivr edges (different hostnames, which is
     *  what is needed when one is SNI-blocked) and githack. */
    private fun ghCdnUrls(g: GhTarget): List<String> {
        if (g.path.isBlank()) return emptyList()
        val q = "gh/${g.user}/${g.repo}@${g.ref}/${g.path}"
        return listOf(
            "https://cdn.jsdelivr.net/$q",
            "https://fastly.jsdelivr.net/$q",
            "https://gcore.jsdelivr.net/$q",
            "https://testingcf.jsdelivr.net/$q",
            "https://jsdelivr.b-cdn.net/$q",
            "https://raw.githack.com/${g.user}/${g.repo}/${g.ref}/${g.path}",
        )
    }

    /** True when [url] already IS the canonical raw form (so it needs no
     *  canonicalization and can be tried first). */
    private fun isCanonicalRawForm(url: String): Boolean =
        url.startsWith("https://raw.githubusercontent.com/") ||
            RE_GH_RAW_PATH.matches(url)

    /**
     * Rewrites any published form of a GitHub file (or a repo base) URL to
     * raw.githubusercontent. Used before resolving a manifest's RELATIVE plugin
     * paths: joining them onto whichever CDN won the manifest race is how a
     * plugin URL ends up pinned to one host with no alternatives, and the raw
     * form is both canonical and the one every mirror can be derived from.
     * Anything unrecognized is returned untouched.
     */
    fun canonicalGithubFileUrl(url: String): String {
        val base = normalizeDriveUrl(url.trim())
        val gh = parseGhTarget(base) ?: return base
        return ghRawUrl(gh)
    }


    /** True when a body parses as a repo manifest of any kind — an object
     *  (CloudStream/Hikari/Nuvio) or a bare array (Aniyomi) — which is the same
     *  shape test [fetchRepoJson] applies to its in-JVM candidates. */
    private fun looksLikeManifest(text: String): Boolean =
        runCatching { org.json.JSONObject(text) }.isSuccess ||
            runCatching { org.json.JSONArray(text) }.isSuccess

    /**
     * The AUTHORITATIVE URLs for a file: the URL the caller gave (normalized),
     * raw.githubusercontent's canonical form, and github.com's own `/raw/`
     * path. These cannot be a stale CDN copy of a branch file, so they are
     * always tried before any mirror.
     *
     * When the caller's URL is itself a CDN/frontdoor form (a jsDelivr plugin
     * URL is the common case — see the note above [parseGhTarget]) the canonical
     * raw file comes FIRST: a CDN caches a branch file for days, so it is the
     * one thing that must not decide what gets installed.
     *
     * A RELEASE ASSET has no such family — the bytes live on github.com alone —
     * so this list is the single URL the caller gave, and [mirrorVariants] is
     * what supplies its alternatives (see [githubFrontdoors]).
     */
    internal fun originVariants(url: String): List<String> {
        val base = normalizeDriveUrl(url.trim())
        val gh = parseGhTarget(base) ?: return listOf(base)
        val out = linkedSetOf<String>()
        if (isCanonicalRawForm(base)) {
            out.add(base)
            out.add(ghRawUrl(gh))
            out.add(ghRawPathUrl(gh))
        } else {
            out.add(ghRawUrl(gh))
            out.add(base)
            out.add(ghRawPathUrl(gh))
        }
        return out.toList()
    }

    /**
     * CDN and proxy copies of the same file. Two things make these a FALLBACK
     * and never a first choice:
     *
     *  - a CDN (jsDelivr especially) caches a branch file for days, so a mirror
     *    can serve an OLD revision — for an extension `.cs3` that means a jar
     *    whose `manifest.json` is missing, i.e. "the extension won't load";
     *  - a proxy frontdoor can answer HTTP 200 with a small HTML error page.
     *    A 100-byte page beats a 90 KB file in a race, so racing mirrors first
     *    is how a download "succeeds" with garbage ([isWebPage] is the belt to
     *    this braces).
     */
    internal fun mirrorVariants(url: String): List<String> {
        val base = normalizeDriveUrl(url.trim())
        val gh = parseGhTarget(base)
        val out = linkedSetOf<String>()
        if (gh != null) {
            // Every CDN copy of the file, whichever of them the caller's URL
            // happened to be — a jsDelivr plugin URL now gets the raw form, the
            // other jsDelivr edges, githack AND the frontdoors, instead of the
            // empty list it used to get for being "not GitHub".
            out.addAll(ghCdnUrls(gh))
            out.addAll(githubFrontdoors(ghRawUrl(gh)))
            // The URL the caller gave is already in [originVariants]; a mirror
            // list that repeats it would spend a socket racing itself.
            out.remove(base)
            return out.toList()
        }
        // NOT a raw file path — and this is the case this branch exists for: a
        // RELEASE ASSET (`github.com/<u>/<r>/releases/download/<tag>/<file>`).
        // Those bytes are published nowhere but github.com, so jsDelivr and
        // githack cannot serve them and this list used to come back EMPTY. A repo
        // that ships its extensions as release assets (the official Hikari one
        // does: `…/releases/download/continuous/<name>.jar`) therefore had
        // exactly ONE candidate URL, and on a network where github.com is
        // blocked or TLS-filtered every install of it ended in "no mirror served
        // the file" — which is the bug report this fixes.
        if (isGithubUrl(base)) return githubFrontdoors(base)
        return emptyList()
    }

    /** Hosts that serve only GitHub's own content: repo pages, raw files, release
     *  assets, and the CDN edge those assets redirect to. */
    private val GITHUB_HOSTS = listOf(
        "github.com",
        "raw.githubusercontent.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "github-releases.githubusercontent.com",
        "codeload.github.com",
        "gist.githubusercontent.com",
    )

    /** True when [url] is served by GitHub (or its asset CDN). */
    internal fun isGithubUrl(url: String): Boolean {
        val host = runCatching { java.net.URI(url.trim()).host?.lowercase() }.getOrNull() ?: return false
        return GITHUB_HOSTS.any { host == it || host.endsWith("." + it) }
    }

    /**
     * Frontdoors for networks where TLS to every GitHub-family host dies (the
     * classic "SSL protocol error" on raw + jsDelivr while normal sites still
     * load) or where the whole family is blocked by name.
     *
     * Each one is handed the FULL GitHub URL, which is what makes them the only
     * mirrors a release asset can have — and is why they are now generated for
     * every github.com URL and not only for raw file paths (see
     * [mirrorVariants]). Different hostnames, same files; a dead one costs
     * nothing because they are all raced at once. (gh-proxy.net was removed: it
     * answers 200 with a 122-byte stub, and being tiny it won every race.)
     */
    private fun githubFrontdoors(url: String): List<String> =
        FRONTDOOR_HOSTS.map { "https://$it/$url" }

    /** The proxy frontdoors, in one place: [githubFrontdoors] builds the URLs and
     *  [stripFrontdoor] unwraps them, so the two can never drift apart. */
    private val FRONTDOOR_HOSTS = listOf(
        "ghfast.top",
        "ghproxy.net",
        "gh-proxy.com",
        "ghproxy.cc",
        "gh.llkk.cc",
        "github.moeyy.xyz",
        "hub.gitmirror.com",
    )

    /** Every candidate URL for a file: authoritative first, mirrors after. */
    private fun urlVariants(url: String): List<String> =
        originVariants(url) + mirrorVariants(url)

    /**
     * True when a body is plainly NOT the file that was asked for — an HTML or
     * XML page, which is what a proxy frontdoor or a captive portal returns
     * (with HTTP 200) instead of the bytes. Every "robust" fetch races mirrors,
     * and the wrong answer is usually the FASTEST one, so this check is what
     * keeps a race from "succeeding" with an error page.
     */
    fun isWebPage(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.isEmpty()) return false
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1)
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            .lowercase()
        if (head.startsWith("<!doctype") || head.startsWith("<html")) return true
        if (head.startsWith("<") && (head.contains("<head") || head.contains("<body") || head.contains("<title"))) return true
        // An XML error document (S3/Cloudflare style) — still not the asset.
        if (head.startsWith("<?xml") && head.contains("error")) return true
        return false
    }

    /** True when the OS has an HTTP(S) proxy configured — used to decide
     *  whether a final no-proxy rescue pass is worth trying. */
    private fun systemProxyInUse(): Boolean {
        return try {
            java.net.ProxySelector.getDefault()
                ?.select(java.net.URI("https://raw.githubusercontent.com/"))
                ?.any { it.type() != java.net.Proxy.Type.DIRECT } == true
        } catch (t: Throwable) {
            false
        }
    }

    /** Same stack as [client] but with the system proxy DISABLED. A leftover
     *  OS proxy config (typical after uninstalling a VPN/Clash/Psiphon) makes
     *  every JVM connection fail with "connection refused"/"connect timed
     *  out" while the browser and the phone on the same network work fine —
     *  the last-resort pass on this client rescues exactly that case. */
    private val noProxyClient: OkHttpClient by lazy {
        applyConscryptTls(OkHttpClient.Builder())
            .proxy(java.net.Proxy.NO_PROXY)
            .dns(HikariDns)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Final rescue client: Conscrypt TLS pinned to TLS 1.2, no proxy. Some
     *  Windows machines/networks fail every TLS 1.3 handshake inside
     *  Conscrypt ("Read error: Failure in SSL library, usually a protocol
     *  error") while TLS 1.2 goes through — and a broken system proxy can
     *  compound it. Still Conscrypt (never JDK TLS — see applyConscryptTls:
     *  the JDK SSL stack's lazy class-init can kill the JVM once Conscrypt is
     *  the default provider). */
    private val rescueClient: OkHttpClient by lazy {
        try {
            val tls12 = okhttp3.ConnectionSpec.Builder(okhttp3.ConnectionSpec.MODERN_TLS)
                .tlsVersions(okhttp3.TlsVersion.TLS_1_2)
                .build()
            applyConscryptTls(OkHttpClient.Builder())
                .proxy(java.net.Proxy.NO_PROXY)
                .connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT, tls12))
                .dns(HikariDns)
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        } catch (t: Throwable) {
            noProxyClient
        }
    }

    fun fetchStringRobust(url: String, headers: Map<String, String> = emptyMap()): Result<String> {
        val started = System.currentTimeMillis()
        val origins = MirrorMemory.order(url, originVariants(url))
        val mirrors = MirrorMemory.order(url, mirrorVariants(url))
        val walk = Walk()
        fun attempt(c: OkHttpClient, u: String, throughProxy: Boolean): String? {
            val r = getStringStrictOn(c, u, headers)
            if (r.isSuccess) {
                MirrorMemory.markAlive(u)
                MirrorMemory.rememberWinner(url, u)
            } else {
                val cause = r.exceptionOrNull()
                walk.record(u, cause, throughProxy)
                if (isHostFailure(cause)) MirrorMemory.markDead(u)
            }
            return r.getOrNull()
        }
        // One wave of passes over EVERY candidate at once, so the answer is the
        // fastest host on the first stack that works — not the sum of the dead
        // ones, and not "the repo is unreachable" just because this machine
        // cannot do TLS 1.3 through its network filter (see [passes]).
        //
        // Within a pass the AUTHORITATIVE URLs are raced first ([originVariants])
        // and the CDN/proxy mirrors only if those all fail: racing them together
        // lets a stale CDN copy of a branch file answer first, which is how a
        // repo add shows an old extension list and an install fetches an old jar.
        //
        // [Walk] is what keeps a hopeless network from costing a minute: a host
        // whose certificate was rejected is not asked again on the next pass,
        // and a pass where every failure was a certificate rejection ends the
        // ladder outright (see [Walk.hopeless]).
        for (pass in passes()) {
            walk.startPass(pass.noProxy)
            for ((wave, kind) in listOf(
                walk.live(origins, pass.noProxy) to "origin",
                walk.live(mirrors, pass.noProxy) to "mirror",
            )) {
                if (wave.isEmpty()) continue
                val window = if (kind == "origin") ORIGIN_WINDOW_MS else MIRROR_WINDOW_MS
                val got = raceFirst(wave, FANOUT, window) { attempt(passClient(pass), it, pass.noProxy) }
                if (got != null) {
                    System.err.println(
                        "net-ladder: served by pass '" + pass.key + "' (" + kind + " " + got.first + ") in " +
                            (System.currentTimeMillis() - started) + "ms",
                    )
                    lastWinPassKey = pass.key
                    NetMemory.remember(pass)
                    return Result.success(got.second)
                }
            }
            if (walk.hopeless()) {
                System.err.println("net-ladder: every candidate failed certificate verification — no other pass can change a trust decision")
                break
            }
            System.err.println("net-ladder: pass '" + pass.key + "' exhausted every candidate")
        }
        // The full-timeout client, for hosts that are merely slow rather than
        // dead (a throttled link can need longer than the probe) — on the
        // compatibility stack, since every normal attempt has now failed. Hosts
        // that already failed for a stack-independent reason are skipped.
        val left = walk.live(origins + mirrors, false)
        if (left.isNotEmpty()) {
            raceFirst(left, FANOUT, 15_000L) { u ->
                runCatching { getStringStrictOn(client, u, headers).getOrNull() }
                    .getOrNull()?.also { MirrorMemory.markAlive(u) }
            }?.let { return Result.success(it.second) }
        }
        // The JVM's TLS stack is out of ideas; the machine's own stack still has
        // a different trust store and a different TLS implementation (see
        // [osHttpClient]).
        if (osHttpClient() != null) {
            raceFirst(origins + mirrors, OS_PARALLELISM, OS_WINDOW_MS) { osFetchString(it) }?.let {
                System.err.println("os-http: served " + it.first + " for $url")
                return Result.success(it.second)
            }
        }
        System.err.println("net-fetch: gave up on $url after " + (System.currentTimeMillis() - started) + "ms")
        return Result.failure(Exception(summariseFailures(walk.failures, walk.hostsWithFailures())))
    }

    /**
     * One "try every candidate URL until one answers" walk.
     *
     * Two rules turn a blocked network from a minute-long spinner into a few
     * seconds of honest failure:
     *
     *  - a host that failed for a reason **no other TLS stack can change** — a
     *    rejected certificate, a refused connection, a dead DNS name, an HTTP
     *    error — is never tried again in a later pass *on the same route*;
     *  - a pass in which EVERY failure was a certificate rejection ends the walk
     *    outright: the TLS-1.2 pass presents the same chain to the same trust
     *    store, so it cannot make that trust store trust it.
     *
     * The route matters and is therefore part of the key: going through the
     * system proxy and going direct are genuinely different paths (a leftover
     * OS proxy is exactly the desktop misconfiguration the no-proxy pass
     * rescues, and a proxy can present its own chain), so a host failed through
     * the proxy is still asked again directly.
     */
    /**
     * The third-party copies of a file — CDN edges and proxy frontdoors, exactly
     * the hostnames [mirrorVariants] builds. Their TLS is their own: a broken
     * certificate there says nothing about the file or its real host, which is
     * why the ladder does not treat their failures as the file's fate.
     */
    private val MIRROR_HOSTS = listOf(
        "jsdelivr.net", "githack.com", "b-cdn.net", "ghfast.top",
        "ghproxy.net", "gh-proxy.com", "ghproxy.cc", "llkk.cc",
        "statically.io", "gitmirror.com", "moeyy.xyz",
    )

    internal fun isMirrorHost(url: String): Boolean {
        val host = runCatching { java.net.URI(url.trim()).host ?: "" }.getOrDefault("").lowercase()
        if (host.isBlank()) return false
        return MIRROR_HOSTS.any { host == it || host.endsWith("." + it) }
    }

    private class Walk {

        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

        /** host → the last thing it said, for the user-facing summary. */
        private val hostFailures = java.util.concurrent.ConcurrentHashMap<String, Throwable>()

        fun hostsWithFailures(): List<String> = hostFailures.keys.sorted()

        /** Route-keyed: "url|p" through the system proxy, "url|d" direct. */
        private val spent = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        /** Failures of THIS pass, split by who answered: an authoritative URL of
         *  the file, or one of the third-party copies of it. See [hopeless]. */
        private val originPassFailures = java.util.concurrent.ConcurrentHashMap<String, Throwable>()
        private val mirrorPassFailures = java.util.concurrent.ConcurrentHashMap<String, Throwable>()

        private var passFailures = java.util.concurrent.ConcurrentHashMap<String, Throwable>()

        private var viaProxy = false

        fun startPass(throughProxy: Boolean) {
            passFailures = java.util.concurrent.ConcurrentHashMap()
            originPassFailures.clear()
            mirrorPassFailures.clear()
            viaProxy = throughProxy
        }

        /** The candidates still worth asking on this pass. */
        fun live(urls: List<String>, throughProxy: Boolean): List<String> =
            urls.filter { !spent.containsKey(route(it, throughProxy)) }

        fun record(url: String, cause: Throwable?, throughProxy: Boolean) {
            if (cause == null) return
            failures += cause
            runCatching { MirrorMemory.hostOf(url).let { if (it.isNotBlank()) hostFailures[it] = cause } }
            val key = route(url, throughProxy)
            passFailures[key] = cause
            // Split by who answered: the file's own host or a third-party copy of
            // it. Only the former says anything about the file being reachable.
            if (Http.isMirrorHost(url)) mirrorPassFailures[key] = cause else originPassFailures[key] = cause
            if (!Http.isTlsStackFailure(cause)) spent[key] = true
        }

        /** True when this pass proved that no other stack can get through: every
         *  candidate failed CERTIFICATE verification.
         *
         *  A trust decision does not depend on the TLS version and cannot be
         *  retried away, so the remaining passes would ask the same hosts the
         *  same question and get the same answer — that re-asking is what made
         *  one rejected certificate into a minute of "Fetching repo…".
         *
         *  The route matters: through a proxy the chain may be the proxy's own,
         *  so a DIRECT pass is still worth its (bounded) time — but when the OS
         *  has no proxy configured the "proxy" passes ARE direct, and waiting
         *  for one of them would only delay the error by a whole pass. */
        fun hopeless(): Boolean {
            // Only the file's OWN hosts can prove the file is unreachable: a
            // mirror is a third party whose TLS can be broken (and often is)
            // while the file itself is perfectly available — which is exactly
            // how "this machine's certificate store doesn't trust the site's CA
            // chain, asked: ghproxy.cc" became the reason a GitHub repo would
            // not load.
            if (originPassFailures.isEmpty()) return false
            if (!originPassFailures.values.all { Http.isCertTrustFailure(it) }) return false
            if (mirrorPassFailures.values.any { !Http.isCertTrustFailure(it) }) return false
            return !viaProxy || !Http.systemProxyInUse()
        }

        private fun route(url: String, throughProxy: Boolean): String =
            url + (if (throughProxy) "|p" else "|d")
    }

    fun fetchBytesRobust(url: String, headers: Map<String, String> = emptyMap()): ByteArray? {
        val started = System.currentTimeMillis()
        val origins = MirrorMemory.order(url, originVariants(url))
        val mirrors = MirrorMemory.order(url, mirrorVariants(url))
        val walk = Walk()
        fun attempt(c: OkHttpClient, u: String, throughProxy: Boolean): ByteArray? {
            val r = runCatching { getOn(c, u, headers) }
            val b = r.getOrNull()?.use { if (it.isSuccessful) it.body?.bytes() else null }
            if (b == null) {
                walk.record(u, r.exceptionOrNull() ?: Exception("HTTP error for " + u), throughProxy)
                MirrorMemory.markDead(u)
                return null
            }
            if (isWebPage(b)) {
                // A frontdoor answering 200 with an HTML error page is the one
                // way a mirror can "win" a race with the wrong answer — a
                // 122-byte stub beats an 88 KB extension every time. See
                // [mirrorVariants].
                System.err.println("net: ignoring a web page served for $u")
                walk.record(u, Exception("an HTML error page, not the file"), throughProxy)
                MirrorMemory.markDead(u)
                return null
            }
            MirrorMemory.markAlive(u)
            MirrorMemory.rememberWinner(url, u)
            return b
        }
        // Authoritative URLs first, mirrors after — a CS3/JAR downloaded from a
        // stale CDN copy installs an extension that cannot load. Hosts that
        // already failed for a stack-independent reason are not re-asked on the
        // next pass (see [Walk]).
        for (pass in passes()) {
            walk.startPass(pass.noProxy)
            raceFirst(walk.live(origins, pass.noProxy), FANOUT, ORIGIN_WINDOW_MS) { attempt(passClient(pass), it, pass.noProxy) }
                ?.let { return it.second }
            raceFirst(walk.live(mirrors, pass.noProxy), FANOUT, MIRROR_WINDOW_MS) { attempt(passClient(pass), it, pass.noProxy) }
                ?.let { return it.second }
            if (walk.hopeless()) {
                System.err.println("net-ladder(bytes): every candidate failed certificate verification — stopping")
                break
            }
        }
        if (systemProxyInUse()) {
            raceFirst(walk.live(origins + mirrors, true), FANOUT, 20_000L) { attempt(noProxyClient, it, true) }?.let { return it.second }
        }
        // The JVM's TLS stack is out of ideas; the machine's own stack still has
        // a different trust store and a different TLS implementation (see
        // [osHttpClient]).
        if (osHttpClient() != null) {
            raceFirst(origins + mirrors, OS_PARALLELISM, OS_WINDOW_MS) { osFetchBytes(it) }?.let {
                System.err.println("os-http: served " + it.first + " for $url")
                return it.second
            }
        }
        System.err.println("net-bytes: gave up on $url after " + (System.currentTimeMillis() - started) + "ms")
        return null
    }

    /**
     * Streams a download to [dest], walking the mirror chain from
     * [urlVariants]. [onAttempt] reports every mirror that was tried and why
     * it failed (ok=true on the one that succeeded) so the UI can show the
     * REAL cause instead of a generic "check your connection".
     *
     * A final rescue pass repeats every mirror with the system proxy
     * bypassed ([noProxyClient]) — the classic "browser works, JVM doesn't"
     * desktop misconfiguration.
     */
    fun downloadToRobust(
        url: String,
        dest: java.io.File,
        headers: Map<String, String> = emptyMap(),
        onProgress: ((Long, Long) -> Unit)? = null,
        onAttempt: ((url: String, ok: Boolean, reason: String?) -> Unit)? = null,
    ): Boolean {
        // Authoritative URLs first, CDN/proxy mirrors second (see
        // [mirrorVariants]): an extension install must never "succeed" with a
        // stale or wrong file just because a mirror answered first.
        val origins = MirrorMemory.order(url, originVariants(url))
        val ordered = origins + MirrorMemory.order(url, mirrorVariants(url))
        // Overall budget: on a black-hole network every mirror costs a full
        // connect timeout; bound the walk so the UI doesn't sit for minutes
        // (the Android app caps installs at 90s for the same reason).
        val deadline = System.currentTimeMillis() + DOWNLOAD_BUDGET_MS
        // A release asset's "authoritative" list is github.com on its own — there
        // is no raw/CDN family to fall back on — so a network that filters
        // github.com has nothing to succeed with until the frontdoors are
        // brought in. Giving that single host the full origin window meant every
        // install from a release-asset repo (the official Hikari one) waited out
        // the window before a single proxy was tried. See RELEASE_ORIGIN_FIRST_MS.
        val releaseAsset = parseGhTarget(normalizeDriveUrl(url.trim())) == null && isGithubUrl(url)
        val originWindow = if (releaseAsset) RELEASE_ORIGIN_FIRST_MS else ORIGIN_FIRST_MS
        sweepParts(dest)
        // A certificate rejection is not worth walking the rest of the ladder
        // for: the TLS-1.2 pass, the direct route and the mirrors all present
        // the same chain to the same trust store. Counting it here is what kept
        // "install" from sitting on a spinner for a minute on a machine whose
        // store cannot verify the host (see [trustStore] and
        // [TlsTrustSelfTest]).
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val certRejections = java.util.concurrent.atomic.AtomicInteger()
        var certRejected = false
        val counting: (String, Boolean, String?) -> Unit = { u, ok, why ->
            if (!ok) {
                attempts.incrementAndGet()
                if (why != null && why.contains(CERT_TRUST_MESSAGE)) certRejections.incrementAndGet()
                if (attempts.get() > 2 && certRejections.get() == attempts.get()) certRejected = true
            }
            onAttempt?.invoke(u, ok, why)
        }
        if (raceDownload(origins, dest, headers, onProgress, counting, deadline, null, originWindow, url)) return true
        if (certRejected) {
            System.err.println("downloadToRobust: every candidate was rejected by the certificate store — stopping")
            return false
        }
        // Everything in one wave from here: the origins get another chance
        // ALONGSIDE the mirrors, with the rest of the budget. Waiting the whole
        // origin window before a single mirror was tried is what made a
        // blocked-but-not-dead origin (the common case on a filtered network,
        // where the connect hangs instead of failing) turn every install into a
        // one-minute spinner. A slow-but-working origin is not lost by that —
        // it is in this wave too, and this wave is the long one.
        if (raceDownload(ordered, dest, headers, onProgress, counting, deadline, null, MAIN_RACE_MS, url)) return true
        if (certRejected) {
            System.err.println("downloadToRobust: every candidate was rejected by the certificate store — stopping")
            return false
        }
        // Rescue passes with the system proxy bypassed ([noProxyClient]) and then
        // with TLS pinned to 1.2 ([rescueClient]) — the two desktop
        // misconfigurations where every request fails while the browser works.
        if (systemProxyInUse() &&
            raceDownload(ordered, dest, headers, onProgress, counting, deadline, noProxyClient, RESCUE_RACE_MS, url, "(no proxy)")
        ) return true
        if (raceDownload(ordered, dest, headers, onProgress, counting, deadline, rescueClient, RESCUE_RACE_MS, url, "(TLS 1.2)")) return true
        // Every in-JVM stack has now failed. Hand the SAME candidate list to the
        // OS's own HTTP client — Schannel + the Windows certificate store, i.e.
        // the browser's TLS stack, running in a process of its own (see
        // [osHttpClient]). This is the only path that can turn "the browser
        // loads it, the app does not" into a finished install.
        if (raceDownloadViaOs(ordered, dest, counting, maxOf(20_000L, deadline - System.currentTimeMillis()))) return true
        System.err.println("downloadToRobust failed for $url")
        return false
    }

    /** How long one wave of raced mirrors may take before the next wave starts. */
    private const val MAIN_RACE_MS = 45_000L
    private const val RESCUE_RACE_MS = 20_000L

    /**
     * How long the AUTHORITATIVE hosts alone get before the mirrors are brought
     * in.
     *
     * This exists because of what the user sees: on a network that filters
     * GitHub (the mirrors in [mirrorVariants] exist precisely for that case) the
     * origin does not usually fail fast — it hangs — so waiting a full 45 s
     * window on it, and only then racing the mirrors, is a one-minute install
     * for a file a proxy frontdoor would have served in a second. Twelve seconds
     * is comfortably more than an origin needs to prove it is usable (bytes on
     * the wire, or a connect that failed outright) and it costs a working origin
     * nothing, because the second wave includes the origins again.
     */
    private const val ORIGIN_FIRST_MS = 12_000L

    /**
     * The origin window for a RELEASE ASSET, which is much shorter for a
     * structural reason: there is only one authoritative host for those bytes
     * (github.com), so "wait for the origin to prove itself" costs the whole
     * window and buys nothing when that host is filtered — the frontdoors behind
     * it are the only candidates that can answer. Four seconds is still ample for
     * github.com to start streaming a `.jar` on a network where it works (the
     * origin stays in the long wave behind this, so a slow-but-working host is
     * not lost).
     */
    private const val RELEASE_ORIGIN_FIRST_MS = 4_000L

    /** True when what landed on disk is really an HTML page (a mirror's error
     *  page) rather than the asset — read back from the file, so it also covers
     *  a body that was compressed on the wire. */
    private fun looksLikeHtmlFile(f: java.io.File): Boolean = runCatching {
        if (!f.isFile || f.length() <= 0L) return@runCatching true
        val len = minOf(f.length(), 512L).toInt()
        val head = ByteArray(len)
        java.io.FileInputStream(f).use { it.read(head) }
        isWebPage(head)
    }.getOrDefault(false)

    /** Part-file name for one raced attempt at [dest]. */
    private fun partOf(dest: java.io.File, key: String): java.io.File =
        java.io.File(dest.parentFile, dest.name + ".part-" + Integer.toHexString(key.hashCode()))

    /** Removes leftovers of an interrupted download (a part file from a killed
     *  attempt would otherwise sit in the extensions folder forever). */
    private fun sweepParts(dest: java.io.File) {
        runCatching {
            dest.parentFile?.listFiles()?.forEach { f ->
                if (f.name.startsWith(dest.name + ".part-")) f.delete()
            }
        }
    }

    /**
     * Downloads [dest] from whichever mirror answers first.
     *
     * Every mirror of the same file is raced instead of being tried one after
     * another, so the cost of a blocked or throttled host is paid in parallel
     * rather than in series — the single biggest reason an extension install
     * could take a minute for a 100KB file. Each attempt streams into its OWN
     * part file (two threads must never share an output stream); the first one
     * to finish is moved into place and the rest are cleaned up.
     */
    private fun raceDownload(
        variants: List<String>,
        dest: java.io.File,
        headers: Map<String, String>,
        onProgress: ((Long, Long) -> Unit)?,
        onAttempt: ((String, Boolean, String?) -> Unit)?,
        deadline: Long,
        via: OkHttpClient?,
        windowMs: Long,
        rememberFor: String? = null,
        note: String = "",
    ): Boolean {
        if (System.currentTimeMillis() > deadline) return false
        // Every mirror starts at once. Taking only the first eight and racing
        // them four at a time meant the working mirror waited behind the blocked
        // ones for a whole connect timeout — see [FANOUT].
        val wave = variants
        // Never let one wave run past the overall budget: the second and third
        // waves used to be allowed their FULL window even with seconds left on
        // the clock, which is how a failed install could still take two minutes.
        val window = minOf(windowMs, maxOf(2_000L, deadline - System.currentTimeMillis()))
        val staging = java.util.concurrent.ConcurrentHashMap<String, java.io.File>()
        val winner = raceFirst(wave, FANOUT, window) { u ->
            if (System.currentTimeMillis() > deadline) return@raceFirst null
            val part = partOf(dest, u)
            staging[u] = part
            val reason = downloadToReason(u, part, headers, onProgress, via)
            if (reason == null && !looksLikeHtmlFile(part)) {
                MirrorMemory.markAlive(u)
                part
            } else {
                // A frontdoor that answers 200 with a small HTML page "wins" a
                // race every time (100 bytes beats 100 KB), so a body that is
                // really a web page is a FAILED download, not a fast one.
                val why = reason ?: "an HTML error page, not the file"
                // A host that refused, timed out or broke TLS is skipped for the
                // next ten minutes (see [MirrorMemory]) — the difference between
                // one slow install and every install being slow.
                val r = why.lowercase()
                if (r.contains("timed out") || r.contains("timeout") || r.contains("refused") ||
                    r.contains("ssl") || r.contains("unreachable") || r.contains("protocol error") ||
                    reason == null
                ) {
                    MirrorMemory.markDead(u)
                }
                runCatching { part.delete() }
                onAttempt?.invoke(u, false, if (note.isBlank()) why else "$why $note")
                null
            }
        }
        if (winner == null) {
            staging.values.forEach { runCatching { it.delete() } }
            return false
        }
        val (from, part) = winner
        val ok = runCatching {
            dest.delete()
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            dest.isFile && dest.length() > 0
        }.getOrDefault(false)
        if (!ok) {
            onAttempt?.invoke(from, false, "couldn't write ${dest.name}")
            return false
        }
        onAttempt?.invoke(from, true, note.ifBlank { null })
        if (rememberFor != null) MirrorMemory.rememberWinner(rememberFor, from)
        // Losers may still be mid-write: remove what's already on disk, and let
        // a sweep a minute later catch anything that lands after this returns.
        staging.values.forEach { p -> if (p.absolutePath != part.absolutePath) runCatching { p.delete() } }
        val sweeper = Thread {
            runCatching { Thread.sleep(90_000L) }
            sweepParts(dest)
        }
        sweeper.isDaemon = true
        sweeper.name = "hikari-part-sweep"
        sweeper.start()
        return true
    }

    // ── the OS's own HTTP stack, as a last resort ───────────────────────────

    /**
     * The OS-native HTTP client, when this machine has one.
     *
     * Everything above this runs inside the JVM, on Conscrypt + our own trust
     * store. There is a class of machine where that cannot work at all — a
     * TLS-inspecting filter, a driver-level firewall, a certificate the JVM's
     * stack refuses — while the user's BROWSER loads the very same URL. For
     * those, `curl.exe` (Windows 10 1803+) and PowerShell are the honest answer:
     * they use Schannel and the Windows certificate store, which is literally
     * the stack the browser uses, and they are separate PROCESSES, so nothing
     * about this JVM's TLS, trust store, proxy handling or DNS is involved.
     *
     * It is only ever reached after every in-JVM attempt has failed, so it costs
     * the healthy path nothing.
     */
    @Volatile private var osHttpTool: String? = null

    @Volatile private var osHttpProbed = false

    /** The OS client's path (for the logs and the self-tests), or null. */
    fun osHttpClient(): String? {
        if (osHttpProbed) return osHttpTool
        synchronized(this) {
            if (osHttpProbed) return osHttpTool
            val wind = System.getProperty("os.name").orEmpty().lowercase().contains("win")
            // Windows is what this pass is FOR (Schannel + the Windows cert
            // store). On every other platform plain `curl` is used when it
            // happens to be installed, so the plumbing — process, timeout,
            // HTML-page rejection, rename into place — is exercised by the CI
            // run on the test machine instead of first being tried on a user's
            // filtered Windows box. If there is no curl, this is simply null and
            // nothing changes.
            osHttpTool = if (wind) {
                findOnPath("curl.exe") ?: findOnPath("curl") ?: findOnPath("powershell.exe")
            } else {
                findOnPath("curl")
            }
            osHttpProbed = true
            System.err.println("os-http: " + (osHttpTool ?: "none on this machine"))
            return osHttpTool
        }
    }

    /** A program on PATH (System32 first: the msys/cygwin `curl` that ships with
     *  Git for Windows carries its own CA bundle — a third trust decision again,
     *  and not the browser's). */
    private fun findOnPath(exe: String): String? {
        val roots = ArrayList<String>()
        System.getenv("SystemRoot")?.let { roots.add(java.io.File(it, "System32").absolutePath) }
        System.getenv("PATH")?.split(java.io.File.pathSeparator)?.let { roots.addAll(it) }
        for (d in roots) {
            if (d.isBlank()) continue
            val f = runCatching { java.io.File(d, exe) }.getOrNull() ?: continue
            if (f.isFile) return f.absolutePath
        }
        return null
    }

    /** How long one OS-client attempt may take. curl is a ~50ms process, so a
     *  window this size is only ever spent on a host that is black-holing. */
    private const val OS_WINDOW_MS = 25_000L

    /** How many candidate URLs are handed to the OS client at once. */
    private const val OS_PARALLELISM = 4

    /**
     * Downloads [url] to [dest] with the OS's own HTTP client.
     *
     * Returns null on success, or a short reason on failure — the same contract
     * as [downloadToReason], so callers can report it like any other attempt.
     */
    fun osFetchToFile(url: String, dest: java.io.File): String? {
        val tool = osHttpClient() ?: return "no OS HTTP client on this machine"
        val tmp = java.io.File(dest.parentFile, dest.name + ".osdl")
        runCatching { tmp.delete() }
        var proc: Process? = null
        return try {
            dest.parentFile?.mkdirs()
            val cmd = if (tool.lowercase().endsWith("powershell.exe")) {
                // PowerShell's own WebClient — Schannel underneath, and no
                // `curl` requirements at all. TLS 1.2 is set explicitly: the
                // PowerShell default on some machines is still SSL3/TLS1.0, and
                // GitHub refuses those outright, which would look like the very
                // failure this pass exists to rescue.
                val safeUrl = url.replace("'", "''")
                val safeOut = tmp.absolutePath.replace("'", "''")
                listOf(
                    tool, "-NoProfile", "-NonInteractive", "-Command",
                    "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; " +
                        "\$ProgressPreference = 'SilentlyContinue'; " +
                        "\$wc = New-Object Net.WebClient; " +
                        "\$wc.Headers.Add('User-Agent', '" + UA.replace("'", "''") + "'); " +
                        "\$wc.DownloadFile('" + safeUrl + "', '" + safeOut + "')",
                )
            } else {
                listOf(
                    tool, "-fsSL", "--ssl-no-revoke", "--connect-timeout", "8",
                    "--max-time", "90", "-A", UA, "-o", tmp.absolutePath, url,
                )
            }
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            proc = p
            // Drain on its own thread: a full pipe buffer would deadlock the
            // child, but draining *inline* would block this thread for as long
            // as the child lives — i.e. the timeout below would never be reached.
            Thread { runCatching { p.inputStream.readBytes() } }.apply {
                isDaemon = true
                name = "hikari-os-http-drain"
                start()
            }
            if (!p.waitFor(110, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return "timed out"
            }
            if (p.exitValue() != 0) return "exit " + p.exitValue()
            if (!tmp.isFile || tmp.length() <= 0L) return "no bytes came back"
            if (looksLikeHtmlFile(tmp)) {
                tmp.delete()
                return "an HTML error page, not the file"
            }
            dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            if (dest.isFile && dest.length() > 0L) null else "couldn't write " + dest.name
        } catch (e: InterruptedException) {
            // The race moved on; don't leave the child behind holding a socket.
            runCatching { proc?.destroyForcibly() }
            Thread.currentThread().interrupt()
            "interrupted"
        } catch (t: Throwable) {
            humanMessage(t)
        }
    }

    /** [osFetchToFile] into a temp file, returning the bytes (web pages
     *  rejected, exactly like [fetchBytesRobust]). */
    fun osFetchBytes(url: String): ByteArray? {
        if (osHttpClient() == null) return null
        val tmp = runCatching {
            java.io.File.createTempFile("hikari-osdl", ".bin").apply { deleteOnExit() }
        }.getOrNull() ?: return null
        return try {
            val why = osFetchToFile(url, tmp)
            if (why != null) {
                System.err.println("os-http: $url — $why")
                return null
            }
            val bytes = runCatching { tmp.readBytes() }.getOrNull()
            if (bytes == null || bytes.isEmpty() || isWebPage(bytes)) null else bytes
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /** [osFetchToFile] into a temp file, returning the text. */
    fun osFetchString(url: String): String? {
        val bytes = osFetchBytes(url) ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * The same "race every candidate, first one to land wins" walk as
     * [raceDownload], run through the OS's HTTP client instead of the JVM's TLS
     * stack (see [osHttpClient]). Reached only when everything above failed.
     */
    private fun raceDownloadViaOs(
        variants: List<String>,
        dest: java.io.File,
        onAttempt: ((String, Boolean, String?) -> Unit)?,
        budgetMs: Long,
    ): Boolean {
        if (osHttpClient() == null || variants.isEmpty()) return false
        val window = minOf(OS_WINDOW_MS, maxOf(4_000L, budgetMs))
        val winner = raceFirst(variants, OS_PARALLELISM, window) { u ->
            val part = partOf(dest, "os:" + u)
            runCatching { part.delete() }
            if (osFetchToFile(u, part) == null && part.isFile && part.length() > 0L) part else null
        } ?: return false
        val (from, part) = winner
        val ok = runCatching {
            dest.delete()
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            dest.isFile && dest.length() > 0L
        }.getOrDefault(false)
        if (ok) {
            System.err.println("os-http: the download was served by " + from)
            onAttempt?.invoke(from, true, "the OS HTTP client (Windows' own TLS stack)")
        }
        return ok
    }

    /**
     * Turns Google Drive share/download URLs into the direct-download form that
     * serves raw file bytes (no virus-scan HTML page). Handles:
     *   drive.google.com/uc?export=download&id=X
     *   drive.google.com/open?id=X
     *   drive.google.com/file/d/<id>/view
     */
    fun normalizeDriveUrl(url: String): String {
        val u = url.trim().trim('"', '\'')
        if (u.isBlank()) return u
        val id = Regex("""drive\.google\.com/(?:uc|open)\?(?:.*&)?id=([^&\s"']+)""")
            .find(u)?.groupValues?.get(1)
            ?: Regex("""drive\.google\.com/file/d/([^/\s"']+)""")
                .find(u)?.groupValues?.get(1)
        return if (id != null) {
            "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"
        } else u
    }
    // ── the file a repo's index names but the repository does not serve ─────
    //
    // A repo publishes two things written by two different jobs: the index
    // (`index.min.json`, `dist/plugins.json`, a `plugins` array) and the files
    // themselves. They drift apart constantly, and every drift shows up as an
    // install that cannot work:
    //
    //  * an index that keeps an entry after its build stopped publishing it —
    //    the SkyStream "Stars" repo cleaned up six of its eleven plugins
    //    (`dev.akash.stars.anichi` and friends) and left the listings alone, so
    //    every install of them ended in "Download failed — check the URL";
    //  * a build that changed a file name without the index following —
    //    `aniyomiorg/aniyomi-extensions` publishes `apk/aniyomi-all.jellyfin-
    //    v14.17.apk` while its own index says `apk/anime-all.jellyfin-v14.17
    //    .apk`, and the OsmerGalarragaTKD repo lists `-v14.28-release.apk` for
    //    files that are published as `-v14.28.apk`;
    //  * a file that moved folder (dist/ → build/, apk/ → repo/apk/).
    //
    // GitHub can be asked what a repository ACTUALLY contains, so the fix is to
    // ask, match what was asked for against what is there, and use the file
    // that exists. The alternative — reporting every one of those as "check the
    // URL" — sends users hunting for a mistake that is not theirs.

    /** Repository file lists already fetched, per `user/repo@ref`. */
    private val repoTreeCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /**
     * Every blob path in [user]/[repo] at [ref], or null when the repository
     * could not be asked (no network to api.github.com, a rate limit, a private
     * repo). Cached for the process, because an install walks one repo's files
     * repeatedly.
     */
    private fun repoTree(user: String, repo: String, ref: String): List<String>? {
        val key = "$user/$repo@$ref"
        repoTreeCache[key]?.let { return it }
        val api = "https://api.github.com/repos/$user/$repo/git/trees/$ref?recursive=1"
        val text = runCatching { getStringStrict(api).getOrNull() }.getOrNull() ?: return null
        val arr = runCatching { org.json.JSONObject(text).optJSONArray("tree") }.getOrNull() ?: return null
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") != "blob") continue
            val path = o.optString("path")
            if (path.isNotBlank()) out.add(path)
        }
        if (out.isEmpty()) return null
        repoTreeCache[key] = out
        return out
    }

    /**
     * A file name reduced to what identifies the extension it belongs to: no
     * folder, no extension, no version/build suffix, no `aniyomi-`/`anime-`
     * family prefix, no separators.
     *
     * `aniyomi-all.jellyfin-v14.17.apk`, `anime-all.jellyfin-v14.17.apk` and
     * `aniyomi-all.jellyfin-v14.17-release.apk` all reduce to `alljellyfin`,
     * which is exactly the tolerance a repo index needs.
     */
    private fun fileKey(name: String): String {
        var n = name.substringAfterLast('/')
        n = n.replace(Regex("(?i)\\.(apk|sky|js|mjs|json|zip|jar|hiki)$"), "")
        n = n.replace(Regex("(?i)^(aniyomi|anime|aniyomix|animetv|mihon|tachiyomi)[-_.]"), "")
        n = n.replace(Regex("(?i)[-_]v?\\d+([.\\d]*).*$"), "")
        return n.lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    /** The file's extension, lowercased (`apk`, `sky`, `js`…). */
    private fun fileExt(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    /** Roughly the version a file name carries, so the newest of several
     *  matching builds is the one chosen. */
    private fun versionRank(name: String): Long {
        val m = Regex("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?").find(name) ?: return 0L
        val a = m.groupValues.getOrNull(1)?.toLongOrNull() ?: 0L
        val b = m.groupValues.getOrNull(2)?.toLongOrNull() ?: 0L
        val c = m.groupValues.getOrNull(3)?.toLongOrNull() ?: 0L
        return a * 1_000_000L + b * 1_000L + c
    }

    /**
     * What [want] (a path from a repo's index) is called in [paths] (what the
     * repo actually holds), or null when nothing matches.
     *
     * Four tries, most exact first: the same path, the same file name anywhere,
     * the same file key with the same extension (the renamed-build case), and
     * the same file key in the SAME folder (a repo that keeps two builds of one
     * extension side by side). When several files still match — a repo that
     * keeps `-v14.27.apk` next to `-v14.28.apk` — the newest wins.
     */
    private fun matchRepoPath(paths: List<String>, want: String): String? {
        val wanted = want.trimStart('/')
        val wantedLower = wanted.lowercase()
        paths.firstOrNull { it.lowercase() == wantedLower }?.let { return it }
        val wantedName = wanted.substringAfterLast('/').lowercase()
        val wantedDir = wanted.substringBeforeLast('/', "")
        val sameName = paths.filter { it.substringAfterLast('/').lowercase() == wantedName }
        if (sameName.size == 1) return sameName.first()
        val key = fileKey(wanted)
        if (key.isBlank()) return null
        val ext = fileExt(wanted)
        val sameKey = paths.filter { fileKey(it) == key && fileExt(it) == ext }
        if (sameKey.isEmpty()) return null
        val sameDir = sameKey.filter { it.substringBeforeLast('/', "").lowercase() == wantedDir.lowercase() }
        val pool = if (sameDir.isNotEmpty()) sameDir else sameKey
        if (pool.size == 1) return pool.first()
        return pool.maxByOrNull { versionRank(it.substringAfterLast('/')) }
    }

    /**
     * The URL to fetch a GitHub file from when the URL a repo's index gave does
     * not serve it: the repository is asked what it holds, the requested name is
     * matched against that, and the matching file's canonical raw URL comes
     * back. Null when the file is not in the repository at all (it was removed
     * upstream — the caller says so), or when GitHub could not be asked.
     */
    fun repairGithubFileUrl(url: String): String? {
        val gh = parseGhTarget(normalizeDriveUrl(url.trim())) ?: return null
        if (gh.path.isBlank()) return null
        val paths = repoTree(gh.user, gh.repo, gh.ref) ?: return null
        val hit = matchRepoPath(paths, gh.path) ?: return null
        if (hit == gh.path) return null
        System.err.println("net: repaired $url -> $hit")
        return ghRawUrl(gh.copy(path = hit))
    }

    /** True when the repository really does not hold the file [url] names (as
     *  opposed to GitHub not answering). */
    fun fileGoneFromRepo(url: String): Boolean {
        val gh = parseGhTarget(normalizeDriveUrl(url.trim())) ?: return false
        if (gh.path.isBlank()) return false
        val paths = repoTree(gh.user, gh.repo, gh.ref) ?: return false
        return matchRepoPath(paths, gh.path) == null
    }

    /**
     * Every file the repository holding [url] publishes, or null when GitHub
     * could not be asked (no network to api.github.com, a rate limit, a private
     * repo).
     *
     * Exists so a self-test can tell "the repair did not work" from "GitHub
     * would not tell us": the two both end in a null from [repairGithubFileUrl],
     * and only one of them is a bug in this app.
     */
    fun repoFileList(url: String): List<String>? {
        val gh = parseGhTarget(normalizeDriveUrl(url.trim())) ?: return null
        if (gh.path.isBlank()) return null
        return repoTree(gh.user, gh.repo, gh.ref)
    }

    /**
     * A plugin file's bytes, with the repairs above applied, plus the reason
     * there are none when there are none.
     *
     * [usedUrl] is where the bytes actually came from (a repaired URL when the
     * index was stale), so a caller can say so; [error] is a sentence the UI can
     * show as-is. The old behaviour — `fetchBytesRobust(url) ?: "Download
     * failed — check the URL"` — is what made a deleted-upstream plugin and an
     * unreachable host look identical.
     */
    class PluginDownload(val bytes: ByteArray?, val usedUrl: String, val error: String?)

    fun downloadPluginFile(url: String, headers: Map<String, String> = emptyMap()): PluginDownload {
        val direct = runCatching { fetchBytesRobust(url, headers) }.getOrNull()
        if (direct != null && direct.isNotEmpty()) return PluginDownload(direct, url, null)
        val repaired = runCatching { repairGithubFileUrl(url) }.getOrNull()
        if (repaired != null) {
            val bytes = runCatching { fetchBytesRobust(repaired, headers) }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                return PluginDownload(bytes, repaired, "the file had moved in its repository — fetched from $repaired")
            }
        }
        val error = when {
            fileGoneFromRepo(url) ->
                "the repository no longer holds this file (its index still lists it) — the extension was removed upstream"
            else -> "Download failed — check the URL"
        }
        System.err.println("net-plugin: gave up on $url ($error)")
        return PluginDownload(null, url, error)
    }
}

/**
 * DNS-over-HTTPS resolver used by [HikariDns] and the mpv proxy
 * ([desktop.player.LocalProxy]). Queries Cloudflare then Google over HTTPS —
 * the same "Secure DNS" a desktop browser uses — so hosts that the OS resolver
 * filters (a common ISP/border DNS block) still resolve to their real IPs. The
 * result is cached per-host for 60s.
 */
object DoH {

    // Own OkHttp client on Conscrypt TLS. The JDK's java.net.http client runs
    // on sun.security.ssl, whose class-init fails fatally on Windows
    // (NoClassDefFoundError: SSLSessionImpl) once Conscrypt is installed — so
    // even DNS-over-HTTPS must stay on Conscrypt. Uses the SYSTEM resolver,
    // not [HikariDns] (that would recurse back into DoH).
    private val client: OkHttpClient by lazy {
        Http.applyConscryptTls(OkHttpClient.Builder())
            .proxySelector(java.net.ProxySelector.getDefault())
            .followRedirects(true)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /**
     * DoH endpoints, in two deliberate flavours.
     *
     * IP-literal endpoints first (`1.1.1.1`, `8.8.8.8`, `9.9.9.9:5053`,
     * `1.0.0.1`): they need no name resolution at all, which makes them the
     * only endpoints that can rescue the case DoH exists for here — an ISP
     * resolver that is filtered, hijacked, or unreachable. A named endpoint
     * (`cloudflare-dns.com`) has to be resolved by the very resolver being
     * worked around, so it is a useful fallback but a useless rescue. The
     * regional resolvers (AliDNS, DNSPod, AdGuard) are included for networks
     * where the global ones are the blocked ones.
     */
    private val ENDPOINTS = listOf(
        "https://1.1.1.1/dns-query",
        "https://8.8.8.8/resolve",
        "https://9.9.9.9:5053/dns-query",
        "https://1.0.0.1/dns-query",
        "https://cloudflare-dns.com/dns-query",
        "https://dns.google/resolve",
        "https://dns.adguard-dns.com/dns-query",
        "https://doh.alidns.com/dns-query",
        "https://doh.pub/dns-query",
        "https://dns.quad9.net/dns-query",
    )

    private data class Entry(val addrs: List<InetAddress>, val expiry: Long)
    private val cache = ConcurrentHashMap<String, Entry>()
    private const val TTL_MS = 60_000L
    /** A failed lookup is cached for much less long than a good one: an ISP
     *  filter that drops out for a moment must not pin "no such host" for a
     *  whole minute. */
    private const val FAILED_TTL_MS = 10_000L

    /** How many endpoints are queried at once. The IP-literal ones are first in
     *  [ENDPOINTS], so a rescue never waits behind a named endpoint. */
    private const val PARALLEL = 6

    /** Resolves [host], asking several providers in PARALLEL and taking the
     *  first non-empty answer. Serial querying was the wrong shape here: with
     *  ten endpoints and an 8s timeout, one blocked provider per lookup cost
     *  the app eight seconds *per host*, on the way to a timeout that the next
     *  provider would have answered immediately. */
    fun resolve(host: String): List<InetAddress> {
        cache[host]?.let { if (System.currentTimeMillis() < it.expiry) return it.addrs }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(PARALLEL, ENDPOINTS.size)) { r ->
            Thread(r, "hikari-doh").apply { isDaemon = true }
        }
        val answered = java.util.concurrent.LinkedBlockingQueue<List<InetAddress>>()
        ENDPOINTS.take(PARALLEL * 2).forEach { endpoint ->
            pool.execute {
                val addrs = runCatching { query(endpoint, host) }.getOrDefault(emptyList())
                if (addrs.isNotEmpty()) runCatching { answered.put(addrs) }
            }
        }
        var addrs = emptyList<InetAddress>()
        runCatching {
            val first = answered.poll(6, TimeUnit.SECONDS)
            if (first != null) addrs = first
        }
        pool.shutdownNow()
        cache[host] = Entry(
            addrs,
            System.currentTimeMillis() + if (addrs.isEmpty()) FAILED_TTL_MS else TTL_MS,
        )
        return addrs
    }

    /** One JSON DoH query. Returns the A records, or an empty list. */
    private fun query(endpoint: String, host: String): List<InetAddress> {
        val url = "$endpoint?name=${URLEncoder.encode(host, StandardCharsets.UTF_8)}&type=A"
        val req = Request.Builder().url(url)
            .header("accept", "application/dns-json")
            .header("User-Agent", Http.UA)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val obj = org.json.JSONObject(resp.body?.string() ?: "")
            val answers = obj.optJSONArray("Answer") ?: return emptyList()
            val ips = mutableListOf<InetAddress>()
            for (i in 0 until answers.length()) {
                val a = answers.optJSONObject(i) ?: continue
                if (a.optInt("type") == 1) {
                    a.optString("data").takeIf { it.isNotBlank() }?.let { raw ->
                        runCatching { ips.add(InetAddress.getByName(raw)) }
                    }
                }
            }
            return ips
        }
    }

}

/**
 * OkHttp [okhttp3.Dns] that prefers DoH ([DoH]) and falls back to the OS
 * resolver. Attached to the app's shared client, so every catalog/stream HTTP
 * request resolves the way the user's browser does.
 */
object HikariDns : okhttp3.Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val doh = runCatching { DoH.resolve(hostname) }.getOrDefault(emptyList())
        if (doh.isNotEmpty()) return doh
        return try {
            okhttp3.Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            throw e
        }
    }
}
