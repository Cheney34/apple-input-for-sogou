package de.robv.android.xposed;
public class XSharedPreferences {
    public XSharedPreferences(String packageName, String prefFileName) {}
    public XSharedPreferences(java.io.File prefFile) {}
    public void reload() {}
    public boolean getBoolean(String key, boolean defValue) { return defValue; }
    public String getString(String key, String defValue) { return defValue; }
    public int getInt(String key, int defValue) { return defValue; }
    public long getLong(String key, long defValue) { return defValue; }
    public boolean contains(String key) { return false; }
}
