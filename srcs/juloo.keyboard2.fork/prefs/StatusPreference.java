package juloo.keyboard2.fork.prefs;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.Preference;
import android.util.AttributeSet;
import juloo.keyboard2.fork.DirectBootAwarePreferences;

/*
 ** StatusPreference
 ** - Read-only preference that displays dynamic status information
 ** - Updates summary based on current preference values
 */
public class StatusPreference extends Preference {
  
  public StatusPreference(Context context, AttributeSet attrs) {
    super(context, attrs);
    setEnabled(false); // Make it read-only
    updateSummary();
  }
  
  @Override
  protected void onAttachedToActivity() {
    super.onAttachedToActivity();
    updateSummary();
  }
  
  public void updateSummary() {
    String key = getKey();
    if (key == null) return;
    
    SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(getContext());
    
    if ("floating_keyboard_current_dimensions".equals(key)) {
      // Get current orientation
      boolean currentLandscape = getContext().getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
      String suffix = currentLandscape ? "_landscape" : "_portrait";
      
      String widthKey = "floating_keyboard_width_px" + suffix;
      String heightKey = "floating_keyboard_height_px" + suffix;
      
      int widthPx = prefs.getInt(widthKey, -1);
      int heightPx = prefs.getInt(heightKey, -1);
      
      if (widthPx != -1 && heightPx != -1) {
        setSummary(widthPx + " × " + heightPx + " px");
      } else {
        setSummary("Not yet set");
      }
    } else if ("floating_keyboard_current_position".equals(key)) {
      // Get current orientation
      boolean currentLandscape = getContext().getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
      String suffix = currentLandscape ? "_landscape" : "_portrait";
      
      String xKey = "floating_keyboard_x" + suffix;
      String yKey = "floating_keyboard_y" + suffix;
      
      int x = prefs.getInt(xKey, -1);
      int y = prefs.getInt(yKey, -1);
      
      if (x != -1 && y != -1) {
        setSummary("(" + x + ", " + y + ") px");
      } else {
        setSummary("Using defaults");
      }
    }
  }
}