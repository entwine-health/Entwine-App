// Local persistence: device token in Keystore-backed prefs (R-SEC-02);
// composed text + quiet time + last-session day in DataStore (I2, R-NOT-02).
package health.entwine.lucy.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.first
import java.time.LocalTime

private val Context.dataStore by preferencesDataStore(name = "lucy")

private val K_COMPOSED = stringPreferencesKey("composed_text")
private val K_QUIET = stringPreferencesKey("quiet_time")
private val K_LAST_DAY = stringPreferencesKey("last_session_day")
private val K_CRISIS = stringPreferencesKey("crisis_targets_json")

class Store(private val ctx: Context) {
    // Reason: the device credential must survive in hardware-backed storage,
    // never plain prefs (R-SEC-02).
    private val secure by lazy {
        EncryptedSharedPreferences.create(
            ctx, "lucy_secure",
            MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var deviceToken: String?
        get() = secure.getString("device_token", null)
        set(v) = secure.edit().putString("device_token", v).apply()

    suspend fun composedText(): String =
        ctx.dataStore.data.first()[K_COMPOSED] ?: ""

    suspend fun setComposedText(v: String) {
        ctx.dataStore.edit { it[K_COMPOSED] = v } // survives process death (I2)
    }

    suspend fun quietTime(): LocalTime? =
        ctx.dataStore.data.first()[K_QUIET]?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

    suspend fun setQuietTime(v: LocalTime) {
        ctx.dataStore.edit { it[K_QUIET] = v.toString() }
    }

    suspend fun lastSessionDay(): String? = ctx.dataStore.data.first()[K_LAST_DAY]

    suspend fun setLastSessionDay(day: String) {
        ctx.dataStore.edit { it[K_LAST_DAY] = day }
    }

    /** Crisis targets cached from the last session.ready (WS §5.3). */
    suspend fun cachedCrisisTargets(): String? = ctx.dataStore.data.first()[K_CRISIS]

    suspend fun setCachedCrisisTargets(jsonText: String) {
        ctx.dataStore.edit { it[K_CRISIS] = jsonText }
    }
}
