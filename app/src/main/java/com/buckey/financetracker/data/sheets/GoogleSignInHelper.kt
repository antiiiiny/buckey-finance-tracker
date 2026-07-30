package com.buckey.financetracker.data.sheets

import android.content.Context
import android.content.Intent
import android.util.Log
import com.buckey.financetracker.BuildConfig
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.util.ExponentialBackOff
import com.google.api.services.sheets.v4.SheetsScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages Google Sign-In OAuth flow and access token retrieval.
 *
 * Scoped to [SheetsScopes.SPREADSHEETS] only — no Drive-wide scope (Rule 9).
 * Reads the client ID from [BuildConfig.GOOGLE_CLIENT_ID].
 */
object GoogleSignInHelper {

    private const val TAG = "GoogleSignInHelper"

    /** The single OAuth scope this app requests */
    private val SCOPE = SheetsScopes.SPREADSHEETS

    /**
     * Builds the [GoogleSignInOptions] used throughout the app.
     */
    private fun buildSignInOptions(): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(com.google.android.gms.common.api.Scope(SCOPE))
            .requestIdToken(BuildConfig.GOOGLE_CLIENT_ID)
            .requestEmail()
            .build()
    }

    /**
     * Returns a [GoogleSignInClient] configured with the app's OAuth options.
     */
    fun getClient(context: Context): GoogleSignInClient {
        return GoogleSignIn.getClient(context, buildSignInOptions())
    }

    /**
     * Returns an intent to start the Google Sign-In flow.
     * Pass this to [android.app.Activity.startActivityForResult] or an [ActivityResultLauncher].
     */
    fun getSignInIntent(context: Context): Intent {
        return getClient(context).signInIntent
    }

    /**
     * Returns the last signed-in account, or null if the user is not signed in.
     */
    fun getLastSignedInAccount(context: Context): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(context)
    }

    /**
     * Returns true if the user is currently signed in with a Google account.
     */
    fun isSignedIn(context: Context): Boolean {
        return getLastSignedInAccount(context) != null
    }

    /**
     * Signs the user out and revokes all tokens.
     */
    suspend fun signOut(context: Context) = withContext(Dispatchers.IO) {
        try {
            getClient(context).signOut().await()
            Log.i(TAG, "User signed out successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Sign-out failed: ${e.message}")
        }
    }

    /**
     * Revokes the current token and signs out.
     */
    suspend fun revokeAccess(context: Context) = withContext(Dispatchers.IO) {
        try {
            getClient(context).revokeAccess().await()
            Log.i(TAG, "Access revoked successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Revoke access failed: ${e.message}")
        }
    }

    /**
     * Creates a [GoogleAccountCredential] for the given account.
     * Used by [GoogleSheetsRepository] to build the Sheets API client.
     */
    fun getCredential(context: Context, account: GoogleSignInAccount): GoogleAccountCredential {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(SCOPE)
        )
        credential.selectedAccount = account.account
        credential.backOff = ExponentialBackOff()
        return credential
    }

    /**
     * Handles the result from the sign-in intent.
     * Call this from [android.app.Activity.onActivityResult] or your ActivityResultLauncher callback.
     *
     * @param data The intent data from the sign-in result
     * @return The signed-in account, or null if sign-in failed
     */
    fun handleSignInResult(data: Intent?): GoogleSignInAccount? {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.result
            Log.i(TAG, "Sign-in successful: ${account.email}")
            account
        } catch (e: Exception) {
            Log.e(TAG, "Sign-in failed: ${e.message}")
            null
        }
    }
}