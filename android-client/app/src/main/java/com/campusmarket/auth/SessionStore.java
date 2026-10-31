package com.campusmarket.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SessionStore {
    private static final String ALIAS = "campus_market_session_v1";
    private final SharedPreferences preferences;
    public SessionStore(Context context) { preferences = context.getApplicationContext().getSharedPreferences("session", Context.MODE_PRIVATE); }
    public boolean hasSavedSession() { return preferences.contains("ciphertext"); }
    public String token() {
        if (!hasSavedSession()) return null;
        if (preferences.getLong("expiresAt", 0) <= System.currentTimeMillis()) { clear(); return null; }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = Base64.decode(preferences.getString("iv", ""), Base64.NO_WRAP);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(Base64.decode(preferences.getString("ciphertext", ""), Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException exception) { clear(); return null; }
    }
    public void save(String token, long expiresAt) throws IOException {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}") || expiresAt <= System.currentTimeMillis()) {
            throw new IOException("Invalid login session");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            String encrypted = Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
            if (!preferences.edit().putString("ciphertext", encrypted)
                    .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).putLong("expiresAt", expiresAt).commit()) {
                throw new IOException("Cannot persist session");
            }
        } catch (GeneralSecurityException exception) { throw new IOException("Secure session storage unavailable"); }
    }
    public void clear() { preferences.edit().clear().commit(); }
    private static synchronized SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
