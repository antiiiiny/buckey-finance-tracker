package com.buckey.financetracker.data.sheets

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages Google Sign-In and OAuth token lifecycle for Sheets API access.
 *
 * Scoped to [SheetsScopes.SPREADSHEETS] only — no Drive-wide scope (Rule 9).
 */
object GoogleSignInHelper {

    private const val TAG = "GoogleSignInHelper"

    private fun getSignInOptions(): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(com.google.android.gms.common.api.Scope(SheetsScopes.SPREADSHEETS))
            .requestEmail()
            .build()
    }

    /** Returns the [GoogleSignInClient] configured for Sheets-only access. */
    fun getSignInClient(context: Context): GoogleSignInClient {
        return GoogleSignIn.getClient(context, getSignInOptions())
    }

    /** Returns a [android.content.Intent] that launches the Google Sign-In UI. */
    fun getSignInIntent(context: Context): Intent {
        return getSignInClient(context).signInIntent
    }

    /** Returns the last signed-in account, or null if the user is not signed in. */
    fun getLastSignedInAccount(context: Context): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(context)
    }

    /** Returns true if the user is currently signed in to Google. */
    fun isSignedIn(context: Context): Boolean {
        return getLastSignedInAccount(context) != null
    }

    /** Builds a [GoogleAccountCredential] from the last signed-in account. */
    private fun getCredential(context: Context): GoogleAccountCredential? {
        val account = getLastSignedInAccount(context) ?: return null
        return GoogleAccountCredential.usingOAuth2(
            context,
            listOf(SheetsScopes.SPREADSHEETS)
        ).apply { selectedAccount = account.account }
    }

    /**
     * Obtains a fresh OAuth access token for the Sheets API.
     * This is a blocking network call — ensure it runs on a background thread.
     */
    suspend fun getAccessToken(context: Context): String? = withContext(Dispatchers.IO) {
        val credential = getCredential(context) ?: return@withContext null
        try {
            credential.token
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get access token: ${e.message}")
            null
        }
    }

    /**
     * Builds a [Sheets] service instance authenticated with the signed-in account.
     * Returns null if the user is not signed in.
     */
    fun buildSheetsService(context: Context): Sheets? {
        val credential = getCredential(context) ?: return null
        return Sheets.Builder(
            NetHttpTransport(),
            GsonFactory(),
            credential
        )
            .setApplicationName("Buckey Finance Tracker")
            .build()
    }

    /** Signs the user out and revokes the current token. */
    fun signOut(context: Context) {
        getSignInClient(context).signOut()
        Log.i(TAG, "User signed out of Google")
    }
}