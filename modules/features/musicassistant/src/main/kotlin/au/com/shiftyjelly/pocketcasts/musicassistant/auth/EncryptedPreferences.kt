package au.com.shiftyjelly.pocketcasts.musicassistant.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException

internal fun encryptedPreferences(context: Context, name: String): SharedPreferences {
    return openOrReset(context, name) { createEncryptedPreferences(context, name) }
}

internal fun freshEncryptedPreferences(context: Context, name: String): SharedPreferences {
    context.deleteSharedPreferences(name)
    return encryptedPreferences(context, name)
}

internal fun openOrReset(context: Context, name: String, open: () -> SharedPreferences): SharedPreferences {
    return try {
        open()
    } catch (e: GeneralSecurityException) {
        context.deleteSharedPreferences(name)
        open()
    } catch (e: IOException) {
        context.deleteSharedPreferences(name)
        open()
    }
}

// security-crypto 1.1.0 deprecates EncryptedSharedPreferences; the spec still requires it for the Music Assistant token.
@Suppress("DEPRECATION")
private fun createEncryptedPreferences(context: Context, name: String): SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context,
        name,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}
