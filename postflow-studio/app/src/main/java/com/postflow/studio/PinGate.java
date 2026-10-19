package com.postflow.studio;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.security.SecureRandom;
import java.security.MessageDigest;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Local owner-only PIN gate; unrelated to future OAuth. */
public final class PinGate {
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("postflow_owner_security",Context.MODE_PRIVATE);}
    static boolean hasPin(Context c){return prefs(c).contains("hash");}
    static boolean valid(String pin){return pin!=null && pin.matches("[0-9]{4,8}");}
    private static byte[] hash(char[] password,byte[] salt) throws Exception {
        PBEKeySpec spec=new PBEKeySpec(password,salt,100_000,256);
        try {return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
        finally{spec.clearPassword();}
    }
    static boolean setup(Context c,String pin){
        if(!valid(pin))return false;
        try {
            byte[] salt=new byte[24];new SecureRandom().nextBytes(salt);
            byte[] result=hash(pin.toCharArray(),salt);
            prefs(c).edit().putString("salt",Base64.encodeToString(salt,Base64.NO_WRAP))
                .putString("hash",Base64.encodeToString(result,Base64.NO_WRAP)).apply();
            return true;
        }catch(Exception ignored){return false;}
    }
    static boolean verify(Context c,String pin){
        if(!valid(pin))return false;
        try {
            byte[] salt=Base64.decode(prefs(c).getString("salt",""),Base64.NO_WRAP);
            byte[] expected=Base64.decode(prefs(c).getString("hash",""),Base64.NO_WRAP);
            return MessageDigest.isEqual(expected,hash(pin.toCharArray(),salt));
        }catch(Exception ignored){return false;}
    }
    private PinGate(){}
}