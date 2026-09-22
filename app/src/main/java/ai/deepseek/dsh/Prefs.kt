package ai.deepseek.dsh

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Настройки v2 (с нуля).
 * Секрет zen_key — ТОЛЬКО в EncryptedSharedPreferences (Android Keystore).
 * В yaml/логах/коде значений секретов нет — только референс apiKeyEnv (R-07).
 */
class Prefs(c: Context) {
    private val plain = c.getSharedPreferences("dsh", Context.MODE_PRIVATE)
    private val mk = MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val sec = EncryptedSharedPreferences.create(
        c, "dsh-sec", mk,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun zenKey(): String = sec.getString("zen_key", "") ?: ""
    fun setZenKey(v: String) = sec.edit().putString("zen_key", v).apply()

    fun model(): String =
        plain.getString("model", "muse-spark-1.3-contributor-free")
            ?: "muse-spark-1.3-contributor-free"
    fun setModel(v: String) = plain.edit().putString("model", v).apply()

    fun locale(): String = plain.getString("locale", "ru") ?: "ru"
    fun setLocale(v: String) = plain.edit().putString("locale", v).apply()

    fun amoled(): Boolean = plain.getBoolean("amoled", true)
    fun setAmoled(v: Boolean) = plain.edit().putBoolean("amoled", v).apply()

    fun fontScale(): Float = plain.getFloat("font_scale", 1.0f)
    fun setFontScale(v: Float) = plain.edit().putFloat("font_scale", v).apply()

    fun nodeRamMb(): Int = plain.getInt("node_ram", 2048)
    fun setNodeRamMb(v: Int) = plain.edit().putInt("node_ram", v).apply()

    fun cacheLimitMb(): Int = plain.getInt("cache_limit", 300)

    fun backupHour(): Int = plain.getInt("backup_hour", 3)
    fun setBackupHour(v: Int) = plain.edit().putInt("backup_hour", v).apply()

    fun autoStart(): Boolean = plain.getBoolean("autostart", true)
    fun setAutoStart(v: Boolean) = plain.edit().putBoolean("autostart", v).apply()
}
