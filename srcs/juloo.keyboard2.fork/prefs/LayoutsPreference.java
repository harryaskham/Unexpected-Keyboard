package juloo.keyboard2.fork.prefs;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import juloo.keyboard2.fork.*;
import org.json.JSONException;
import org.json.JSONObject;

public class LayoutsPreference extends ListGroupPreference<LayoutsPreference.Layout>
{
  static final String KEY = "layouts";
  static final List<Layout> DEFAULT =
    Collections.singletonList((Layout)new SystemLayout());
  static final ListGroupPreference.Serializer<Layout> SERIALIZER =
    new Serializer();

  /** Text displayed for each layout in the dialog list. */
  String[] _layout_display_names;

  public LayoutsPreference(Context ctx, AttributeSet attrs)
  {
    super(ctx, attrs);
    setKey(KEY);
    Resources res = ctx.getResources();
    _layout_display_names = res.getStringArray(R.array.pref_layout_entries);
  }

  /** Obtained from [res/values/layouts.xml]. */
  static List<String> _unsafe_layout_ids_str = null;
  static TypedArray _unsafe_layout_ids_res = null;

  /** Layout internal names. Contains "system" and "custom". */
  public static List<String> get_layout_names(Resources res)
  {
    if (_unsafe_layout_ids_str == null)
      _unsafe_layout_ids_str = Arrays.asList(
          res.getStringArray(R.array.pref_layout_values));
    return _unsafe_layout_ids_str;
  }

  /** Layout resource id for a layout name. [-1] if not found. */
  public static int layout_id_of_name(Resources res, String name)
  {
    if (_unsafe_layout_ids_res == null)
      _unsafe_layout_ids_res = res.obtainTypedArray(R.array.layout_ids);
    int i = get_layout_names(res).indexOf(name);
    if (i >= 0)
      return _unsafe_layout_ids_res.getResourceId(i, 0);
    return -1;
  }

  /** [null] for the "system" layout. */
  public static List<KeyboardData> load_from_preferences(Resources res, SharedPreferences prefs)
  {
    List<KeyboardData> layouts = new ArrayList<KeyboardData>();
    for (Layout l : load_from_preferences(KEY, prefs, DEFAULT, SERIALIZER))
    {
      if (l instanceof NamedLayout)
        layouts.add(layout_of_string(res, ((NamedLayout)l).name));
      else if (l instanceof CustomLayout)
        layouts.add(((CustomLayout)l).parsed);
      else if (l instanceof DirectoryLayout)
      {
        // Directory layouts are not stored - they are expanded into individual custom layouts
        // This should not happen in normal operation
        layouts.add(null);
      }
      else // instanceof SystemLayout
        layouts.add(null);
    }
    return layouts;
  }

  /** Does not call [prefs.commit()]. */
  public static void save_to_preferences(SharedPreferences.Editor prefs, List<Layout> items)
  {
    save_to_preferences(KEY, prefs, items, SERIALIZER);
  }

  public static KeyboardData layout_of_string(Resources res, String name)
  {
    int id = layout_id_of_name(res, name);
    if (id > 0)
      return KeyboardData.load(res, id);
    // Might happen when the app is downgraded, return the system layout.
    return null;
  }

  @Override
  protected void onSetInitialValue(boolean restoreValue, Object defaultValue)
  {
    super.onSetInitialValue(restoreValue, defaultValue);
    if (_values.size() == 0)
      set_values(new ArrayList<Layout>(DEFAULT), false);
  }

