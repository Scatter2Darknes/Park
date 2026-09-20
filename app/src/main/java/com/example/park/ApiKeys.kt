package com.example.park

import android.content.Context
import android.util.Log

/**
 * Reads each API key from a plain one-line file in app/src/main/assets/, rather than having
 * the key committed directly in source (which is what this project shared as a zip for review
 * would otherwise leak). Create these two files yourself — they're not included:
 *
 *   app/src/main/assets/stadia_api_key.txt   \u2014 your Stadia Maps API key, and nothing else
 *   app/src/main/assets/datasf_app_token.txt \u2014 your Socrata/DataSF app token, and nothing else
 *
 * Each file is exactly one line: the raw key/token value, no quotes, no "key=" prefix. Add
 * both filenames to .gitignore if this project is in version control, since assets do still
 * ship inside the built APK (readable by anyone who unzips it) — this only keeps the key out
 * of source control and out of anything you export/share from the project, not out of the
 * installed app itself.
 *
 * Cached after the first successful read per process — getTileURLString gets called once per
 * tile, far too often to reopen a file for.
 *
 * A person this app is shared with can also set their own key from Settings, so they're on
 * their own quota rather than whichever key is baked into the build they were handed — that
 * value lives in DataStore (see SettingsRepository.stadiaApiKeyOverride/dataSfAppTokenOverride)
 * and is mirrored into the volatile fields below by ParkApp at startup, and again immediately
 * whenever Settings saves a new one. The override is checked first; an empty/unset override
 * falls through to the asset-file value exactly as before, so a build with no override ever
 * configured behaves identically to the pre-override code.
 */
object ApiKeys {
    @Volatile private var stadiaKeyCache: String? = null
    @Volatile private var dataSfTokenCache: String? = null
    @Volatile private var stadiaKeyOverride: String? = null
    @Volatile private var dataSfTokenOverride: String? = null

    /** Called once from ParkApp at startup (with whatever's currently in DataStore, if
     *  anything), and again from Settings the moment someone saves a new key — so a freshly
     *  entered key takes effect on the very next tile/API request, not just after a restart. */
    fun setStadiaMapsKeyOverride(key: String?) {
        stadiaKeyOverride = key?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun setDataSfAppTokenOverride(token: String?) {
        dataSfTokenOverride = token?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun stadiaMapsKey(context: Context): String =
        stadiaKeyOverride
            ?: (stadiaKeyCache ?: readAssetLine(context, "stadia_api_key.txt").also { stadiaKeyCache = it })

    fun dataSfAppToken(context: Context): String =
        dataSfTokenOverride
            ?: (dataSfTokenCache ?: readAssetLine(context, "datasf_app_token.txt").also { dataSfTokenCache = it })

    private fun readAssetLine(context: Context, filename: String): String =
        try {
            context.applicationContext.assets.open(filename).bufferedReader().use {
                it.readLine()?.trim() ?: ""
            }
        } catch (e: Exception) {
            // Missing file is expected until the key is actually configured — logged rather
            // than thrown, since a blank key should fail as "the API call rejected it" (a
            // state the rest of the app already has to handle) rather than a crash here.
            Log.w("ApiKeys", "Could not read $filename from assets \u2014 falling back to blank", e)
            ""
        }
}