package com.sadad.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Local app-lock PIN verifier; only a salted verifier is stored on the device. */
final class LocalSecurity {
    private final SharedPreferences preferences;
    LocalSecurity(Context context) { preferences = context.getSharedPreferences("sadad.local.security", Context.MODE_PRIVATE); }

    synchronized void setPin(String pin) throws Exception {
        if (pin == null || pin.length() < 4 || pin.length() > 12) throw new IllegalArgumentException("رمز القفل يجب أن يكون من 4 إلى 12 رقمًا.");
        for (int i=0;i<pin.length();i++) if (!Character.isDigit(pin.charAt(i))) throw new IllegalArgumentException("استخدم أرقامًا فقط لرمز قفل التطبيق.");
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        byte[] hash = derive(pin, salt);
        preferences.edit().putString("pin_salt", hex(salt)).putString("pin_hash", hex(hash))
                .remove("pin_failed_attempts").remove("pin_lock_level").remove("pin_locked_until").apply();
    }

    synchronized boolean hasPin() { return !preferences.getString("pin_hash", "").isEmpty(); }
    synchronized long pinLockoutRemainingMs() { return Math.max(0L, preferences.getLong("pin_locked_until", 0L) - System.currentTimeMillis()); }
    synchronized boolean verifyPin(String pin) {
        if (pinLockoutRemainingMs() > 0L) return false;
        try {
            byte[] salt=unhex(preferences.getString("pin_salt", "")); byte[] expected=unhex(preferences.getString("pin_hash", ""));
            if(salt.length==0||expected.length==0)return false;
            boolean matches = MessageDigest.isEqual(expected,derive(pin == null ? "" : pin,salt));
            if (matches) {
                preferences.edit().remove("pin_failed_attempts").remove("pin_lock_level").remove("pin_locked_until").apply();
                return true;
            }
            int failures = preferences.getInt("pin_failed_attempts", 0) + 1;
            SharedPreferences.Editor editor = preferences.edit().putInt("pin_failed_attempts", failures);
            if (failures >= 5) {
                int level = Math.min(6, preferences.getInt("pin_lock_level", 0) + 1);
                long delay = Math.min(15 * 60_000L, 30_000L << (level - 1));
                editor.putInt("pin_lock_level", level).putLong("pin_locked_until", System.currentTimeMillis() + delay).putInt("pin_failed_attempts", 0);
            }
            editor.apply();
            return false;
        } catch(Exception e){return false;}
    }
    synchronized void clearPin(){preferences.edit().remove("pin_salt").remove("pin_hash").remove("pin_failed_attempts").remove("pin_lock_level").remove("pin_locked_until").apply();}

    private byte[] derive(String value,byte[] salt)throws Exception{
        PBEKeySpec spec=new PBEKeySpec(value.toCharArray(),salt,120000,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
        finally{spec.clearPassword();}
    }
    private static String hex(byte[] data){StringBuilder out=new StringBuilder(data.length*2);for(byte b:data)out.append(String.format(java.util.Locale.US,"%02x",b));return out.toString();}
    private static byte[] unhex(String value){if(value.length()%2!=0)return new byte[0];byte[] out=new byte[value.length()/2];for(int i=0;i<out.length;i++)out[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return out;}
}
