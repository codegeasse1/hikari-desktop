package com.hikari.app.net

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * CI test for the failure that made every extension repo unloadable on Windows:
 * the app's TLS trust anchors.
 *
 * The bug it guards against: every HTTP client here runs on Conscrypt, and
 * Conscrypt builds its trust store from the JDK's `lib/security/cacerts`
 * snapshot (`Platform.getDefaultCertKeyStore()`). That snapshot no longer
 * contains the legacy Comodo/Sectigo **AAA Certificate Services** root, while
 * GitHub's CDN still serves chains that end at a Sectigo root cross-signed by
 * it — so on a real user's machine the browser opened the repo fine and the app
 * answered
 *
 *     Unacceptable certificate: CN=AAA Certificate Services, O=Comodo CA Limited …
 *
 * ("adding any repo shows this", twice reported), and the ladder's retries made
 * each attempt take up to a minute before it gave up.
 *
 * What this test proves:
 *  1. [Http.trustStore] assembles anchors from the JDK store, the OS store and
 *     `cacerts-extra.pem`, and the AAA root really is among them;
 *  2. a live `raw.githubusercontent.com` fetch — the exact call "add repo"
 *     makes — succeeds through the app's own stack, and quickly;
 *  3. the same fetch through a store built ONLY from the JDK's cacerts is
 *     reported (the evidence that the extra anchor is what fixed it).
 *
 * It needs the network (the point of it is a real TLS handshake), so a runner
 * without internet fails loudly rather than passing silently.
 */
