package juloo.keyboard2.fork;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LogPrinter;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import juloo.keyboard2.fork.prefs.LayoutsPreference;

public class Keyboard2 extends InputMethodService
  implements SharedPreferences.OnSharedPreferenceChangeListener
{
  private Keyboard2View _keyboardView;
  private KeyEventHandler _keyeventhandler;
  /** If not 'null', the layout to use instead of [_config.current_layout]. */
  private KeyboardData _currentSpecialLayout;
  /** Layout associated with the currently selected locale. Not 'null'. */
  private KeyboardData _localeTextLayout;
  private ViewGroup _emojiPane = null;
  private ViewGroup _clipboard_pane = null;
  public int actionId; // Action performed by the Action key.
  private Handler _handler;

  private Config _config;

  private FoldStateTracker _foldStateTracker;
  
  // Floating keyboard support
  private WindowManager _windowManager;
  private boolean _floatingKeyboardActive = false;
  private View _floatingKeyboardView;
  private WindowManager.LayoutParams _floatingLayoutParams;

  /** Layout currently visible before it has been modified. */
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

  /** Layout currently visible. */
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
    _keyboardView.setKeyboard(current_layout());
    if (_floatingKeyboardActive && _floatingKeyboardView != null) {
      ((Keyboard2View)_floatingKeyboardView).setKeyboard(current_layout());
    }
  }

  void incrTextLayout(int delta)
  {
    int s = _config.layouts.size();
    setTextLayout((_config.get_current_layout() + delta + s) % s);
  }

  void setSpecialLayout(KeyboardData l)
  {
    _currentSpecialLayout = l;
    _keyboardView.setKeyboard(l);
    if (_floatingKeyboardActive && _floatingKeyboardView != null) {
      ((Keyboard2View)_floatingKeyboardView).setKeyboard(l);
    }
  }

  KeyboardData loadLayout(int layout_id)
  {
    return KeyboardData.load(getResources(), layout_id);
  }

  /** Load a layout that contains a numpad. */
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
    _keyboardView = (Keyboard2View)inflate_view(R.layout.keyboard);
    _keyboardView.reset();
    Logs.set_debug_logs(getResources().getBoolean(R.bool.debug_logs));
    ClipboardHistoryService.on_startup(this, _keyeventhandler);
    _foldStateTracker.setChangedCallback(() -> { refresh_config(); });
    
    // Initialize floating keyboard components
    _windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    
    // Clean up floating keyboard
    removeFloatingKeyboard();
    _foldStateTracker.close();
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
    List<ExtraKeys> extra_keys = new ArrayList<ExtraKeys>();
    for (InputMethodSubtype s : enabled_subtypes)
      extra_keys.add(extra_keys_of_subtype(s));
    _config.extra_keys_subtype = ExtraKeys.merge(extra_keys);
  }

  InputMethodManager get_imm()
  {
    return (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
  }

  @TargetApi(12)
  private InputMethodSubtype defaultSubtypes(InputMethodManager imm, List<InputMethodSubtype> enabled_subtypes)
  {
    if (VERSION.SDK_INT < 24)
      return imm.getCurrentInputMethodSubtype();
    // Android might return a random subtype, for example, the first in the
    // list alphabetically.
    InputMethodSubtype current_subtype = imm.getCurrentInputMethodSubtype();
    if (current_subtype == null)
      return null;
    for (InputMethodSubtype s : enabled_subtypes)
      if (s.getLanguageTag().equals(current_subtype.getLanguageTag()))
        return s;
    return null;
  }

  private void refreshSubtypeImm()
  {
    InputMethodManager imm = get_imm();
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

  private void refresh_action_label(EditorInfo info)
  {
    // First try to look at 'info.actionLabel', if it isn't set, look at
    // 'imeOptions'.
    if (info.actionLabel != null)
    {
      _config.actionLabel = info.actionLabel.toString();
      actionId = info.actionId;
      _config.swapEnterActionKey = false;
    }
    else
    {
      int action = info.imeOptions & EditorInfo.IME_MASK_ACTION;
      _config.actionLabel = actionLabel_of_imeAction(action); // Might be null
      actionId = action;
      _config.swapEnterActionKey =
        (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0;
    }
  }

  /** Might re-create the keyboard view. [_keyboardView.setKeyboard()] and
      [setInputView()] must be called soon after. */
  private void refresh_config()
  {
    int prev_theme = _config.theme;
    boolean prev_floating = _config.floating_keyboard;
    _config.refresh(getResources(), _foldStateTracker.isUnfolded());
    refreshSubtypeImm();
    
    // Handle floating keyboard mode changes
    Logs.debug("Floating keyboard mode check: prev=" + prev_floating + ", current=" + _config.floating_keyboard);
    if (prev_floating != _config.floating_keyboard) {
      if (_config.floating_keyboard) {
        Logs.debug("Switching to floating keyboard mode");
        createFloatingKeyboard();
      } else {
        Logs.debug("Switching from floating keyboard mode");
        removeFloatingKeyboard();
        // Ensure docked mode is properly set up
        setInputView(_keyboardView);
      }
    }
    
    // Refreshing the theme config requires re-creating the views
    if (prev_theme != _config.theme)
    {
      _keyboardView = (Keyboard2View)inflate_view(R.layout.keyboard);
      _emojiPane = null;
      _clipboard_pane = null;
      if (!_config.floating_keyboard) {
        // Ensure we're in docked mode
        setInputView(_keyboardView);
      } else {
        // Recreate floating keyboard with new theme
        removeFloatingKeyboard();
        createFloatingKeyboard();
      }
    }
    _keyboardView.reset();
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
    _keyboardView.setKeyboard(current_layout());
    if (_floatingKeyboardActive && _floatingKeyboardView != null) {
      ((Keyboard2View)_floatingKeyboardView).setKeyboard(current_layout());
    }
    _keyeventhandler.started(info);
    
    // Debug InputConnection at start
    InputConnection initialConn = getCurrentInputConnection();
    android.util.Log.d("juloo.keyboard2.fork", "onStartInputView InputConnection: " + initialConn);
    if (initialConn != null) {
      android.util.Log.d("juloo.keyboard2.fork", "onStartInputView Connection hashCode: " + Integer.toHexString(initialConn.hashCode()));
    }
    android.util.Log.d("juloo.keyboard2.fork", "Package: " + (info.packageName != null ? info.packageName : "null"));
    
    // Handle floating keyboard mode
    Logs.debug("onStartInputView: floating_keyboard=" + _config.floating_keyboard);
    if (_config.floating_keyboard) {
      Logs.debug("Creating floating keyboard in onStartInputView");
      createFloatingKeyboard();
      if (_floatingKeyboardActive) {
        Logs.debug("Floating keyboard is active");
        
        // Debug InputConnection after floating keyboard creation
        InputConnection postFloatingConn = getCurrentInputConnection();
        android.util.Log.d("juloo.keyboard2.fork", "Post-floating InputConnection: " + postFloatingConn);
        if (postFloatingConn != null) {
          android.util.Log.d("juloo.keyboard2.fork", "Post-floating Connection hashCode: " + Integer.toHexString(postFloatingConn.hashCode()));
        }
        android.util.Log.d("juloo.keyboard2.fork", "InputConnection changed: " + (initialConn != postFloatingConn));
      }
    } else {
      Logs.debug("Using regular keyboard mode");
      removeFloatingKeyboard();
      setInputView(_keyboardView);
    }
    
    Logs.debug_startup_input_view(info, _config);
  }

  @Override
  public void setInputView(View v)
  {
    ViewParent parent = v.getParent();
    if (parent != null && parent instanceof ViewGroup)
      ((ViewGroup)parent).removeView(v);
    super.setInputView(v);
    updateSoftInputWindowLayoutParams();
  }


  @Override
  public void updateFullscreenMode() {
    super.updateFullscreenMode();
    updateSoftInputWindowLayoutParams();
  }

  @Override
  public void onComputeInsets(Insets outInsets) {
    super.onComputeInsets(outInsets);
    
    if (_floatingKeyboardActive && _floatingContainer != null && _floatingKeyboardView != null) {
      // In floating mode, apps can use the full screen
      outInsets.contentTopInsets = 0;
      outInsets.visibleTopInsets = 0;
      outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION;
      
      // Define touchable region to include only areas with actual keys
      // This allows touches in gaps to pass through to the app
      int x = _floatingLayoutParams != null ? _floatingLayoutParams.x : 0;
      int y = _floatingLayoutParams != null ? _floatingLayoutParams.y : 0;
      
      // Get container dimensions
      int containerWidth = _floatingContainer.getWidth();
      int containerHeight = _floatingContainer.getHeight();
      
      if (containerWidth > 0 && containerHeight > 0) {
        // Set touchable region to the entire floating keyboard area
        // The keyboard view itself will handle determining key vs gap touches
        outInsets.touchableRegion.setEmpty();
        outInsets.touchableRegion.set(x, y, x + containerWidth, y + containerHeight);
      }
    }
    // For docked mode, let super.onComputeInsets handle it normally
  }

  private void updateSoftInputWindowLayoutParams() {
    final Window window = getWindow().getWindow();
    // On API >= 35, Keyboard2View behaves as edge-to-edge
    // APIs 30 to 34 have visual artifact when edge-to-edge is enabled
    if (VERSION.SDK_INT >= 35)
    {
      WindowManager.LayoutParams wattrs = window.getAttributes();
      wattrs.layoutInDisplayCutoutMode =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
      // Allow to draw behind system bars
      wattrs.setFitInsetsTypes(0);
      window.setDecorFitsSystemWindows(false);
    }
    updateLayoutHeightOf(window, ViewGroup.LayoutParams.MATCH_PARENT);
    final View inputArea = window.findViewById(android.R.id.inputArea);

    updateLayoutHeightOf(
            (View) inputArea.getParent(),
            isFullscreenMode()
                    ? ViewGroup.LayoutParams.MATCH_PARENT
                    : ViewGroup.LayoutParams.WRAP_CONTENT);
    updateLayoutGravityOf((View) inputArea.getParent(), Gravity.BOTTOM);

  }

  private static void updateLayoutHeightOf(final Window window, final int layoutHeight) {
    final WindowManager.LayoutParams params = window.getAttributes();
    if (params != null && params.height != layoutHeight) {
      params.height = layoutHeight;
      window.setAttributes(params);
    }
  }

  private static void updateLayoutHeightOf(final View view, final int layoutHeight) {
    final ViewGroup.LayoutParams params = view.getLayoutParams();
    if (params != null && params.height != layoutHeight) {
      params.height = layoutHeight;
      view.setLayoutParams(params);
    }
  }

  private static void updateLayoutGravityOf(final View view, final int layoutGravity) {
    final ViewGroup.LayoutParams lp = view.getLayoutParams();
    if (lp instanceof LinearLayout.LayoutParams) {
      final LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) lp;
      if (params.gravity != layoutGravity) {
        params.gravity = layoutGravity;
        view.setLayoutParams(params);
      }
    } else if (lp instanceof FrameLayout.LayoutParams) {
      final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) lp;
      if (params.gravity != layoutGravity) {
        params.gravity = layoutGravity;
        view.setLayoutParams(params);
      }
    }
  }

  @Override
  public void onCurrentInputMethodSubtypeChanged(InputMethodSubtype subtype)
  {
    refreshSubtypeImm();
    _keyboardView.setKeyboard(current_layout());
  }

  @Override
  public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd, int candidatesStart, int candidatesEnd)
  {
    super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
    _keyeventhandler.selection_updated(oldSelStart, newSelStart);
    if ((oldSelStart == oldSelEnd) != (newSelStart == newSelEnd))
      _keyboardView.set_selection_state(newSelStart != newSelEnd);
  }

  @Override
  public void onFinishInputView(boolean finishingInput)
  {
    super.onFinishInputView(finishingInput);
    _keyboardView.reset();
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences _prefs, String _key)
  {
    refresh_config();
    _keyboardView.setKeyboard(current_layout());
  }

  @Override
  public boolean onEvaluateFullscreenMode()
  {
    /* Entirely disable fullscreen mode. */
    return false;
  }

  /** Not static */
  public class Receiver implements KeyEventHandler.IReceiver
  {
    public void handle_event_key(KeyValue.Event ev)
    {
      switch (ev)
      {
        case CONFIG:
          Intent intent = new Intent(Keyboard2.this, SettingsActivity.class);
          intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
          startActivity(intent);
          break;

        case SWITCH_TEXT:
          _currentSpecialLayout = null;
          _keyboardView.setKeyboard(current_layout());
          break;

        case SWITCH_NUMERIC:
          setSpecialLayout(loadNumpad(R.xml.numeric));
          break;

        case SWITCH_EMOJI:
          if (_emojiPane == null)
            _emojiPane = (ViewGroup)inflate_view(R.layout.emoji_pane);
          setInputView(_emojiPane);
          break;

        case SWITCH_CLIPBOARD:
          if (_clipboard_pane == null)
            _clipboard_pane = (ViewGroup)inflate_view(R.layout.clipboard_pane);
          setInputView(_clipboard_pane);
          break;

        case SWITCH_BACK_EMOJI:
        case SWITCH_BACK_CLIPBOARD:
          setInputView(_keyboardView);
          break;

        case CHANGE_METHOD_PICKER:
          get_imm().showInputMethodPicker();
          break;

        case CHANGE_METHOD_AUTO:
          if (VERSION.SDK_INT < 28)
            get_imm().switchToLastInputMethod(getConnectionToken());
          else
            switchToNextInputMethod(false);
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

        case SWITCH_VOICE_TYPING:
          if (!VoiceImeSwitcher.switch_to_voice_ime(Keyboard2.this, get_imm(),
                Config.globalPrefs()))
            _config.shouldOfferVoiceTyping = false;
          break;

        case SWITCH_VOICE_TYPING_CHOOSER:
          VoiceImeSwitcher.choose_voice_ime(Keyboard2.this, get_imm(),
              Config.globalPrefs());
          break;

        case TOGGLE_FLOATING:
          toggle_floating_mode();
          break;
      }
    }

    public void set_shift_state(boolean state, boolean lock)
    {
      _keyboardView.set_shift_state(state, lock);
    }

    public void set_compose_pending(boolean pending)
    {
      _keyboardView.set_compose_pending(pending);
    }

    public void selection_state_changed(boolean selection_is_ongoing)
    {
      _keyboardView.set_selection_state(selection_is_ongoing);
    }

    public InputConnection getCurrentInputConnection()
    {
      InputConnection conn = Keyboard2.this.getCurrentInputConnection();
      android.util.Log.d("juloo.keyboard2.fork", "Receiver.getCurrentInputConnection() called");
      android.util.Log.d("juloo.keyboard2.fork", "  Floating mode: " + _floatingKeyboardActive);
      android.util.Log.d("juloo.keyboard2.fork", "  Returned connection: " + conn);
      if (conn != null) {
        android.util.Log.d("juloo.keyboard2.fork", "  Connection hashCode: " + Integer.toHexString(conn.hashCode()));
      }
      return conn;
    }

    public Handler getHandler()
    {
      return _handler;
    }
  }

  private IBinder getConnectionToken()
  {
    return getWindow().getWindow().getAttributes().token;
  }

  private void toggle_floating_mode()
  {
    boolean newFloatingState = !_config.floating_keyboard;
    android.util.Log.d("juloo.keyboard2.fork", "Toggling floating mode: " + _config.floating_keyboard + " -> " + newFloatingState);
    
    // Update the preference immediately
    SharedPreferences.Editor editor = Config.globalPrefs().edit();
    editor.putBoolean("floating_keyboard", newFloatingState);
    editor.commit(); // Use commit() for immediate write instead of apply()
    
    // Force immediate config refresh to ensure UI updates
    refresh_config();
    
    android.util.Log.d("juloo.keyboard2.fork", "Floating mode toggled to: " + _config.floating_keyboard);
  }

  private View inflate_view(int layout)
  {
    return View.inflate(new ContextThemeWrapper(this, _config.theme), layout, null);
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
    Logs.debug("createFloatingKeyboard called");
    if (_floatingKeyboardActive) {
      Logs.debug("Floating keyboard already active");
      return;
    }
    
    if (!hasSystemAlertWindowPermission()) {
      requestSystemAlertWindowPermission();
      return;
    }
    
    try {
      // Hide the docked keyboard by requesting to hide self
      requestHideSelf(0);
      
      // Make the IME window completely invisible and non-interactive
      Window imeWindow = getWindow().getWindow();
      WindowManager.LayoutParams imeParams = imeWindow.getAttributes();
      imeParams.height = 1; // Minimize height
      imeParams.width = 1;  // Minimize width  
      imeParams.x = -1000;  // Move off screen
      imeParams.y = -1000;  // Move off screen
      imeParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | 
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
      imeWindow.setAttributes(imeParams);
      
      // Create floating keyboard view (clone of current keyboard)
      _floatingKeyboardView = inflate_view(R.layout.keyboard);
      ((Keyboard2View)_floatingKeyboardView).setKeyboard(current_layout());
      ((Keyboard2View)_floatingKeyboardView).reset();
      
      // Create container with drag handle using custom pass-through layout
      PassThroughLinearLayout container = new PassThroughLinearLayout(this);
      container.setOrientation(LinearLayout.VERTICAL);
      
      // Create simple drag handle - 10px height, fully draggable
      View dragHandle = new View(this);
      
      // Create rounded background drawable
      GradientDrawable handleDrawable = new GradientDrawable();
      handleDrawable.setColor(0xFF4C566A); // Darker Nord color
      handleDrawable.setCornerRadius(6 * getResources().getDisplayMetrics().density); // 6dp radius
      dragHandle.setBackground(handleDrawable);
      
      int handleHeight = 10; // Simple 10px height
      int screenWidth = getResources().getDisplayMetrics().widthPixels;
      int handleWidth = (int) (screenWidth * 0.2f); // 20% of screen width
      int marginBottom = (int) (3 * getResources().getDisplayMetrics().density); // 3dp margin
      
      LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(handleWidth, handleHeight);
      handleParams.gravity = Gravity.CENTER_HORIZONTAL; // Center the handle
      handleParams.setMargins(0, 0, 0, marginBottom); // Add margin under handle
      container.addView(dragHandle, handleParams);
      
      // Add keyboard with full width
      LinearLayout.LayoutParams keyboardParams = new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      container.addView(_floatingKeyboardView, keyboardParams);
      
      // Set up window parameters for overlay - allow pass-through for unhandled touches
      WindowManager.LayoutParams params = new WindowManager.LayoutParams(
          WindowManager.LayoutParams.WRAP_CONTENT,
          WindowManager.LayoutParams.WRAP_CONTENT,
          VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
          WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | 
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
          WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
          PixelFormat.TRANSLUCENT);
      
      params.gravity = Gravity.TOP | Gravity.LEFT;
      params.x = 100;
      params.y = 300;
      
      // Add floating window
      _windowManager.addView(container, params);
      _floatingLayoutParams = params;
      _floatingContainer = container;
      
      // Set up drag functionality
      dragHandle.setOnTouchListener(new FloatingDragTouchListener());
      
      _floatingKeyboardActive = true;
      android.util.Log.d("juloo.keyboard2.fork", "Floating overlay window created");
      
    } catch (Exception e) {
      Logs.exn("Failed to create floating keyboard", e);
      _config.floating_keyboard = false;
    }
  }
  
  private void removeFloatingKeyboard() {
    if (_floatingKeyboardActive) {
      try {
        // Remove the floating overlay window
        if (_floatingContainer != null) {
          _windowManager.removeView(_floatingContainer);
          _floatingContainer = null;
        }
        
        // Restore normal IME window behavior
        getWindow().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        
      } catch (Exception e) {
        Logs.exn("Failed to remove floating keyboard", e);
      }
      
      _floatingKeyboardActive = false;
      _floatingKeyboardView = null;
      _floatingLayoutParams = null;
      
      android.util.Log.d("juloo.keyboard2.fork", "Floating keyboard removed");
    }
  }

  private LinearLayout _floatingContainer;

  private class FloatingDragTouchListener implements View.OnTouchListener {
    private float startX, startY;
    private float startTouchX, startTouchY;

    @Override
    public boolean onTouch(View v, MotionEvent event) {
      if (!_floatingKeyboardActive || _floatingLayoutParams == null) {
        return false;
      }

      switch (event.getAction()) {
        case MotionEvent.ACTION_DOWN:
          startX = _floatingLayoutParams.x;
          startY = _floatingLayoutParams.y;
          startTouchX = event.getRawX();
          startTouchY = event.getRawY();
          return true;

        case MotionEvent.ACTION_MOVE:
          float deltaX = event.getRawX() - startTouchX;
          float deltaY = event.getRawY() - startTouchY;
          
          _floatingLayoutParams.x = (int) (startX + deltaX);
          _floatingLayoutParams.y = (int) (startY + deltaY);
          
          _windowManager.updateViewLayout(_floatingContainer, _floatingLayoutParams);
          return true;

        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          return true;
      }
      
      return false;
    }
  }

  // Custom container that allows touch pass-through for gaps
  private class PassThroughLinearLayout extends LinearLayout {
    public PassThroughLinearLayout(Context context) {
      super(context);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
      // Never intercept - always let children try first
      return false;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
      // Let children handle the event first
      boolean handled = super.dispatchTouchEvent(ev);
      
      if (!handled) {
        // If no child handled it, it's a gap touch - try to pass through
        android.util.Log.d("juloo.keyboard2.fork", "Gap touch detected - attempting pass-through");
        
        // For gap touches, we need to make the window temporarily non-touchable
        // so the touch can pass to the app behind
        if (_floatingLayoutParams != null && ev.getAction() == MotionEvent.ACTION_DOWN) {
          try {
            // Temporarily remove touchable flag to let this touch pass through
            _floatingLayoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            _windowManager.updateViewLayout(this, _floatingLayoutParams);
            
            // Restore touchable flag after a short delay
            postDelayed(new Runnable() {
              @Override
              public void run() {
                if (_floatingLayoutParams != null) {
                  _floatingLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                  _windowManager.updateViewLayout(PassThroughLinearLayout.this, _floatingLayoutParams);
                }
              }
            }, 50); // 50ms delay
            
          } catch (Exception e) {
            android.util.Log.e("juloo.keyboard2.fork", "Failed to toggle touchable flag: " + e.getMessage());
          }
        }
      }
      
      return handled;
    }
  }
  
}
