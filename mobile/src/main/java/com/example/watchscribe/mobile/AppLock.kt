package com.example.watchscribe.mobile

import android.content.Context
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * App lock with the phone's own fingerprint / face / PIN (Android BiometricPrompt).
 * Checked entirely on the device: no account, nothing is sent anywhere.
 */
object AppLock {
    private const val PREFS = "app_lock"
    private const val RELOCK_AFTER_MS = 60_000L
    private const val AUTH = BIOMETRIC_STRONG or BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    data class Locked(val value: Boolean)
    private val _state = MutableStateFlow(Locked(false))
    val locked: StateFlow<Locked> = _state

    private var leftAt = 0L
    private var unlockedOnce = false

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = p(ctx).getBoolean("on", false)

    /** True if the phone has a fingerprint/face or at least a screen lock PIN/pattern. */
    fun available(ctx: Context) = BiometricManager.from(ctx).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

    fun onStart(a: FragmentActivity) {
        applySecureFlag(a)
        if (!enabled(a)) { _state.value = Locked(false); return }
        if (!unlockedOnce || System.currentTimeMillis() - leftAt > RELOCK_AFTER_MS) {
            _state.value = Locked(true)
            prompt(a) { }
        }
    }

    fun onStop() { leftAt = System.currentTimeMillis() }

    /** Shows the system unlock sheet; [done] gets true on success. */
    fun prompt(a: FragmentActivity, title: String = "Unlock Sotto", done: (Boolean) -> Unit) {
        val bp = BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                unlockedOnce = true
                _state.value = Locked(false)
                done(true)
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) { done(false) }
        })
        bp.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Fingerprint, face or screen lock · checked on this phone only")
                .setAllowedAuthenticators(AUTH)
                .build()
        )
    }

    /** Turning the lock on or off needs one successful unlock first. */
    fun setEnabled(a: FragmentActivity, on: Boolean, done: (Boolean) -> Unit) {
        prompt(a, if (on) "Turn on app lock" else "Turn off app lock") { ok ->
            if (ok) { p(a).edit().putBoolean("on", on).apply(); applySecureFlag(a) }
            done(ok)
        }
    }

    /** While locked-mode is on, hide Sotto's content in the recent-apps view and screenshots. */
    private fun applySecureFlag(a: FragmentActivity) {
        if (enabled(a)) a.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else a.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
