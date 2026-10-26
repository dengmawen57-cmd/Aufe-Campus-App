package cn.aufe.campus.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 加密凭据存储（对齐彩带小工具 CredentialStore，并做了「永不因密钥问题丢登录」加固）
 *
 * 为什么要独立于 Cookie：
 * - Cookie 是服务端会话，有真实过期时间；本机凭据是我们自己的恢复凭据。
 * - 参考 app 的做法：**登录成功后始终保存凭据**（用于会话恢复与快速登录），
 *   "记住密码" 只影响是否回填输入框，不影响能否静默恢复。
 *
 * ## 加固点（原实现会在真实机型上把「恒定登录」打回原形）
 * 1) EncryptedSharedPreferences 的 MasterKey 放在 Keystore 里。系统重置（换锁屏、
 *    从备份恢复、卸载重装残留、部分 ROM 升级）后 KeyStore 别名失效，
 *    `create()` 抛异常，原实现直接在 Application 构造里崩掉；
 * 2) 更隐蔽的是「能创建但读不出来」：读 value 时抛 AEADBadTagException，
 *    于是每次读凭据都失败 → 静默恢复永远失败 → 用户每次都要重新登录。
 * 本类对这两种情况都做「删库重建」兜底：宁可让用户重新登录一次，也不再持续失败。
 *
 * 使用 EncryptedSharedPreferences（AES-256-SIV 加密 key / AES-256-GCM 加密 value）。
 */
class CredentialStore(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var prefs: SharedPreferences? = openPrefs()

    /**
     * 打开加密 prefs；失败则删掉旧文件重建一次。
     * 返回 null 表示当前设备完全无法使用加密存储（此时上层按「无凭据」处理，不影响 App 启动）。
     */
    private fun openPrefs(): SharedPreferences? {
        createPrefs()?.let { return it }
        // 第一次失败：极可能是旧文件的 MasterKey 已失效，删库重建
        runCatching { appContext.deleteSharedPreferences(FILE_NAME) }
        return createPrefs()
    }

    private fun createPrefs(): SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    /**
     * 读操作统一入口：解密失败即视为文件损坏，重建后返回 null（不是崩溃）。
     * 泛型用 R（非空）承载，避免调用方写出嵌套可空类型。
     */
    private fun <T> read(block: (SharedPreferences) -> T?): T? {
        val p = prefs ?: return null
        return try {
            block(p)
        } catch (_: Throwable) {
            resetBroken()
            null
        }
    }

    /** 读取一组凭据（缺关键字段返回 null） */
    private fun readCreds(
        p: SharedPreferences,
        keyUser: String,
        keyPass: String,
        keyJwUser: String,
        keyJwPass: String
    ): Credentials? {
        val user = p.getString(keyUser, null)?.takeIf { it.isNotBlank() } ?: return null
        val pass = p.getString(keyPass, null)?.takeIf { it.isNotBlank() } ?: return null
        val jwUser = p.getString(keyJwUser, null)?.takeIf { it.isNotBlank() } ?: user
        val jwPass = p.getString(keyJwPass, null)?.takeIf { it.isNotBlank() } ?: pass
        return Credentials(user, pass, jwUser, jwPass)
    }

    /** 写操作统一入口：绝不把异常抛给登录流程 */
    private fun write(block: (SharedPreferences.Editor) -> Unit) {
        var p = prefs
        if (p == null) {
            p = openPrefs()
            prefs = p
        }
        if (p == null) return
        try {
            val editor = p.edit()
            block(editor)
            editor.apply()
        } catch (_: Throwable) {
            resetBroken()
        }
    }

    @Synchronized
    private fun resetBroken() {
        runCatching { appContext.deleteSharedPreferences(FILE_NAME) }
        prefs = openPrefs()
    }

    /** 会话凭据：登录成功后始终保存，用于重启后的静默恢复 */
    fun save(creds: Credentials) {
        write { e ->
            e.putString(KEY_VPN_USER, creds.vpnUser)
            e.putString(KEY_VPN_PASS, creds.vpnPass)
            e.putString(KEY_JW_USER, creds.jwUser)
            e.putString(KEY_JW_PASS, creds.jwPass)
        }
    }

    /** 读取会话凭据；任一关键字段缺失即视为不可恢复 */
    fun load(): Credentials? = read { p ->
        readCreds(p, KEY_VPN_USER, KEY_VPN_PASS, KEY_JW_USER, KEY_JW_PASS)
    }

    fun clear() {
        write { e ->
            e.remove(KEY_VPN_USER)
            e.remove(KEY_VPN_PASS)
            e.remove(KEY_JW_USER)
            e.remove(KEY_JW_PASS)
        }
    }

    fun hasCredentials(): Boolean = read { p ->
        p.contains(KEY_VPN_USER) && p.contains(KEY_VPN_PASS)
    } ?: false

    // ---------- 记住密码（仅用于回填输入框） ----------

    fun saveRemembered(creds: Credentials) {
        write { e ->
            e.putString(KEY_REMEMBERED_VPN_USER, creds.vpnUser)
            e.putString(KEY_REMEMBERED_VPN_PASS, creds.vpnPass)
            e.putString(KEY_REMEMBERED_JW_USER, creds.jwUser)
            e.putString(KEY_REMEMBERED_JW_PASS, creds.jwPass)
            e.putBoolean(KEY_REMEMBER_ENABLED, true)
        }
    }

    fun loadRemembered(): Credentials? = read { p ->
        if (!p.getBoolean(KEY_REMEMBER_ENABLED, false)) null
        else readCreds(
            p,
            KEY_REMEMBERED_VPN_USER,
            KEY_REMEMBERED_VPN_PASS,
            KEY_REMEMBERED_JW_USER,
            KEY_REMEMBERED_JW_PASS
        )
    }

    fun clearRemembered() {
        write { e ->
            e.remove(KEY_REMEMBERED_VPN_USER)
            e.remove(KEY_REMEMBERED_VPN_PASS)
            e.remove(KEY_REMEMBERED_JW_USER)
            e.remove(KEY_REMEMBERED_JW_PASS)
            e.remove(KEY_REMEMBER_ENABLED)
        }
    }

    private companion object {
        const val FILE_NAME = "aufe_credentials"

        const val KEY_VPN_USER = "vpn_user"
        const val KEY_VPN_PASS = "vpn_pass"
        const val KEY_JW_USER = "jw_user"
        const val KEY_JW_PASS = "jw_pass"
        const val KEY_REMEMBERED_VPN_USER = "remembered_vpn_user"
        const val KEY_REMEMBERED_VPN_PASS = "remembered_vpn_pass"
        const val KEY_REMEMBERED_JW_USER = "remembered_jw_user"
        const val KEY_REMEMBERED_JW_PASS = "remembered_jw_pass"
        const val KEY_REMEMBER_ENABLED = "remember_password_enabled"
    }
}

/** 登录凭据：VPN 门户与教务系统各一组（多数学校两套相同，故默认回落） */
data class Credentials(
    val vpnUser: String,
    val vpnPass: String,
    val jwUser: String,
    val jwPass: String
)
