package juloo.keyboard2.fork;

import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.widget.Toast;
import juloo.keyboard2.fork.prefs.StatusPreference;
import java.io.File;
import java.io.FileWriter;
import java.io.FileInputStream;
import java.util.Map;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

public class SettingsActivity extends PreferenceActivity
{
  private static final String EXPORT_DIR = "/storage/emulated/0/shared/unexpected_keyboard";
  private static final String EXPORT_FILE = "settings.xml";

  @Override
  public void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    // The preferences can't be read when in direct-boot mode. Avoid crashing
    // and don't allow changing the settings.
    // Run the config migration on this prefs as it might be different from the
    // one used by the keyboard, which have been migrated.
    try
    {
      Config.migrate(getPreferenceManager().getSharedPreferences());
    }
    catch (Exception _e) { fallbackEncrypted(); return; }
    addPreferencesFromResource(R.xml.settings);

    boolean foldableDevice = FoldStateTracker.isFoldableDevice(this);
    findPreference("margin_bottom_portrait_unfolded").setEnabled(foldableDevice);
    findPreference("margin_bottom_landscape_unfolded").setEnabled(foldableDevice);
    findPreference("horizontal_margin_portrait_unfolded").setEnabled(foldableDevice);
    findPreference("horizontal_margin_landscape_unfolded").setEnabled(foldableDevice);
    findPreference("keyboard_height_unfolded").setEnabled(foldableDevice);
    findPreference("keyboard_height_landscape_unfolded").setEnabled(foldableDevice);

    // Update status preferences to show current values
    StatusPreference dimensionsStatus = (StatusPreference)findPreference("floating_keyboard_current_dimensions");
    StatusPreference positionStatus = (StatusPreference)findPreference("floating_keyboard_current_position");
    if (dimensionsStatus != null) dimensionsStatus.updateSummary();
    if (positionStatus != null) positionStatus.updateSummary();

    // Export settings button
    Preference exportPref = findPreference("export_settings");
    if (exportPref != null)
    {
      exportPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
        @Override
        public boolean onPreferenceClick(Preference preference) {
          exportSettings();
          return true;
        }
      });
    }

    // Import settings button
    Preference importPref = findPreference("import_settings");
    if (importPref != null)
    {
      importPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
        @Override
        public boolean onPreferenceClick(Preference preference) {
          importSettings();
          return true;
        }
      });
    }
  }

  void fallbackEncrypted()
  {
    // Can't communicate with the user here.
    finish();
  }

  protected void onStop()
  {
    DirectBootAwarePreferences
      .copy_preferences_to_protected_storage(this,
          getPreferenceManager().getSharedPreferences());
    super.onStop();
  }

  /** Export all settings to an XML file. */
  private void exportSettings()
  {
    try
    {
      SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
      Map<String, ?> allPrefs = prefs.getAll();

      File dir = new File(EXPORT_DIR);
      if (!dir.exists()) dir.mkdirs();
      File file = new File(dir, EXPORT_FILE);

      StringBuilder xml = new StringBuilder();
      xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
      xml.append("<keyboard-settings>\n");

      for (Map.Entry<String, ?> entry : allPrefs.entrySet())
      {
        String key = escapeXml(entry.getKey());
        Object value = entry.getValue();
        String type;
        String valueStr;

        if (value instanceof Boolean) {
          type = "boolean";
          valueStr = value.toString();
        } else if (value instanceof Integer) {
          type = "int";
          valueStr = value.toString();
        } else if (value instanceof Float) {
          type = "float";
          valueStr = value.toString();
        } else if (value instanceof Long) {
          type = "long";
          valueStr = value.toString();
        } else if (value instanceof String) {
          type = "string";
          valueStr = escapeXml((String)value);
        } else {
          continue; // Skip unsupported types (Sets etc.)
        }

        xml.append("  <pref key=\"").append(key)
           .append("\" type=\"").append(type)
           .append("\" value=\"").append(valueStr).append("\"/>\n");
      }

      xml.append("</keyboard-settings>\n");

      FileWriter writer = new FileWriter(file);
      writer.write(xml.toString());
      writer.close();

      Toast.makeText(this, "Settings exported to " + file.getAbsolutePath(),
          Toast.LENGTH_LONG).show();
    }
    catch (Exception e)
    {
      Toast.makeText(this, "Export failed: " + e.getMessage(),
          Toast.LENGTH_LONG).show();
    }
  }

  /** Import settings from an XML file. */
  private void importSettings()
  {
    try
    {
      File file = new File(EXPORT_DIR, EXPORT_FILE);
      if (!file.exists())
      {
        Toast.makeText(this, "No settings file found at " + file.getAbsolutePath(),
            Toast.LENGTH_LONG).show();
        return;
      }

      SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
      SharedPreferences.Editor editor = prefs.edit();

      XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
      XmlPullParser parser = factory.newPullParser();
      FileInputStream fis = new FileInputStream(file);
      parser.setInput(fis, "UTF-8");

      int count = 0;
      int eventType = parser.getEventType();
      while (eventType != XmlPullParser.END_DOCUMENT)
      {
        if (eventType == XmlPullParser.START_TAG && "pref".equals(parser.getName()))
        {
          String key = parser.getAttributeValue(null, "key");
          String type = parser.getAttributeValue(null, "type");
          String value = parser.getAttributeValue(null, "value");

          if (key != null && type != null && value != null)
          {
            switch (type)
            {
              case "boolean":
                editor.putBoolean(key, Boolean.parseBoolean(value));
                break;
              case "int":
                editor.putInt(key, Integer.parseInt(value));
                break;
              case "float":
                editor.putFloat(key, Float.parseFloat(value));
                break;
              case "long":
                editor.putLong(key, Long.parseLong(value));
                break;
              case "string":
                editor.putString(key, value);
                break;
            }
            count++;
          }
        }
        eventType = parser.next();
      }
      fis.close();

      editor.apply();
      Toast.makeText(this, "Imported " + count + " settings from " + file.getAbsolutePath() +
          "\nRestart keyboard for changes to take effect.",
          Toast.LENGTH_LONG).show();

      // Recreate the activity to show updated values
      recreate();
    }
    catch (Exception e)
    {
      Toast.makeText(this, "Import failed: " + e.getMessage(),
          Toast.LENGTH_LONG).show();
    }
  }

  private static String escapeXml(String s)
  {
    return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
  }
}
