package com.bgmi.utils;

import android.content.Context;
import android.content.SharedPreferences;


@SuppressWarnings({"unused","deprecation"})
public class Prefs {
    private static Prefs instance;
    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext()
                .getSharedPreferences("ark_settings", Context.MODE_PRIVATE);
    }

    public static Prefs with(Context ctx) {
        if (instance == null) instance = new Prefs(ctx);
        return instance;
    }

    // LoginActivity API
    public String  read(String key, String def)  { return sp.getString(key, def); }
    public void    write(String key, String val) { sp.edit().putString(key, val).apply(); }

    // Extended API
    public boolean getBool(String k, boolean d)  { return sp.getBoolean(k, d); }
    public void    setBool(String k, boolean v)  { sp.edit().putBoolean(k, v).apply(); }
    public int     getInt(String k, int d)        { return sp.getInt(k, d); }
    public void    setInt(String k, int v)        { sp.edit().putInt(k, v).apply(); }
    public String  getSt(String k, String def)    { return sp.getString(k, def); }
    public void    setSt(String k, String v)      { sp.edit().putString(k, v).apply(); }
}
