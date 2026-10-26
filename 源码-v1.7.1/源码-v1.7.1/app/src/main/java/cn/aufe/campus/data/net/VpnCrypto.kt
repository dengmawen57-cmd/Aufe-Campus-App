package cn.aufe.campus.data.net

import android.util.Base64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * VPN 门户 RSA / CAS DES 加密（移植自彩带小工具 AUFEConnection）。
 *
 * 说明：彩带小工具使用 BouncyCastle 实现，这里改用 Android 自带的 javax.crypto
 * （RSA/ECB/PKCS1Padding 与 DESede/ECB/PKCS7Padding 均为标准算法），
 * 加密结果与 BouncyCastle 完全一致，且无需额外依赖（省去 bcprov 约 2MB 体积）。
 */
object VpnCrypto {

    /**
     * VPN 门户：RSA(PKCS#1 v1.5) 加密。
     * @param plaintext 明文（"密码_CSRF码"）
     * @param modulusHex 十六进制模数（<RSA_ENCRYPT_KEY>）
     * @param exponentStr 十进制或十六进制指数（<RSA_ENCRYPT_EXP>）
     * @return 小写十六进制密文
     */
    fun rsaEncrypt(plaintext: String, modulusHex: String, exponentStr: String): String {
        val modulus = BigInteger(modulusHex, 16)
        val exponent = if (exponentStr.all { it.isDigit() }) {
            BigInteger(exponentStr)
        } else {
            BigInteger(exponentStr, 16)
        }
        val pubKey = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, exponent))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, pubKey)
        val out = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return out.joinToString("") { "%02x".format(it) }
    }

    /**
     * CAS：以 lt 为密钥的 3DES（8 字节密钥重复 3 次组成 24 字节），Base64 输出。
     */
    fun desEncrypt(plaintext: String, key: String): String {
        val src = key.toByteArray(Charsets.UTF_8)
        val keyBytes = when {
            src.size > 8 -> src.copyOf(8)
            src.size < 8 -> src.copyOf(8)   // 不足 8 字节补 0
            else -> src
        }
        val triple = ByteArray(24).also {
            System.arraycopy(keyBytes, 0, it, 0, 8)
            System.arraycopy(keyBytes, 0, it, 8, 8)
            System.arraycopy(keyBytes, 0, it, 16, 8)
        }
        val cipher = Cipher.getInstance("DESede/ECB/PKCS7Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(triple, "DESede"))
        val out = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }
}