  String label_of_layout(Layout l)
  {
    if (l instanceof NamedLayout)
    {
      String lname = ((NamedLayout)l).name;
      int value_i = get_layout_names(getContext().getResources()).indexOf(lname);
      return value_i < 0 ? lname : _layout_display_names[value_i];
    }
    else if (l instanceof CustomLayout)
    {
      // Use the layout's name if possible
      CustomLayout cl = (CustomLayout)l;
      if (cl.parsed != null && cl.parsed.name != null
          && !cl.parsed.name.equals(""))
        return cl.parsed.name;
      else
        return getContext().getString(R.string.pref_layout_e_custom);
    }
    else if (l instanceof DirectoryLayout)
    {
      DirectoryLayout dl = (DirectoryLayout)l;
      String dirName = new File(dl.path).getName();
      return "Directory: " + dirName + " (" + dl.layouts.size() + " layouts)";
    }
    else // instanceof SystemLayout
      return getContext().getString(R.string.pref_layout_e_system);
  }

  @Override
  String label_of_value(Layout value, int i)
  {
    return getContext().getString(R.string.pref_layouts_item, i + 1,
        label_of_layout(value));
  }

  @Override
  AddButton on_attach_add_button(AddButton prev_btn)
  {
    if (prev_btn == null)
      return new LayoutsAddButton(getContext());
    return prev_btn;
  }

  @Override
  boolean should_allow_remove_item(Layout value)
  {
    return (_values.size() > 1 && !(value instanceof DirectoryLayout));
  }

  @Override
  ListGroupPreference.Serializer<Layout> get_serializer() { return SERIALIZER; }

  void select_dialog(final SelectionCallback callback)
  {
    // Add directory option to layout names
    String[] enhancedLayoutNames = new String[_layout_display_names.length + 3];
    System.arraycopy(_layout_display_names, 0, enhancedLayoutNames, 0, _layout_display_names.length);
    enhancedLayoutNames[_layout_display_names.length] = "Load from Directory";
    enhancedLayoutNames[_layout_display_names.length + 1] = "Refresh Directory Layouts";
    enhancedLayoutNames[_layout_display_names.length + 2] = "Remove All Custom Layouts";
    
    ArrayAdapter layouts = new ArrayAdapter(getContext(), android.R.layout.simple_list_item_1, enhancedLayoutNames);
    new AlertDialog.Builder(getContext())
      .setView(View.inflate(getContext(), R.layout.dialog_edit_text, null))
      .setAdapter(layouts, new DialogInterface.OnClickListener(){
        public void onClick(DialogInterface _dialog, int which)
        {
          if (which == _layout_display_names.length)
          {
            // Load from Directory option selected
            select_directory(callback);
            return;
          }
          if (which == _layout_display_names.length + 1)
          {
            // Refresh Directory Layouts option selected
            refreshDirectoryLayouts(callback);
            return;
          }
          if (which == _layout_display_names.length + 2)
          {
            // Remove All Custom Layouts option selected
            removeAllCustomLayouts(callback);
            return;
          }
          
          String name = get_layout_names(getContext().getResources()).get(which);
          switch (name)
          {
            case "system":
              callback.select(new SystemLayout());
              break;
            case "custom":
              select_custom(callback, read_initial_custom_layout());
              break;
            default:
              callback.select(new NamedLayout(name));
              break;
          }
        }
      })
      .show();
  }

