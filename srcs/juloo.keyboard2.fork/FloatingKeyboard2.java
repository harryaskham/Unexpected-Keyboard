package juloo.keyboard2.fork;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.Toast;
import java.util.Arrays;
import java.util.List;
import juloo.keyboard2.fork.prefs.LayoutsPreference;

public class FloatingKeyboard2 extends InputMethodService
  implements SharedPreferences.OnSharedPreferenceChangeListener
{
  // Handle styling constants for consistency across all handles
  private static final int HANDLE_HEIGHT_DP = 24;
  private static final float HANDLE_WIDTH_SCREEN_PERCENT = 0.2f;
  private static final int HANDLE_COLOR_INACTIVE = 0xFF5E81AC; // Nord blue
  private static final int HANDLE_COLOR_ACTIVE = 0xFFD8DEE9; // Light gray when pressed
  private static final int HANDLE_MARGIN_TOP_DP = 3;
  private static final int HANDLE_MARGIN_SIDE_DP = 8;
  
  // Touch area constants for better usability
  private static final int HANDLE_TOUCH_HEIGHT_DP = 48; // Double the visual height for easier targeting
  private static final float HANDLE_TOUCH_WIDTH_SCREEN_PERCENT = 0.25f; // 25% instead of 20% for easier targeting
  
  private Keyboard2View _keyboardView;
  private KeyEventHandler _keyeventhandler;
  private KeyboardData _currentSpecialLayout;
  private KeyboardData _localeTextLayout;
  public int actionId;
  private Handler _handler;
  private Config _config;
  private FoldStateTracker _foldStateTracker;
  
  // Floating keyboard specific
  private WindowManager _windowManager;
  private boolean _floatingKeyboardActive = false;
  private View _floatingKeyboardView;
  private WindowManager.LayoutParams _floatingLayoutParams;
  private ViewGroup _floatingContainer;
  
  // Separate window for toggle button to remain touchable in passthrough mode
  private View _toggleButtonWindow;
  private WindowManager.LayoutParams _toggleLayoutParams;

  KeyboardData current_layout_unmodified()
  {
    if (_currentSpecialLayout != null)
      return _currentSpecialLayout;
    KeyboardData layout = null;
    int layout_i = _config.get_current_layout();
    if (layout_i >= _config.layouts.size())
      layout_i = 0;
    if (layout_i < _config.layouts.size())
      layout = _config.layouts.get(layout_i);
    if (layout == null)
      layout = _localeTextLayout;
    return layout;
  }

  KeyboardData current_layout()
  {
    if (_currentSpecialLayout != null)
      return _currentSpecialLayout;
    return LayoutModifier.modify_layout(current_layout_unmodified());
  }

  void setTextLayout(int l)
  {
    _config.set_current_layout(l);
    _currentSpecialLayout = null;
    if (_floatingKeyboardActive && _floatingKeyboardView != null) {
      ((Keyboard2View)_floatingKeyboardView).setKeyboard(current_layout());
    }
  }

  void setSpecialLayout(KeyboardData l)
  {
    _currentSpecialLayout = l;
    if (_floatingKeyboardActive && _floatingKeyboardView != null) {
      ((Keyboard2View)_floatingKeyboardView).setKeyboard(l);
    }
  }

  KeyboardData loadLayout(int layout_id)
  {
    return KeyboardData.load(getResources(), layout_id);
  }

  KeyboardData loadNumpad(int layout_id)
  {
    return LayoutModifier.modify_numpad(KeyboardData.load(getResources(), layout_id),
        current_layout_unmodified());
  }

  KeyboardData loadPinentry(int layout_id)
  {
    return LayoutModifier.modify_pinentry(KeyboardData.load(getResources(), layout_id),
        current_layout_unmodified());
  }

  @Override
  public void onCreate()
  {
    super.onCreate();
    SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(this);
    _handler = new Handler(getMainLooper());
    _keyeventhandler = new KeyEventHandler(this.new Receiver());
    _foldStateTracker = new FoldStateTracker(this);
    Config.initGlobalConfig(prefs, getResources(), _keyeventhandler, _foldStateTracker.isUnfolded());
    prefs.registerOnSharedPreferenceChangeListener(this);
    _config = Config.globalConfig();
    Logs.set_debug_logs(getResources().getBoolean(R.bool.debug_logs));
    ClipboardHistoryService.on_startup(this, _keyeventhandler);
    _foldStateTracker.setChangedCallback(() -> { refresh_config(); });
    
    _windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    removeFloatingKeyboard();
    _foldStateTracker.close();
  }

  private void refreshSubtypeImm()
  {
    InputMethodManager imm = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
    _config.shouldOfferVoiceTyping = true;
    KeyboardData default_layout = null;
    _config.extra_keys_subtype = null;
    if (VERSION.SDK_INT >= 12)
    {
      List<InputMethodSubtype> enabled_subtypes = getEnabledSubtypes(imm);
      InputMethodSubtype subtype = defaultSubtypes(imm, enabled_subtypes);
      if (subtype != null)
      {
        String s = subtype.getExtraValueOf("default_layout");
        if (s != null)
          default_layout = LayoutsPreference.layout_of_string(getResources(), s);
        refreshAccentsOption(imm, enabled_subtypes);
      }
    }
    if (default_layout == null)
      default_layout = loadLayout(R.xml.latn_qwerty_us);
    _localeTextLayout = default_layout;
  }

  private List<InputMethodSubtype> getEnabledSubtypes(InputMethodManager imm)
  {
    String pkg = getPackageName();
    for (InputMethodInfo imi : imm.getEnabledInputMethodList())
      if (imi.getPackageName().equals(pkg))
        return imm.getEnabledInputMethodSubtypeList(imi, true);
    return Arrays.asList();
  }

  @TargetApi(12)
  private ExtraKeys extra_keys_of_subtype(InputMethodSubtype subtype)
  {
    String extra_keys = subtype.getExtraValueOf("extra_keys");
    String script = subtype.getExtraValueOf("script");
    if (extra_keys != null)
      return ExtraKeys.parse(script, extra_keys);
    return ExtraKeys.EMPTY;
  }

  private void refreshAccentsOption(InputMethodManager imm, List<InputMethodSubtype> enabled_subtypes)
  {
    java.util.List<ExtraKeys> extra_keys = new java.util.ArrayList<ExtraKeys>();
    for (InputMethodSubtype s : enabled_subtypes)
      extra_keys.add(extra_keys_of_subtype(s));
    _config.extra_keys_subtype = ExtraKeys.merge(extra_keys);
  }

  @TargetApi(12)
  private InputMethodSubtype defaultSubtypes(InputMethodManager imm, List<InputMethodSubtype> enabled_subtypes)
  {
    if (VERSION.SDK_INT < 24)
      return imm.getCurrentInputMethodSubtype();
    InputMethodSubtype current_subtype = imm.getCurrentInputMethodSubtype();
    if (current_subtype == null)
      return null;
    for (InputMethodSubtype s : enabled_subtypes)
      if (s.getLanguageTag().equals(current_subtype.getLanguageTag()))
        return s;
    return null;
  }

  private void refresh_config()
  {
    _config.refresh(getResources(), _foldStateTracker.isUnfolded());
    refreshSubtypeImm();
  }

  private KeyboardData refresh_special_layout(EditorInfo info)
  {
    switch (info.inputType & InputType.TYPE_MASK_CLASS)
    {
      case InputType.TYPE_CLASS_NUMBER:
      case InputType.TYPE_CLASS_PHONE:
      case InputType.TYPE_CLASS_DATETIME:
        if (_config.selected_number_layout == NumberLayout.PIN)
          return loadPinentry(R.xml.pin);
        else if (_config.selected_number_layout == NumberLayout.NUMBER)
          return loadNumpad(R.xml.numeric);
      default:
        break;
    }
    return null;
  }

  @Override
  public void onStartInputView(EditorInfo info, boolean restarting)
  {
    refresh_config();
    refresh_action_label(info);
    _currentSpecialLayout = refresh_special_layout(info);
    _keyeventhandler.started(info);
    
    // Always create floating keyboard for floating IME
    createFloatingKeyboard();
    
    if (_floatingKeyboardActive && _floatingKeyboardView != null) {
      ((Keyboard2View)_floatingKeyboardView).setKeyboard(current_layout());
    }
  }

  @Override
  public void onFinishInputView(boolean finishingInput)
  {
    super.onFinishInputView(finishingInput);
    // Hide floating keyboard when input view is finished
    removeFloatingKeyboard();
  }

  @Override
  public void onFinishInput()
  {
    super.onFinishInput();
    // Also hide when input is completely finished
    removeFloatingKeyboard();
  }

  private void refresh_action_label(EditorInfo info)
  {
    if (info.actionLabel != null)
    {
      _config.actionLabel = info.actionLabel.toString();
      actionId = info.actionId;
      _config.swapEnterActionKey = false;
    }
    else
    {
      int action = info.imeOptions & EditorInfo.IME_MASK_ACTION;
      _config.actionLabel = actionLabel_of_imeAction(action);
      actionId = action;
      _config.swapEnterActionKey =
        (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0;
    }
  }

  private String actionLabel_of_imeAction(int action)
  {
    int res;
    switch (action)
    {
      case EditorInfo.IME_ACTION_NEXT: res = R.string.key_action_next; break;
      case EditorInfo.IME_ACTION_DONE: res = R.string.key_action_done; break;
      case EditorInfo.IME_ACTION_GO: res = R.string.key_action_go; break;
      case EditorInfo.IME_ACTION_PREVIOUS: res = R.string.key_action_prev; break;
      case EditorInfo.IME_ACTION_SEARCH: res = R.string.key_action_search; break;
      case EditorInfo.IME_ACTION_SEND: res = R.string.key_action_send; break;
      case EditorInfo.IME_ACTION_UNSPECIFIED:
      case EditorInfo.IME_ACTION_NONE:
      default: return null;
    }
    return getResources().getString(res);
  }

  @Override
  public void onCurrentInputMethodSubtypeChanged(InputMethodSubtype subtype)
  {
    refreshSubtypeImm();
    if (_floatingKeyboardActive && _floatingKeyboardView != null) {
      ((Keyboard2View)_floatingKeyboardView).setKeyboard(current_layout());
    }
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences _prefs, String _key)
  {
    refresh_config();
    if (_floatingKeyboardActive && _floatingKeyboardView != null) {
      ((Keyboard2View)_floatingKeyboardView).setKeyboard(current_layout());
    }
  }

  @Override
  public boolean onEvaluateFullscreenMode()
  {
    return false;
  }

  public class Receiver implements KeyEventHandler.IReceiver
  {
    public void handle_event_key(KeyValue.Event ev)
    {
      switch (ev)
      {
        case CONFIG:
          Intent intent = new Intent(FloatingKeyboard2.this, SettingsActivity.class);
          intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
          startActivity(intent);
          break;

        case SWITCH_TEXT:
          _currentSpecialLayout = null;
          if (_floatingKeyboardActive && _floatingKeyboardView != null) {
            ((Keyboard2View)_floatingKeyboardView).setKeyboard(current_layout());
          }
          break;

        case SWITCH_NUMERIC:
          setSpecialLayout(loadNumpad(R.xml.numeric));
          break;

        case ACTION:
          InputConnection conn = getCurrentInputConnection();
          if (conn != null)
            conn.performEditorAction(actionId);
          break;

        case SWITCH_FORWARD:
          incrTextLayout(1);
          break;

        case SWITCH_BACKWARD:
          incrTextLayout(-1);
          break;

        case SWITCH_GREEKMATH:
          setSpecialLayout(loadNumpad(R.xml.greekmath));
          break;

        case CAPS_LOCK:
          set_shift_state(true, true);
          break;

        case TOGGLE_FLOATING:
          switch_to_docked_ime();
          break;
      }
    }

    public void set_shift_state(boolean state, boolean lock)
    {
      if (_floatingKeyboardActive && _floatingKeyboardView != null) {
        ((Keyboard2View)_floatingKeyboardView).set_shift_state(state, lock);
      }
    }

    public void set_compose_pending(boolean pending)
    {
      if (_floatingKeyboardActive && _floatingKeyboardView != null) {
        ((Keyboard2View)_floatingKeyboardView).set_compose_pending(pending);
      }
    }

    public void selection_state_changed(boolean selection_is_ongoing)
    {
      if (_floatingKeyboardActive && _floatingKeyboardView != null) {
        ((Keyboard2View)_floatingKeyboardView).set_selection_state(selection_is_ongoing);
      }
    }

    public InputConnection getCurrentInputConnection()
    {
      return FloatingKeyboard2.this.getCurrentInputConnection();
    }

    public Handler getHandler()
    {
      return _handler;
    }
  }

  void incrTextLayout(int delta)
  {
    int s = _config.layouts.size();
    setTextLayout((_config.get_current_layout() + delta + s) % s);
  }

  private void switch_to_docked_ime()
  {
    android.util.Log.d("juloo.keyboard2.fork", "Switching to docked IME");
    
    // Switch to the main (docked) keyboard IME
    InputMethodManager imm = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
    String dockedImeId = getPackageName() + "/.Keyboard2";
    
    try {
      // Request to switch to the docked IME
      imm.setInputMethod(getWindow().getWindow().getAttributes().token, dockedImeId);
      android.util.Log.d("juloo.keyboard2.fork", "Requested switch to docked IME: " + dockedImeId);
    } catch (Exception e) {
      android.util.Log.e("juloo.keyboard2.fork", "Failed to switch to docked IME: " + e.getMessage());
      // Fallback - show IME picker so user can manually select
      imm.showInputMethodPicker();
    }
  }

  private View inflate_view(int layout)
  {
    return View.inflate(new android.view.ContextThemeWrapper(this, _config.theme), layout, null);
  }
  
  private void showDebugToast(String message) {
    if (_config != null && isDebugModeEnabled()) {
      Toast.makeText(this, "FloatingKB: " + message, Toast.LENGTH_SHORT).show();
    }
  }
  
  private boolean isDebugModeEnabled() {
    return DirectBootAwarePreferences.get_shared_preferences(this)
        .getBoolean("floating_debug_mode", false);
  }
  
  private void updateFloatingKeyboardWidth(int widthPercent) {
    SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(this);
    SharedPreferences.Editor editor = prefs.edit();
    
    // Update the appropriate floating keyboard width setting based on orientation
    boolean landscape = _config.orientation_landscape;
    boolean unfolded = _config.foldable_unfolded;
    
    String prefKey;
    if (landscape) {
      prefKey = unfolded ? "floating_keyboard_width_landscape_unfolded" : "floating_keyboard_width_landscape";
    } else {
      prefKey = unfolded ? "floating_keyboard_width_unfolded" : "floating_keyboard_width";
    }
    
    editor.putInt(prefKey, widthPercent);
    editor.apply();
    
    // Update the runtime config
    _config.floatingKeyboardWidthPercent = widthPercent;
    
    showDebugToast("Width updated to " + widthPercent + "%");
  }

  private void updateFloatingKeyboardHeight(int heightPercent) {
    SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(this);
    SharedPreferences.Editor editor = prefs.edit();
    
    // Update the appropriate floating keyboard height setting based on orientation
    boolean landscape = _config.orientation_landscape;
    boolean unfolded = _config.foldable_unfolded;
    
    String prefKey;
    if (landscape) {
      prefKey = unfolded ? "floating_keyboard_height_landscape_unfolded" : "floating_keyboard_height_landscape";
    } else {
      prefKey = unfolded ? "floating_keyboard_height_unfolded" : "floating_keyboard_height";
    }
    
    editor.putInt(prefKey, heightPercent);
    editor.apply();
    
    // Update the runtime config
    _config.floatingKeyboardHeightPercent = heightPercent;
    
    showDebugToast("Height updated to " + heightPercent + "%");
  }

  private void saveFloatingKeyboardPosition() {
    if (_floatingLayoutParams != null) {
      SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(this);
      SharedPreferences.Editor editor = prefs.edit();
      editor.putInt("floating_keyboard_x", _floatingLayoutParams.x);
      editor.putInt("floating_keyboard_y", _floatingLayoutParams.y);
      editor.apply();
      
      if (isDebugModeEnabled()) {
        showDebugToast("Position saved: " + _floatingLayoutParams.x + "," + _floatingLayoutParams.y);
      }
    }
  }

  private void clampKeyboardPositionToScreen() {
    if (_floatingLayoutParams == null || _floatingContainer == null) {
      return;
    }
    
    // Get screen dimensions and keyboard dimensions
    DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
    int screenWidth = displayMetrics.widthPixels;
    int screenHeight = displayMetrics.heightPixels;
    int keyboardWidth = _floatingContainer.getWidth();
    int keyboardHeight = _floatingContainer.getHeight();
    
    // Check if keyboard dimensions are valid (measured)
    if (keyboardWidth <= 0 || keyboardHeight <= 0) {
      android.util.Log.d("FloatingKeyboard", "Keyboard not yet measured, skipping bounds check");
      return;
    }
    
    // Calculate clamped position
    int clampedX = Math.max(0, Math.min(_floatingLayoutParams.x, screenWidth - keyboardWidth));
    int clampedY = Math.max(0, Math.min(_floatingLayoutParams.y, screenHeight - keyboardHeight));
    
    // Only update if position changed
    if (clampedX != _floatingLayoutParams.x || clampedY != _floatingLayoutParams.y) {
      android.util.Log.d("FloatingKeyboard", "Clamping keyboard position from " + _floatingLayoutParams.x + "," + _floatingLayoutParams.y + " to " + clampedX + "," + clampedY);
      _floatingLayoutParams.x = clampedX;
      _floatingLayoutParams.y = clampedY;
      _windowManager.updateViewLayout(_floatingContainer, _floatingLayoutParams);
      
      // Save the corrected position
      saveFloatingKeyboardPosition();
    }
  }
  
  private void refreshFloatingKeyboard() {
    if (_floatingKeyboardView != null && _floatingKeyboardActive) {
      // Refresh the config to pick up new dimensions
      _config.refresh(getResources(), _config.foldable_unfolded);
      
      // Update the keyboard view with new layout
      ((Keyboard2View)_floatingKeyboardView).setKeyboard(current_layout());
      ((Keyboard2View)_floatingKeyboardView).reset();
      
      // Request layout update
      if (_floatingContainer != null) {
        _floatingContainer.requestLayout();
        _floatingContainer.invalidate();
        
        // Apply bounds checking after resize to prevent off-screen positioning
        _floatingContainer.post(new Runnable() {
          @Override
          public void run() {
            clampKeyboardPositionToScreen();
          }
        });
      }
    }
  }
  
  private void runFloatingKeyboardTests(ResizableFloatingContainer container) {
    android.util.Log.d("FloatingKeyboard", "=== RUNNING FLOATING KEYBOARD TESTS ===");
    showDebugToast("Running floating keyboard diagnostics...");
    
    // Test 1: View Hierarchy Analysis
    testViewHierarchy(container);
    
    // Test 2: Touch Region Analysis
    testTouchRegions(container);
    
    // Test 3: Window Properties
    testWindowProperties();
    
    // Test 4: Synthetic Touch Tests
    testSyntheticTouches(container);
    
    android.util.Log.d("FloatingKeyboard", "=== TESTS COMPLETE ===");
    showDebugToast("Diagnostics complete - check for handle visibility");
  }
  
  private void testViewHierarchy(ResizableFloatingContainer container) {
    android.util.Log.d("FloatingKeyboard", "--- View Hierarchy Test ---");
    android.util.Log.d("FloatingKeyboard", "Container type: " + container.getClass().getSimpleName());
    android.util.Log.d("FloatingKeyboard", "Container size: " + container.getWidth() + "x" + container.getHeight());
    android.util.Log.d("FloatingKeyboard", "Container child count: " + container.getChildCount());
    android.util.Log.d("FloatingKeyboard", "Container clickable: " + container.isClickable());
    android.util.Log.d("FloatingKeyboard", "Container enabled: " + container.isEnabled());
    
    showDebugToast("Container: " + container.getWidth() + "x" + container.getHeight() + 
                   " with " + container.getChildCount() + " children");
    
    for (int i = 0; i < container.getChildCount(); i++) {
      View child = container.getChildAt(i);
      android.util.Log.d("FloatingKeyboard", "Child " + i + ":");
      android.util.Log.d("FloatingKeyboard", "  Type: " + child.getClass().getSimpleName());
      android.util.Log.d("FloatingKeyboard", "  Size: " + child.getWidth() + "x" + child.getHeight());
      android.util.Log.d("FloatingKeyboard", "  Position: " + child.getLeft() + "," + child.getTop());
      android.util.Log.d("FloatingKeyboard", "  Visible: " + (child.getVisibility() == View.VISIBLE));
      android.util.Log.d("FloatingKeyboard", "  Clickable: " + child.isClickable());
      android.util.Log.d("FloatingKeyboard", "  HasOnTouchListener: " + child.hasOnClickListeners());
      android.util.Log.d("FloatingKeyboard", "  Elevation: " + child.getElevation());
      
      if (child == _floatingKeyboardView) {
        android.util.Log.d("FloatingKeyboard", "  >>> This is the keyboard view");
      }
    }
  }
  
  private void testTouchRegions(ResizableFloatingContainer container) {
    android.util.Log.d("FloatingKeyboard", "--- Touch Region Test ---");
    
    // Test various points to see what should receive touches
    int containerWidth = container.getWidth();
    int containerHeight = container.getHeight();
    
    // Test points
    float[][] testPoints = {
      {containerWidth * 0.5f, 15f}, // Center top (drag handle area)
      {containerWidth * 0.9f, 15f}, // Right top (resize handle area)  
      {containerWidth * 0.5f, containerHeight * 0.5f}, // Center middle (keyboard area)
      {containerWidth * 0.1f, containerHeight * 0.9f}, // Bottom left (keyboard area)
      {containerWidth * 1.1f, containerHeight * 0.5f}, // Outside container
    };
    
    String[] pointNames = {"Drag Handle", "Resize Handle", "Keyboard Center", "Keyboard Bottom", "Outside"};
    
    for (int i = 0; i < testPoints.length; i++) {
      float x = testPoints[i][0];
      float y = testPoints[i][1];
      
      android.util.Log.d("FloatingKeyboard", "Test point " + pointNames[i] + " (" + x + "," + y + "):");
      
      // Check which child would receive this touch
      View hitChild = findChildViewUnder(container, x, y);
      if (hitChild != null) {
        android.util.Log.d("FloatingKeyboard", "  Would hit: " + hitChild.getClass().getSimpleName());
      } else {
        android.util.Log.d("FloatingKeyboard", "  Would hit: container background");
      }
    }
  }
  
  private void testWindowProperties() {
    android.util.Log.d("FloatingKeyboard", "--- Window Properties Test ---");
    
    if (_floatingLayoutParams != null) {
      android.util.Log.d("FloatingKeyboard", "Window type: " + _floatingLayoutParams.type);
      android.util.Log.d("FloatingKeyboard", "Window flags: " + _floatingLayoutParams.flags);
      android.util.Log.d("FloatingKeyboard", "Window format: " + _floatingLayoutParams.format);
      android.util.Log.d("FloatingKeyboard", "Window size: " + _floatingLayoutParams.width + "x" + _floatingLayoutParams.height);
      android.util.Log.d("FloatingKeyboard", "Window position: " + _floatingLayoutParams.x + "," + _floatingLayoutParams.y);
      
      // Check specific flags
      int flags = _floatingLayoutParams.flags;
      android.util.Log.d("FloatingKeyboard", "FLAG_NOT_TOUCH_MODAL: " + ((flags & WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) != 0));
      android.util.Log.d("FloatingKeyboard", "FLAG_NOT_FOCUSABLE: " + ((flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0));
      android.util.Log.d("FloatingKeyboard", "FLAG_WATCH_OUTSIDE_TOUCH: " + ((flags & WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH) != 0));
    }
  }
  
  private void testSyntheticTouches(ResizableFloatingContainer container) {
    android.util.Log.d("FloatingKeyboard", "--- Synthetic Touch Test ---");
    
    // Create synthetic touch events to test the touch handling
    long downTime = android.os.SystemClock.uptimeMillis();
    long eventTime = android.os.SystemClock.uptimeMillis();
    
    // Test touch on resize handle area
    float resizeX = container.getWidth() * 0.9f;
    float resizeY = 15f;
    
    MotionEvent syntheticDown = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, resizeX, resizeY, 0);
    
    android.util.Log.d("FloatingKeyboard", "Sending synthetic DOWN touch to resize area (" + resizeX + "," + resizeY + ")");
    
    // Test which view would handle this
    View targetView = findChildViewUnder(container, resizeX, resizeY);
    if (targetView != null) {
      android.util.Log.d("FloatingKeyboard", "Target view for synthetic touch: " + targetView.getClass().getSimpleName());
      boolean handled = targetView.dispatchTouchEvent(syntheticDown);
      android.util.Log.d("FloatingKeyboard", "Synthetic touch handled by target: " + handled);
    } else {
      android.util.Log.d("FloatingKeyboard", "No target view found, testing container");
      boolean handled = container.dispatchTouchEvent(syntheticDown);
      android.util.Log.d("FloatingKeyboard", "Synthetic touch handled by container: " + handled);
    }
    
    syntheticDown.recycle();
  }
  
  private View findChildViewUnder(ViewGroup parent, float x, float y) {
    for (int i = parent.getChildCount() - 1; i >= 0; i--) {
      View child = parent.getChildAt(i);
      if (child.getVisibility() == View.VISIBLE) {
        if (x >= child.getLeft() && x < child.getRight() && 
            y >= child.getTop() && y < child.getBottom()) {
          return child;
        }
      }
    }
    return null;
  }
  
  private boolean hasSystemAlertWindowPermission() {
    if (VERSION.SDK_INT >= 23) {
      return Settings.canDrawOverlays(this);
    }
    return true;
  }
  
  private void requestSystemAlertWindowPermission() {
    if (VERSION.SDK_INT >= 23 && !hasSystemAlertWindowPermission()) {
      Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
          Uri.parse("package:" + getPackageName()));
      intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      startActivity(intent);
      Toast.makeText(this, "Please enable 'Display over other apps' permission for floating keyboard", 
          Toast.LENGTH_LONG).show();
    }
  }
  
  private void createFloatingKeyboard() {
    if (_floatingKeyboardActive) {
      return;
    }
    
    if (!hasSystemAlertWindowPermission()) {
      requestSystemAlertWindowPermission();
      return;
    }
    
    try {
      // Create floating keyboard view with pass-through background
      _floatingKeyboardView = inflate_view(R.layout.keyboard);
      ((Keyboard2View)_floatingKeyboardView).setKeyboard(current_layout());
      ((Keyboard2View)_floatingKeyboardView).reset();
      
      // Ensure keyboard view gets proper touch and event handling
      ((Keyboard2View)_floatingKeyboardView).setEnabled(true);
      ((Keyboard2View)_floatingKeyboardView).setClickable(true);
      ((Keyboard2View)_floatingKeyboardView).setFocusable(true);
      
      // Revert background - keyboard has its own opacity controls
      // _floatingKeyboardView.setBackground(null); // Removed - let keyboard handle its own background
      
      // Create container with drag handle and resize handle
      ResizableFloatingContainer container = new ResizableFloatingContainer(this);
      
      // Add keyboard first
      FrameLayout.LayoutParams keyboardParams = new FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
      keyboardParams.gravity = Gravity.CENTER;
      keyboardParams.setMargins(0, 30, 0, 0); // Leave space for drag handle at top
      container.addView(_floatingKeyboardView, keyboardParams);
      
      // Create drag handle with expanded touch area using consistent styling constants
      int screenWidth = getResources().getDisplayMetrics().widthPixels;
      
      // Create touch container (larger invisible area for easier touching)
      FrameLayout dragTouchContainer = new FrameLayout(this);
      int touchWidth = (int) (screenWidth * HANDLE_TOUCH_WIDTH_SCREEN_PERCENT);
      FrameLayout.LayoutParams touchParams = new FrameLayout.LayoutParams(touchWidth, HANDLE_TOUCH_HEIGHT_DP);
      touchParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
      touchParams.setMargins(0, HANDLE_MARGIN_TOP_DP, 0, 0);
      
      // Create visual handle (smaller, centered within touch area)
      View dragHandle = new View(this);
      GradientDrawable handleDrawable = new GradientDrawable();
      handleDrawable.setColor(HANDLE_COLOR_INACTIVE);
      handleDrawable.setCornerRadius(6 * getResources().getDisplayMetrics().density);
      dragHandle.setBackground(handleDrawable);
      
      int visualWidth = (int) (screenWidth * HANDLE_WIDTH_SCREEN_PERCENT);
      FrameLayout.LayoutParams visualParams = new FrameLayout.LayoutParams(visualWidth, HANDLE_HEIGHT_DP);
      visualParams.gravity = Gravity.CENTER; // Center the visual handle within the touch area
      
      // Add visual handle to touch container
      dragTouchContainer.addView(dragHandle, visualParams);
      
      // Add touch container to main container
      container.addView(dragTouchContainer, touchParams);
      
      // Create resize handle after keyboard is added
      container.createResizeHandle();
      
      // Create passthrough toggle button
      container.createPassthroughToggle();
      
      // Set up window parameters for overlay - enable pass-through for gaps
      int windowFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | 
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
      
      // Try to enable split-touch for better gap handling
      if (VERSION.SDK_INT >= 11) {
        windowFlags |= WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
      }
      
      android.util.Log.d("FloatingKeyboard", "Using window flags: " + windowFlags);
      
      WindowManager.LayoutParams params = new WindowManager.LayoutParams(
          WindowManager.LayoutParams.WRAP_CONTENT,
          WindowManager.LayoutParams.WRAP_CONTENT,
          VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
          windowFlags,
          PixelFormat.TRANSLUCENT);
      
      params.gravity = Gravity.TOP | Gravity.LEFT;
      
      // Restore saved position or use defaults
      SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(this);
      params.x = prefs.getInt("floating_keyboard_x", 100);
      params.y = prefs.getInt("floating_keyboard_y", 300);
      
      _windowManager.addView(container, params);
      _floatingLayoutParams = params;
      _floatingContainer = container;
      
      // Apply bounds checking after keyboard is added and measured
      container.post(new Runnable() {
        @Override
        public void run() {
          clampKeyboardPositionToScreen();
        }
      });
      
      dragTouchContainer.setOnTouchListener(new FloatingDragTouchListener(dragHandle));
      
      android.util.Log.d("FloatingKeyboard", "Drag handle created - Visual: " + visualWidth + "x" + HANDLE_HEIGHT_DP + ", Touch: " + touchWidth + "x" + HANDLE_TOUCH_HEIGHT_DP);
      container.setWindowManager(_windowManager, params);
      
      _floatingKeyboardActive = true;
      
      // Run programmatic tests after everything is set up
      container.post(new Runnable() {
        @Override
        public void run() {
          runFloatingKeyboardTests(container);
        }
      });
      
    } catch (Exception e) {
      Logs.exn("Failed to create floating keyboard", e);
    }
  }
  
  private void removeFloatingKeyboard() {
    if (_floatingKeyboardActive && _floatingContainer != null) {
      try {
        // Clean up toggle button window if it exists
        removeToggleButtonWindow();
        
        _windowManager.removeView(_floatingContainer);
      } catch (Exception e) {
        Logs.exn("Failed to remove floating keyboard", e);
      }
      _floatingKeyboardActive = false;
      _floatingKeyboardView = null;
      _floatingLayoutParams = null;
      _floatingContainer = null;
    }
  }

  private class FloatingDragTouchListener implements View.OnTouchListener {
    private float startX, startY;
    private float startTouchX, startTouchY;
    private View handleView;
    private GradientDrawable originalDrawable;

    public FloatingDragTouchListener(View handle) {
      this.handleView = handle;
      this.originalDrawable = (GradientDrawable) handle.getBackground();
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
      android.util.Log.d("FloatingKeyboard", "DragTouchListener.onTouch: " + event.getAction());
      
      if (!_floatingKeyboardActive || _floatingLayoutParams == null) {
        android.util.Log.d("FloatingKeyboard", "Drag touch rejected - active=" + _floatingKeyboardActive + " params=" + (_floatingLayoutParams != null));
        showDebugToast("Drag touch rejected - active=" + _floatingKeyboardActive);
        return false;
      }

      switch (event.getAction()) {
        case MotionEvent.ACTION_DOWN:
          // Change handle color when touched
          GradientDrawable activeDrawable = (GradientDrawable) originalDrawable.getConstantState().newDrawable();
          activeDrawable.setColor(HANDLE_COLOR_ACTIVE);
          handleView.setBackground(activeDrawable);
          
          startX = _floatingLayoutParams.x;
          startY = _floatingLayoutParams.y;
          startTouchX = event.getRawX();
          startTouchY = event.getRawY();
          
          showDebugToast("Drag started at " + (int)startTouchX + "," + (int)startTouchY);
          return true;

        case MotionEvent.ACTION_MOVE:
          float deltaX = event.getRawX() - startTouchX;
          float deltaY = event.getRawY() - startTouchY;
          
          // Calculate new position with bounds checking
          int newX = (int) (startX + deltaX);
          int newY = (int) (startY + deltaY);
          
          // Get screen dimensions and keyboard dimensions for bounds checking
          DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
          int screenWidth = displayMetrics.widthPixels;
          int screenHeight = displayMetrics.heightPixels;
          int keyboardWidth = _floatingContainer.getWidth();
          int keyboardHeight = _floatingContainer.getHeight();
          
          // Clamp position to screen bounds (prevent off-screen movement)
          _floatingLayoutParams.x = Math.max(0, Math.min(newX, screenWidth - keyboardWidth));
          _floatingLayoutParams.y = Math.max(0, Math.min(newY, screenHeight - keyboardHeight));
          
          _windowManager.updateViewLayout(_floatingContainer, _floatingLayoutParams);
          return true;

        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          // Restore original handle color
          handleView.setBackground(originalDrawable);
          // Save final position
          saveFloatingKeyboardPosition();
          showDebugToast("Drag ended");
          return true;
      }
      
      return false;
    }
  }


  private class ResizableFloatingContainer extends FrameLayout {
    private View resizeHandle;
    private View passthroughToggle;
    private FrameLayout passthroughTouchContainer;
    private WindowManager windowManager;
    private WindowManager.LayoutParams layoutParams;
    private float initialScale = 1.0f;
    private float currentScale = 1.0f;
    private boolean isResizing = false;
    private float resizeStartX, resizeStartY;
    private int initialWidth, initialHeight;
    private int initialWidthPercent, initialHeightPercent;
    private int initialWindowY;
    private boolean passthroughMode = false;

    public ResizableFloatingContainer(Context context) {
      super(context);
      // Set pivot point for scaling to top-left corner
      setPivotX(0);
      setPivotY(0);
      // Don't create resize handle here, create it after keyboard is added
    }

    public void setWindowManager(WindowManager wm, WindowManager.LayoutParams lp) {
      windowManager = wm;
      layoutParams = lp;
    }

    public void createResizeHandle() {
      // Create resize handle with expanded touch area using consistent styling constants
      int screenWidth = getResources().getDisplayMetrics().widthPixels;
      
      // Create touch container (larger invisible area for easier touching)
      FrameLayout resizeTouchContainer = new FrameLayout(getContext());
      int touchWidth = (int) (screenWidth * HANDLE_TOUCH_WIDTH_SCREEN_PERCENT);
      FrameLayout.LayoutParams touchParams = new FrameLayout.LayoutParams(touchWidth, HANDLE_TOUCH_HEIGHT_DP);
      touchParams.gravity = Gravity.TOP | Gravity.RIGHT;
      touchParams.setMargins(0, HANDLE_MARGIN_TOP_DP, HANDLE_MARGIN_SIDE_DP, 0);
      
      // Create visual handle (smaller, centered within touch area)
      resizeHandle = new View(getContext());
      GradientDrawable resizeDrawable = new GradientDrawable();
      resizeDrawable.setColor(HANDLE_COLOR_INACTIVE);
      resizeDrawable.setCornerRadius(6 * getResources().getDisplayMetrics().density);
      resizeHandle.setBackground(resizeDrawable);
      
      int visualWidth = (int) (screenWidth * HANDLE_WIDTH_SCREEN_PERCENT);
      FrameLayout.LayoutParams visualParams = new FrameLayout.LayoutParams(visualWidth, HANDLE_HEIGHT_DP);
      visualParams.gravity = Gravity.CENTER; // Center the visual handle within the touch area
      
      // Add visual handle to touch container
      resizeTouchContainer.addView(resizeHandle, visualParams);
      
      // Set touch listener on container, but pass visual handle for color changes
      resizeTouchContainer.setOnTouchListener(new ResizeTouchListener(resizeHandle));
      addView(resizeTouchContainer, touchParams);
      
      android.util.Log.d("FloatingKeyboard", "Resize handle created and added - Visual: " + visualWidth + "x" + HANDLE_HEIGHT_DP + ", Touch: " + touchWidth + "x" + HANDLE_TOUCH_HEIGHT_DP + " Children: " + getChildCount());
      
      // Force visibility and make sure it's on top
      resizeHandle.setVisibility(View.VISIBLE);
      resizeHandle.bringToFront();
      resizeHandle.setElevation(20.0f);
    }

    public void createPassthroughToggle() {
      // Create passthrough toggle with expanded touch area using consistent styling constants
      int screenWidth = getResources().getDisplayMetrics().widthPixels;
      
      // Create touch container (larger invisible area for easier touching)
      passthroughTouchContainer = new FrameLayout(getContext());
      int touchWidth = (int) (screenWidth * HANDLE_TOUCH_WIDTH_SCREEN_PERCENT);
      FrameLayout.LayoutParams touchParams = new FrameLayout.LayoutParams(touchWidth, HANDLE_TOUCH_HEIGHT_DP);
      touchParams.gravity = Gravity.TOP | Gravity.LEFT;
      touchParams.setMargins(HANDLE_MARGIN_SIDE_DP, HANDLE_MARGIN_TOP_DP, 0, 0);
      
      // Create visual handle (smaller, centered within touch area)
      passthroughToggle = new View(getContext());
      GradientDrawable toggleDrawable = new GradientDrawable();
      toggleDrawable.setColor(HANDLE_COLOR_INACTIVE);
      toggleDrawable.setCornerRadius(6 * getResources().getDisplayMetrics().density);
      passthroughToggle.setBackground(toggleDrawable);
      
      int visualWidth = (int) (screenWidth * HANDLE_WIDTH_SCREEN_PERCENT);
      FrameLayout.LayoutParams visualParams = new FrameLayout.LayoutParams(visualWidth, HANDLE_HEIGHT_DP);
      visualParams.gravity = Gravity.CENTER; // Center the visual handle within the touch area
      
      passthroughTouchContainer.addView(passthroughToggle, visualParams);
      
      // Set touch listener on container, but pass visual handle for color changes
      passthroughTouchContainer.setOnTouchListener(new PassthroughToggleTouchListener(passthroughToggle));
      addView(passthroughTouchContainer, touchParams);
      
      // Initially hidden since we start in normal mode
      passthroughTouchContainer.setVisibility(View.GONE);
      passthroughToggle.setElevation(20.0f);
      
      android.util.Log.d("FloatingKeyboard", "Passthrough toggle created and added - Visual: " + visualWidth + "x" + HANDLE_HEIGHT_DP + ", Touch: " + touchWidth + "x" + HANDLE_TOUCH_HEIGHT_DP);
      
      // Test if handle gets layout correctly
      resizeHandle.post(new Runnable() {
        @Override
        public void run() {
          android.util.Log.d("FloatingKeyboard", "Resize handle final position: " + resizeHandle.getLeft() + "," + resizeHandle.getTop() + " to " + resizeHandle.getRight() + "," + resizeHandle.getBottom() + " visibility=" + resizeHandle.getVisibility());
        }
      });
    }

    private class PassthroughToggleTouchListener implements View.OnTouchListener {
      private View handleView;
      private GradientDrawable originalDrawable;

      public PassthroughToggleTouchListener(View handle) {
        this.handleView = handle;
        this.originalDrawable = (GradientDrawable) handle.getBackground();
      }

      @Override
      public boolean onTouch(View v, MotionEvent event) {
        android.util.Log.d("FloatingKeyboard", "PassthroughToggleTouchListener.onTouch: " + event.getAction());
        
        switch (event.getAction()) {
          case MotionEvent.ACTION_DOWN:
            // Change handle color when touched
            GradientDrawable activeDrawable = (GradientDrawable) originalDrawable.getConstantState().newDrawable();
            activeDrawable.setColor(HANDLE_COLOR_ACTIVE);
            handleView.setBackground(activeDrawable);
            return true;

          case MotionEvent.ACTION_UP:
            // Restore original handle color
            handleView.setBackground(originalDrawable);
            
            // Toggle back to normal mode
            exitPassthroughMode();
            showDebugToast("Keyboard touches re-enabled");
            return true;
            
          case MotionEvent.ACTION_CANCEL:
            // Restore original handle color
            handleView.setBackground(originalDrawable);
            return true;
        }
        
        return false;
      }
    }

    private class ResizeTouchListener implements View.OnTouchListener {
      private View handleView;
      private GradientDrawable originalDrawable;

      public ResizeTouchListener(View handle) {
        this.handleView = handle;
        this.originalDrawable = (GradientDrawable) handle.getBackground();
      }

      @Override
      public boolean onTouch(View v, MotionEvent event) {
        if (windowManager == null || layoutParams == null) {
          return false;
        }

        android.util.Log.d("FloatingKeyboard", "ResizeTouchListener.onTouch: " + event.getAction());
        
        switch (event.getAction()) {
          case MotionEvent.ACTION_DOWN:
            // Change handle color when touched
            GradientDrawable activeDrawable = (GradientDrawable) originalDrawable.getConstantState().newDrawable();
            activeDrawable.setColor(HANDLE_COLOR_ACTIVE);
            handleView.setBackground(activeDrawable);
            
            isResizing = true;
            resizeStartX = event.getRawX();
            resizeStartY = event.getRawY();
            initialWidth = ResizableFloatingContainer.this.getWidth();
            initialHeight = ResizableFloatingContainer.this.getHeight();
            initialWidthPercent = _config.floatingKeyboardWidthPercent;
            initialHeightPercent = _config.floatingKeyboardHeightPercent;
            initialWindowY = _floatingLayoutParams.y;
            
            android.util.Log.d("FloatingKeyboard", "Resize start at: " + resizeStartX + ", " + resizeStartY + " Container size: " + initialWidth + "x" + initialHeight + " Initial: " + initialWidthPercent + "%x" + initialHeightPercent + "%");
            showDebugToast("Resize started - " + initialWidthPercent + "%x" + initialHeightPercent + "%");
            return true;

          case MotionEvent.ACTION_MOVE:
            if (isResizing) {
              float deltaX = event.getRawX() - resizeStartX;
              float deltaY = event.getRawY() - resizeStartY;
              
              // Calculate width percentage based on horizontal movement from initial values
              // Drag right = bigger (positive deltaX increases width)
              // Drag left = smaller (negative deltaX decreases width)
              float widthChange = deltaX / 1200.0f; // Reduced sensitivity (1200px = 50% change)
              float newWidthScale = (initialWidthPercent / 100.0f) + widthChange;
              newWidthScale = Math.max(0.5f, Math.min(1.0f, newWidthScale)); // Clamp 50%-100%
              int newWidthPercent = (int)(newWidthScale * 100);
              
              // Calculate height percentage based on vertical movement from initial values
              // Drag up = bigger (negative deltaY increases height)
              // Drag down = smaller (positive deltaY decreases height)
              float heightChange = -deltaY / 800.0f; // Reduced sensitivity (800px = full range)
              
              // Get the max height for current orientation
              int maxHeight = _config.orientation_landscape ? 50 : 50; // Same max as settings
              int minHeight = 10;
              
              float newHeightScale = (initialHeightPercent / 100.0f) + heightChange;
              newHeightScale = Math.max(minHeight / 100.0f, Math.min(maxHeight / 100.0f, newHeightScale));
              int newHeightPercent = (int)(newHeightScale * 100);
              
              // Update both dimensions in config
              updateFloatingKeyboardWidth(newWidthPercent);
              updateFloatingKeyboardHeight(newHeightPercent);
              
              // Adjust window Y position to make keyboard grow upward from resize handle
              // When dragging up (negative deltaY), keyboard gets taller and should move up
              // When dragging down (positive deltaY), keyboard gets shorter and should move down
              int newY = initialWindowY + (int)(deltaY * 0.25f); // Reduced sensitivity to match resize
              
              // Get screen dimensions for bounds checking during resize
              DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
              int screenWidth = displayMetrics.widthPixels;
              int screenHeight = displayMetrics.heightPixels;
              int currentKeyboardWidth = _floatingContainer.getWidth();
              int currentKeyboardHeight = _floatingContainer.getHeight();
              
              // Clamp resize position to screen bounds
              _floatingLayoutParams.x = Math.max(0, Math.min(_floatingLayoutParams.x, screenWidth - currentKeyboardWidth));
              _floatingLayoutParams.y = Math.max(0, Math.min(newY, screenHeight - currentKeyboardHeight));
              
              _windowManager.updateViewLayout(_floatingContainer, _floatingLayoutParams);
              
              // Force keyboard redraw with new dimensions
              refreshFloatingKeyboard();
              
              android.util.Log.d("FloatingKeyboard", "Resize - Width: " + newWidthPercent + "% Height: " + newHeightPercent + "% (deltaX: " + deltaX + " deltaY: " + deltaY + ")");
              
              return true;
            }
            break;

          case MotionEvent.ACTION_UP:
          case MotionEvent.ACTION_CANCEL:
            // Restore original handle color
            handleView.setBackground(originalDrawable);
            isResizing = false;
            // Save final position after resize
            saveFloatingKeyboardPosition();
            android.util.Log.d("FloatingKeyboard", "Resize end, final scale: " + currentScale);
            showDebugToast("Resize ended - final scale: " + String.format("%.1f", currentScale) + "x (applied: " + String.format("%.1f", ResizableFloatingContainer.this.getScaleX()) + "x)");
            return true;
        }
        
        return false;
      }
    }

    public void setTouchableRegionEmpty() {
      // touchableRegion is not available in older APIs, so we'll use a different approach
      android.util.Log.d("FloatingKeyboard", "Cannot set touchable region - using view-level pass-through");
    }

    public void updateTouchableRegionForKeys() {
      // Since touchableRegion API is not available, we rely on view-level touch handling
      android.util.Log.d("FloatingKeyboard", "Cannot use touchable region - relying on dispatchTouchEvent");
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
      super.onLayout(changed, left, top, right, bottom);
      if (changed && getWidth() > 0 && getHeight() > 0) {
        // Update touchable region after layout
        post(new Runnable() {
          @Override
          public void run() {
            updateTouchableRegionForKeys();
          }
        });
      }
    }

    private void enterPassthroughMode() {
      if (!passthroughMode && _floatingLayoutParams != null && windowManager != null) {
        passthroughMode = true;
        
        try {
          // Make the entire main window not touchable
          _floatingLayoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
          windowManager.updateViewLayout(this, _floatingLayoutParams);
          
          // Dim the keyboard to show it's in passthrough mode
          if (_floatingKeyboardView != null) {
            _floatingKeyboardView.setAlpha(0.3f);
          }
          
          // Hide the toggle button from the main window
          if (passthroughTouchContainer != null) {
            passthroughTouchContainer.setVisibility(View.GONE);
          }
          
          // Create a separate touchable window for the toggle button
          createToggleButtonWindow();
          
          android.util.Log.d("FloatingKeyboard", "Entered passthrough mode - main window not touchable, separate toggle window created");
        } catch (Exception e) {
          android.util.Log.e("FloatingKeyboard", "Error entering passthrough mode: " + e.getMessage());
        }
      }
    }

    public void exitPassthroughMode() {
      if (passthroughMode && _floatingLayoutParams != null && windowManager != null) {
        passthroughMode = false;
        
        try {
          // Remove the separate toggle button window
          removeToggleButtonWindow();
          
          // Restore main window touchability
          _floatingLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
          windowManager.updateViewLayout(this, _floatingLayoutParams);
          
          // Restore keyboard full opacity
          if (_floatingKeyboardView != null) {
            _floatingKeyboardView.setAlpha(1.0f);
          }
          
          android.util.Log.d("FloatingKeyboard", "Exited passthrough mode - main window touchable, keyboard restored, toggle window removed");
        } catch (Exception e) {
          android.util.Log.e("FloatingKeyboard", "Error exiting passthrough mode: " + e.getMessage());
        }
      }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
      if (event.getAction() == MotionEvent.ACTION_DOWN && _floatingKeyboardView != null) {
        float containerX = event.getX();
        float containerY = event.getY();
        
        android.util.Log.d("FloatingKeyboard", "Container intercepting touch: x=" + containerX + " y=" + containerY + " passthrough=" + passthroughMode);
        
        // Always allow handle touches through
        if (containerY <= 30) {
          android.util.Log.d("FloatingKeyboard", "Handle area - allowing normal processing");
          return false; // Don't intercept, let handles work
        }
        
        if (passthroughMode) {
          // In passthrough mode, check if this is in the keyboard area and pass it through
          float keyboardX = containerX - _floatingKeyboardView.getLeft();
          float keyboardY = containerY - _floatingKeyboardView.getTop();
          
          if (keyboardX >= 0 && keyboardX < _floatingKeyboardView.getWidth() && 
              keyboardY >= 0 && keyboardY < _floatingKeyboardView.getHeight()) {
            // This is a keyboard area touch in passthrough mode - don't handle it at all
            android.util.Log.d("FloatingKeyboard", "Keyboard touch in passthrough mode - allowing passthrough");
            return false; // Let it pass through completely
          }
        } else {
          // Normal mode - check for gap touches to enter passthrough mode
          float keyboardX = containerX - _floatingKeyboardView.getLeft();
          float keyboardY = containerY - _floatingKeyboardView.getTop();
          
          if (keyboardX >= 0 && keyboardX < _floatingKeyboardView.getWidth() && 
              keyboardY >= 0 && keyboardY < _floatingKeyboardView.getHeight()) {
            
            KeyboardData.Key key = ((Keyboard2View)_floatingKeyboardView).getKeyAtPosition(keyboardX, keyboardY);
            if (key == null) {
              // This is a gap touch - enter passthrough mode
              android.util.Log.d("FloatingKeyboard", "Gap touch detected - entering passthrough mode");
              enterPassthroughMode();
              showDebugToast("Passthrough mode enabled - tap top-left button to re-enable keyboard");
              return true; // Intercept this touch and consume it
            } else {
              android.util.Log.d("FloatingKeyboard", "Key touch detected - normal processing");
            }
          }
        }
      }
      
      return false; // Don't intercept by default
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      if (passthroughMode && event.getAction() == MotionEvent.ACTION_DOWN && _floatingKeyboardView != null) {
        float containerX = event.getX();
        float containerY = event.getY();
        
        // Always allow handle touches through
        if (containerY <= 30) {
          return super.onTouchEvent(event); // Let handles work normally
        }
        
        // Check if this is in the keyboard area and pass it through
        float keyboardX = containerX - _floatingKeyboardView.getLeft();
        float keyboardY = containerY - _floatingKeyboardView.getTop();
        
        if (keyboardX >= 0 && keyboardX < _floatingKeyboardView.getWidth() && 
            keyboardY >= 0 && keyboardY < _floatingKeyboardView.getHeight()) {
          // This is a keyboard area touch in passthrough mode - don't consume it
          android.util.Log.d("FloatingKeyboard", "Container onTouchEvent in passthrough mode - not consuming touch");
          return false; // Don't consume the event, let it pass through
        }
      }
      
      return super.onTouchEvent(event);
    }



  }

  private void createToggleButtonWindow() {
    if (_toggleButtonWindow != null) {
      return; // Already created
    }
    
    try {
      // Create a new toggle button
      _toggleButtonWindow = new View(this);
      
      GradientDrawable toggleDrawable = new GradientDrawable();
      toggleDrawable.setColor(HANDLE_COLOR_INACTIVE);
      toggleDrawable.setCornerRadius(6 * getResources().getDisplayMetrics().density);
      _toggleButtonWindow.setBackground(toggleDrawable);
      
      // Set up touch listener for the toggle button
      _toggleButtonWindow.setOnTouchListener(new View.OnTouchListener() {
        private GradientDrawable originalDrawable = toggleDrawable;
        
        @Override
        public boolean onTouch(View v, MotionEvent event) {
          android.util.Log.d("FloatingKeyboard", "Separate toggle button touched: " + event.getAction());
          
          switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
              // Change handle color when touched
              GradientDrawable activeDrawable = new GradientDrawable();
              activeDrawable.setColor(HANDLE_COLOR_ACTIVE);
              activeDrawable.setCornerRadius(6 * getResources().getDisplayMetrics().density);
              v.setBackground(activeDrawable);
              return true;

            case MotionEvent.ACTION_UP:
              // Restore original handle color
              v.setBackground(originalDrawable);
              
              // Toggle back to normal mode
              if (_floatingContainer instanceof ResizableFloatingContainer) {
                ((ResizableFloatingContainer)_floatingContainer).exitPassthroughMode();
              }
              showDebugToast("Keyboard touches re-enabled");
              return true;
              
            case MotionEvent.ACTION_CANCEL:
              // Restore original handle color
              v.setBackground(originalDrawable);
              return true;
          }
          
          return false;
        }
      });
      
      int screenWidth = getResources().getDisplayMetrics().widthPixels;
      int handleWidth = (int) (screenWidth * HANDLE_WIDTH_SCREEN_PERCENT);
      
      // Position it at the same location as the main window's toggle button would be
      _toggleLayoutParams = new WindowManager.LayoutParams(
          handleWidth,
          HANDLE_HEIGHT_DP,
          VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
          WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
          PixelFormat.TRANSLUCENT);
      
      _toggleLayoutParams.gravity = Gravity.TOP | Gravity.LEFT;
      _toggleLayoutParams.x = _floatingLayoutParams.x + HANDLE_MARGIN_SIDE_DP; // Same left margin as in main window
      _toggleLayoutParams.y = _floatingLayoutParams.y + HANDLE_MARGIN_TOP_DP; // Same top margin as in main window
      
      _windowManager.addView(_toggleButtonWindow, _toggleLayoutParams);
      
      android.util.Log.d("FloatingKeyboard", "Separate toggle button window created at " + _toggleLayoutParams.x + "," + _toggleLayoutParams.y);
    } catch (Exception e) {
      android.util.Log.e("FloatingKeyboard", "Error creating toggle button window: " + e.getMessage());
    }
  }

  private void removeToggleButtonWindow() {
    if (_toggleButtonWindow != null) {
      try {
        _windowManager.removeView(_toggleButtonWindow);
        _toggleButtonWindow = null;
        _toggleLayoutParams = null;
        android.util.Log.d("FloatingKeyboard", "Separate toggle button window removed");
      } catch (Exception e) {
        android.util.Log.e("FloatingKeyboard", "Error removing toggle button window: " + e.getMessage());
      }
    }
  }
}