/*
 * Android stub for PC compilation only.
 */
package android.content;

public class Intent {
    public Intent() {}
    public Intent(Context context, Class cls) {}
    public Intent setAction(String action) { return this; }
    public String getAction() { return null; }
    public boolean hasExtra(String name) { return false; }
    public String getStringExtra(String name) { return null; }
    public Intent putExtra(String name, String value) { return this; }
    public Intent putExtra(String name, boolean value) { return this; }
}
