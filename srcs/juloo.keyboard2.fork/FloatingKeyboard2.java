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
  // Handle styling constants
  private static final int HANDLE_COLOR_INACTIVE = 0xFF3B4252; // Nord darker blue-gray
  private static final int HANDLE_COLOR_ACTIVE = 0xFFD8DEE9; // Light gray when pressed
  private static final int HANDLE_MARGIN_TOP_DP = 3;
  private static final int HANDLE_MARGIN_SIDE_DP = 8;
  
  // Handle opacity constants
  private static final float HANDLE_ACTIVE_ALPHA = 0.8f;    // 80% opacity when keyboard is active
  private static final float HANDLE_DIMMED_ALPHA = 0.4f;    // Dimmed when keyboard is disabled
  private static final float HANDLE_REENABLE_ALPHA = 0.8f;  // Re-enable handle always 80%
  
  // Visual feedback state for floating key modes
  private static boolean visualDragModeActive = false;
  private static boolean visualResizeModeActive = false;
  
  // Public static methods for visual feedback
  public static boolean isFloatingDragModeActive() {
    return visualDragModeActive;
  }
  
  public static boolean isFloatingResizeModeActive() {
    return visualResizeModeActive;
  }
  
  private static void clearFloatingModeVisuals() {
    visualDragModeActive = false;
    visualResizeModeActive = false;
  }
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
  
  // Handle references for opacity control
  private View _dragHandle;
  private View _resizeHandle;
  private View _passthroughToggle;

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
    // Only hide floating keyboard if persistence is disabled
    if (!_config.keyboard_persistence_enabled) {
      android.util.Log.d("juloo.keyboard2.fork", "Hiding floating keyboard (persistence disabled)");
      removeFloatingKeyboard();
    } else {
      android.util.Log.d("juloo.keyboard2.fork", "Keeping floating keyboard visible (persistence enabled)");
    }
  }

  @Override
  public void onFinishInput()
  {
    super.onFinishInput();
    // Only hide when input is completely finished if persistence is disabled
    if (!_config.keyboard_persistence_enabled) {
      android.util.Log.d("juloo.keyboard2.fork", "Hiding floating keyboard on finish input (persistence disabled)");
      removeFloatingKeyboard();
    } else {
      android.util.Log.d("juloo.keyboard2.fork", "Keeping floating keyboard visible on finish input (persistence enabled)");
    }
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

        case TOGGLE_PERSISTENCE:
          toggle_keyboard_persistence();
          break;

        case FLOATING_MOVE:
          // Start drag mode immediately
          startKeyboardDragMode();
          break;

        case FLOATING_RESIZE:
          // Start resize mode immediately
          startKeyboardResizeMode();
          break;

        case FLOATING_ENABLE_PASSTHROUGH:
          // Toggle passthrough mode (same as clicking the disable handle)
          if (_floatingContainer instanceof ResizableFloatingContainer) {
            ((ResizableFloatingContainer)_floatingContainer).enterPassthroughMode();
          }
          break;
        case SNAP_LEFT:
          snapKeyboardLeft();
          break;
        case SNAP_RIGHT:
          snapKeyboardRight();
          break;
        case FILL_WIDTH:
          fillKeyboardWidth();
          break;
        case TOGGLE_FLOATING_DOCKED:
          toggleFloatingDock();
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

    public void sendSystemKeyEvent(int keyCode)
    {
      try {
        android.util.Log.d("juloo.keyboard2.fork", "FloatingKeyboard2: Sending system-wide key event: " + keyCode);
        sendDownUpKeyEvents(keyCode);
      } catch (Exception e) {
        android.util.Log.e("juloo.keyboard2.fork", "FloatingKeyboard2: Error sending system key event: " + e.getMessage());
      }
    }

    public void handle_event_key_with_value(KeyValue keyValue)
    {
      LayoutSwitchingUtils.handleEventKeyWithValue(keyValue, _config, new LayoutSwitchingUtils.LayoutSwitcher() {
        @Override
        public void setTextLayout(int layoutIndex) {
          FloatingKeyboard2.this.setTextLayout(layoutIndex);
        }
      });
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
    
    // Use proper InputMethodService.switchInputMethod() instead of InputMethodManager.setInputMethod()
    String dockedImeId = getPackageName() + "/.Keyboard2";
    
    try {
      // Direct switch using InputMethodService method - more reliable than setInputMethod
      if (android.os.Build.VERSION.SDK_INT >= 28) {
        // For API 28+, switchInputMethod with subtype
        switchInputMethod(dockedImeId, null);
      } else {
        // For older APIs, use the simpler switchInputMethod
        switchInputMethod(dockedImeId);
      }
      android.util.Log.d("juloo.keyboard2.fork", "Successfully switched to docked IME: " + dockedImeId);
    } catch (Exception e) {
      android.util.Log.e("juloo.keyboard2.fork", "Failed to switch to docked IME: " + e.getMessage());
      // More specific fallback - try the old method before showing picker
      try {
        InputMethodManager imm = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        imm.setInputMethod(getWindow().getWindow().getAttributes().token, dockedImeId);
        android.util.Log.d("juloo.keyboard2.fork", "Fallback switch successful");
      } catch (Exception e2) {
        android.util.Log.e("juloo.keyboard2.fork", "Fallback failed, showing IME picker: " + e2.getMessage());
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker();
      }
    }
  }

  private void toggle_keyboard_persistence()
  {
    boolean currentState = _config.keyboard_persistence_enabled;
    boolean newState = !currentState;
    
    android.util.Log.d("juloo.keyboard2.fork", "Toggling keyboard persistence from " + currentState + " to " + newState);
    
    _config.set_keyboard_persistence_enabled(newState);
    
    // Show toast feedback to user
    String message = newState ? "Keyboard persistence enabled" : "Keyboard persistence disabled";
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
  }

  private void startKeyboardDragMode()
  {
    android.util.Log.d("FloatingKeyboard", "Starting keyboard drag mode via key press");
    
    if (!_floatingKeyboardActive || _floatingContainer == null) {
      android.util.Log.d("FloatingKeyboard", "Cannot start drag mode - floating keyboard not active");
      return;
    }
    
    // Set visual feedback state
    visualDragModeActive = true;
    if (_floatingKeyboardView != null) {
      ((Keyboard2View)_floatingKeyboardView).invalidate(); // Trigger redraw
    }
    
    if (_floatingContainer instanceof ResizableFloatingContainer) {
      ((ResizableFloatingContainer)_floatingContainer).startKeyDragMode();
    }
  }

  private void startKeyboardResizeMode()
  {
    android.util.Log.d("FloatingKeyboard", "Starting keyboard resize mode via key press");
    
    if (!_floatingKeyboardActive || _floatingContainer == null) {
      android.util.Log.d("FloatingKeyboard", "Cannot start resize mode - floating keyboard not active");
      return;
    }
    
    // Set visual feedback state
    visualResizeModeActive = true;
    if (_floatingKeyboardView != null) {
      ((Keyboard2View)_floatingKeyboardView).invalidate(); // Trigger redraw
    }
    
    if (_floatingContainer instanceof ResizableFloatingContainer) {
      ((ResizableFloatingContainer)_floatingContainer).startKeyResizeMode();
    }
  }

  private void updateHandleOpacity(boolean keyboardActive)
  {
    android.util.Log.d("juloo.keyboard2.fork", "Updating handle opacity - keyboard active: " + keyboardActive);
    
    if (keyboardActive) {
      // Keyboard is active - set handles to 80% opacity
      if (_dragHandle != null) {
        _dragHandle.setAlpha(HANDLE_ACTIVE_ALPHA);
      }
      if (_resizeHandle != null) {
        _resizeHandle.setAlpha(HANDLE_ACTIVE_ALPHA);
      }
      if (_passthroughToggle != null) {
        _passthroughToggle.setAlpha(HANDLE_REENABLE_ALPHA); // Always 80% for re-enable
      }
    } else {
      // Keyboard is disabled - dim drag and resize handles, but keep re-enable handle at 80%
      if (_dragHandle != null) {
        _dragHandle.setAlpha(HANDLE_DIMMED_ALPHA);
      }
      if (_resizeHandle != null) {
        _resizeHandle.setAlpha(HANDLE_DIMMED_ALPHA);
      }
      if (_passthroughToggle != null) {
        _passthroughToggle.setAlpha(HANDLE_REENABLE_ALPHA); // Always 80% as specified
      }
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
      
      // Calculate proper spacing for handles above keyboard
      Config config = Config.globalConfig();
      DisplayMetrics dm = getResources().getDisplayMetrics();
      
      // Calculate space needed for handles above keyboard
      int handleHeight = (int) config.handle_height_px;
      int handleMargin = (int) config.handle_margin_px;
      // Keyboard starts after handles, with optional additional margin
      // When margin=0, keyboard starts immediately after handles (at handleHeight position)
      int topSpaceForHandles = handleHeight + handleMargin;
      
      // Add keyboard with proper top margin for handles
      FrameLayout.LayoutParams keyboardParams = new FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
      keyboardParams.gravity = Gravity.CENTER;
      keyboardParams.setMargins(0, topSpaceForHandles, 0, 12);
      container.addView(_floatingKeyboardView, keyboardParams);
      
      // Create drag handle with expanded touch area using calculated spacing (if enabled)
      if (config.showDragHandle) {
        // Create drag handle container in the reserved space above keyboard
        FrameLayout dragTouchContainer = new FrameLayout(this);
        int dragHandleWidth = (int) config.handle_width_px;
        int dragHandleHeight = (int) config.handle_height_px;
        FrameLayout.LayoutParams touchParams = new FrameLayout.LayoutParams(dragHandleWidth, dragHandleHeight);
        touchParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        
        // Position handle using only the user-configured margin
        // When handle_margin_px is 0, handle sits directly on keyboard top edge
        touchParams.setMargins(0, 0, 0, 0);
        
        // Create visual handle (fills entire touch container)
        _dragHandle = new View(this);
        GradientDrawable handleDrawable = new GradientDrawable();
        handleDrawable.setColor(HANDLE_COLOR_INACTIVE);
        handleDrawable.setCornerRadius(6 * dm.density);
        _dragHandle.setBackground(handleDrawable);
        
        FrameLayout.LayoutParams visualParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, 
            FrameLayout.LayoutParams.MATCH_PARENT);
        
        // Add visual handle to touch container
        dragTouchContainer.addView(_dragHandle, visualParams);
        
        // Add touch container to main container
        container.addView(dragTouchContainer, touchParams);
        
        // Set up touch listener
        dragTouchContainer.setOnTouchListener(new FloatingDragTouchListener(_dragHandle));
      }
      
      // Always create resize functionality for key-initiated resize, but conditionally show handle
      container.createResizeHandle();
      _resizeHandle = container.getResizeHandle();
      
      // Hide resize handle if disabled in settings
      if (!config.showResizeHandle && _resizeHandle != null) {
        _resizeHandle.setVisibility(View.GONE);
      }
      
      // Create passthrough toggle button (if enabled)
      if (config.showPassthroughHandle) {
        container.createPassthroughToggle();
        _passthroughToggle = container.getPassthroughToggle();
      }
      
      // Set initial handle opacity to active state (80%)
      updateHandleOpacity(true);
      
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
      
      android.util.Log.d("FloatingKeyboard", "Drag handle created - Size: " + (config.showDragHandle ? config.handle_width_px + "x" + config.handle_height_px : "disabled"));
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
      // Create a proper copy of the drawable to preserve the inactive state
      GradientDrawable original = (GradientDrawable) handle.getBackground();
      this.originalDrawable = (GradientDrawable) original.getConstantState().newDrawable().mutate();
      this.originalDrawable.setColor(HANDLE_COLOR_INACTIVE); // Ensure it's inactive
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
    private ResizeTouchListener resizeTouchListener;
    private WindowManager windowManager;
    private WindowManager.LayoutParams layoutParams;
    private float initialScale = 1.0f;
    private float currentScale = 1.0f;
    private boolean isResizing = false;
    private float resizeStartX, resizeStartY;
    private int initialWidth, initialHeight;
    private int initialWidthPercent, initialHeightPercent;
    private int initialWindowX, initialWindowY;
    
    // Key-initiated drag state
    private boolean isDragging = false;
    private float dragStartX, dragStartY;
    private int dragInitialX, dragInitialY;
    
    private boolean passthroughMode = false;
    
    // Key-initiated drag/resize modes
    private boolean keyDragMode = false;
    private boolean keyResizeMode = false;

    public ResizableFloatingContainer(Context context) {
      super(context);
      // Set pivot point for scaling to top-left corner
      setPivotX(0);
      setPivotY(0);
      // Don't create resize handle here, create it after keyboard is added
    }
    
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
      super.onMeasure(widthMeasureSpec, heightMeasureSpec);
      
      Config config = Config.globalConfig();
      if (config != null) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int handleHeight = (int) config.handle_height_px;
        int handleMargin = (int) config.handle_margin_px;
        // Use only user-configured margin - no additional hardcoded margin
        int minExtraHeight = handleHeight + handleMargin;
        
        int currentWidth = getMeasuredWidth();
        int currentHeight = getMeasuredHeight();
        
        // Ensure container has minimum height for handles and keyboard
        // Don't add minExtraHeight to currentHeight since keyboard margin already accounts for this
        int finalHeight = currentHeight;
        
        // Ensure container is wide enough - keyboard should never be clipped
        // Find the keyboard view and ensure container width accommodates it
        int keyboardWidth = 0;
        for (int i = 0; i < getChildCount(); i++) {
          View child = getChildAt(i);
          if (child instanceof Keyboard2View) {
            child.measure(
              MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
              MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            );
            keyboardWidth = Math.max(keyboardWidth, child.getMeasuredWidth());
          }
        }
        
        // Container width should be at least as wide as keyboard, regardless of handle width
        int finalWidth = Math.max(currentWidth, keyboardWidth);
        
        setMeasuredDimension(finalWidth, finalHeight);
        
        android.util.Log.d("FloatingKeyboard", "Container measured - width: " + currentWidth + 
                          " -> " + finalWidth + "px, height: " + currentHeight + " -> " + finalHeight + 
                          "px, keyboard width: " + keyboardWidth + "px");
      }
    }

    public void setWindowManager(WindowManager wm, WindowManager.LayoutParams lp) {
      windowManager = wm;
      layoutParams = lp;
    }

    public void startKeyDragMode() {
      keyDragMode = true;
      android.util.Log.d("FloatingKeyboard", "Key drag mode activated");
      Toast.makeText(getContext(), "Touch keyboard to start dragging", Toast.LENGTH_SHORT).show();
    }

    public void startKeyResizeMode() {
      keyResizeMode = true;
      android.util.Log.d("FloatingKeyboard", "Key resize mode activated");
      Toast.makeText(getContext(), "Touch keyboard to start resizing", Toast.LENGTH_SHORT).show();
    }

    private void startDragFromTouch(MotionEvent event) {
      android.util.Log.d("FloatingKeyboard", "Starting drag from key-initiated touch");
      
      // Initialize drag state similar to FloatingDragTouchListener
      if (_dragHandle != null) {
        // Change handle color to show it's active
        GradientDrawable activeDrawable = new GradientDrawable();
        activeDrawable.setColor(HANDLE_COLOR_ACTIVE);
        activeDrawable.setCornerRadius(6 * getResources().getDisplayMetrics().density);
        _dragHandle.setBackground(activeDrawable);
      }
      
      isDragging = true;
      dragStartX = event.getRawX();
      dragStartY = event.getRawY();
      dragInitialX = _floatingLayoutParams.x;
      dragInitialY = _floatingLayoutParams.y;
      
      android.util.Log.d("FloatingKeyboard", "Key-initiated drag start at: " + dragStartX + "," + dragStartY + 
                        " from window position: " + dragInitialX + "," + dragInitialY);
      Toast.makeText(getContext(), "Dragging started", Toast.LENGTH_SHORT).show();
    }

    private void startResizeFromTouch(MotionEvent event) {
      android.util.Log.d("FloatingKeyboard", "Starting resize from key-initiated touch - delegating to ResizeTouchListener");
      
      // Simply delegate to the existing ResizeTouchListener by creating a synthetic DOWN event
      // and setting isResizing=true so subsequent MOVE events will be handled by the existing logic
      if (resizeTouchListener != null) {
        // Create a synthetic ACTION_DOWN event to initialize the ResizeTouchListener
        MotionEvent syntheticDown = MotionEvent.obtain(
          event.getDownTime(), 
          event.getEventTime(), 
          MotionEvent.ACTION_DOWN, 
          event.getRawX(), 
          event.getRawY(), 
          0
        );
        
        // Pass the synthetic event to the resize touch listener to initialize its state
        resizeTouchListener.onTouch(resizeHandle, syntheticDown);
        syntheticDown.recycle();
        
        android.util.Log.d("FloatingKeyboard", "Key-initiated resize delegated to ResizeTouchListener");
        Toast.makeText(getContext(), "Resizing started", Toast.LENGTH_SHORT).show();
      } else {
        android.util.Log.e("FloatingKeyboard", "ResizeTouchListener not available for key-initiated resize");
      }
    }

    public void createResizeHandle() {
      // Create resize handle with expanded touch area using config values
      DisplayMetrics dm = getResources().getDisplayMetrics();
      Config config = Config.globalConfig();
      
      // Create touch container for resize handle
      FrameLayout resizeTouchContainer = new FrameLayout(getContext());
      int handleWidth = (int) config.handle_width_px;
      int handleHeight = (int) config.handle_height_px;
      FrameLayout.LayoutParams touchParams = new FrameLayout.LayoutParams(handleWidth, handleHeight);
      touchParams.gravity = Gravity.TOP | Gravity.RIGHT;
      // Position handle using only user-configured margin
      int resizeHandleSideMargin = (int) (HANDLE_MARGIN_SIDE_DP * dm.density);
      touchParams.setMargins(0, 0, resizeHandleSideMargin, 0);
      
      // Create visual handle (fills entire touch container)
      resizeHandle = new View(getContext());
      
      // Set inactive background IMMEDIATELY to avoid any timing issues
      GradientDrawable inactiveDrawable = new GradientDrawable();
      inactiveDrawable.setColor(HANDLE_COLOR_INACTIVE);
      inactiveDrawable.setCornerRadius(6 * dm.density);
      resizeHandle.setBackground(inactiveDrawable);
      android.util.Log.d("FloatingKeyboard", "Set resize handle to inactive color immediately: " + Integer.toHexString(HANDLE_COLOR_INACTIVE));
      
      FrameLayout.LayoutParams visualParams = new FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.MATCH_PARENT, 
          FrameLayout.LayoutParams.MATCH_PARENT);
      
      // Add visual handle to touch container
      resizeTouchContainer.addView(resizeHandle, visualParams);
      
      // Create touch listener after the drawable is set
      resizeTouchListener = new ResizeTouchListener(resizeHandle);
      resizeTouchContainer.setOnTouchListener(resizeTouchListener);
      addView(resizeTouchContainer, touchParams);
      
      android.util.Log.d("FloatingKeyboard", "Resize handle created and added - Size: " + handleWidth + "x" + handleHeight + " Children: " + getChildCount());
      
      // Force visibility and make sure it's on top
      resizeHandle.setVisibility(View.VISIBLE);
      resizeHandle.bringToFront();
      resizeHandle.setElevation(20.0f);
      
      // Add a delayed check to catch any initialization issues that happen after layout
      resizeHandle.post(new Runnable() {
        @Override
        public void run() {
          // Double-check that the handle is still inactive after layout is complete
          resizeTouchListener.forceInactiveState();
          android.util.Log.d("FloatingKeyboard", "Post-layout: forced resize handle to inactive state");
        }
      });
    }

    public void createPassthroughToggle() {
      // Create passthrough toggle with expanded touch area using config values
      DisplayMetrics dm = getResources().getDisplayMetrics();
      Config config = Config.globalConfig();
      
      // Create touch container for passthrough toggle
      passthroughTouchContainer = new FrameLayout(getContext());
      int handleWidth = (int) config.handle_width_px;
      int handleHeight = (int) config.handle_height_px;
      FrameLayout.LayoutParams touchParams = new FrameLayout.LayoutParams(handleWidth, handleHeight);
      touchParams.gravity = Gravity.TOP | Gravity.LEFT;
      // Position handle using only user-configured margin
      int passthroughToggleSideMargin = (int) (HANDLE_MARGIN_SIDE_DP * dm.density);
      touchParams.setMargins(passthroughToggleSideMargin, 0, 0, 0);
      
      // Create visual handle (fills entire touch container)
      passthroughToggle = new View(getContext());
      GradientDrawable toggleDrawable = new GradientDrawable();
      toggleDrawable.setColor(HANDLE_COLOR_INACTIVE);
      toggleDrawable.setCornerRadius(6 * dm.density);
      passthroughToggle.setBackground(toggleDrawable);
      
      FrameLayout.LayoutParams visualParams = new FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.MATCH_PARENT, 
          FrameLayout.LayoutParams.MATCH_PARENT);
      
      passthroughTouchContainer.addView(passthroughToggle, visualParams);
      
      // Set touch listener on container, but pass visual handle for color changes
      passthroughTouchContainer.setOnTouchListener(new PassthroughToggleTouchListener(passthroughToggle));
      addView(passthroughTouchContainer, touchParams);
      
      // Always visible - toggle behavior will handle enable/disable states
      passthroughTouchContainer.setVisibility(View.VISIBLE);
      passthroughToggle.setElevation(20.0f);
      
      // Set initial appearance based on current state
      updateToggleButtonAppearance();
      android.util.Log.d("FloatingKeyboard", "Passthrough toggle created and added - Size: " + handleWidth + "x" + handleHeight);
      
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
        // Create a proper copy of the drawable to preserve the inactive state
        GradientDrawable original = (GradientDrawable) handle.getBackground();
        this.originalDrawable = (GradientDrawable) original.getConstantState().newDrawable().mutate();
        this.originalDrawable.setColor(HANDLE_COLOR_INACTIVE); // Ensure it's inactive
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
            
            // Toggle between enabled and disabled states
            if (passthroughMode) {
              exitPassthroughMode();
              showDebugToast("Keyboard enabled");
            } else {
              enterPassthroughMode();
              showDebugToast("Keyboard disabled (passthrough)");
            }
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
        
        // Use the existing background drawable if it's a GradientDrawable, otherwise create a new one
        if (handle.getBackground() instanceof GradientDrawable) {
          this.originalDrawable = (GradientDrawable) handle.getBackground();
          android.util.Log.d("FloatingKeyboard", "ResizeTouchListener constructor - using existing drawable");
        } else {
          // Fallback: create a fresh inactive drawable
          this.originalDrawable = new GradientDrawable();
          this.originalDrawable.setColor(HANDLE_COLOR_INACTIVE);
          this.originalDrawable.setCornerRadius(6 * handle.getContext().getResources().getDisplayMetrics().density);
          handle.setBackground(this.originalDrawable);
          android.util.Log.d("FloatingKeyboard", "ResizeTouchListener constructor - created new inactive drawable: " + Integer.toHexString(HANDLE_COLOR_INACTIVE));
        }
      }
      
      public void forceInactiveState() {
        // Force the handle to use the inactive drawable
        if (handleView != null && originalDrawable != null) {
          // Log current background before changing
          if (handleView.getBackground() instanceof GradientDrawable) {
            android.util.Log.d("FloatingKeyboard", "forceInactiveState() - current background is GradientDrawable");
          } else {
            android.util.Log.d("FloatingKeyboard", "forceInactiveState() - current background type: " + 
              (handleView.getBackground() != null ? handleView.getBackground().getClass().getSimpleName() : "null"));
          }
          
          handleView.setBackground(originalDrawable);
          android.util.Log.d("FloatingKeyboard", "forceInactiveState() called - setting to inactive color: " + Integer.toHexString(HANDLE_COLOR_INACTIVE));
        } else {
          android.util.Log.d("FloatingKeyboard", "forceInactiveState() - handleView or originalDrawable is null");
        }
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
            
            // Calculate initial dimensions from config percentages to avoid size jumps
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int scrWidth = dm.widthPixels;
            int scrHeight = dm.heightPixels;
            
            initialWidth = Math.round(scrWidth * _config.floatingKeyboardWidthPercent / 100f);
            initialHeight = Math.round(scrHeight * _config.floatingKeyboardHeightPercent / 100f);
            initialWidthPercent = _config.floatingKeyboardWidthPercent;
            initialHeightPercent = _config.floatingKeyboardHeightPercent;
            initialWindowX = _floatingLayoutParams.x;
            initialWindowY = _floatingLayoutParams.y;
            
            android.util.Log.d("FloatingKeyboard", "Resize start at: " + resizeStartX + ", " + resizeStartY + " Config-based size: " + initialWidth + "x" + initialHeight + " Initial: " + initialWidthPercent + "%x" + initialHeightPercent + "%");
            showDebugToast("Resize started - " + initialWidthPercent + "%x" + initialHeightPercent + "%");
            return true;

          case MotionEvent.ACTION_MOVE:
            if (isResizing) {
              float deltaX = event.getRawX() - resizeStartX;
              float deltaY = event.getRawY() - resizeStartY;
              
              // Get screen dimensions for calculations
              DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
              int screenWidth = displayMetrics.widthPixels;
              int screenHeight = displayMetrics.heightPixels;
              
              // Calculate new pixel-based dimensions directly from touch movement
              // Drag right = bigger (positive deltaX increases width)
              // Drag left = smaller (negative deltaX decreases width)
              int newKeyboardWidth = Math.round(initialWidth + deltaX);
              
              // Drag up = bigger (negative deltaY increases height)  
              // Drag down = smaller (positive deltaY decreases height)
              int newKeyboardHeight = Math.round(initialHeight - deltaY);
              
              // Apply pixel-based constraints
              int minKeyboardWidth = Math.round(screenWidth * 0.3f);  // Minimum 30% screen width
              int maxKeyboardWidth = screenWidth;                     // Maximum 100% screen width
              int minKeyboardHeight = Math.round(screenHeight * 0.1f); // Minimum 10% screen height
              int maxKeyboardHeight = Math.round(screenHeight * 0.6f); // Maximum 60% screen height
              
              // Clamp to constraints
              newKeyboardWidth = Math.max(minKeyboardWidth, Math.min(newKeyboardWidth, maxKeyboardWidth));
              newKeyboardHeight = Math.max(minKeyboardHeight, Math.min(newKeyboardHeight, maxKeyboardHeight));
              
              // Convert pixel dimensions back to percentages for config storage
              float newWidthPercent = (float)newKeyboardWidth / screenWidth * 100f;
              float newHeightPercent = (float)newKeyboardHeight / screenHeight * 100f;
              
              // Update dimensions in config (rounded to int for preferences)
              updateFloatingKeyboardWidth(Math.round(newWidthPercent));
              updateFloatingKeyboardHeight(Math.round(newHeightPercent));
              
              // Calculate new window position for top-right resize behavior
              // Bottom-left corner should remain fixed in place
              int newX = initialWindowX; // Keep left edge fixed when growing wider
              int newY = (initialWindowY + initialHeight) - newKeyboardHeight; // Keep bottom edge fixed
              
              // Apply bounds checking to prevent off-screen positioning
              newX = Math.max(0, Math.min(newX, screenWidth - newKeyboardWidth));
              newY = Math.max(0, Math.min(newY, screenHeight - newKeyboardHeight));
              
              _floatingLayoutParams.x = newX;
              _floatingLayoutParams.y = newY;
              _windowManager.updateViewLayout(_floatingContainer, _floatingLayoutParams);
              
              // Force keyboard redraw with new dimensions
              refreshFloatingKeyboard();
              
              android.util.Log.d("FloatingKeyboard", "Pixel-based resize - " + newKeyboardWidth + "x" + newKeyboardHeight + "px (" + Math.round(newWidthPercent) + "%x" + Math.round(newHeightPercent) + "%) delta: " + Math.round(deltaX) + "x" + Math.round(deltaY) + "px");
              
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
            android.util.Log.d("FloatingKeyboard", "Resize end - final dimensions: " + ResizableFloatingContainer.this.getWidth() + "x" + ResizableFloatingContainer.this.getHeight() + "px");
            showDebugToast("Resize ended - " + ResizableFloatingContainer.this.getWidth() + "x" + ResizableFloatingContainer.this.getHeight() + "px");
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
          // Make the main window not touchable so touches pass through to underlying apps
          _floatingLayoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
          windowManager.updateViewLayout(_floatingContainer, _floatingLayoutParams);
          
          // Dim the keyboard to show it's in passthrough mode
          if (_floatingKeyboardView != null) {
            Config config = Config.globalConfig();
            float disabledAlpha = config.keyboardDisabledOpacity / 100.0f;
            _floatingKeyboardView.setAlpha(disabledAlpha);
          }
          
          // Update handle opacity for disabled state
          updateHandleOpacity(false);
          
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
          // Restore main window touchability
          _floatingLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
          windowManager.updateViewLayout(_floatingContainer, _floatingLayoutParams);
          
          // Restore keyboard full opacity
          if (_floatingKeyboardView != null) {
            _floatingKeyboardView.setAlpha(1.0f);
          }
          
          // Update handle opacity for active state
          updateHandleOpacity(true);
          
          // Remove the separate toggle button window and restore toggle button appearance
          removeToggleButtonWindow();
          updateToggleButtonAppearance();
          
          android.util.Log.d("FloatingKeyboard", "Exited passthrough mode - main window touchable, keyboard restored, toggle window removed");
        } catch (Exception e) {
          android.util.Log.e("FloatingKeyboard", "Error exiting passthrough mode: " + e.getMessage());
        }
      }
    }

    private void updateToggleButtonAppearance() {
      if (passthroughToggle != null) {
        GradientDrawable toggleDrawable = new GradientDrawable();
        toggleDrawable.setCornerRadius(6 * getResources().getDisplayMetrics().density);
        
        if (passthroughMode) {
          // Disabled state - use a different color (orange/red to indicate disabled)
          toggleDrawable.setColor(0xFFBF616A); // Nord red - indicates disabled
        } else {
          // Enabled state - use normal inactive color
          toggleDrawable.setColor(HANDLE_COLOR_INACTIVE); // Nord blue - indicates enabled
        }
        
        passthroughToggle.setBackground(toggleDrawable);
        android.util.Log.d("FloatingKeyboard", "Updated toggle button appearance - passthrough mode: " + passthroughMode);
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
          // Normal mode - check for special key modes first, then gap touches
          if (keyDragMode || keyResizeMode) {
            // In key-initiated drag/resize mode - intercept any keyboard area touch
            float keyboardX = containerX - _floatingKeyboardView.getLeft();
            float keyboardY = containerY - _floatingKeyboardView.getTop();
            
            if (keyboardX >= 0 && keyboardX < _floatingKeyboardView.getWidth() && 
                keyboardY >= 0 && keyboardY < _floatingKeyboardView.getHeight()) {
              // Start drag or resize mode
              if (keyDragMode) {
                android.util.Log.d("FloatingKeyboard", "Starting key-initiated drag with touch at " + event.getRawX() + "," + event.getRawY());
                startDragFromTouch(event);
                keyDragMode = false; // Reset mode after starting
              } else if (keyResizeMode) {
                android.util.Log.d("FloatingKeyboard", "Starting key-initiated resize with touch at " + event.getRawX() + "," + event.getRawY());
                startResizeFromTouch(event);
                keyResizeMode = false; // Reset mode after starting
              }
              return true; // Intercept this touch
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
                showDebugToast("Keyboard disabled - tap top-left toggle button to re-enable");
                return true; // Intercept this touch and consume it
              } else {
                android.util.Log.d("FloatingKeyboard", "Key touch detected - normal processing");
              }
            }
          }
        }
      }
      
      return false; // Don't intercept by default
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      // Handle key-initiated drag and resize
      if (isDragging) {
        switch (event.getAction()) {
          case MotionEvent.ACTION_MOVE:
            float deltaX = event.getRawX() - dragStartX;
            float deltaY = event.getRawY() - dragStartY;
            
            _floatingLayoutParams.x = dragInitialX + (int) deltaX;
            _floatingLayoutParams.y = dragInitialY + (int) deltaY;
            windowManager.updateViewLayout(this, _floatingLayoutParams);
            return true;
            
          case MotionEvent.ACTION_UP:
          case MotionEvent.ACTION_CANCEL:
            isDragging = false;
            
            // Clear visual feedback
            FloatingKeyboard2.clearFloatingModeVisuals();
            if (_floatingKeyboardView != null) {
              ((Keyboard2View)_floatingKeyboardView).invalidate(); // Trigger redraw
            }
            
            // Restore drag handle color
            if (_dragHandle != null) {
              GradientDrawable inactiveDrawable = new GradientDrawable();
              inactiveDrawable.setColor(HANDLE_COLOR_INACTIVE);
              inactiveDrawable.setCornerRadius(6 * getResources().getDisplayMetrics().density);
              _dragHandle.setBackground(inactiveDrawable);
            }
            
            FloatingKeyboard2.this.saveFloatingKeyboardPosition();
            android.util.Log.d("FloatingKeyboard", "Key-initiated drag ended");
            return true;
        }
      }
      
      // Handle key-initiated resize by delegating to ResizeTouchListener
      if (isResizing && resizeTouchListener != null) {
        // Delegate all MOVE and UP events to the existing ResizeTouchListener
        boolean handled = resizeTouchListener.onTouch(resizeHandle, event);
        
        // Clear visual feedback on resize end
        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
          isResizing = false;
          FloatingKeyboard2.clearFloatingModeVisuals();
          if (_floatingKeyboardView != null) {
            ((Keyboard2View)_floatingKeyboardView).invalidate(); // Trigger redraw
          }
          android.util.Log.d("FloatingKeyboard", "Key-initiated resize ended (delegated)");
        }
        
        return handled;
      }
      
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

    // Getter methods for handle references
    public View getResizeHandle() {
      return resizeHandle;
    }

    public View getPassthroughToggle() {
      return passthroughToggle;
    }
  }

  private void createToggleButtonWindow() {
    if (_toggleButtonWindow != null) {
      return; // Already created
    }
    
    try {
      // Get keyboard and layout information to calculate top-right key position
      KeyboardData keyboard = _floatingKeyboardView != null ? 
        ((Keyboard2View)_floatingKeyboardView).getCurrentKeyboard() : null;
      
      if (keyboard == null || keyboard.rows.isEmpty()) {
        android.util.Log.e("FloatingKeyboard", "Cannot create toggle button - keyboard data unavailable");
        return;
      }
      
      // Calculate key dimensions and position
      DisplayMetrics dm = getResources().getDisplayMetrics();
      Config config = Config.globalConfig();
      
      // Get keyboard view metrics
      Keyboard2View keyboardView = (Keyboard2View)_floatingKeyboardView;
      float keyWidth = keyboardView.getKeyWidth();
      float marginLeft = keyboardView.getMarginLeft();
      float marginTop = keyboardView.getMarginTop();
      Theme.Computed tc = keyboardView.getThemeComputed();
      
      // Extract colors from existing paint objects
      int keyColor = tc.key.bg_paint.getColor();
      int keyActivatedColor = tc.key_activated.bg_paint.getColor();
      int borderColor = tc.key.border_left_paint.getColor();
      
      // Use simple colors for text - these will work with most themes
      int labelColor = 0xFF2E3440; // Nord dark blue-gray for normal state
      int activatedLabelColor = 0xFFD8DEE9; // Nord light gray for activated state
      
      // Find the top-right key (first row, last column)
      KeyboardData.Row firstRow = keyboard.rows.get(0);
      KeyboardData.Key topRightKey = firstRow.keys.get(firstRow.keys.size() - 1);
      
      // Calculate the position and size of the top-right key
      float x = marginLeft + tc.margin_left;
      for (int i = 0; i < firstRow.keys.size() - 1; i++) {
        KeyboardData.Key key = firstRow.keys.get(i);
        x += key.shift * keyWidth + key.width * keyWidth;
      }
      x += topRightKey.shift * keyWidth;
      
      float y = tc.margin_top + firstRow.shift * tc.row_height;
      float keyW = keyWidth * topRightKey.width - tc.horizontal_margin;
      float keyH = firstRow.height * tc.row_height - tc.vertical_margin;
      
      // Create a custom TextView to display the keyboard glyph
      android.widget.TextView toggleButton = new android.widget.TextView(this);
      toggleButton.setText("⌨");
      toggleButton.setTextColor(labelColor);
      toggleButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, keyH * 0.4f); // Scale text to key
      toggleButton.setGravity(android.view.Gravity.CENTER);
      
      // Style the button to look like a keyboard key
      android.graphics.drawable.GradientDrawable keyDrawable = new android.graphics.drawable.GradientDrawable();
      keyDrawable.setColor(keyColor);
      keyDrawable.setCornerRadius(tc.key.border_radius);
      if (tc.key.border_width > 0) {
        keyDrawable.setStroke((int)tc.key.border_width, borderColor);
      }
      toggleButton.setBackground(keyDrawable);
      
      _toggleButtonWindow = toggleButton;
      
      // Set up touch listener for the toggle button with drag support
      _toggleButtonWindow.setOnTouchListener(new View.OnTouchListener() {
        private android.graphics.drawable.GradientDrawable originalDrawable = keyDrawable;
        private float startX, startY;
        private int startWindowX, startWindowY;
        private boolean isDragging = false;
        private static final float DRAG_THRESHOLD = 10f; // pixels
        
        @Override
        public boolean onTouch(View v, MotionEvent event) {
          android.util.Log.d("FloatingKeyboard", "Re-enable button touched: " + event.getAction());
          
          switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
              // Record starting position for drag detection
              startX = event.getRawX();
              startY = event.getRawY();
              startWindowX = _toggleLayoutParams.x;
              startWindowY = _toggleLayoutParams.y;
              isDragging = false;
              
              // Style like an activated key
              android.graphics.drawable.GradientDrawable activeDrawable = new android.graphics.drawable.GradientDrawable();
              activeDrawable.setColor(keyActivatedColor);
              activeDrawable.setCornerRadius(tc.key_activated.border_radius);
              if (tc.key_activated.border_width > 0) {
                activeDrawable.setStroke((int)tc.key_activated.border_width, borderColor);
              }
              v.setBackground(activeDrawable);
              ((android.widget.TextView)v).setTextColor(activatedLabelColor);
              return true;
              
            case MotionEvent.ACTION_MOVE:
              float deltaX = event.getRawX() - startX;
              float deltaY = event.getRawY() - startY;
              float distance = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
              
              if (distance > DRAG_THRESHOLD) {
                isDragging = true;
                
                // Update window position
                _toggleLayoutParams.x = startWindowX + (int)deltaX;
                _toggleLayoutParams.y = startWindowY + (int)deltaY;
                
                try {
                  _windowManager.updateViewLayout(_toggleButtonWindow, _toggleLayoutParams);
                } catch (Exception e) {
                  android.util.Log.e("FloatingKeyboard", "Error updating re-enable button position: " + e.getMessage());
                }
              }
              return true;
              
            case MotionEvent.ACTION_UP:
              // Restore original key style
              v.setBackground(originalDrawable);
              ((android.widget.TextView)v).setTextColor(labelColor);
              
              if (!isDragging) {
                // This was a tap, not a drag - re-enable the keyboard
                if (_floatingContainer instanceof ResizableFloatingContainer) {
                  ((ResizableFloatingContainer)_floatingContainer).exitPassthroughMode();
                }
                showDebugToast("Keyboard touches re-enabled");
              } else {
                // This was a drag - save the new position
                saveToggleButtonPosition(_toggleLayoutParams.x, _toggleLayoutParams.y);
                showDebugToast("Re-enable button moved to new position");
                android.util.Log.d("FloatingKeyboard", "Re-enable button dragged to: " + _toggleLayoutParams.x + "," + _toggleLayoutParams.y);
              }
              return true;
              
            case MotionEvent.ACTION_CANCEL:
              // Restore original key style
              v.setBackground(originalDrawable);
              ((android.widget.TextView)v).setTextColor(labelColor);
              return true;
          }
          
          return false;
        }
      });
      
      // Position the toggle button exactly where the top-right key would be
      _toggleLayoutParams = new WindowManager.LayoutParams(
          (int)keyW,
          (int)keyH,
          VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
          WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
          PixelFormat.TRANSLUCENT);
      
      _toggleLayoutParams.gravity = Gravity.TOP | Gravity.LEFT;
      
      // Check for saved position, otherwise use default top-right key position
      android.content.SharedPreferences prefs = getSharedPreferences("FloatingKeyboard", MODE_PRIVATE);
      boolean hasSavedPosition = prefs.contains("toggle_button_x") && prefs.contains("toggle_button_y");
      
      if (hasSavedPosition) {
        _toggleLayoutParams.x = prefs.getInt("toggle_button_x", _floatingLayoutParams.x + (int)x);
        _toggleLayoutParams.y = prefs.getInt("toggle_button_y", _floatingLayoutParams.y + (int)y);
        android.util.Log.d("FloatingKeyboard", "Using saved toggle button position: " + _toggleLayoutParams.x + "," + _toggleLayoutParams.y);
      } else {
        _toggleLayoutParams.x = _floatingLayoutParams.x + (int)x;
        _toggleLayoutParams.y = _floatingLayoutParams.y + (int)y;
        android.util.Log.d("FloatingKeyboard", "Using default toggle button position: " + _toggleLayoutParams.x + "," + _toggleLayoutParams.y);
      }
      
      _windowManager.addView(_toggleButtonWindow, _toggleLayoutParams);
      
      android.util.Log.d("FloatingKeyboard", "Re-enable button created at top-right key position: " + 
                        _toggleLayoutParams.x + "," + _toggleLayoutParams.y + 
                        " with size: " + (int)keyW + "x" + (int)keyH);
    } catch (Exception e) {
      android.util.Log.e("FloatingKeyboard", "Error creating toggle button window: " + e.getMessage());
    }
  }

  private void saveToggleButtonPosition(int x, int y) {
    try {
      android.content.SharedPreferences prefs = getSharedPreferences("FloatingKeyboard", MODE_PRIVATE);
      android.content.SharedPreferences.Editor editor = prefs.edit();
      editor.putInt("toggle_button_x", x);
      editor.putInt("toggle_button_y", y);
      editor.apply();
      android.util.Log.d("FloatingKeyboard", "Saved toggle button position: " + x + "," + y);
    } catch (Exception e) {
      android.util.Log.e("FloatingKeyboard", "Error saving toggle button position: " + e.getMessage());
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

  private void snapKeyboardLeft()
  {
    android.util.Log.d("FloatingKeyboard", "Snapping keyboard to left");
    
    if (!_floatingKeyboardActive || _floatingLayoutParams == null) {
      android.util.Log.d("FloatingKeyboard", "Cannot snap left - floating keyboard not active");
      return;
    }
    
    Config config = Config.globalConfig();
    DisplayMetrics dm = getResources().getDisplayMetrics();
    
    // Update keyboard configuration width to snap percentage
    SharedPreferences.Editor editor = Config.globalPrefs().edit();
    if (config.orientation_landscape) {
      if (config.foldable_unfolded) {
        editor.putInt("floating_keyboard_width_landscape_unfolded", config.snapWidthPercent);
      } else {
        editor.putInt("floating_keyboard_width_landscape", config.snapWidthPercent);
      }
    } else {
      if (config.foldable_unfolded) {
        editor.putInt("floating_keyboard_width_unfolded", config.snapWidthPercent);
      } else {
        editor.putInt("floating_keyboard_width", config.snapWidthPercent);
      }
    }
    editor.apply();
    
    // Position at left edge
    _floatingLayoutParams.x = 0;
    
    // Refresh the keyboard with new dimensions
    refreshFloatingKeyboard();
    
    showDebugToast("Snapped to left (" + config.snapWidthPercent + "% width)");
    android.util.Log.d("FloatingKeyboard", "Keyboard snapped left with width=" + config.snapWidthPercent + "%");
  }

  private void snapKeyboardRight()
  {
    android.util.Log.d("FloatingKeyboard", "Snapping keyboard to right");
    
    if (!_floatingKeyboardActive || _floatingLayoutParams == null) {
      android.util.Log.d("FloatingKeyboard", "Cannot snap right - floating keyboard not active");
      return;
    }
    
    Config config = Config.globalConfig();
    DisplayMetrics dm = getResources().getDisplayMetrics();
    
    // Update keyboard configuration width to snap percentage
    SharedPreferences.Editor editor = Config.globalPrefs().edit();
    if (config.orientation_landscape) {
      if (config.foldable_unfolded) {
        editor.putInt("floating_keyboard_width_landscape_unfolded", config.snapWidthPercent);
      } else {
        editor.putInt("floating_keyboard_width_landscape", config.snapWidthPercent);
      }
    } else {
      if (config.foldable_unfolded) {
        editor.putInt("floating_keyboard_width_unfolded", config.snapWidthPercent);
      } else {
        editor.putInt("floating_keyboard_width", config.snapWidthPercent);
      }
    }
    editor.apply();
    
    // Calculate right position after refresh (will be updated by refreshFloatingKeyboard)
    int snapWidth = (int)(dm.widthPixels * config.snapWidthPercent / 100.0f);
    int rightPosition = dm.widthPixels - snapWidth;
    
    // Refresh the keyboard with new dimensions first
    refreshFloatingKeyboard();
    
    // Then position at right edge
    _floatingLayoutParams.x = rightPosition;
    _windowManager.updateViewLayout(_floatingContainer, _floatingLayoutParams);
    
    showDebugToast("Snapped to right (" + config.snapWidthPercent + "% width)");
    android.util.Log.d("FloatingKeyboard", "Keyboard snapped right with width=" + config.snapWidthPercent + "%");
  }

  private void fillKeyboardWidth()
  {
    android.util.Log.d("FloatingKeyboard", "Filling keyboard width to 100%");
    
    if (!_floatingKeyboardActive || _floatingLayoutParams == null) {
      android.util.Log.d("FloatingKeyboard", "Cannot fill width - floating keyboard not active");
      return;
    }
    
    DisplayMetrics dm = getResources().getDisplayMetrics();
    
    // Set width to 100% and position to left edge
    _floatingLayoutParams.width = dm.widthPixels;
    _floatingLayoutParams.x = 0;
    
    try {
      _windowManager.updateViewLayout(_floatingContainer, _floatingLayoutParams);
      showDebugToast("Keyboard width filled to 100%");
      android.util.Log.d("FloatingKeyboard", "Keyboard width filled: width=" + dm.widthPixels + ", x=0");
    } catch (Exception e) {
      android.util.Log.e("FloatingKeyboard", "Error filling keyboard width: " + e.getMessage());
    }
  }

  private void toggleFloatingDock()
  {
    android.util.Log.d("FloatingKeyboard", "Toggling floating dock mode");
    
    if (!_floatingKeyboardActive || _floatingLayoutParams == null) {
      android.util.Log.d("FloatingKeyboard", "Cannot toggle dock - floating keyboard not active");
      return;
    }
    
    Config config = Config.globalConfig();
    boolean wasDocked = config.isFloatingDocked;
    
    if (!wasDocked) {
      // Enter docked mode
      enterDockedMode();
    } else {
      // Exit docked mode  
      exitDockedMode();
    }
    
    // Update config state
    config.set_floating_docked(!wasDocked);
    
    String message = config.isFloatingDocked ? "Entered docked mode" : "Exited docked mode";
    showDebugToast(message);
    android.util.Log.d("FloatingKeyboard", "Floating dock toggled to: " + config.isFloatingDocked);
  }

  private void enterDockedMode()
  {
    android.util.Log.d("FloatingKeyboard", "Entering docked mode");
    
    DisplayMetrics dm = getResources().getDisplayMetrics();
    
    // Save current position for restoration later
    android.content.SharedPreferences prefs = getSharedPreferences("FloatingKeyboard", MODE_PRIVATE);
    android.content.SharedPreferences.Editor editor = prefs.edit();
    editor.putInt("pre_dock_x", _floatingLayoutParams.x);
    editor.putInt("pre_dock_y", _floatingLayoutParams.y);
    editor.putInt("pre_dock_width", _floatingLayoutParams.width);
    editor.apply();
    
    // Position keyboard at bottom of screen, full width
    _floatingLayoutParams.width = dm.widthPixels;
    _floatingLayoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
    _floatingLayoutParams.x = 0;
    _floatingLayoutParams.y = dm.heightPixels - getKeyboardHeight();
    
    // Add flag to reserve system UI space
    _floatingLayoutParams.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
    _floatingLayoutParams.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
    
    try {
      _windowManager.updateViewLayout(_floatingContainer, _floatingLayoutParams);
      android.util.Log.d("FloatingKeyboard", "Entered docked mode successfully");
    } catch (Exception e) {
      android.util.Log.e("FloatingKeyboard", "Error entering docked mode: " + e.getMessage());
    }
  }

  private void exitDockedMode()
  {
    android.util.Log.d("FloatingKeyboard", "Exiting docked mode");
    
    // Restore previous position and size
    android.content.SharedPreferences prefs = getSharedPreferences("FloatingKeyboard", MODE_PRIVATE);
    int prevX = prefs.getInt("pre_dock_x", 100);
    int prevY = prefs.getInt("pre_dock_y", 100);
    int prevWidth = prefs.getInt("pre_dock_width", 800);
    
    _floatingLayoutParams.width = prevWidth;
    _floatingLayoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
    _floatingLayoutParams.x = prevX;
    _floatingLayoutParams.y = prevY;
    
    // Remove docked mode flags
    _floatingLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
    _floatingLayoutParams.systemUiVisibility = 0;
    
    try {
      _windowManager.updateViewLayout(_floatingContainer, _floatingLayoutParams);
      android.util.Log.d("FloatingKeyboard", "Exited docked mode successfully");
    } catch (Exception e) {
      android.util.Log.e("FloatingKeyboard", "Error exiting docked mode: " + e.getMessage());
    }
  }

  private int getKeyboardHeight()
  {
    if (_floatingKeyboardView != null) {
      _floatingKeyboardView.measure(
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
      );
      return _floatingKeyboardView.getMeasuredHeight();
    }
    // Fallback height estimate
    return (int)(getResources().getDisplayMetrics().heightPixels * 0.25f);
  }
}