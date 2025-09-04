package juloo.keyboard2.fork;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.Looper;
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
  
  // Stuck key prevention - ignore input for a short time after mode switches
  private static final long INPUT_IGNORE_DELAY_MS = 100;
  
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
  
  // Public method to clear visual feedback from external operations
  public static void clearAllVisualFeedback() {
    clearFloatingModeVisuals();
    android.util.Log.d("FloatingKeyboard", "Cleared all visual feedback modes");
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
  
  // Position and dimensions of the key that triggered passthrough mode (for button placement)
  private int _triggeringKeyScreenX = -1;
  private int _triggeringKeyScreenY = -1;
  private float _triggeringKeyWidth = -1;
  private float _triggeringKeyHeight = -1;
  
  // Removed handle references - functionality now handled by key values

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
    
    // Only create floating keyboard if it doesn't exist
    if (!_floatingKeyboardActive) {
      createFloatingKeyboard();
    }
    
    if (_floatingKeyboardActive && _floatingKeyboardView != null) {
      ((Keyboard2View)_floatingKeyboardView).setKeyboard(current_layout());
      // Clear any stuck key states on startup - do this multiple times to ensure it sticks
      Keyboard2View kbView = (Keyboard2View)_floatingKeyboardView;
      kbView.reset();
      // Post another reset to clear any state that might get set after layout
      kbView.post(new Runnable() {
        @Override
        public void run() {
          kbView.reset();
          android.util.Log.d("FloatingKeyboard", "Post-layout keyboard state reset to prevent stuck keys");
        }
      });
      android.util.Log.d("FloatingKeyboard", "Cleared keyboard state on startup to prevent stuck keys");
    }
  }

  @Override
  public void onConfigurationChanged(android.content.res.Configuration newConfig)
  {
    super.onConfigurationChanged(newConfig);
    
    android.util.Log.d("FloatingKeyboard", "Configuration changed - recreating floating keyboard");
    
    if (_floatingKeyboardActive) {
      // Don't save position here - the orientation has already changed
      // and we'd save to the wrong orientation keys
      
      // Recreate keyboard with new orientation settings
      removeFloatingKeyboard();
      
      // Refresh config with new orientation
      _config.refresh(getResources(), _foldStateTracker != null ? _foldStateTracker.isUnfolded() : false);
      
      // Recreate keyboard which will load position for new orientation
      createFloatingKeyboard();
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
      // Force container to remeasure with new config dimensions
      if (_floatingContainer != null) {
        _floatingContainer.requestLayout();
      }
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
          // Check if we're in passthrough mode - if so, move the passthrough keyboard instead
          if (_floatingContainer instanceof ResizableFloatingContainer && 
              ((ResizableFloatingContainer)_floatingContainer).isInPassthroughMode()) {
            startPassthroughKeyboardDragMode();
          } else {
            // Start drag mode for main keyboard
            startKeyboardDragMode();
          }
          break;

        case FLOATING_RESIZE:
          // Start resize mode immediately
          startKeyboardResizeMode();
          break;

        case FLOATING_ENABLE_PASSTHROUGH:
          // Capture the position of the triggering key before entering passthrough mode
          capturePassthroughTriggeringKeyPosition();
          // Toggle passthrough mode (same as clicking the disable handle)
          if (_floatingContainer instanceof ResizableFloatingContainer) {
            ((ResizableFloatingContainer)_floatingContainer).enterPassthroughMode();
          }
          break;
        case FLOATING_DISABLE_PASSTHROUGH:
          // Exit passthrough mode and re-enable keyboard touches
          if (_floatingContainer instanceof ResizableFloatingContainer) {
            ((ResizableFloatingContainer)_floatingContainer).exitPassthroughMode();
          }
          break;
        case SNAP_LEFT:
          // Clear any active visual modes before snapping
          clearAllVisualFeedback();
          // Force immediate completion of current key event to prevent stuck keys
          if (_floatingKeyboardView instanceof Keyboard2View) {
            ((Keyboard2View)_floatingKeyboardView).reset();
          }
          snapKeyboardLeft();
          break;
        case SNAP_RIGHT:
          // Clear any active visual modes before snapping
          clearAllVisualFeedback();
          // Force immediate completion of current key event to prevent stuck keys
          if (_floatingKeyboardView instanceof Keyboard2View) {
            ((Keyboard2View)_floatingKeyboardView).reset();
          }
          snapKeyboardRight();
          break;
        case SNAP_TOP:
          // Clear any active visual modes before snapping
          clearAllVisualFeedback();
          // Force immediate completion of current key event to prevent stuck keys
          if (_floatingKeyboardView instanceof Keyboard2View) {
            ((Keyboard2View)_floatingKeyboardView).reset();
          }
          snapKeyboardTop();
          break;
        case SNAP_BOTTOM:
          // Clear any active visual modes before snapping
          clearAllVisualFeedback();
          // Force immediate completion of current key event to prevent stuck keys
          if (_floatingKeyboardView instanceof Keyboard2View) {
            ((Keyboard2View)_floatingKeyboardView).reset();
          }
          snapKeyboardBottom();
          break;
        case FILL_WIDTH:
          // Clear any active visual modes before filling
          clearAllVisualFeedback();
          // Force immediate completion of current key event to prevent stuck keys
          if (_floatingKeyboardView instanceof Keyboard2View) {
            ((Keyboard2View)_floatingKeyboardView).reset();
          }
          fillKeyboardWidth();
          break;
        case TOGGLE_FLOATING_DOCKED:
          toggleFloatingDock();
          break;
        case CENTER_HORIZONTAL:
          centerKeyboardHorizontal();
          break;
        case CENTER_VERTICAL:
          centerKeyboardVertical();
          break;
        case CENTER_BOTH:
          centerKeyboardBoth();
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

  // Removed updateHandleOpacity method - no handles to manage

  private void startPassthroughKeyboardDragMode()
  {
    android.util.Log.d("FloatingKeyboard", "Passthrough keyboard drag requested (not yet implemented)");
    
    if (_passthroughKeyboardView == null || _passthroughLayoutParams == null) {
      android.util.Log.d("FloatingKeyboard", "Cannot start passthrough drag mode - passthrough keyboard not active");
      return;
    }
    
    // For now, just show a message that drag mode was requested
    // TODO: Implement proper drag mode without breaking touch handling
    showDebugToast("Passthrough drag mode (not yet implemented)");
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
      
      // Get current orientation directly from system resources to ensure accuracy
      boolean currentLandscape = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
      boolean currentUnfolded = _foldStateTracker != null ? _foldStateTracker.isUnfolded() : false;
      
      // Position settings don't have unfolded variants, use basic orientation
      String suffix = currentLandscape ? "_landscape" : "_portrait";
      
      String xKey = "floating_keyboard_x" + suffix;
      String yKey = "floating_keyboard_y" + suffix;
      
      editor.putInt(xKey, _floatingLayoutParams.x);
      editor.putInt(yKey, _floatingLayoutParams.y);
      editor.apply();
      
      android.util.Log.d("FloatingKeyboard", "Position saved (" + suffix + "): " + _floatingLayoutParams.x + "," + _floatingLayoutParams.y + " to keys " + xKey + "," + yKey + " (current orientation: landscape=" + currentLandscape + ", unfolded=" + currentUnfolded + ")");
      
      if (isDebugModeEnabled()) {
        showDebugToast("Position saved (" + suffix + "): " + _floatingLayoutParams.x + "," + _floatingLayoutParams.y);
      }
    }
  }
  
  private int[] loadFloatingKeyboardDimensions() {
    SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(this);
    DisplayMetrics dm = getResources().getDisplayMetrics();
    
    // Get current orientation
    boolean currentLandscape = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    boolean currentUnfolded = _foldStateTracker != null ? _foldStateTracker.isUnfolded() : false;
    
    String suffix;
    if (currentUnfolded) {
      suffix = currentLandscape ? "_landscape_unfolded" : "_portrait_unfolded";
    } else {
      suffix = currentLandscape ? "_landscape" : "_portrait";
    }
    
    String widthKey = "floating_keyboard_width_px" + suffix;
    String heightKey = "floating_keyboard_height_px" + suffix;
    
    // Try to load pixel dimensions first
    int widthPx = prefs.getInt(widthKey, -1);
    int heightPx = prefs.getInt(heightKey, -1);
    
    // If no pixel dimensions saved, calculate from percentages (for fresh install or upgrade)
    if (widthPx == -1 || heightPx == -1) {
      // Use the percentage values from config which loads from preferences with defaults
      widthPx = (int)(dm.widthPixels * _config.floatingKeyboardWidthPercent / 100.0f);
      heightPx = (int)(dm.heightPixels * _config.floatingKeyboardHeightPercent / 100.0f);
      
      android.util.Log.d("FloatingKeyboard", "No saved pixel dimensions, calculated from percentages: " + 
                        widthPx + "x" + heightPx + "px (" + _config.floatingKeyboardWidthPercent + "% x " + 
                        _config.floatingKeyboardHeightPercent + "%)");
      
      // Save these calculated pixel dimensions for next time
      SharedPreferences.Editor editor = prefs.edit();
      editor.putInt(widthKey, widthPx);
      editor.putInt(heightKey, heightPx);
      editor.apply();
    } else {
      android.util.Log.d("FloatingKeyboard", "Loaded saved pixel dimensions: " + widthPx + "x" + heightPx + "px");
      
      // Update config percentages to match loaded pixel dimensions
      _config.floatingKeyboardWidthPercent = Math.round(widthPx * 100f / dm.widthPixels);
      _config.floatingKeyboardHeightPercent = Math.round(heightPx * 100f / dm.heightPixels);
    }
    
    return new int[] { widthPx, heightPx };
  }

  private void saveFloatingKeyboardDimensions(int widthPx, int heightPx) {
    SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(this);
    SharedPreferences.Editor editor = prefs.edit();
    
    // Get current orientation directly from system resources to ensure accuracy
    boolean currentLandscape = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    boolean currentUnfolded = _foldStateTracker != null ? _foldStateTracker.isUnfolded() : false;
    
    String suffix;
    if (currentUnfolded) {
      suffix = currentLandscape ? "_landscape_unfolded" : "_portrait_unfolded";
    } else {
      suffix = currentLandscape ? "_landscape" : "_portrait";
    }
    
    String widthKey = "floating_keyboard_width_px" + suffix;
    String heightKey = "floating_keyboard_height_px" + suffix;
    
    editor.putInt(widthKey, widthPx);
    editor.putInt(heightKey, heightPx);
    
    // Also update the percentage values for backward compatibility
    DisplayMetrics dm = getResources().getDisplayMetrics();
    int widthPercent = Math.round(widthPx * 100f / dm.widthPixels);
    int heightPercent = Math.round(heightPx * 100f / dm.heightPixels);
    
    String widthPercentKey, heightPercentKey;
    if (currentLandscape) {
      widthPercentKey = currentUnfolded ? "floating_keyboard_width_landscape_unfolded" : "floating_keyboard_width_landscape";
      heightPercentKey = currentUnfolded ? "floating_keyboard_height_landscape_unfolded" : "floating_keyboard_height_landscape";
    } else {
      widthPercentKey = currentUnfolded ? "floating_keyboard_width_unfolded" : "floating_keyboard_width";
      heightPercentKey = currentUnfolded ? "floating_keyboard_height_unfolded" : "floating_keyboard_height";
    }
    
    editor.putInt(widthPercentKey, widthPercent);
    editor.putInt(heightPercentKey, heightPercent);
    editor.apply();
    
    // Update config with new percentages
    _config.floatingKeyboardWidthPercent = widthPercent;
    _config.floatingKeyboardHeightPercent = heightPercent;
    
    android.util.Log.d("FloatingKeyboard", "Saved dimensions: " + widthPx + "x" + heightPx + "px (" + widthPercent + "% x " + heightPercent + "%) to keys " + widthKey + ", " + heightKey);
  }

  private void clampKeyboardPositionToScreen() {
    if (_floatingLayoutParams == null || _floatingContainer == null) {
      return;
    }
    
    // Get screen dimensions and keyboard dimensions
    DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
    int screenWidth = displayMetrics.widthPixels;
    int screenHeight = displayMetrics.heightPixels;
    
    // For overlay windows, we need to account for system UI constraints
    // Get the actual usable area by checking the window manager's display metrics
    WindowManager wm = getSystemService(WindowManager.class);
    if (wm != null) {
      android.graphics.Point size = new android.graphics.Point();
      wm.getDefaultDisplay().getRealSize(size);
      screenWidth = size.x;
      screenHeight = size.y;
      android.util.Log.d("FloatingKeyboard", "Real screen size: " + screenWidth + "x" + screenHeight + " vs metrics: " + displayMetrics.widthPixels + "x" + displayMetrics.heightPixels);
    }
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
      // Don't refresh the config - we just updated it manually and don't want to overwrite
      // _config.refresh(getResources(), _config.foldable_unfolded);
      
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
    
    // Clear any stale visual mode feedback when creating new keyboard
    clearFloatingModeVisuals();
    android.util.Log.d("FloatingKeyboard", "Cleared visual mode feedback during keyboard creation");
    
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
      
      // No handles needed - add keyboard directly  
      Config config = Config.globalConfig();
      
      // Add keyboard without handle spacing
      FrameLayout.LayoutParams keyboardParams = new FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
      keyboardParams.gravity = Gravity.TOP | Gravity.LEFT;  // Changed from CENTER to avoid centering issues
      keyboardParams.setMargins(0, 0, 0, 0);  // Remove bottom margin that was creating dead space
      container.addView(_floatingKeyboardView, keyboardParams);
      
      // Removed drag handle creation - functionality handled by key values
      
      // Removed resize and passthrough handle creation - functionality handled by key values
      
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
      
      // Restore saved position based on orientation or use defaults
      SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(this);
      
      // Get current orientation directly from system resources to ensure accuracy
      boolean currentLandscape = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
      boolean currentUnfolded = _foldStateTracker != null ? _foldStateTracker.isUnfolded() : false;
      
      // Position settings don't have unfolded variants, use basic orientation
      String suffix = currentLandscape ? "_landscape" : "_portrait";
      
      String xKey = "floating_keyboard_x" + suffix;
      String yKey = "floating_keyboard_y" + suffix;
      
      params.x = prefs.getInt(xKey, 100);
      params.y = prefs.getInt(yKey, 300);
      
      android.util.Log.d("FloatingKeyboard", "Position loaded (" + suffix + "): " + params.x + "," + params.y + " from keys " + xKey + "," + yKey + " (current orientation: landscape=" + currentLandscape + ", unfolded=" + currentUnfolded + ")");
      android.util.Log.d("FloatingKeyboard", "Config dimensions: " + _config.floatingKeyboardWidthPercent + "% x " + _config.floatingKeyboardHeightPercent + "%");
      
      // Prevent spurious touch events during keyboard creation
      container.ignoreInputUntil = System.currentTimeMillis() + INPUT_IGNORE_DELAY_MS;
      
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
      
      android.util.Log.d("FloatingKeyboard", "Floating keyboard created without handles");
      container.setWindowManager(_windowManager, params);
      
      _floatingKeyboardActive = true;
      
      
    } catch (Exception e) {
      Logs.exn("Failed to create floating keyboard", e);
    }
  }
  
  private void removeFloatingKeyboard() {
    if (_floatingKeyboardActive && _floatingContainer != null) {
      try {
        // Clean up toggle button window if it exists
        removeToggleButtonWindow();
        
        // Clean up passthrough keyboard if it exists
        removePassthroughKeyboard();
        
        _windowManager.removeView(_floatingContainer);
      } catch (Exception e) {
        Logs.exn("Failed to remove floating keyboard", e);
      }
      
      // Clear any keyboard state before removing references
      if (_floatingKeyboardView instanceof Keyboard2View) {
        ((Keyboard2View)_floatingKeyboardView).reset();
        android.util.Log.d("FloatingKeyboard", "Reset keyboard state during removal");
      }
      
      // Clear any pending operations in the key event handler to prevent stuck key loops
      try {
        if (_keyeventhandler != null && _handler != null) {
          // Clear all pending handler operations that might be causing stuck key loops
          _handler.removeCallbacksAndMessages(null);
          android.util.Log.d("FloatingKeyboard", "Cleared all pending handler operations to prevent stuck keys");
        }
      } catch (Exception e) {
        android.util.Log.w("FloatingKeyboard", "Could not clear handler operations: " + e.getMessage());
      }
      
      _floatingKeyboardActive = false;
      _floatingKeyboardView = null;
      _floatingLayoutParams = null;
      _floatingContainer = null;
    }
  }

  // Removed FloatingDragTouchListener class - no longer needed


  private class ResizableFloatingContainer extends FrameLayout {
    // Removed handle field declarations - no longer needed
    private FrameLayout passthroughTouchContainer;
    // Removed ResizeTouchListener field - no longer needed
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
    
    private long ignoreInputUntil = 0;

    public ResizableFloatingContainer(Context context) {
      super(context);
      // Set pivot point for scaling to top-left corner
      setPivotX(0);
      setPivotY(0);
      // Don't create resize handle here, create it after keyboard is added
    }
    
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
      // Load the saved pixel dimensions (with fallback to percentage-based defaults)
      int[] dimensions = FloatingKeyboard2.this.loadFloatingKeyboardDimensions();
      int targetWidth = dimensions[0];
      int targetHeight = dimensions[1];
      
      Config config = Config.globalConfig();
      
      // Measure children with the target dimensions
      int actualHeight = 0;
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i);
        if (child instanceof Keyboard2View) {
          // Force the keyboard view to use the target dimensions
          int childWidthSpec = MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY);
          int childHeightSpec = MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.AT_MOST);
          child.measure(childWidthSpec, childHeightSpec);
          // Get the actual measured height of the keyboard
          actualHeight = child.getMeasuredHeight();
        } else {
          // Measure other children normally
          child.measure(
            MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.AT_MOST)
          );
        }
      }
      
      // Use the actual keyboard height for the container height
      int containerHeight = actualHeight > 0 ? actualHeight : targetHeight;
      setMeasuredDimension(targetWidth, containerHeight);
      
      android.util.Log.d("FloatingKeyboard", "Container measured - target: " + targetWidth + "x" + targetHeight + 
                        "px (" + config.floatingKeyboardWidthPercent + "% x " + config.floatingKeyboardHeightPercent + "%)");
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
      
      // Initialize drag state - no handles to update
      
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
      android.util.Log.d("FloatingKeyboard", "Starting resize from key-initiated touch");
      
      // Initialize resize state directly without ResizeTouchListener
      isResizing = true;
      resizeStartX = event.getRawX();
      resizeStartY = event.getRawY();
      
      // Disable keyboard touch processing during resize
      if (_floatingKeyboardView != null) {
        _floatingKeyboardView.setClickable(false);
        _floatingKeyboardView.setFocusable(false);
        android.util.Log.d("FloatingKeyboard", "Disabled keyboard view touch processing for resize");
      }
      
      // Calculate initial dimensions from config percentages
      // Load current dimensions in pixels
      int[] dimensions = FloatingKeyboard2.this.loadFloatingKeyboardDimensions();
      initialWidth = dimensions[0];
      initialHeight = dimensions[1];
      
      DisplayMetrics dm = getResources().getDisplayMetrics();
      Config config = Config.globalConfig();
      initialWidthPercent = config.floatingKeyboardWidthPercent;
      initialHeightPercent = config.floatingKeyboardHeightPercent;
      initialWindowX = _floatingLayoutParams.x;
      initialWindowY = _floatingLayoutParams.y;
      
      android.util.Log.d("FloatingKeyboard", "Key-initiated resize started - isResizing: " + isResizing);
      Toast.makeText(getContext(), "Resizing started", Toast.LENGTH_SHORT).show();
    }

    // Removed createResizeHandle method - functionality handled by key values

    // Removed createPassthroughToggle method - functionality handled by key values

    // Removed PassthroughToggleTouchListener class - no longer needed

    // Removed ResizeTouchListener class - no longer needed

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
      // Force keyboard to be at the top-left of the container with no offset
      if (_floatingKeyboardView != null) {
        int kbWidth = _floatingKeyboardView.getMeasuredWidth();
        int kbHeight = _floatingKeyboardView.getMeasuredHeight();
        _floatingKeyboardView.layout(0, 0, kbWidth, kbHeight);
        android.util.Log.d("FloatingKeyboard", "Laid out keyboard at (0,0) with size " + kbWidth + "x" + kbHeight);
      }
      
      // Layout any other children normally (though there shouldn't be any)
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i);
        if (child != _floatingKeyboardView) {
          child.layout(left, top, right, bottom);
        }
      }
      
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
          // Clear any stuck key states before switching modes
          clearKeyboardState();
          
          // Make the main window not touchable so touches pass through to underlying apps
          _floatingLayoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
          windowManager.updateViewLayout(_floatingContainer, _floatingLayoutParams);
          
          // Dim the keyboard to show it's in passthrough mode
          if (_floatingKeyboardView != null) {
            float disabledAlpha = Config.globalConfig().keyboardDisabledOpacity / 100.0f;
            _floatingKeyboardView.setAlpha(disabledAlpha);
          }
          
          // No handles to update opacity
          
          // Create the toggle button for passthrough mode
          createToggleButtonWindow();
          
          android.util.Log.d("FloatingKeyboard", "Entered passthrough mode - main window not touchable, passthrough keyboard created");
        } catch (Exception e) {
          android.util.Log.e("FloatingKeyboard", "Error entering passthrough mode: " + e.getMessage());
        }
      }
    }

    public boolean isInPassthroughMode() {
      return passthroughMode;
    }

    public void exitPassthroughMode() {
      if (passthroughMode && _floatingLayoutParams != null && windowManager != null) {
        passthroughMode = false;
        
        try {
          // Clear any stuck key states before switching modes
          clearKeyboardState();
          
          // Restore main window touchability
          _floatingLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
          windowManager.updateViewLayout(_floatingContainer, _floatingLayoutParams);
          
          // Restore keyboard full opacity
          if (_floatingKeyboardView != null) {
            _floatingKeyboardView.setAlpha(1.0f);
          }
          
          // No handles to update opacity
          
          // Remove the toggle button window
          removeToggleButtonWindow();
          
          android.util.Log.d("FloatingKeyboard", "Exited passthrough mode - main window touchable, keyboard restored, passthrough keyboard removed");
        } catch (Exception e) {
          android.util.Log.e("FloatingKeyboard", "Error exiting passthrough mode: " + e.getMessage());
        }
      }
    }

    // Removed updateToggleButtonAppearance method - no toggle buttons

    private void clearKeyboardState() {
      android.util.Log.d("FloatingKeyboard", "Clearing keyboard state to prevent stuck keys");
      
      // Clear all visual feedback modes that might cause keys to appear stuck
      FloatingKeyboard2.clearAllVisualFeedback();
      
      // Reset keyboard view state to clear any pressed keys - do this multiple times
      if (_floatingKeyboardView instanceof Keyboard2View) {
        Keyboard2View keyboardView = (Keyboard2View)_floatingKeyboardView;
        // Use the reset method to clear keyboard state
        keyboardView.reset();
        // Force a second reset after a brief delay to catch any state that gets re-set
        keyboardView.postDelayed(new Runnable() {
          @Override
          public void run() {
            keyboardView.reset();
            FloatingKeyboard2.clearAllVisualFeedback(); // Clear again after delay
            android.util.Log.d("FloatingKeyboard", "Secondary keyboard state reset completed");
          }
        }, 50); // 50ms delay
        android.util.Log.d("FloatingKeyboard", "Reset keyboard view state (with delayed secondary reset)");
      }
      
      // Set a flag to ignore input for a short period after mode transitions
      ignoreInputUntil = System.currentTimeMillis() + INPUT_IGNORE_DELAY_MS;
      android.util.Log.d("FloatingKeyboard", "Ignoring input for " + INPUT_IGNORE_DELAY_MS + "ms to prevent stuck keys");
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
      // Check if we should ignore input after mode transitions to prevent stuck keys
      if (System.currentTimeMillis() < ignoreInputUntil) {
        return true; // Intercept and consume the event
      }
      
      // Always intercept events when we're in drag or resize mode
      if (isDragging || isResizing) {
        android.util.Log.d("FloatingKeyboard", "Intercepting touch event - drag: " + isDragging + " resize: " + isResizing);
        return true;
      }
      
      if (event.getAction() == MotionEvent.ACTION_DOWN && _floatingKeyboardView != null) {
        float containerX = event.getX();
        float containerY = event.getY();
        
        // First check if the touch is even within the keyboard view bounds
        float keyboardLeft = _floatingKeyboardView.getLeft();
        float keyboardTop = _floatingKeyboardView.getTop();
        float keyboardRight = keyboardLeft + _floatingKeyboardView.getWidth();
        float keyboardBottom = keyboardTop + _floatingKeyboardView.getHeight();
        
        // If touch is outside keyboard bounds, don't intercept it at all
        if (containerX < keyboardLeft || containerX > keyboardRight || 
            containerY < keyboardTop || containerY > keyboardBottom) {
          android.util.Log.d("FloatingKeyboard", "Touch outside keyboard bounds - passing through: touch=(" + containerX + "," + containerY + ") keyboard=(" + keyboardLeft + "," + keyboardTop + "," + keyboardRight + "," + keyboardBottom + ")");
          return false; // Don't intercept touches outside the keyboard
        }
        
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
                // Capture the position of the key that has FLOATING_ENABLE_PASSTHROUGH mapping
                // This ensures the toggle button appears in the same position as if the key was pressed
                capturePassthroughTriggeringKeyPosition();
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
      // Check if we should ignore input after mode transitions to prevent stuck keys
      if (System.currentTimeMillis() < ignoreInputUntil) {
        android.util.Log.d("FloatingKeyboard", "Ignoring touch event during cooldown period to prevent stuck keys");
        return true; // Consume the event
      }
      
      // Check if touch is within keyboard bounds
      if (_floatingKeyboardView != null) {
        float containerX = event.getX();
        float containerY = event.getY();
        float keyboardLeft = _floatingKeyboardView.getLeft();
        float keyboardTop = _floatingKeyboardView.getTop();
        float keyboardRight = keyboardLeft + _floatingKeyboardView.getWidth();
        float keyboardBottom = keyboardTop + _floatingKeyboardView.getHeight();
        
        // If touch is outside keyboard bounds and we're not dragging/resizing, don't handle it
        if (!isDragging && !isResizing && 
            (containerX < keyboardLeft || containerX > keyboardRight || 
             containerY < keyboardTop || containerY > keyboardBottom)) {
          android.util.Log.d("FloatingKeyboard", "Touch event outside keyboard bounds - not handling");
          return false; // Don't handle touches outside the keyboard
        }
      }
      
      // Handle key-initiated drag and resize
      if (isDragging) {
        switch (event.getAction()) {
          case MotionEvent.ACTION_MOVE:
            float deltaX = event.getRawX() - dragStartX;
            float deltaY = event.getRawY() - dragStartY;
            
            _floatingLayoutParams.x = dragInitialX + (int) deltaX;
            _floatingLayoutParams.y = dragInitialY + (int) deltaY;
            android.util.Log.d("FloatingKeyboard", "Drag move - window size: " + _floatingLayoutParams.width + "x" + _floatingLayoutParams.height);
            windowManager.updateViewLayout(this, _floatingLayoutParams);
            return true;
            
          case MotionEvent.ACTION_UP:
          case MotionEvent.ACTION_CANCEL:
            isDragging = false;
            
            // Clamp position to screen bounds after drag ends
            clampKeyboardPositionToScreen();
            
            Config config = Config.globalConfig();
            android.util.Log.d("FloatingKeyboard", "Drag ended - config percentages: " + config.floatingKeyboardWidthPercent + "% x " + config.floatingKeyboardHeightPercent + "%");
            
            // Clear visual feedback
            FloatingKeyboard2.clearFloatingModeVisuals();
            if (_floatingKeyboardView != null) {
              ((Keyboard2View)_floatingKeyboardView).invalidate(); // Trigger redraw
            }
            
            // No drag handle to restore
            
            FloatingKeyboard2.this.saveFloatingKeyboardPosition();
            android.util.Log.d("FloatingKeyboard", "Key-initiated drag ended");
            return true;
        }
      }
      
      // Handle key-initiated resize directly
      if (isResizing) {
        if (event.getAction() == MotionEvent.ACTION_MOVE) {
          float deltaX = event.getRawX() - resizeStartX;
          float deltaY = event.getRawY() - resizeStartY;
          
          android.util.Log.d("FloatingKeyboard", "Resize drag - deltaX: " + deltaX + ", deltaY: " + deltaY);
          android.util.Log.d("FloatingKeyboard", "Initial dimensions: " + initialWidth + "x" + initialHeight);
          
          // Get screen dimensions for calculations
          DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
          int screenWidth = displayMetrics.widthPixels;
          int screenHeight = displayMetrics.heightPixels;
          
          // Calculate new pixel-based dimensions
          int newKeyboardWidth = Math.round(initialWidth + deltaX);
          int newKeyboardHeight = Math.round(initialHeight + deltaY);
          
          android.util.Log.d("FloatingKeyboard", "Calculated new dimensions: " + newKeyboardWidth + "x" + newKeyboardHeight);
          
          // Apply constraints
          int minKeyboardWidth = Math.round(screenWidth * 0.3f);
          int maxKeyboardWidth = screenWidth;
          int minKeyboardHeight = Math.round(screenHeight * 0.1f);
          int maxKeyboardHeight = Math.round(screenHeight * 0.6f);
          
          newKeyboardWidth = Math.max(minKeyboardWidth, Math.min(newKeyboardWidth, maxKeyboardWidth));
          newKeyboardHeight = Math.max(minKeyboardHeight, Math.min(newKeyboardHeight, maxKeyboardHeight));
          
          // Convert to percentages and update config
          int newWidthPercent = Math.round(100f * newKeyboardWidth / screenWidth);
          int newHeightPercent = Math.round(100f * newKeyboardHeight / screenHeight);
          
          android.util.Log.d("FloatingKeyboard", "New percentages: " + newWidthPercent + "% x " + newHeightPercent + "%");
          
          Config config = Config.globalConfig();
          android.util.Log.d("FloatingKeyboard", "Current config percentages: " + config.floatingKeyboardWidthPercent + "% x " + config.floatingKeyboardHeightPercent + "%");
          
          if (newWidthPercent != config.floatingKeyboardWidthPercent || newHeightPercent != config.floatingKeyboardHeightPercent) {
            android.util.Log.d("FloatingKeyboard", "Updating config and window layout");
            config.floatingKeyboardWidthPercent = newWidthPercent;
            config.floatingKeyboardHeightPercent = newHeightPercent;
            
            // CRITICAL: Also persist to SharedPreferences to prevent reset on config refresh
            FloatingKeyboard2.this.saveFloatingKeyboardDimensions(newKeyboardWidth, newKeyboardHeight);
            
            // Update the window layout parameters to actually resize the window
            if (_floatingLayoutParams != null && FloatingKeyboard2.this._windowManager != null) {
              _floatingLayoutParams.width = newKeyboardWidth;
              _floatingLayoutParams.height = newKeyboardHeight;
              FloatingKeyboard2.this._windowManager.updateViewLayout(FloatingKeyboard2.this._floatingContainer, _floatingLayoutParams);
            }
            
            FloatingKeyboard2.this.refreshFloatingKeyboard();
          } else {
            android.util.Log.d("FloatingKeyboard", "No change in percentages - not updating");
          }
          return true;
        } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
          isResizing = false;
          FloatingKeyboard2.clearFloatingModeVisuals();
          
          // Re-enable keyboard touch processing after resize
          if (_floatingKeyboardView != null) {
            _floatingKeyboardView.setClickable(true);
            _floatingKeyboardView.setFocusable(true);
            ((Keyboard2View)_floatingKeyboardView).invalidate();
            android.util.Log.d("FloatingKeyboard", "Re-enabled keyboard view touch processing after resize");
          }
          
          FloatingKeyboard2.this.saveFloatingKeyboardPosition();
          android.util.Log.d("FloatingKeyboard", "Key-initiated resize ended");
          return true;
        }
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

    // Removed getter methods for handles - no longer needed
  }

  private View _passthroughKeyboardView = null;
  private WindowManager.LayoutParams _passthroughLayoutParams = null;
  private KeyboardData _passthroughKeyboardData = null;

  private void createPassthroughKeyboard() {
    // This method is deprecated - we now use the DirectionalReEnableButton approach instead
    android.util.Log.d("FloatingKeyboard", "Passthrough keyboard method called but not implemented - using toggle button instead");
  }

  private void removePassthroughKeyboard() {
    if (_passthroughKeyboardView != null) {
      try {
        _windowManager.removeView(_passthroughKeyboardView);
        android.util.Log.d("FloatingKeyboard", "Removed passthrough keyboard");
      } catch (Exception e) {
        android.util.Log.e("FloatingKeyboard", "Error removing passthrough keyboard: " + e.getMessage());
      }
      _passthroughKeyboardView = null;
      _passthroughLayoutParams = null;
      _passthroughKeyboardData = null;
    }
  }

  private KeyboardData parsePassthroughKeyboardXml(String xmlString) {
    try {
      // Parse the XML string into a KeyboardData object using load_string_exn
      return KeyboardData.load_string_exn(xmlString);
    } catch (Exception e) {
      android.util.Log.e("FloatingKeyboard", "Error parsing passthrough keyboard XML: " + e.getMessage());
      return null;
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
      int labelColor = 0xFFD8DEE9; // Nord light gray - more visible
      int activatedLabelColor = 0xFF2E3440; // Nord dark blue-gray for activated state
      
      // Use the captured triggering key position if available, otherwise fall back to top-right
      float x, y, keyW, keyH;
      
      if (_triggeringKeyScreenX >= 0 && _triggeringKeyScreenY >= 0 && 
          _triggeringKeyWidth > 0 && _triggeringKeyHeight > 0) {
        // Convert captured screen coordinates back to keyboard-relative coordinates
        x = _triggeringKeyScreenX - _floatingLayoutParams.x;
        y = _triggeringKeyScreenY - _floatingLayoutParams.y;
        
        // Use captured key dimensions for exact match
        keyW = _triggeringKeyWidth;
        keyH = _triggeringKeyHeight;
        
        android.util.Log.d("FloatingKeyboard", "Using captured key dimensions for toggle button: (" + x + "," + y + ") size=(" + keyW + "x" + keyH + ")");
      } else {
        // Fallback: Find the top-right key (first row, last column)
        KeyboardData.Row firstRow = keyboard.rows.get(0);
        KeyboardData.Key topRightKey = firstRow.keys.get(firstRow.keys.size() - 1);
        
        // Calculate the position and size of the top-right key
        x = marginLeft + tc.margin_left;
        for (int i = 0; i < firstRow.keys.size() - 1; i++) {
          KeyboardData.Key key = firstRow.keys.get(i);
          x += key.shift * keyWidth + key.width * keyWidth;
        }
        x += topRightKey.shift * keyWidth;
        
        y = tc.margin_top + firstRow.shift * tc.row_height;
        keyW = keyWidth * topRightKey.width - tc.horizontal_margin;
        keyH = firstRow.height * tc.row_height - tc.vertical_margin;
        
        android.util.Log.d("FloatingKeyboard", "Using fallback top-right key position for toggle button: (" + x + "," + y + ")");
      }
      
      // Create a custom view to display the keyboard glyph with directional arrows
      DirectionalReEnableButton toggleButton = new DirectionalReEnableButton(this);
      toggleButton.setMainGlyph("⌨");
      toggleButton.setLabelColor(labelColor);
      toggleButton.setTextSize(keyH * 0.4f);
      
      // Style the button to look like a keyboard key
      android.graphics.drawable.GradientDrawable keyDrawable = new android.graphics.drawable.GradientDrawable();
      keyDrawable.setColor(keyColor);
      keyDrawable.setCornerRadius(tc.key.border_radius);
      if (tc.key.border_width > 0) {
        keyDrawable.setStroke((int)tc.key.border_width, borderColor);
      }
      toggleButton.setBackground(keyDrawable);
      
      _toggleButtonWindow = toggleButton;
      
      // Set up touch listener for the toggle button with drag support and directional swipes
      _toggleButtonWindow.setOnTouchListener(new View.OnTouchListener() {
        private android.graphics.drawable.GradientDrawable originalDrawable = keyDrawable;
        private float startX, startY;
        private int startWindowX, startWindowY;
        private boolean isDragging = false;
        private boolean wasSwipe = false;
        private static final float DRAG_THRESHOLD = 15f; // pixels
        private static final float SWIPE_THRESHOLD = 30f; // pixels for swipe detection
        
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
              wasSwipe = false;
              
              // Style like an activated key
              android.graphics.drawable.GradientDrawable activeDrawable = new android.graphics.drawable.GradientDrawable();
              activeDrawable.setColor(keyActivatedColor);
              activeDrawable.setCornerRadius(tc.key_activated.border_radius);
              if (tc.key_activated.border_width > 0) {
                activeDrawable.setStroke((int)tc.key_activated.border_width, borderColor);
              }
              v.setBackground(activeDrawable);
              if (v instanceof DirectionalReEnableButton) {
                ((DirectionalReEnableButton)v).setLabelColor(activatedLabelColor);
              }
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
              if (v instanceof DirectionalReEnableButton) {
                ((DirectionalReEnableButton)v).setLabelColor(labelColor);
              }
              
              // Check for swipe gesture first
              deltaX = event.getRawX() - startX;
              deltaY = event.getRawY() - startY;
              distance = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
              
              if (!isDragging && distance > SWIPE_THRESHOLD) {
                // This was a swipe - determine direction and send arrow key
                if (Math.abs(deltaX) > Math.abs(deltaY)) {
                  // Horizontal swipe
                  if (deltaX > 0) {
                    // Swipe right - send right arrow key
                    sendSystemKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
                    showDebugToast("→ Right arrow");
                  } else {
                    // Swipe left - send left arrow key  
                    sendSystemKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_LEFT);
                    showDebugToast("← Left arrow");
                  }
                } else {
                  // Vertical swipe
                  if (deltaY < 0) {
                    // Swipe up - send up arrow key
                    sendSystemKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_UP);
                    showDebugToast("↑ Up arrow");
                  } else {
                    // Swipe down - send down arrow key
                    sendSystemKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_DOWN);
                    showDebugToast("↓ Down arrow");
                  }
                }
                wasSwipe = true;
              } else if (!isDragging && !wasSwipe) {
                // This was a tap in center, not a drag or swipe - re-enable the keyboard
                if (_floatingContainer instanceof ResizableFloatingContainer) {
                  ((ResizableFloatingContainer)_floatingContainer).exitPassthroughMode();
                }
                showDebugToast("Keyboard touches re-enabled");
              } else if (isDragging) {
                // This was a drag - save the new position
                saveToggleButtonPosition(_toggleLayoutParams.x, _toggleLayoutParams.y);
                showDebugToast("Re-enable button moved to new position");
                android.util.Log.d("FloatingKeyboard", "Re-enable button dragged to: " + _toggleLayoutParams.x + "," + _toggleLayoutParams.y);
              }
              return true;
              
            case MotionEvent.ACTION_CANCEL:
              // Restore original key style
              v.setBackground(originalDrawable);
              if (v instanceof DirectionalReEnableButton) {
                ((DirectionalReEnableButton)v).setLabelColor(labelColor);
              }
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
      
      // Position priority: 1) Saved position (if enabled), 2) Captured triggering key position, 3) Default fallback position
      android.content.SharedPreferences prefs = getSharedPreferences("FloatingKeyboard", MODE_PRIVATE);
      boolean hasSavedPosition = prefs.contains("toggle_button_x") && prefs.contains("toggle_button_y");
      
      if (hasSavedPosition && config.rememberFloatingReEnableButtonPosition) {
        _toggleLayoutParams.x = prefs.getInt("toggle_button_x", _floatingLayoutParams.x + (int)x);
        _toggleLayoutParams.y = prefs.getInt("toggle_button_y", _floatingLayoutParams.y + (int)y);
        android.util.Log.d("FloatingKeyboard", "Using saved toggle button position: " + _toggleLayoutParams.x + "," + _toggleLayoutParams.y);
      } else if (_triggeringKeyScreenX >= 0 && _triggeringKeyScreenY >= 0) {
        // Use the captured triggering key screen position directly
        _toggleLayoutParams.x = _triggeringKeyScreenX;
        _toggleLayoutParams.y = _triggeringKeyScreenY;
        android.util.Log.d("FloatingKeyboard", "Using captured triggering key position: " + _toggleLayoutParams.x + "," + _toggleLayoutParams.y);
      } else {
        _toggleLayoutParams.x = _floatingLayoutParams.x + (int)x;
        _toggleLayoutParams.y = _floatingLayoutParams.y + (int)y;
        android.util.Log.d("FloatingKeyboard", "Using fallback toggle button position: " + _toggleLayoutParams.x + "," + _toggleLayoutParams.y);
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
      Config config = Config.globalConfig();
      if (config.rememberFloatingReEnableButtonPosition) {
        android.content.SharedPreferences prefs = getSharedPreferences("FloatingKeyboard", MODE_PRIVATE);
        android.content.SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("toggle_button_x", x);
        editor.putInt("toggle_button_y", y);
        editor.apply();
        android.util.Log.d("FloatingKeyboard", "Saved toggle button position: " + x + "," + y);
      } else {
        android.util.Log.d("FloatingKeyboard", "Position memory disabled, not saving toggle button position");
      }
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

  private void capturePassthroughTriggeringKeyPosition() {
    try {
      if (_floatingKeyboardView == null) {
        android.util.Log.e("FloatingKeyboard", "Cannot capture key position - keyboard view is null");
        return;
      }

      KeyboardData keyboard = ((Keyboard2View)_floatingKeyboardView).getCurrentKeyboard();
      if (keyboard == null) {
        android.util.Log.e("FloatingKeyboard", "Cannot capture key position - keyboard data is null");
        return;
      }

      // Find the key that has FLOATING_ENABLE_PASSTHROUGH event
      KeyboardData.Key triggeringKey = null;
      int rowIndex = -1;
      int keyIndex = -1;

      outerLoop:
      for (int r = 0; r < keyboard.rows.size(); r++) {
        KeyboardData.Row row = keyboard.rows.get(r);
        for (int k = 0; k < row.keys.size(); k++) {
          KeyboardData.Key key = row.keys.get(k);
          // Check all key positions (center + corners/edges) for the passthrough event
          for (KeyValue keyValue : key.keys) {
            if (keyValue != null && keyValue.getKind() == KeyValue.Kind.Event && 
                keyValue.getEvent() == KeyValue.Event.FLOATING_ENABLE_PASSTHROUGH) {
              triggeringKey = key;
              rowIndex = r;
              keyIndex = k;
              break outerLoop;
            }
          }
        }
      }

      if (triggeringKey == null) {
        android.util.Log.w("FloatingKeyboard", "Could not find key with FLOATING_ENABLE_PASSTHROUGH event");
        // Reset to invalid position and dimensions
        _triggeringKeyScreenX = -1;
        _triggeringKeyScreenY = -1;
        _triggeringKeyWidth = -1;
        _triggeringKeyHeight = -1;
        return;
      }

      // Calculate the position of the triggering key using the same logic as Keyboard2View drawing
      Keyboard2View keyboardView = (Keyboard2View)_floatingKeyboardView;
      float keyWidth = keyboardView.getKeyWidth();
      float marginLeft = keyboardView.getMarginLeft();
      Theme.Computed tc = keyboardView.getThemeComputed();

      // Calculate X position
      KeyboardData.Row triggeringRow = keyboard.rows.get(rowIndex);
      float x = marginLeft + tc.margin_left;
      for (int i = 0; i < keyIndex; i++) {
        KeyboardData.Key key = triggeringRow.keys.get(i);
        x += key.shift * keyWidth + key.width * keyWidth;
      }
      x += triggeringKey.shift * keyWidth;

      // Calculate Y position  
      float y = tc.margin_top;
      for (int i = 0; i < rowIndex; i++) {
        y += keyboard.rows.get(i).height * tc.row_height;
      }
      y += triggeringRow.shift * tc.row_height;

      // Calculate key dimensions using the same logic as Keyboard2View
      _triggeringKeyWidth = keyWidth * triggeringKey.width - tc.horizontal_margin;
      _triggeringKeyHeight = triggeringRow.height * tc.row_height - tc.vertical_margin;

      // Convert to screen coordinates
      if (_floatingLayoutParams != null) {
        _triggeringKeyScreenX = _floatingLayoutParams.x + (int)x;
        _triggeringKeyScreenY = _floatingLayoutParams.y + (int)y;
        
        android.util.Log.d("FloatingKeyboard", "Captured triggering key: position=(" + _triggeringKeyScreenX + "," + _triggeringKeyScreenY + "), dimensions=(" + _triggeringKeyWidth + "x" + _triggeringKeyHeight + ")");
      } else {
        android.util.Log.e("FloatingKeyboard", "Cannot convert to screen coordinates - floating layout params is null");
        _triggeringKeyScreenX = -1;
        _triggeringKeyScreenY = -1;
        _triggeringKeyWidth = -1;
        _triggeringKeyHeight = -1;
      }

    } catch (Exception e) {
      android.util.Log.e("FloatingKeyboard", "Error capturing triggering key position: " + e.getMessage());
      _triggeringKeyScreenX = -1;
      _triggeringKeyScreenY = -1;
      _triggeringKeyWidth = -1;
      _triggeringKeyHeight = -1;
    }
  }

  private void sendSystemKeyEvent(int keyCode) {
    try {
      // Send both down and up events for the key
      long downTime = android.os.SystemClock.uptimeMillis();
      android.view.KeyEvent downEvent = new android.view.KeyEvent(downTime, downTime, 
                                          android.view.KeyEvent.ACTION_DOWN, keyCode, 0);
      android.view.KeyEvent upEvent = new android.view.KeyEvent(downTime, downTime + 50, 
                                        android.view.KeyEvent.ACTION_UP, keyCode, 0);
      
      // Send the events to the system
      android.view.InputDevice.getDevice(downEvent.getDeviceId());
      getCurrentInputConnection().sendKeyEvent(downEvent);
      getCurrentInputConnection().sendKeyEvent(upEvent);
      
      android.util.Log.d("FloatingKeyboard", "Sent system key event: " + keyCode);
    } catch (Exception e) {
      android.util.Log.e("FloatingKeyboard", "Error sending system key event: " + e.getMessage());
    }
  }

  private void resizeKeyboardToSnapDimensions()
  {
    Config config = Config.globalConfig();
    
    // Update keyboard configuration to snap dimensions
    SharedPreferences.Editor editor = Config.globalPrefs().edit();
    if (config.orientation_landscape) {
      if (config.foldable_unfolded) {
        editor.putInt("floating_keyboard_width_landscape_unfolded", config.snapWidthPercent);
        editor.putInt("floating_keyboard_height_landscape_unfolded", config.snapHeightPercent);
      } else {
        editor.putInt("floating_keyboard_width_landscape", config.snapWidthPercent);
        editor.putInt("floating_keyboard_height_landscape", config.snapHeightPercent);
      }
    } else {
      if (config.foldable_unfolded) {
        editor.putInt("floating_keyboard_width_unfolded", config.snapWidthPercent);
        editor.putInt("floating_keyboard_height_unfolded", config.snapHeightPercent);
      } else {
        editor.putInt("floating_keyboard_width", config.snapWidthPercent);
        editor.putInt("floating_keyboard_height", config.snapHeightPercent);
      }
    }
    editor.apply();
    
    // Refresh config and recreate keyboard with new dimensions
    config.refresh(getResources(), config.foldable_unfolded);
    
    // Clear all states before recreating keyboard
    clearAllVisualFeedback();
    if (_floatingKeyboardView instanceof Keyboard2View) {
      ((Keyboard2View)_floatingKeyboardView).reset();
    }
    
    // Recreate keyboard with new dimensions
    removeFloatingKeyboard();
    createFloatingKeyboard();
    
    android.util.Log.d("FloatingKeyboard", "Resized keyboard to snap dimensions: " + 
                      config.snapWidthPercent + "% x " + config.snapHeightPercent + "%");
  }

  private void snapKeyboardLeft()
  {
    android.util.Log.d("FloatingKeyboard", "Snapping keyboard to left");
    
    if (!_floatingKeyboardActive || _floatingLayoutParams == null) {
      android.util.Log.d("FloatingKeyboard", "Cannot snap left - floating keyboard not active");
      return;
    }
    
    Config config = Config.globalConfig();
    
    // Resize if enabled
    if (config.snapResizeEnabled) {
      resizeKeyboardToSnapDimensions();
    }
    
    // Position at left edge
    _floatingLayoutParams.x = 0;
    _windowManager.updateViewLayout(_floatingContainer, _floatingLayoutParams);
    saveFloatingKeyboardPosition();
    
    String message = config.snapResizeEnabled ? 
      "Snapped to left (" + config.snapWidthPercent + "% x " + config.snapHeightPercent + "%)" :
      "Snapped to left (size unchanged)";
    showDebugToast(message);
    android.util.Log.d("FloatingKeyboard", message);
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
    
    // Resize if enabled
    if (config.snapResizeEnabled) {
      resizeKeyboardToSnapDimensions();
    }
    
    // Position at right edge - use actual container width
    int keyboardWidth = _floatingContainer != null ? _floatingContainer.getWidth() : _floatingLayoutParams.width;
    int rightPosition = dm.widthPixels - keyboardWidth;
    _floatingLayoutParams.x = rightPosition;
    _windowManager.updateViewLayout(_floatingContainer, _floatingLayoutParams);
    saveFloatingKeyboardPosition();
    
    String message = config.snapResizeEnabled ? 
      "Snapped to right (" + config.snapWidthPercent + "% x " + config.snapHeightPercent + "%)" :
      "Snapped to right (size unchanged)";
    showDebugToast(message);
    android.util.Log.d("FloatingKeyboard", message);
  }

  private void snapKeyboardTop()
  {
    android.util.Log.d("FloatingKeyboard", "Snapping keyboard to top");
    
    if (!_floatingKeyboardActive || _floatingLayoutParams == null) {
      android.util.Log.d("FloatingKeyboard", "Cannot snap top - floating keyboard not active");
      return;
    }
    
    Config config = Config.globalConfig();
    
    // Resize if enabled
    if (config.snapResizeEnabled) {
      resizeKeyboardToSnapDimensions();
    }
    
    // Position at top edge
    _floatingLayoutParams.y = 0;
    _windowManager.updateViewLayout(_floatingContainer, _floatingLayoutParams);
    saveFloatingKeyboardPosition();
    
    String message = config.snapResizeEnabled ? 
      "Snapped to top (" + config.snapWidthPercent + "% x " + config.snapHeightPercent + "%)" :
      "Snapped to top (size unchanged)";
    showDebugToast(message);
    android.util.Log.d("FloatingKeyboard", message);
  }

  private void snapKeyboardBottom()
  {
    android.util.Log.d("FloatingKeyboard", "Snapping keyboard to bottom");
    
    if (!_floatingKeyboardActive || _floatingLayoutParams == null) {
      android.util.Log.d("FloatingKeyboard", "Cannot snap bottom - floating keyboard not active");
      return;
    }
    
    Config config = Config.globalConfig();
    
    // Resize if enabled
    if (config.snapResizeEnabled) {
      resizeKeyboardToSnapDimensions();
    }
    
    // Position at bottom edge - use actual container height
    DisplayMetrics dm = getResources().getDisplayMetrics();
    int keyboardHeight = _floatingContainer != null ? _floatingContainer.getHeight() : _floatingLayoutParams.height;
    int bottomPosition = dm.heightPixels - keyboardHeight;
    _floatingLayoutParams.y = bottomPosition;
    _windowManager.updateViewLayout(_floatingContainer, _floatingLayoutParams);
    saveFloatingKeyboardPosition();
    
    String message = config.snapResizeEnabled ? 
      "Snapped to bottom (" + config.snapWidthPercent + "% x " + config.snapHeightPercent + "%)" :
      "Snapped to bottom (size unchanged)";
    showDebugToast(message);
    android.util.Log.d("FloatingKeyboard", message);
  }

  private void fillKeyboardWidth()
  {
    android.util.Log.d("FloatingKeyboard", "Filling keyboard width to 100%");
    
    if (!_floatingKeyboardActive || _floatingLayoutParams == null) {
      android.util.Log.d("FloatingKeyboard", "Cannot fill width - floating keyboard not active");
      return;
    }
    
    Config config = Config.globalConfig();
    
    // Update keyboard configuration width to 100%
    SharedPreferences.Editor editor = Config.globalPrefs().edit();
    if (config.orientation_landscape) {
      if (config.foldable_unfolded) {
        editor.putInt("floating_keyboard_width_landscape_unfolded", 100);
      } else {
        editor.putInt("floating_keyboard_width_landscape", 100);
      }
    } else {
      if (config.foldable_unfolded) {
        editor.putInt("floating_keyboard_width_unfolded", 100);
      } else {
        editor.putInt("floating_keyboard_width", 100);
      }
    }
    editor.apply();
    
    // Refresh config to pick up new width values
    config.refresh(getResources(), false);
    
    // Clear all states before recreating keyboard to prevent stuck keys
    clearAllVisualFeedback();
    if (_floatingKeyboardView instanceof Keyboard2View) {
      ((Keyboard2View)_floatingKeyboardView).reset();
      android.util.Log.d("FloatingKeyboard", "Pre-recreation keyboard reset for fill_width");
    }
    
    // Clear any pending handler operations that might cause stuck key loops during recreation
    try {
      if (_handler != null) {
        _handler.removeCallbacksAndMessages(null);
        android.util.Log.d("FloatingKeyboard", "Cleared pending handler operations before fill_width recreation");
      }
    } catch (Exception e) {
      android.util.Log.w("FloatingKeyboard", "Could not clear handler operations: " + e.getMessage());
    }
    
    // Add a small delay to let any pending events complete
    Handler handler = new Handler(getMainLooper());
    handler.postDelayed(new Runnable() {
      @Override
      public void run() {
        try {
          // Completely recreate the floating keyboard with new dimensions
          removeFloatingKeyboard();
          createFloatingKeyboard();
          
          // Clear any visual mode feedback after recreation
          clearFloatingModeVisuals();
          
          // Position at left edge
          if (_floatingLayoutParams != null) {
            _floatingLayoutParams.x = 0;
            _windowManager.updateViewLayout(_floatingContainer, _floatingLayoutParams);
          }
          
          // Final cleanup and redraw
          clearAllVisualFeedback();
          if (_floatingKeyboardView != null) {
            ((Keyboard2View)_floatingKeyboardView).invalidate();
          }
          
          showDebugToast("Keyboard width filled to 100%");
          android.util.Log.d("FloatingKeyboard", "Keyboard width filled to 100%");
        } catch (Exception e) {
          android.util.Log.e("FloatingKeyboard", "Error during fill_width recreation: " + e.getMessage());
        }
      }
    }, 100); // 100ms delay to let events complete
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

  private void centerKeyboardHorizontal()
  {
    android.util.Log.d("FloatingKeyboard", "Centering keyboard horizontally");
    
    if (!_floatingKeyboardActive || _floatingLayoutParams == null) {
      android.util.Log.d("FloatingKeyboard", "Cannot center horizontally - floating keyboard not active");
      return;
    }
    
    DisplayMetrics dm = getResources().getDisplayMetrics();
    // Get the actual container width instead of layout params width
    int keyboardWidth = _floatingContainer != null ? _floatingContainer.getWidth() : _floatingLayoutParams.width;
    int centerX = (dm.widthPixels - keyboardWidth) / 2;
    
    android.util.Log.d("FloatingKeyboard", "Centering horizontally: screen=" + dm.widthPixels + "px, keyboard=" + keyboardWidth + "px, centerX=" + centerX + "px");
    _floatingLayoutParams.x = centerX;
    _windowManager.updateViewLayout(_floatingContainer, _floatingLayoutParams);
    saveFloatingKeyboardPosition();
    
    showDebugToast("Keyboard centered horizontally");
    android.util.Log.d("FloatingKeyboard", "Keyboard centered horizontally at x=" + centerX);
  }

  private void centerKeyboardVertical()
  {
    android.util.Log.d("FloatingKeyboard", "Centering keyboard vertically");
    
    if (!_floatingKeyboardActive || _floatingLayoutParams == null) {
      android.util.Log.d("FloatingKeyboard", "Cannot center vertically - floating keyboard not active");
      return;
    }
    
    DisplayMetrics dm = getResources().getDisplayMetrics();
    // Get the actual container height instead of layout params height
    int keyboardHeight = _floatingContainer != null ? _floatingContainer.getHeight() : _floatingLayoutParams.height;
    int centerY = (dm.heightPixels - keyboardHeight) / 2;
    
    android.util.Log.d("FloatingKeyboard", "Centering vertically: screen=" + dm.heightPixels + "px, keyboard=" + keyboardHeight + "px, centerY=" + centerY + "px");
    _floatingLayoutParams.y = centerY;
    _windowManager.updateViewLayout(_floatingContainer, _floatingLayoutParams);
    saveFloatingKeyboardPosition();
    
    showDebugToast("Keyboard centered vertically");
    android.util.Log.d("FloatingKeyboard", "Keyboard centered vertically at y=" + centerY);
  }

  private void centerKeyboardBoth()
  {
    android.util.Log.d("FloatingKeyboard", "Centering keyboard both horizontally and vertically");
    
    if (!_floatingKeyboardActive || _floatingLayoutParams == null) {
      android.util.Log.d("FloatingKeyboard", "Cannot center - floating keyboard not active");
      return;
    }
    
    DisplayMetrics dm = getResources().getDisplayMetrics();
    // Get the actual container dimensions instead of layout params dimensions
    int keyboardWidth = _floatingContainer != null ? _floatingContainer.getWidth() : _floatingLayoutParams.width;
    int keyboardHeight = _floatingContainer != null ? _floatingContainer.getHeight() : _floatingLayoutParams.height;
    int centerX = (dm.widthPixels - keyboardWidth) / 2;
    int centerY = (dm.heightPixels - keyboardHeight) / 2;
    
    android.util.Log.d("FloatingKeyboard", "Centering both: screen=" + dm.widthPixels + "x" + dm.heightPixels + "px, keyboard=" + keyboardWidth + "x" + keyboardHeight + "px, center=(" + centerX + "," + centerY + ")");
    _floatingLayoutParams.x = centerX;
    _floatingLayoutParams.y = centerY;
    _windowManager.updateViewLayout(_floatingContainer, _floatingLayoutParams);
    saveFloatingKeyboardPosition();
    
    showDebugToast("Keyboard centered");
    android.util.Log.d("FloatingKeyboard", "Keyboard centered at x=" + centerX + ", y=" + centerY);
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

  // Simple custom view that renders a single key like regular keyboard keys
  private class PassthroughKeyView extends View {
    private KeyboardData.Key _key;
    private Theme.Computed _themeComputed;
    private int _keyWidth, _keyHeight;
    private android.graphics.RectF _tmpRect = new android.graphics.RectF();
    
    public PassthroughKeyView(Context context, KeyboardData keyboardData, int width, int height) {
      super(context);
      _keyWidth = width;
      _keyHeight = height;
      
      // Get the first (and should be only) key from the keyboard data
      if (keyboardData != null && keyboardData.rows.size() > 0 && keyboardData.rows.get(0).keys.size() > 0) {
        _key = keyboardData.rows.get(0).keys.get(0);
      }
      
      // Get theme from the main keyboard view to ensure consistency
      if (_floatingKeyboardView instanceof Keyboard2View) {
        _themeComputed = ((Keyboard2View)_floatingKeyboardView).getThemeComputed();
      } else {
        // Fallback if main keyboard not available
        Config config = Config.globalConfig();
        Theme theme = new Theme(FloatingKeyboard2.this, null);
        _themeComputed = new Theme.Computed(theme, config, width, keyboardData, true, FloatingKeyboard2.this);
      }
      
      // Set up simple touch handling: tap to show keyboard, drag to move
      setOnTouchListener(new OnTouchListener() {
        private float startRawX, startRawY;
        private boolean isDragging = false;
        private static final float DRAG_THRESHOLD = 10f;
        
        @Override
        public boolean onTouch(View v, MotionEvent event) {
          switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
              startRawX = event.getRawX();
              startRawY = event.getRawY();
              isDragging = false;
              return true;
              
            case MotionEvent.ACTION_MOVE:
              float deltaX = event.getRawX() - startRawX;
              float deltaY = event.getRawY() - startRawY;
              float distance = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
              
              if (distance > DRAG_THRESHOLD) {
                isDragging = true;
                
                // Update passthrough keyboard position
                if (_passthroughLayoutParams != null && FloatingKeyboard2.this._windowManager != null) {
                  _passthroughLayoutParams.x += (int)deltaX;
                  _passthroughLayoutParams.y += (int)deltaY;
                  FloatingKeyboard2.this._windowManager.updateViewLayout(_passthroughKeyboardView, _passthroughLayoutParams);
                  startRawX = event.getRawX();
                  startRawY = event.getRawY();
                }
              }
              return true;
              
            case MotionEvent.ACTION_UP:
              if (!isDragging) {
                // This was a tap - show keyboard (send center key)
                if (_key != null && _key.keys[0] != null) {
                  Config.globalConfig().handler.key_up(_key.keys[0], Pointers.Modifiers.EMPTY);
                }
              }
              return true;
          }
          return false;
        }
      });
    }
    
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
      setMeasuredDimension(_keyWidth, _keyHeight);
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      
      if (_key == null) return;
      
      float width = getWidth();
      float height = getHeight();
      
      // Use exact same rendering logic as Keyboard2View.onDraw()
      boolean isKeyDown = false; // Passthrough key is never pressed down
      Theme.Computed.Key tc_key = isKeyDown ? _themeComputed.key_activated : _themeComputed.key;
      
      drawKeyFrame(canvas, 0, 0, width, height, tc_key);
      
      if (_key.keys[0] != null) {
        drawLabel(canvas, _key.keys[0], width / 2f, 0, height, isKeyDown, tc_key);
      }
    }
    
    // Copy the exact key frame drawing method from Keyboard2View
    private void drawKeyFrame(Canvas canvas, float x, float y, float keyW, float keyH,
        Theme.Computed.Key tc) {
      float r = tc.border_radius;
      float w = tc.border_width;
      float padding = w / 2.f;
      _tmpRect.set(x + padding, y + padding, x + keyW - padding, y + keyH - padding);
      canvas.drawRoundRect(_tmpRect, r, r, tc.bg_paint);
      if (w > 0.f) {
        float overlap = r - r * 0.85f + w; // sin(45°)
        drawBorder(canvas, x, y, x + overlap, y + keyH, tc.border_left_paint, tc);
        drawBorder(canvas, x + keyW - overlap, y, x + keyW, y + keyH, tc.border_right_paint, tc);
        drawBorder(canvas, x, y, x + keyW, y + overlap, tc.border_top_paint, tc);
        drawBorder(canvas, x, y + keyH - overlap, x + keyW, y + keyH, tc.border_bottom_paint, tc);
      }
    }
    
    // Copy the exact border drawing method from Keyboard2View
    private void drawBorder(Canvas canvas, float clipl, float clipt, float clipr,
        float clipb, Paint paint, Theme.Computed.Key tc) {
      float r = tc.border_radius;
      canvas.save();
      canvas.clipRect(clipl, clipt, clipr, clipb);
      canvas.drawRoundRect(_tmpRect, r, r, paint);
      canvas.restore();
    }
    
    // Simplified label drawing - use the theme's regular label color
    private void drawLabel(Canvas canvas, KeyValue kv, float x, float y,
        float keyH, boolean isKeyDown, Theme.Computed.Key tc) {
      if (kv == null) return;
      
      float textSize = scaleTextSize(kv, true);
      
      // Use the theme's regular label color for consistency with main keyboard
      int labelColor = labelColor(kv, isKeyDown, false);
      
      Paint p = tc.label_paint(kv.hasFlagsAny(KeyValue.FLAG_KEY_FONT), labelColor, textSize);
      canvas.drawText(kv.getString(), x, (keyH - p.ascent() - p.descent()) / 2f + y, p);
    }
    
    // Copy the labelColor method from Keyboard2View (simplified for passthrough key)
    private int labelColor(KeyValue k, boolean isKeyDown, boolean sublabel) {
      // Passthrough key is never pressed down, so we skip the isKeyDown logic
      if (k.hasFlagsAny(KeyValue.FLAG_SECONDARY | KeyValue.FLAG_GREYED)) {
        if (k.hasFlagsAny(KeyValue.FLAG_GREYED))
          return _themeComputed.key.bg_paint.getColor(); // Use a fallback color
        return _themeComputed.key.bg_paint.getColor(); // Use a fallback color
      }
      // Get the theme to access label colors
      Theme theme = new Theme(FloatingKeyboard2.this, null);
      return sublabel ? theme.subLabelColor : theme.labelColor;
    }
    
    // Copy the exact scaleTextSize method from Keyboard2View
    private float scaleTextSize(KeyValue k, boolean main_label) {
      float smaller_font = k.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT) ? 0.75f : 1.f;
      float label_size = main_label ? (_keyHeight * 0.4f) : (_keyHeight * 0.3f); // Simplified sizes
      return label_size * smaller_font;
    }
  }

  // Custom view for the re-enable button with directional swipe support  
  private class DirectionalReEnableButton extends View {
    private String mainGlyph = "⌨";
    private int labelColor = 0xFFFFFFFF;
    private float textSize = 40f;
    private Paint textPaint;
    private Paint arrowPaint;
    
    public DirectionalReEnableButton(Context context) {
      super(context);
      init();
    }
    
    private void init() {
      textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
      textPaint.setTextAlign(Paint.Align.CENTER);
      textPaint.setTypeface(android.graphics.Typeface.DEFAULT);
      textPaint.setColor(labelColor);
      textPaint.setTextSize(textSize);
      
      arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
      arrowPaint.setTextAlign(Paint.Align.CENTER);
      arrowPaint.setTypeface(android.graphics.Typeface.DEFAULT);
      arrowPaint.setColor((labelColor & 0x00FFFFFF) | 0xE0000000); // Even more visible semi-transparent
      arrowPaint.setTextSize(textSize * 0.5f); // Larger arrows
    }
    
    public void setMainGlyph(String glyph) {
      this.mainGlyph = glyph;
      invalidate();
    }
    
    public void setLabelColor(int color) {
      this.labelColor = color;
      textPaint.setColor(color);
      arrowPaint.setColor((color & 0x00FFFFFF) | 0xE0000000); // Even more visible semi-transparent
      invalidate();
    }
    
    public void setTextSize(float size) {
      this.textSize = size;
      textPaint.setTextSize(size);
      arrowPaint.setTextSize(size * 0.5f); // Larger arrows
      invalidate();
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      
      int width = getWidth();
      int height = getHeight();
      int centerX = width / 2;
      int centerY = height / 2;
      
      android.util.Log.d("FloatingKeyboard", "Drawing DirectionalReEnableButton: " + width + "x" + height);
      
      // Draw main glyph in center
      Paint.FontMetrics fm = textPaint.getFontMetrics();
      float textHeight = fm.descent - fm.ascent;
      float textOffset = (textHeight / 2) - fm.descent;
      canvas.drawText(mainGlyph, centerX, centerY + textOffset, textPaint);
      
      // No directional arrows needed - just the center keyboard glyph
    }
  }
}