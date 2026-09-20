package com.example.park

import android.content.Context
import android.util.Log
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * Works around a real misconfiguration on data.sf.gov (formerly data.sfgov.org): the server
 * sends only its own leaf certificate, not the "GlobalSign GCC R46 AlphaSSL CA 2025"
 * intermediate needed to complete the chain back to a trusted root. Browsers paper over this
 * transparently via "AIA chasing" \u2014 fetching the missing intermediate themselves from a
 * URL embedded in the leaf certificate \u2014 which is why the exact same URL loads with zero
 * warning in Chrome. Android's plain HttpsURLConnection does not do that, which is why this
 * surfaced identically as "Trust anchor for certification path not found" on both an old
 * Galaxy S9 and a current, actively-updated Galaxy S20 \u2014 confirmed NOT a device
 * trust-store problem, since a stale trust store wouldn't explain the S20 failing the same way.
 *
 * Fix: bundle that one specific missing intermediate certificate and explicitly trust it,
 * ALONE, for this one connection only.
 *
 *   ** REQUIRED SETUP (not done by this file alone): **
 *   1. Go to https://support.globalsign.com/ca-certificates/intermediate-certificates/alphassl-intermediate-certificates
 *   2. Find "GlobalSign GCC R46 AlphaSSL CA 2025" and click "Download Certificate (Binary/DER Encoded)".
 *      (Downloaded directly from GlobalSign, not transcribed by hand \u2014 a single wrong
 *      byte makes a certificate file completely invalid, and web search results truncate
 *      long content, so this is not something to copy out of a search snippet.)
 *   3. Rename the downloaded file to exactly: globalsign_r46_alphassl_ca.crt
 *   4. Place it at: app/src/main/res/raw/globalsign_r46_alphassl_ca.crt
 *      (create the res/raw/ folder if it doesn't exist yet \u2014 Android raw resource
 *      filenames must be lowercase letters, digits, and underscores only, hence no dots
 *      except the one before "crt")
 *
 * A PKIX path is allowed to terminate at ANY certificate in the trust store, not only an
 * actual root \u2014 so trusting just this one intermediate (without also needing GlobalSign's
 * root certificate) is enough to complete the chain [leaf, intermediate] and validate
 * successfully.
 *
 * Scoped to the DataSF connection specifically (via HttpsURLConnection.sslSocketFactory on
 * that one instance in fetchSweepingPage), not installed as the app's global default \u2014
 * an SSLContext that trusts ONLY this one certificate would break every other HTTPS
 * connection the app makes (Stadia Maps tiles, anything else) if applied globally.
 *
 * UNVERIFIED: correct in principle (a standard, well-established pattern for exactly this
 * class of server misconfiguration), but I can't compile-or-run-test it from here \u2014
 * confirm on a real device (the S9 or S20, both confirmed-affected) before trusting it
 * further. If it doesn't compile, the likely culprit is R.raw.globalsign_r46_alphassl_ca not
 * resolving \u2014 that means the setup steps above haven't been done yet in this project.
 */
object DataSfTrustConfig {
    @Volatile private var cachedFactory: SSLSocketFactory? = null
    @Volatile private var buildFailed = false

    /** Null means "couldn't build the custom trust config" (setup steps not done, or the
     *  bundled file is missing/corrupt) \u2014 callers should fall back to the connection's
     *  normal default behavior rather than crash, so a not-yet-completed setup step just
     *  means the original "Trust anchor" failure persists exactly as before this existed,
     *  not a new, different crash. */
    fun sslSocketFactory(context: Context): SSLSocketFactory? {
        cachedFactory?.let { return it }
        if (buildFailed) return null
        return try {
            val cf = CertificateFactory.getInstance("X.509")
            val bundledIntermediate = context.resources
                .openRawResource(R.raw.globalsign_r46_alphassl_ca)
                .use { cf.generateCertificate(it) }

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null, null)
            keyStore.setCertificateEntry("globalsign_r46_alphassl", bundledIntermediate)

            val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trustManagerFactory.init(keyStore)

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustManagerFactory.trustManagers, null)
            sslContext.socketFactory.also { cachedFactory = it }
        } catch (e: Exception) {
            buildFailed = true
            Log.w(
                "DataSfTrustConfig",
                "Could not build the bundled-intermediate trust config \u2014 falling back to " +
                        "the platform default (which will likely still fail with the known " +
                        "\"Trust anchor\" error on this specific endpoint until the raw " +
                        "resource is added \u2014 see the setup steps in this file's class doc)",
                e
            )
            null
        }
    }
}