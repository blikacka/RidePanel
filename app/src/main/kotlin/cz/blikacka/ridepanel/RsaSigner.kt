package cz.blikacka.ridepanel

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher

/**
 * Replica of `net.easyconn.carman.utils.RSAUtil` from the decompiled APK.
 *
 * - On first invocation, generates a 1024-bit RSA key pair and persists both
 *   halves in SharedPreferences (matching `getPrivateKey`/`getPublicKey`).
 * - `encryptHUID(huid)` signs the head-unit's HUID with the phone's RSA
 *   *private* key (despite the name, this is digital-signature framing —
 *   the receiver uses phone's `pubkey` from CLIENT_INFO to verify).
 *
 * The head-unit appears to gate video reception on a valid signature even
 * when `enableSockServerAuth` is absent from CLIENT_INFO, so this is a
 * load-bearing field.
 */
object RsaSigner {
    private const val TAG = "RidePanel.Rsa"
    private const val PREFS = "ridepanel"
    private const val KEY_PRIV = "rsa_priv"
    private const val KEY_PUB = "rsa_pub"
    private const val KEY_SIZE = 1024

    /** Returns the phone's RSA public key in Base64 (no line-wrap). */
    fun publicKeyBase64(ctx: Context): String = ensureKeys(ctx).second

    /** RSA-encrypt the given string with phone's private key (== sign). */
    fun encryptHuid(ctx: Context, huid: String?): String {
        if (huid.isNullOrEmpty()) return ""
        return try {
            val (privBase64, _) = ensureKeys(ctx)
            val keyBytes = Base64.decode(privBase64, Base64.NO_WRAP)
            val privKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, privKey)
            val encrypted = cipher.doFinal(huid.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } catch (t: Throwable) {
            AppLog.e(TAG, "encryptHuid failed", t)
            ""
        }
    }

    private fun ensureKeys(ctx: Context): Pair<String, String> {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val priv = sp.getString(KEY_PRIV, null)
        val pub = sp.getString(KEY_PUB, null)
        if (!priv.isNullOrEmpty() && !pub.isNullOrEmpty()) {
            return priv to pub
        }
        return generateAndStore(sp)
    }

    private fun generateAndStore(sp: android.content.SharedPreferences): Pair<String, String> {
        val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_SIZE) }
        val pair: KeyPair = gen.generateKeyPair()
        val priv = Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP)
        val pub = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)
        sp.edit().putString(KEY_PRIV, priv).putString(KEY_PUB, pub).apply()
        AppLog.i(TAG, "generated 1024-bit RSA key pair (pubkey ${pub.length} chars)")
        return priv to pub
    }
}