fun main(args: Array<String>) {
    println("TlsTrustSelfTest: start")
    var failures = 0
    fun check(name: String, condition: Boolean, detail: String = "") {
        println(if (condition) "  OK   $name" else "  FAIL $name $detail")
        if (!condition) failures++
    }

    Http.init()

    // ── 1. the trust store itself ───────────────────────────────────────────
    val store = runCatching { Http.trustStore() }.getOrNull()
    println("  " + Http.trustStoreReport())
    println("  extra-roots file: " + Http.extraAnchorCount() + " anchor(s)")
    check("a merged trust store was built", store != null)
    val anchors = Http.trustStoreFingerprints()
    check("it holds a realistic number of anchors", anchors.size > 50, "got " + anchors.size)

    // SHA-256 of "AAA Certificate Services" (Comodo CA Limited) — the root the
    // JDK stopped shipping and GitHub's chains still need.
    val AAA = "d7a7a0fb5d7e2731d771e9484ebcdef71d5f0c3e0a2948782bc83ee0ea699ef4"
    check("the missing Comodo/Sectigo AAA root is among the anchors", AAA in anchors)

    // …and it is REALLY in the file we ship. Asserting only against the merged
    // store is not enough: the first version of this shipped with the extra
    // anchors silently unparsed (the store said "extra=0") and passed anyway,
    // because the Windows store happened to carry the same root.
    check(
        "the shipped extra-roots file is present",
        Http::class.java.getResourceAsStream("/cacerts-extra.pem") != null,
    )
    val extraCount = Http.extraAnchorCount()
    check("its certificates are parsed and loaded", extraCount > 0, "extra=" + extraCount)
    val extraFps = Http.extraAnchorFingerprints()
    check(
        "the AAA root is one of them (not just inherited from the OS store)",
        AAA in extraFps,
        "the file holds " + extraFps.size + " anchor(s)",
    )

    // The JDK's own snapshot, read here independently, as the reason the extra
    // root has to be shipped at all. Informational: a future JDK may add it.
    val jdkStore = runCatching {
        val f = File(File(System.getProperty("java.home"), "lib/security"), "cacerts")
        val ks = KeyStore.getInstance("PKCS12")
        f.inputStream().use { ks.load(it, "changeit".toCharArray()) }
        ks
    }.getOrNull()
    var jdkHasAaa = false
    if (jdkStore != null) {
        val md = MessageDigest.getInstance("SHA-256")
        val aliases = jdkStore.aliases()
        while (aliases.hasMoreElements()) {
            val cert = runCatching { jdkStore.getCertificate(aliases.nextElement()) }.getOrNull() ?: continue
            if (md.digest(cert.encoded).joinToString("") { "%02x".format(it) } == AAA) {
                jdkHasAaa = true
                break
            }
        }
    }
    println("  the JDK's own cacerts contains the AAA root: $jdkHasAaa (expected false for Temurin 17/21)")

    // ── 2. the real thing: can the app fetch a GitHub raw file? ─────────────
    val url = "https://raw.githubusercontent.com/codegeasse1/hikari-desktop/main/gradle.properties"
    var body: String? = null
    var elapsed = 0L
    for (attempt in 1..3) {
        val t0 = System.currentTimeMillis()
        val r = Http.fetchStringRobust(url)
        elapsed = System.currentTimeMillis() - t0
        body = r.getOrNull()
        if (body != null) {
            println("  fetch attempt $attempt: OK in ${elapsed}ms")
            break
        }
        println("  fetch attempt $attempt failed in ${elapsed}ms: " + (r.exceptionOrNull()?.message ?: "?"))
        Thread.sleep(2_000)
    }
    check("the app's own stack reads a raw.githubusercontent.com file", body != null)
    check("and it is the real file", body?.contains("jvmargs") == true)
    check("the fetch is fast (under 15s)", elapsed in 1..15_000, "took ${elapsed}ms")

    val chain = Http.serverChainSubjects(url)
    println("  chain served: " + (if (chain.isEmpty()) "(none)" else chain.joinToString("  <-  ")))

    // ── 3. the repo-manifest path, end to end (what "add repo" runs) ────────
    val manifestUrl = "https://raw.githubusercontent.com/codegeasse1/hikari-extensions/main/repo-desktop.json"
    val t1 = System.currentTimeMillis()
    val manifestResult = Http.fetchRepoJson(manifestUrl)
    val manifest = manifestResult.getOrNull()
    val manifestMs = System.currentTimeMillis() - t1
    println("  fetchRepoJson: " + (if (manifest == null) "FAILED" else "OK") + " in ${manifestMs}ms -> " + (manifest?.first ?: ""))
    if (manifest == null) {
        // Show WHY, with the same message the UI would print.
        println("  reason: " + (manifestResult.exceptionOrNull()?.message ?: "?"))
    }
    check("a repo manifest fetches through the mirror ladder", manifest != null)
    check("the manifest really holds plugins", manifest?.second?.contains("\"plugins\"") == true)
    check("the repo fetch is fast (under 20s)", manifest != null && manifestMs < 20_000, "took ${manifestMs}ms")

    // ── 4. evidence: the same fetch with the JDK store alone ────────────────
    if (jdkStore != null) {
        val jdkOnly = runCatching {
            val tmf = TrustManagerFactory.getInstance("X509")
            tmf.init(jdkStore)
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, tmf.trustManagers, null)
            val c = OkHttpClient.Builder()
                .sslSocketFactory(ctx.socketFactory, tmf.trustManagers[0] as javax.net.ssl.X509TrustManager)
                .build()
            c.newCall(Request.Builder().url(url).header("User-Agent", Http.UA).build()).execute().use { it.isSuccessful }
        }
        println("  with ONLY the JDK's store, that same fetch succeeds: " + jdkOnly.getOrElse { "failed: " + (it.message ?: it.javaClass.simpleName) })
    }

    // ── 5. the verifier itself ──────────────────────────────────────────────
    // A SHA-1-signed certificate — exactly the shape of the legacy Comodo/Sectigo
    // "AAA Certificate Services" root that Conscrypt's path builder refuses to
    // build a path through (see Http.HikariTrustManager). CI generates one with
    // keytool and passes it here, so this checks the REAL rules:
    //   * a chain that is not anchored anywhere must fail;
    //   * accepting that certificate must make it verify (the "Trust this
    //     network's certificate…" path) through Http's own verifier;
    //   * and the acceptance must survive a store rebuild.
    val sha1Pem = args.firstOrNull()?.let { File(it) }
    if (sha1Pem == null || !sha1Pem.isFile) {
        println("  (no SHA-1 test certificate passed — skipping the verifier section)")
    } else {
        val sha1Cert = runCatching {
            java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(sha1Pem.inputStream()) as java.security.cert.X509Certificate
        }.getOrNull()
        check("the test certificate could be read", sha1Cert != null, sha1Pem.absolutePath)
        if (sha1Cert != null) {
            check(
                "and it really is SHA-1 signed (the case Conscrypt refuses)",
                sha1Cert.sigAlgOID == "1.2.840.113549.1.1.5",
                sha1Cert.sigAlgName + " (" + sha1Cert.sigAlgOID + ")",
            )
            val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
                .digest(sha1Cert.encoded).joinToString("") { b -> "%02x".format(b) }

            val verifier = Http.trustManager()
            check(
                "the app verifies with its own verifier, not Conscrypt's path builder",
                verifier.javaClass.name.contains("HikariTrustManager"),
                verifier.javaClass.name,
            )

            // Not anchored yet: verification must FAIL (nothing is trusted blindly).
            val before = runCatching { verifier.checkServerTrusted(arrayOf(sha1Cert), "ECDHE_RSA") }
            check("an unanchored SHA-1 chain is rejected", before.isFailure, before.exceptionOrNull()?.message ?: "")
            check(
                "the rejection is classified as a certificate-trust failure",
                Http.isCertTrustFailure(before.exceptionOrNull()),
                before.exceptionOrNull()?.message ?: "",
            )

            val added = Http.trustCertificates(listOf(sha1Cert))
            println("  accepted " + added + " anchor(s) into " + Http.extraTrustedFile().absolutePath)
            check("the acceptance was written", added == 1, "added=" + added)
            check(
                "the accepted certificate is merged into the trust store",
                fingerprint in Http.trustStoreFingerprints(),
            )
            val after = runCatching { Http.trustManager().checkServerTrusted(arrayOf(sha1Cert), "ECDHE_RSA") }
            check(
                "and the same chain now verifies through the app's verifier",
                after.isSuccess,
                after.exceptionOrNull()?.message ?: "",
            )
            // What the platform verifier (Conscrypt's path builder, the old
            // behaviour) says about the same chain — the difference this whole
            // section is about.
            val platform = runCatching {
                val tmf = javax.net.ssl.TrustManagerFactory.getInstance("X509")
                tmf.init(Http.trustStore())
                val tm = tmf.trustManagers[0] as javax.net.ssl.X509TrustManager
                tm.checkServerTrusted(arrayOf(sha1Cert), "ECDHE_RSA")
            }
            println("  the platform verifier would accept that chain: " + platform.isSuccess +
                (platform.exceptionOrNull()?.message?.let { " (" + it + ")" } ?: ""))
        }
    }
    if (failures > 0) {
        println("TlsTrustSelfTest: $failures FAILED")
        kotlin.system.exitProcess(1)
    }
    println("TlsTrustSelfTest: OK")
    kotlin.system.exitProcess(0)
}
