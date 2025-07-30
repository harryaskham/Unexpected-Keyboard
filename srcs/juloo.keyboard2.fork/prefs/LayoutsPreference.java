package juloo.keyboard2.fork.prefs;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
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
        // For directory layouts, add the first valid layout found
        List<KeyboardData> dirLayouts = ((DirectoryLayout)l).layouts;
        if (!dirLayouts.isEmpty())
          layouts.add(dirLayouts.get(0));
        else
          layouts.add(null); // Fallback to system layout if directory is empty
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
    return (_values.size() > 1 && !(value instanceof CustomLayout) && !(value instanceof DirectoryLayout));
  }

  @Override
  ListGroupPreference.Serializer<Layout> get_serializer() { return SERIALIZER; }

  void select_dialog(final SelectionCallback callback)
  {
    // Add directory option to layout names
    String[] enhancedLayoutNames = new String[_layout_display_names.length + 1];
    System.arraycopy(_layout_display_names, 0, enhancedLayoutNames, 0, _layout_display_names.length);
    enhancedLayoutNames[_layout_display_names.length] = "Directory";
    
    ArrayAdapter layouts = new ArrayAdapter(getContext(), android.R.layout.simple_list_item_1, enhancedLayoutNames);
    new AlertDialog.Builder(getContext())
      .setView(View.inflate(getContext(), R.layout.dialog_edit_text, null))
      .setAdapter(layouts, new DialogInterface.OnClickListener(){
        public void onClick(DialogInterface _dialog, int which)
        {
          if (which == _layout_display_names.length)
          {
            // Directory option selected
            select_directory(callback);
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

  /** Dialog for specifying a directory path containing XML layouts. */
  void select_directory(final SelectionCallback callback)
  {
    final EditText input = new EditText(getContext());
    input.setHint("/storage/emulated/0/UnexpectedKeyboard/layouts");
    
    new AlertDialog.Builder(getContext())
      .setTitle("Select Layout Directory")
      .setMessage("Enter the full path to a directory containing XML layout files:")
      .setView(input)
      .setPositiveButton("OK", new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
          String path = input.getText().toString().trim();
          if (!path.isEmpty()) {
            DirectoryLayout dirLayout = new DirectoryLayout(path);
            if (dirLayout.layouts.isEmpty()) {
              // Show warning but still allow selection
              new AlertDialog.Builder(getContext())
                .setTitle("Warning")
                .setMessage("No valid XML layouts found in directory: " + path + "\nAdd layout anyway?")
                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                  @Override
                  public void onClick(DialogInterface dialog, int which) {
                    callback.select(dirLayout);
                  }
                })
                .setNegativeButton("No", null)
                .show();
            } else {
              callback.select(dirLayout);
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

  /** Called when modifying a layout. Custom layouts behave differently. */
  @Override
  void select(final SelectionCallback callback, Layout prev_layout)
  {
    if (prev_layout != null && prev_layout instanceof CustomLayout)
      select_custom(callback, ((CustomLayout)prev_layout).xml);
    else if (prev_layout != null && prev_layout instanceof DirectoryLayout)
    {
      // For directory layouts, show the directory selector with current path pre-filled
      final EditText input = new EditText(getContext());
      input.setText(((DirectoryLayout)prev_layout).path);
      
      new AlertDialog.Builder(getContext())
        .setTitle("Edit Layout Directory")
        .setMessage("Enter the full path to a directory containing XML layout files:")
        .setView(input)
        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            String path = input.getText().toString().trim();
            if (!path.isEmpty()) {
              callback.select(new DirectoryLayout(path));
            }
          }
        })
        .setNegativeButton("Cancel", null)
        .show();
    }
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
        if (!dir.exists() || !dir.isDirectory())
          return result;
          
        File[] xmlFiles = dir.listFiles(new java.io.FilenameFilter() {
          @Override
          public boolean accept(File dir, String name) {
            return name.toLowerCase().endsWith(".xml");
          }
        });
        if (xmlFiles != null)
        {
          for (File xmlFile : xmlFiles)
          {
            try
            {
              FileInputStream fis = new FileInputStream(xmlFile);
              byte[] data = new byte[(int) xmlFile.length()];
              fis.read(data);
              fis.close();
              
              String xmlContent = new String(data, "UTF-8");
              KeyboardData layout = KeyboardData.load_string_exn(xmlContent);
              if (layout != null)
                result.add(layout);
            }
            catch (Exception e)
            {
              // Skip invalid files
            }
          }
        }
      }
      catch (Exception e)
      {
        // Return empty list on error
      }
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