  /** Check if we have storage permissions. */
  private boolean hasStoragePermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      // Android 11+ - check for MANAGE_EXTERNAL_STORAGE
      return Environment.isExternalStorageManager();
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      // Android 6+ - check for READ_EXTERNAL_STORAGE
      return getContext().checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) 
             == PackageManager.PERMISSION_GRANTED;
    }
    return true; // Pre-Android 6
  }
  
  /** Request storage permissions. */
  private void requestStoragePermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      // Android 11+ - request MANAGE_EXTERNAL_STORAGE
      try {
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
        intent.setData(Uri.parse("package:" + getContext().getPackageName()));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
        Toast.makeText(getContext(), 
            "Please enable 'All files access' permission to load layouts from directories", 
            Toast.LENGTH_LONG).show();
      } catch (Exception e) {
        // Fallback to general settings
        Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
      }
    } else {
      Toast.makeText(getContext(), 
          "Storage permission required. Please enable in app settings.", 
          Toast.LENGTH_LONG).show();
    }
  }

  /** Get a suggested directory path that should be accessible. */
  private String getSuggestedLayoutPath() {
    // Try app-specific external files directory first (doesn't require permissions)
    File externalFilesDir = getContext().getExternalFilesDir("layouts");
    if (externalFilesDir != null) {
      return externalFilesDir.getAbsolutePath();
    }
    // Fallback to common accessible locations
    return "/storage/emulated/0/Download/layouts";
  }
  
  /** Dialog for specifying a directory path containing XML layouts. */
  void select_directory(final SelectionCallback callback)
  {
    final EditText input = new EditText(getContext());
    input.setHint(getSuggestedLayoutPath());
    
    new AlertDialog.Builder(getContext())
      .setTitle("Select Layout Directory")
      .setMessage("Enter the full path to a directory containing XML layout files:")
      .setView(input)
      .setPositiveButton("OK", new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
          String path = input.getText().toString().trim();
          if (!path.isEmpty()) {
            // Check if accessing paths outside app directory
            String appDataPath = getContext().getExternalFilesDir(null) != null ? 
                getContext().getExternalFilesDir(null).getAbsolutePath() : "";
            
            if (!path.startsWith(appDataPath) && !hasStoragePermission()) {
              new AlertDialog.Builder(getContext())
                .setTitle("Storage Permission Required")
                .setMessage("Accessing files outside app directory requires storage permission. Use app directory (" + getSuggestedLayoutPath() + ") or grant permission?")
                .setPositiveButton("Grant Permission", new DialogInterface.OnClickListener() {
                  @Override
                  public void onClick(DialogInterface dialog, int which) {
                    requestStoragePermission();
                  }
                })
                .setNegativeButton("Use App Directory", new DialogInterface.OnClickListener() {
                  @Override
                  public void onClick(DialogInterface dialog, int which) {
                    input.setText(getSuggestedLayoutPath());
                  }
                })
                .show();
              return;
            }
            
            DirectoryLayout dirLayout = new DirectoryLayout(path);
            if (dirLayout.layouts.isEmpty()) {
              // Show warning
              new AlertDialog.Builder(getContext())
                .setTitle("No Layouts Found")
                .setMessage("No valid XML layouts found in directory: " + path + "\nCheck device logs with 'adb logcat | grep LayoutsPreference' for detailed error information.")
                .setPositiveButton("OK", null)
                .show();
            } else {
              // Save the directory path for refresh functionality
              getContext().getSharedPreferences("directory_layouts", 0)
                  .edit().putString("last_directory", path).apply();
              
              // Add all layouts from directory as individual custom layouts
              addLayoutsFromDirectory(dirLayout, callback);
            }
          }
        }
      })
      .setNegativeButton("Cancel", null)
      .show();
  }

  /** Dialog for specifying a custom layout. [initial_text] is the layout
      description when modifying a layout. */
  void select_custom(final SelectionCallback callback, String initial_text)
  {
    boolean allow_remove = callback.allow_remove() && _values.size() > 1;
    CustomLayoutEditDialog.show(getContext(), initial_text, allow_remove,
        new CustomLayoutEditDialog.Callback()
        {
          public void select(String text)
          {
            if (text == null)
              callback.select(null);
            else
              callback.select(CustomLayout.parse(text));
          }

          public String validate(String text)
          {
            try
            {
              KeyboardData.load_string_exn(text);
              return null; // Validation passed
            }
            catch (Exception e)
            {
              return e.getMessage();
            }
          }
        });
  }

  /** Remove all custom layouts, keeping only named and system layouts. */
  private void removeAllCustomLayouts(final SelectionCallback callback) {
    new AlertDialog.Builder(getContext())
      .setTitle("Remove All Custom Layouts")
      .setMessage("This will remove all custom layouts loaded from files and directories. Named and system layouts will be kept.\n\nThis action cannot be undone. Are you sure?")
      .setPositiveButton("Remove All", new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
          // Filter out all CustomLayout instances, keeping only NamedLayout and SystemLayout
          List<Layout> filteredLayouts = new ArrayList<Layout>();
          int removedCount = 0;
          
          for (Layout layout : _values) {
            if (layout instanceof CustomLayout) {
              removedCount++;
            } else {
              // Keep named layouts and system layout
              filteredLayouts.add(layout);
            }
          }
          
          // Ensure we always have at least the system layout
          if (filteredLayouts.isEmpty()) {
            filteredLayouts.add(new SystemLayout());
          }
          
          set_values(filteredLayouts, true);
          
          Toast.makeText(getContext(), 
              "Removed " + removedCount + " custom layouts", 
              Toast.LENGTH_SHORT).show();
              
          callback.select(null);
        }
      })
      .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
          callback.select(null);
        }
      })
      .show();
  }

  /** Refresh directory layouts by re-scanning last used directory. */
  private void refreshDirectoryLayouts(SelectionCallback callback) {
    // Get the last used directory path from shared preferences or ask user
    String lastPath = getContext().getSharedPreferences("directory_layouts", 0)
        .getString("last_directory", getSuggestedLayoutPath());
    
    final EditText input = new EditText(getContext());
    input.setText(lastPath);
    
    new AlertDialog.Builder(getContext())
      .setTitle("Refresh Directory Layouts")
      .setMessage("Re-scan directory for layout changes:")
      .setView(input)
      .setPositiveButton("Refresh", new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
          String path = input.getText().toString().trim();
          if (!path.isEmpty()) {
            // Save the path for next time
            getContext().getSharedPreferences("directory_layouts", 0)
                .edit().putString("last_directory", path).apply();
            
            DirectoryLayout dirLayout = new DirectoryLayout(path);
            if (!dirLayout.layouts.isEmpty()) {
              // For refresh, remove existing layouts from this directory first to prevent duplicates
              refreshLayoutsFromDirectory(dirLayout, callback);
            } else {
              Toast.makeText(getContext(), "No layouts found in directory", Toast.LENGTH_SHORT).show();
            }
          }
        }
      })
      .setNegativeButton("Cancel", null)
      .show();
  }

  /** Refresh layouts from a directory, removing duplicates first. */
  private void refreshLayoutsFromDirectory(DirectoryLayout dirLayout, SelectionCallback callback) {
    // First, remove any existing custom layouts that might be from this directory
    // We'll do this by removing layouts with duplicate XML content
    List<String> newXmlContents = new ArrayList<String>();
    
    // Load all XML files and prepare their content
    File dir = new File(dirLayout.path);
    File[] xmlFiles = dir.listFiles(new java.io.FilenameFilter() {
      @Override
      public boolean accept(File dir, String name) {
        return name.toLowerCase().endsWith(".xml");
      }
    });
    
    if (xmlFiles != null) {
      for (File xmlFile : xmlFiles) {
        try {
          FileInputStream fis = new FileInputStream(xmlFile);
          byte[] data = new byte[(int) xmlFile.length()];
          int bytesRead = fis.read(data);
          fis.close();
          
          if (bytesRead > 0) {
            String xmlContent = new String(data, 0, bytesRead, "UTF-8");
            // Test that the XML is valid before adding to removal list
            KeyboardData testLayout = KeyboardData.load_string_exn(xmlContent);
            if (testLayout != null) {
              newXmlContents.add(xmlContent.trim());
            }
          }
        } catch (Exception e) {
          android.util.Log.w("LayoutsPreference", "Failed to pre-load layout from " + xmlFile.getName() + ": " + e.getMessage());
        }
      }
    }
    
    // Remove existing custom layouts that match any of the XML contents we're about to add
    List<Layout> cleanedLayouts = new ArrayList<Layout>();
    int removedCount = 0;
    
    for (Layout layout : _values) {
      if (layout instanceof CustomLayout) {
        String existingXml = ((CustomLayout)layout).xml.trim();
        boolean isDuplicate = false;
        for (String newXml : newXmlContents) {
          if (existingXml.equals(newXml)) {
            isDuplicate = true;
            removedCount++;
            break;
          }
        }
        if (!isDuplicate) {
          cleanedLayouts.add(layout);
        }
      } else {
        // Keep named layouts and system layout
        cleanedLayouts.add(layout);
      }
    }
    
    // Update the layout list with duplicates removed
    set_values(cleanedLayouts, true);
    
    if (removedCount > 0) {
      android.util.Log.i("LayoutsPreference", "Removed " + removedCount + " duplicate layouts before refresh");
    }
    
    // Now add the fresh layouts from the directory
    addLayoutsFromDirectory(dirLayout, callback);
  }

  /** Add all layouts from a directory as individual custom layouts. */
  private void addLayoutsFromDirectory(DirectoryLayout dirLayout, SelectionCallback callback) {
    try {
      File dir = new File(dirLayout.path);
      File[] xmlFiles = dir.listFiles(new java.io.FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
          return name.toLowerCase().endsWith(".xml");
        }
      });
      
      if (xmlFiles != null) {
        // Sort files by name for consistent ordering
        Arrays.sort(xmlFiles, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        
        List<Layout> currentLayouts = new ArrayList<Layout>(_values);
        int addedCount = 0;
        int replacedCount = 0;
        
        for (File xmlFile : xmlFiles) {
          try {
            FileInputStream fis = new FileInputStream(xmlFile);
            byte[] data = new byte[(int) xmlFile.length()];
            int bytesRead = fis.read(data);
            fis.close();
            
            if (bytesRead > 0) {
              String xmlContent = new String(data, 0, bytesRead, "UTF-8");
              
              // Test that the XML is valid
              KeyboardData testLayout = KeyboardData.load_string_exn(xmlContent);
              if (testLayout != null) {
                CustomLayout customLayout = new CustomLayout(xmlContent, testLayout);
                String layoutName = getLayoutDisplayName(testLayout);
                
                // Check if a layout with this name already exists and replace it
                boolean foundExisting = false;
                for (int i = 0; i < currentLayouts.size(); i++) {
                  Layout existingLayout = currentLayouts.get(i);
                  String existingName = getLayoutDisplayName(existingLayout);
                  
                  if (layoutName.equals(existingName)) {
                    // Replace existing layout with same name
                    currentLayouts.set(i, customLayout);
                    foundExisting = true;
                    replacedCount++;
                    android.util.Log.i("LayoutsPreference", "Replaced layout: " + layoutName);
                    break;
                  }
                }
                
                if (!foundExisting) {
                  // Add new layout
                  currentLayouts.add(customLayout);
                  addedCount++;
                  android.util.Log.i("LayoutsPreference", "Added layout: " + layoutName);
                }
              }
            }
          } catch (Exception e) {
            android.util.Log.w("LayoutsPreference", "Failed to load layout from " + xmlFile.getName() + ": " + e.getMessage());
          }
        }
        
        // Ensure we have at least one SystemLayout if no other layouts exist
        boolean hasSystemLayout = false;
        for (Layout layout : currentLayouts) {
          if (layout instanceof SystemLayout) {
            hasSystemLayout = true;
            break;
          }
        }
        
        if (!hasSystemLayout && currentLayouts.isEmpty()) {
          currentLayouts.add(0, new SystemLayout());
        }
        
        // Sort all layouts by name (keeping SystemLayout first if present)
        sortLayoutsByName(currentLayouts);
        set_values(currentLayouts, true);
        
        String message = "Added " + addedCount + " layouts";
        if (replacedCount > 0) {
          message += ", replaced " + replacedCount + " existing";
        }
        message += " from directory";
        
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        
        // Call callback with null to close the dialog
        callback.select(null);
      }
    } catch (Exception e) {
      android.util.Log.e("LayoutsPreference", "Error adding layouts from directory: " + e.getMessage());
      Toast.makeText(getContext(), "Error adding layouts: " + e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  /** Get display name for a layout, handling different layout types. */
  private String getLayoutDisplayName(Layout layout) {
    if (layout instanceof CustomLayout) {
      CustomLayout cl = (CustomLayout)layout;
      if (cl.parsed != null && cl.parsed.name != null && !cl.parsed.name.equals("")) {
        return cl.parsed.name;
      }
      return getContext().getString(R.string.pref_layout_e_custom);
    }
    return label_of_layout(layout);
  }
  
  /** Get display name for a KeyboardData layout. */
  private String getLayoutDisplayName(KeyboardData layout) {
    if (layout != null && layout.name != null && !layout.name.equals("")) {
      return layout.name;
    }
    return "Custom Layout";
  }
  
  /** Sort layouts by name, keeping SystemLayout first if present. */
  private void sortLayoutsByName(List<Layout> layouts) {
    // Separate SystemLayout from others
    List<Layout> systemLayouts = new ArrayList<Layout>();
    List<Layout> otherLayouts = new ArrayList<Layout>();
    
    for (Layout layout : layouts) {
      if (layout instanceof SystemLayout) {
        systemLayouts.add(layout);
      } else {
        otherLayouts.add(layout);
      }
    }
    
    // Sort non-system layouts by display name
    Collections.sort(otherLayouts, (a, b) -> {
      String nameA = getLayoutDisplayName(a);
      String nameB = getLayoutDisplayName(b);
      return nameA.compareToIgnoreCase(nameB);
    });
    
    // Rebuild list with SystemLayout first, then sorted others
    layouts.clear();
    layouts.addAll(systemLayouts);
    layouts.addAll(otherLayouts);
  }

  /** Called when modifying a layout. Custom layouts behave differently. */
  @Override
  void select(final SelectionCallback callback, Layout prev_layout)
  {
    if (prev_layout != null && prev_layout instanceof CustomLayout)
      select_custom(callback, ((CustomLayout)prev_layout).xml);
    else
      select_dialog(callback);
  }

  /** The initial text for the custom layout entry box. The qwerty_us layout is
      a good default and contains a bit of documentation. */
  String read_initial_custom_layout()
  {
    try
    {
      Resources res = getContext().getResources();
      return Utils.read_all_utf8(res.openRawResource(R.raw.latn_qwerty_us));
    }
    catch (Exception _e)
    {
      return "";
    }
  }

  class LayoutsAddButton extends AddButton
  {
    public LayoutsAddButton(Context ctx)
    {
      super(ctx);
      setLayoutResource(R.layout.pref_layouts_add_btn);
    }
  }

  /** A layout selected by the user. The only implementations are
      [NamedLayout], [SystemLayout], [CustomLayout], and [DirectoryLayout]. */
  public interface Layout {}

  public static final class SystemLayout implements Layout
  {
    public SystemLayout() {}
  }

  /** The name of a layout defined in [srcs/layouts]. */
  public static final class NamedLayout implements Layout
  {
    public final String name;
    public NamedLayout(String n) { name = n; }
  }

  /** The XML description of a custom layout. */
  public static final class CustomLayout implements Layout
  {
    public final String xml;
    /** Might be null. */
    public final KeyboardData parsed;
    public CustomLayout(String xml_, KeyboardData k) { xml = xml_; parsed = k; }
    public static CustomLayout parse(String xml)
    {
      KeyboardData parsed = null;
      try { parsed = KeyboardData.load_string_exn(xml); }
      catch (Exception e) {}
      return new CustomLayout(xml, parsed);
    }
  }

  /** A layout loaded from a directory path containing XML files. */
  public static final class DirectoryLayout implements Layout
  {
    public final String path;
    /** List of parsed layouts from directory. Might be empty. */
    public final List<KeyboardData> layouts;
    
    public DirectoryLayout(String path_) 
    { 
      path = path_; 
      layouts = loadLayoutsFromDirectory(path_);
    }
    
    private static List<KeyboardData> loadLayoutsFromDirectory(String dirPath)
    {
      List<KeyboardData> result = new ArrayList<KeyboardData>();
      try
      {
        File dir = new File(dirPath);
        if (!dir.exists()) {
          android.util.Log.w("LayoutsPreference", "Directory does not exist: " + dirPath);
          return result;
        }
        if (!dir.isDirectory()) {
          android.util.Log.w("LayoutsPreference", "Path is not a directory: " + dirPath);
          return result;
        }
        if (!dir.canRead()) {
          android.util.Log.w("LayoutsPreference", "Cannot read directory: " + dirPath);
          return result;
        }
          
        File[] xmlFiles = dir.listFiles(new java.io.FilenameFilter() {
          @Override
          public boolean accept(File dir, String name) {
            return name.toLowerCase().endsWith(".xml");
          }
        });
        
        if (xmlFiles == null) {
          android.util.Log.w("LayoutsPreference", "Failed to list files in directory: " + dirPath);
          return result;
        }
        
        android.util.Log.i("LayoutsPreference", "Found " + xmlFiles.length + " XML files in " + dirPath);
        
        for (File xmlFile : xmlFiles)
        {
          try
          {
            if (!xmlFile.canRead()) {
              android.util.Log.w("LayoutsPreference", "Cannot read file: " + xmlFile.getName());
              continue;
            }
            
            FileInputStream fis = new FileInputStream(xmlFile);
            byte[] data = new byte[(int) xmlFile.length()];
            int bytesRead = fis.read(data);
            fis.close();
            
            if (bytesRead > 0) {
              String xmlContent = new String(data, 0, bytesRead, "UTF-8");
              KeyboardData layout = KeyboardData.load_string_exn(xmlContent);
              if (layout != null) {
                result.add(layout);
                android.util.Log.i("LayoutsPreference", "Successfully loaded layout from: " + xmlFile.getName());
              }
            }
          }
          catch (Exception e)
          {
            android.util.Log.w("LayoutsPreference", "Failed to load layout from " + xmlFile.getName() + ": " + e.getMessage());
          }
        }
      }
      catch (Exception e)
      {
        android.util.Log.e("LayoutsPreference", "Error loading layouts from directory " + dirPath + ": " + e.getMessage());
      }
      
      android.util.Log.i("LayoutsPreference", "Loaded " + result.size() + " layouts from " + dirPath);
      return result;
    }
  }

  /** Named layouts are serialized to strings and custom layouts to JSON
      objects with a [kind] field. */
  public static class Serializer implements ListGroupPreference.Serializer<Layout>
  {
    public Layout load_item(Object obj) throws JSONException
    {
      if (obj instanceof String)
      {
        String name = (String)obj;
        if (name.equals("system"))
          return new SystemLayout();
        return new NamedLayout(name);
      }
      JSONObject obj_ = (JSONObject)obj;
      switch (obj_.getString("kind"))
      {
        case "custom": return CustomLayout.parse(obj_.getString("xml"));
        case "directory": return new DirectoryLayout(obj_.getString("path"));
        case "system": default: return new SystemLayout();
      }
    }

    public Object save_item(Layout v) throws JSONException
    {
      if (v instanceof NamedLayout)
        return ((NamedLayout)v).name;
      if (v instanceof CustomLayout)
        return new JSONObject().put("kind", "custom")
          .put("xml", ((CustomLayout)v).xml);
      if (v instanceof DirectoryLayout)
        return new JSONObject().put("kind", "directory")
          .put("path", ((DirectoryLayout)v).path);
      return new JSONObject().put("kind", "system");
    }
  }
}
