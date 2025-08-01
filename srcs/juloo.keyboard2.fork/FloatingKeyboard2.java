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
import android.provider.Settings;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
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
  private LinearLayout _floatingContainer;

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
      
      // Make keyboard background transparent for pass-through
      _floatingKeyboardView.setBackground(null);
      
      // Create container with drag handle and resize handle
      ResizableFloatingContainer container = new ResizableFloatingContainer(this);
      container.setOrientation(LinearLayout.VERTICAL);
      
      // Create simple drag handle - 16px height, narrow width, fully draggable
      View dragHandle = new View(this);
      
      GradientDrawable handleDrawable = new GradientDrawable();
      handleDrawable.setColor(0xFF4C566A);
      handleDrawable.setCornerRadius(6 * getResources().getDisplayMetrics().density);
      dragHandle.setBackground(handleDrawable);
      
      int handleHeight = 24; // Increased to 24px
      int screenWidth = getResources().getDisplayMetrics().widthPixels;
      int handleWidth = (int) (screenWidth * 0.2f); // Handle is 20% width
      int marginBottom = 3; // 3px bottom margin
      
      // Add handle with specific width, centered
      LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(handleWidth, handleHeight);
      handleParams.gravity = Gravity.CENTER_HORIZONTAL;
      handleParams.setMargins(0, 0, 0, marginBottom);
      container.addView(dragHandle, handleParams);
      
      // Add keyboard with WRAP_CONTENT to be full width
      LinearLayout.LayoutParams keyboardParams = new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      container.addView(_floatingKeyboardView, keyboardParams);
      
      // Set up window parameters for overlay
      WindowManager.LayoutParams params = new WindowManager.LayoutParams(
          WindowManager.LayoutParams.WRAP_CONTENT,
          WindowManager.LayoutParams.WRAP_CONTENT,
          VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
          WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
          PixelFormat.TRANSLUCENT);
      
      params.gravity = Gravity.TOP | Gravity.LEFT;
      params.x = 100;
      params.y = 300;
      
      _windowManager.addView(container, params);
      _floatingLayoutParams = params;
      _floatingContainer = container;
      
      dragHandle.setOnTouchListener(new FloatingDragTouchListener());
      container.setWindowManager(_windowManager, params);
      
      _floatingKeyboardActive = true;
      
    } catch (Exception e) {
      Logs.exn("Failed to create floating keyboard", e);
    }
  }
  
  private void removeFloatingKeyboard() {
    if (_floatingKeyboardActive && _floatingContainer != null) {
      try {
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


  private class ResizableFloatingContainer extends LinearLayout {
    private View resizeHandle;
    private WindowManager windowManager;
    private WindowManager.LayoutParams layoutParams;
    private float initialScale = 1.0f;
    private float currentScale = 1.0f;
    private boolean isResizing = false;
    private float resizeStartX, resizeStartY;
    private int initialWidth, initialHeight;

    public ResizableFloatingContainer(Context context) {
      super(context);
      createResizeHandle();
    }

    public void setWindowManager(WindowManager wm, WindowManager.LayoutParams lp) {
      windowManager = wm;
      layoutParams = lp;
    }

    private void createResizeHandle() {
      // Create resize handle in top-right corner
      resizeHandle = new View(getContext());
      
      GradientDrawable resizeDrawable = new GradientDrawable();
      resizeDrawable.setColor(0xFF4C566A);
      resizeDrawable.setCornerRadius(4 * getResources().getDisplayMetrics().density);
      resizeHandle.setBackground(resizeDrawable);
      
      int handleHeight = (int)(16 * getResources().getDisplayMetrics().density);
      int handleWidth = (int)(32 * getResources().getDisplayMetrics().density);
      LayoutParams handleParams = new LayoutParams(handleWidth, handleHeight);
      handleParams.gravity = Gravity.TOP | Gravity.RIGHT;
      handleParams.setMargins(0, 8, 24, 0);
      
      resizeHandle.setOnTouchListener(new ResizeTouchListener());
      addView(resizeHandle, handleParams);
    }

    private class ResizeTouchListener implements OnTouchListener {
      @Override
      public boolean onTouch(View v, MotionEvent event) {
        if (windowManager == null || layoutParams == null) {
          return false;
        }

        switch (event.getAction()) {
          case MotionEvent.ACTION_DOWN:
            isResizing = true;
            resizeStartX = event.getRawX();
            resizeStartY = event.getRawY();
            initialWidth = getWidth();
            initialHeight = getHeight();
            return true;

          case MotionEvent.ACTION_MOVE:
            if (isResizing) {
              float deltaX = event.getRawX() - resizeStartX;
              float deltaY = event.getRawY() - resizeStartY;
              
              // Calculate new scale based on diagonal distance
              float diagonal = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
              float scaleFactor = 1.0f + (diagonal / 500.0f); // Adjust sensitivity
              
              // Clamp scale between 0.5x and 2.0x
              currentScale = Math.max(0.5f, Math.min(2.0f, scaleFactor));
              
              // Apply scaling to the container
              setScaleX(currentScale);
              setScaleY(currentScale);
              
              return true;
            }
            break;

          case MotionEvent.ACTION_UP:
          case MotionEvent.ACTION_CANCEL:
            isResizing = false;
            return true;
        }
        
        return false;
      }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      // Transform touch coordinates based on current scale
      if (currentScale != 1.0f) {
        float x = event.getX() / currentScale;
        float y = event.getY() / currentScale;
        event.setLocation(x, y);
      }
      return super.onTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
      // Don't intercept touches, let child views handle them first
      return false;
    }
  }
}