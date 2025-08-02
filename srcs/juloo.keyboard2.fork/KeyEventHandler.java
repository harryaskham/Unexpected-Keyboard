package juloo.keyboard2.fork;

import android.annotation.SuppressLint;
import android.os.Looper;
import android.os.Handler;
import android.text.InputType;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import java.util.Iterator;

public final class KeyEventHandler
  implements Config.IKeyEventHandler,
             ClipboardHistoryService.ClipboardPasteCallback
{
  IReceiver _recv;
  Autocapitalisation _autocap;
  /** State of the system modifiers. It is updated whether a modifier is down
      or up and a corresponding key event is sent. */
  Pointers.Modifiers _mods;
  /** Consistent with [_mods]. This is a mutable state rather than computed
      from [_mods] to ensure that the meta state is correct while up and down
      events are sent for the modifier keys. */
  int _meta_state = 0;
  /** Whether to force sending arrow keys to move the cursor when
      [setSelection] could be used instead. */
  boolean _move_cursor_force_fallback = false;
  /** Whether the target app has disabled direct writing (e.g. Samsung apps). */
  boolean _disable_direct_writing = false;

  public KeyEventHandler(IReceiver recv)
  {
    _recv = recv;
    _autocap = new Autocapitalisation(recv.getHandler(),
        this.new Autocapitalisation_callback());
    _mods = Pointers.Modifiers.EMPTY;
  }

  /** Editing just started. */
  public void started(EditorInfo info)
  {
    _autocap.started(info, _recv.getCurrentInputConnection());
    _move_cursor_force_fallback = should_move_cursor_force_fallback(info);
    _disable_direct_writing = should_disable_direct_writing(info);
  }

  /** Selection has been updated. */
  public void selection_updated(int oldSelStart, int newSelStart)
  {
    _autocap.selection_updated(oldSelStart, newSelStart);
  }

  /** A key is being pressed. There will not necessarily be a corresponding
      [key_up] event. */
  @Override
  public void key_down(KeyValue key, boolean isSwipe)
  {
    android.util.Log.d("juloo.keyboard2.fork", "KeyEventHandler.key_down: " + key + ", swipe: " + isSwipe);
    if (key == null)
      return;
    // Stop auto capitalisation when pressing some keys
    switch (key.getKind())
    {
      case Modifier:
        switch (key.getModifier())
        {
          case CTRL:
          case ALT:
          case META:
            _autocap.stop();
            break;
        }
        break;
      case Compose_pending:
        _autocap.stop();
        break;
      case Slider:
        // Don't wait for the next key_up and move the cursor right away. This
        // is called after the trigger distance have been travelled.
        handle_slider(key.getSlider(), key.getSliderRepeat(), true);
        break;
      default: break;
    }
  }

  /** A key has been released. */
  @Override
  public void key_up(KeyValue key, Pointers.Modifiers mods)
  {
    android.util.Log.d("juloo.keyboard2.fork", "KeyEventHandler.key_up: " + key + ", kind: " + (key != null ? key.getKind() : "null"));
    if (key == null)
      return;
    Pointers.Modifiers old_mods = _mods;
    update_meta_state(mods);
    switch (key.getKind())
    {
      case Char: send_text(String.valueOf(key.getChar())); break;
      case String: send_text(key.getString()); break;
      case Event: _recv.handle_event_key(key.getEvent()); break;
      case Keyevent: send_key_down_up(key.getKeyevent()); break;
      case Modifier: break;
      case Editing: handle_editing_key(key.getEditing()); break;
      case Compose_pending: _recv.set_compose_pending(true); break;
      case Slider: handle_slider(key.getSlider(), key.getSliderRepeat(), false); break;
      case Macro: evaluate_macro(key.getMacro()); break;
    }
    update_meta_state(old_mods);
  }

  @Override
  public void mods_changed(Pointers.Modifiers mods)
  {
    update_meta_state(mods);
  }

  @Override
  public void paste_from_clipboard_pane(String content)
  {
    send_text(content);
  }

  /** Update [_mods] to be consistent with the [mods], sending key events if
      needed. */
  void update_meta_state(Pointers.Modifiers mods)
  {
    // Released modifiers
    Iterator<KeyValue> it = _mods.diff(mods);
    while (it.hasNext())
      sendMetaKeyForModifier(it.next(), false);
    // Activated modifiers
    it = mods.diff(_mods);
    while (it.hasNext())
      sendMetaKeyForModifier(it.next(), true);
    _mods = mods;
  }

  // private void handleDelKey(int before, int after)
  // {
  //  CharSequence selection = getCurrentInputConnection().getSelectedText(0);

  //  if (selection != null && selection.length() > 0)
  //  getCurrentInputConnection().commitText("", 1);
  //  else
  //  getCurrentInputConnection().deleteSurroundingText(before, after);
  // }

  void sendMetaKey(int eventCode, int meta_flags, boolean down)
  {
    if (down)
    {
      _meta_state = _meta_state | meta_flags;
      send_keyevent(KeyEvent.ACTION_DOWN, eventCode, _meta_state);
    }
    else
    {
      send_keyevent(KeyEvent.ACTION_UP, eventCode, _meta_state);
      _meta_state = _meta_state & ~meta_flags;
    }
  }

  void sendMetaKeyForModifier(KeyValue kv, boolean down)
  {
    switch (kv.getKind())
    {
      case Modifier:
        switch (kv.getModifier())
        {
          case CTRL:
            sendMetaKey(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_CTRL_ON, down);
            break;
          case ALT:
            sendMetaKey(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_ALT_LEFT_ON | KeyEvent.META_ALT_ON, down);
            break;
          case SHIFT:
            sendMetaKey(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_LEFT_ON | KeyEvent.META_SHIFT_ON, down);
            break;
          case META:
            sendMetaKey(KeyEvent.KEYCODE_META_LEFT, KeyEvent.META_META_LEFT_ON | KeyEvent.META_META_ON, down);
            break;
          default:
            break;
        }
        break;
    }
  }

  void send_key_down_up(int keyCode)
  {
    send_key_down_up(keyCode, _meta_state);
  }

  /** Ignores currently pressed system modifiers. */
  void send_key_down_up(int keyCode, int metaState)
  {
    send_keyevent(KeyEvent.ACTION_DOWN, keyCode, metaState);
    send_keyevent(KeyEvent.ACTION_UP, keyCode, metaState);
  }

  void send_keyevent(int eventAction, int eventCode, int metaState)
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    conn.sendKeyEvent(new KeyEvent(1, 1, eventAction, eventCode, 0,
          metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
          KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
    if (eventAction == KeyEvent.ACTION_UP)
      _autocap.event_sent(eventCode, metaState);
  }

  void send_text(CharSequence text)
  {
    android.util.Log.d("juloo.keyboard2.fork", "KeyEventHandler.send_text: '" + text + "'");
    InputConnection conn = _recv.getCurrentInputConnection();
    android.util.Log.d("juloo.keyboard2.fork", "InputConnection: " + conn);
    if (conn != null) {
      android.util.Log.d("juloo.keyboard2.fork", "InputConnection class: " + conn.getClass().getSimpleName());
      android.util.Log.d("juloo.keyboard2.fork", "InputConnection hashCode: " + Integer.toHexString(conn.hashCode()));
      android.util.Log.d("juloo.keyboard2.fork", "InputConnection toString: " + conn.toString());
      
      // Try to get more info about the connection target
      try {
        ExtractedText extractedText = get_cursor_pos(conn);
        if (extractedText != null) {
          android.util.Log.d("juloo.keyboard2.fork", "Target text length: " + (extractedText.text != null ? extractedText.text.length() : "null"));
          android.util.Log.d("juloo.keyboard2.fork", "Cursor position: " + extractedText.selectionStart + "-" + extractedText.selectionEnd);
        }
      } catch (Exception e) {
        android.util.Log.d("juloo.keyboard2.fork", "Error getting extracted text: " + e.getMessage());
      }
    }
    if (conn == null)
      return;
    
    // Check if we should use key events instead of commitText due to disableDirectWriting
    if (_disable_direct_writing) {
      android.util.Log.d("juloo.keyboard2.fork", "Using key events due to disableDirectWriting");
      send_text_as_key_events(text);
    } else {
      conn.commitText(text, 1);
      android.util.Log.d("juloo.keyboard2.fork", "Text committed to InputConnection");
    }
    _autocap.typed(text);
  }

  /** See {!InputConnection.performContextMenuAction}. */
  void send_context_menu_action(int id)
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    conn.performContextMenuAction(id);
  }

  @SuppressLint("InlinedApi")
  void handle_editing_key(KeyValue.Editing ev)
  {
    switch (ev)
    {
      case COPY: if(is_selection_not_empty()) send_context_menu_action(android.R.id.copy); break;
      case PASTE: send_context_menu_action(android.R.id.paste); break;
      case CUT: if(is_selection_not_empty()) send_context_menu_action(android.R.id.cut); break;
      case SELECT_ALL: send_context_menu_action(android.R.id.selectAll); break;
      case SHARE: send_context_menu_action(android.R.id.shareText); break;
      case PASTE_PLAIN: send_context_menu_action(android.R.id.pasteAsPlainText); break;
      case UNDO: send_context_menu_action(android.R.id.undo); break;
      case REDO: send_context_menu_action(android.R.id.redo); break;
      case REPLACE: send_context_menu_action(android.R.id.replaceText); break;
      case ASSIST: send_context_menu_action(android.R.id.textAssist); break;
      case AUTOFILL: send_context_menu_action(android.R.id.autofill); break;
      case DELETE_WORD: send_key_down_up(KeyEvent.KEYCODE_DEL, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON); break;
      case FORWARD_DELETE_WORD: send_key_down_up(KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON); break;
      case SELECTION_CANCEL: cancel_selection(); break;
    }
  }

  static ExtractedTextRequest _move_cursor_req = null;

  /** Query the cursor position. The extracted text is empty. Returns [null] if
      the editor doesn't support this operation. */
  ExtractedText get_cursor_pos(InputConnection conn)
  {
    if (_move_cursor_req == null)
    {
      _move_cursor_req = new ExtractedTextRequest();
      _move_cursor_req.hintMaxChars = 0;
    }
    return conn.getExtractedText(_move_cursor_req, 0);
  }

  /** [r] might be negative, in which case the direction is reversed. */
  void handle_slider(KeyValue.Slider s, int r, boolean key_down)
  {
    switch (s)
    {
      case Cursor_left: move_cursor(-r); break;
      case Cursor_right: move_cursor(r); break;
      case Cursor_up: move_cursor_vertical(-r); break;
      case Cursor_down: move_cursor_vertical(r); break;
      case Selection_cursor_left: move_cursor_sel(r, true, key_down); break;
      case Selection_cursor_right: move_cursor_sel(r, false, key_down); break;
    }
  }

  /** Move the cursor right or left, if possible without sending key events.
      Unlike arrow keys, the selection is not removed even if shift is not on.
      Falls back to sending arrow keys events if the editor do not support
      moving the cursor or a modifier other than shift is pressed. */
  void move_cursor(int d)
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    ExtractedText et = get_cursor_pos(conn);
    if (et != null && can_set_selection(conn))
    {
      int sel_start = et.selectionStart;
      int sel_end = et.selectionEnd;
      // Continue expanding the selection even if shift is not pressed
      if (sel_end != sel_start)
      {
        sel_end += d;
        if (sel_end == sel_start) // Avoid making the selection empty
          sel_end += d;
      }
      else
      {
        sel_end += d;
        // Leave 'sel_start' where it is if shift is pressed
        if ((_meta_state & KeyEvent.META_SHIFT_ON) == 0)
          sel_start = sel_end;
      }
      if (conn.setSelection(sel_start, sel_end))
        return; // Fallback to sending key events if [setSelection] failed
    }
    move_cursor_fallback(d);
  }

  /** Move one of the two side of a selection. If [sel_left] is true, the left
      position is moved, otherwise the right position is moved. */
  void move_cursor_sel(int d, boolean sel_left, boolean key_down)
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    ExtractedText et = get_cursor_pos(conn);
    if (et != null && can_set_selection(conn))
    {
      int sel_start = et.selectionStart;
      int sel_end = et.selectionEnd;
      // Reorder the selection when the slider has just been pressed. The
      // selection might have been reversed if one end crossed the other end
      // with a previous slider.
      if (key_down && sel_start > sel_end)
      {
        sel_start = et.selectionEnd;
        sel_end = et.selectionStart;
      }
      do
      {
        if (sel_left)
          sel_start += d;
        else
          sel_end += d;
        // Move the cursor twice if moving it once would make the selection
        // empty and stop selection mode.
      } while (sel_start == sel_end);
      if (conn.setSelection(sel_start, sel_end))
        return; // Fallback to sending key events if [setSelection] failed
    }
    move_cursor_fallback(d);
  }

  /** Returns whether the selection can be set using [conn.setSelection()].
      This can happen on Termux or when system modifiers are activated for
      example. */
  boolean can_set_selection(InputConnection conn)
  {
    final int system_mods =
      KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON | KeyEvent.META_META_ON;
    return !_move_cursor_force_fallback && (_meta_state & system_mods) == 0;
  }

  void move_cursor_fallback(int d)
  {
    if (d < 0)
      send_key_down_up_repeat(KeyEvent.KEYCODE_DPAD_LEFT, -d);
    else
      send_key_down_up_repeat(KeyEvent.KEYCODE_DPAD_RIGHT, d);
  }

  /** Move the cursor up and down. This sends UP and DOWN key events that might
      make the focus exit the text box. */
  void move_cursor_vertical(int d)
  {
    if (d < 0)
      send_key_down_up_repeat(KeyEvent.KEYCODE_DPAD_UP, -d);
    else
      send_key_down_up_repeat(KeyEvent.KEYCODE_DPAD_DOWN, d);
  }

  void evaluate_macro(KeyValue[] keys)
  {
    if (keys.length == 0)
      return;
    // Ignore modifiers that are activated at the time the macro is evaluated
    mods_changed(Pointers.Modifiers.EMPTY);
    evaluate_macro_loop(keys, 0, Pointers.Modifiers.EMPTY, _autocap.pause());
  }

  /** Evaluate the macro asynchronously to make sure event are processed in the
      right order. */
  void evaluate_macro_loop(final KeyValue[] keys, int i, Pointers.Modifiers mods, final boolean autocap_paused)
  {
    boolean should_delay = false;
    KeyValue kv = KeyModifier.modify(keys[i], mods);
    if (kv != null)
    {
      if (kv.hasFlagsAny(KeyValue.FLAG_LATCH))
      {
        // Non-special latchable keys clear latched modifiers
        if (!kv.hasFlagsAny(KeyValue.FLAG_SPECIAL))
          mods = Pointers.Modifiers.EMPTY;
        mods = mods.with_extra_mod(kv);
      }
      else
      {
        key_down(kv, false);
        key_up(kv, mods);
        mods = Pointers.Modifiers.EMPTY;
      }
      should_delay = wait_after_macro_key(kv);
    }
    i++;
    if (i >= keys.length) // Stop looping
    {
      _autocap.unpause(autocap_paused);
    }
    else if (should_delay)
    {
      // Add a delay before sending the next key to avoid race conditions
      // causing keys to be handled in the wrong order. Notably, KeyEvent keys
      // handling is scheduled differently than the other edit functions.
      final int i_ = i;
      final Pointers.Modifiers mods_ = mods;
      _recv.getHandler().postDelayed(new Runnable() {
        public void run()
        {
          evaluate_macro_loop(keys, i_, mods_, autocap_paused);
        }
      }, 1000/30);
    }
    else
      evaluate_macro_loop(keys, i, mods, autocap_paused);
  }

  boolean wait_after_macro_key(KeyValue kv)
  {
    switch (kv.getKind())
    {
      case Keyevent:
      case Editing:
      case Event:
        return true;
      case Slider:
        return _move_cursor_force_fallback;
      default:
        return false;
    }
  }

  /** Repeat calls to [send_key_down_up]. */
  void send_key_down_up_repeat(int event_code, int repeat)
  {
    while (repeat-- > 0)
      send_key_down_up(event_code);
  }

  void cancel_selection()
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null)
      return;
    ExtractedText et = get_cursor_pos(conn);
    if (et == null) return;
    final int curs = et.selectionStart;
    // Notify the receiver as Android's [onUpdateSelection] is not triggered.
    if (conn.setSelection(curs, curs));
      _recv.selection_state_changed(false);
  }

  boolean is_selection_not_empty()
  {
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn == null) return false;
    return (conn.getSelectedText(0) != null);
  }

  /** Workaround some apps which answers to [getExtractedText] but do not react
      to [setSelection] while returning [true]. */
  boolean should_move_cursor_force_fallback(EditorInfo info)
  {
    // This catch Acode: which sets several variations at once.
    if ((info.inputType & InputType.TYPE_MASK_VARIATION & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0)
      return true;
    // Godot editor: Doesn't handle setSelection() but returns true.
    return info.packageName.startsWith("org.godotengine.editor");
  }

  /** Check if the target app has disabled direct writing through privateImeOptions. */
  boolean should_disable_direct_writing(EditorInfo info)
  {
    if (info.privateImeOptions != null) {
      boolean disabled = info.privateImeOptions.contains("disableDirectWriting=true");
      android.util.Log.d("juloo.keyboard2.fork", "privateImeOptions: " + info.privateImeOptions + ", disableDirectWriting: " + disabled);
      return disabled;
    }
    return false;
  }

  /** Send text character by character using key events when commitText() is disabled. */
  void send_text_as_key_events(CharSequence text)
  {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      // Try to find a keycode for common characters
      int keyCode = getKeyCodeForChar(c);
      if (keyCode != 0) {
        // Check if we need shift modifier
        boolean needsShift = characterNeedsShift(c);
        if (needsShift) {
          send_key_down_up(keyCode, KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON);
        } else {
          send_key_down_up(keyCode);
        }
      } else {
        // For characters that can't be mapped, try to use Unicode key events
        // This is a fallback that may not work on all devices/apps
        android.util.Log.d("juloo.keyboard2.fork", "Cannot map character '" + c + "' to keycode, trying Unicode");
        send_unicode_key_event(c);
      }
    }
  }

  /** Get keycode for common characters. */
  int getKeyCodeForChar(char c)
  {
    // Map common characters to keycodes
    if (c >= 'a' && c <= 'z') {
      return KeyEvent.KEYCODE_A + (c - 'a');
    } else if (c >= 'A' && c <= 'Z') {
      return KeyEvent.KEYCODE_A + (c - 'A');
    } else if (c >= '0' && c <= '9') {
      return KeyEvent.KEYCODE_0 + (c - '0');
    } else {
      // Map other common characters
      switch (c) {
        case ' ': return KeyEvent.KEYCODE_SPACE;
        case '.': return KeyEvent.KEYCODE_PERIOD;
        case ',': return KeyEvent.KEYCODE_COMMA;
        case '?': return KeyEvent.KEYCODE_SLASH;
        case '!': return KeyEvent.KEYCODE_1;
        case '@': return KeyEvent.KEYCODE_2;
        case '#': return KeyEvent.KEYCODE_3;
        case '$': return KeyEvent.KEYCODE_4;
        case '%': return KeyEvent.KEYCODE_5;
        case '^': return KeyEvent.KEYCODE_6;
        case '&': return KeyEvent.KEYCODE_7;
        case '*': return KeyEvent.KEYCODE_8;
        case '(': return KeyEvent.KEYCODE_9;
        case ')': return KeyEvent.KEYCODE_0;
        case '-': return KeyEvent.KEYCODE_MINUS;
        case '_': return KeyEvent.KEYCODE_MINUS;
        case '=': return KeyEvent.KEYCODE_EQUALS;
        case '+': return KeyEvent.KEYCODE_EQUALS;
        case '[': return KeyEvent.KEYCODE_LEFT_BRACKET;
        case ']': return KeyEvent.KEYCODE_RIGHT_BRACKET;
        case '\\': return KeyEvent.KEYCODE_BACKSLASH;
        case ';': return KeyEvent.KEYCODE_SEMICOLON;
        case ':': return KeyEvent.KEYCODE_SEMICOLON;
        case '\'': return KeyEvent.KEYCODE_APOSTROPHE;
        case '"': return KeyEvent.KEYCODE_APOSTROPHE;
        case '/': return KeyEvent.KEYCODE_SLASH;
        case '\n': return KeyEvent.KEYCODE_ENTER;
        default: return 0; // No mapping
      }
    }
  }

  /** Check if a character requires the shift modifier. */
  boolean characterNeedsShift(char c)
  {
    // Uppercase letters need shift
    if (c >= 'A' && c <= 'Z') return true;
    
    // Special characters that require shift
    switch (c) {
      case '!': case '@': case '#': case '$': case '%':
      case '^': case '&': case '*': case '(': case ')':
      case '_': case '+': case '{': case '}': case '|':
      case ':': case '"': case '<': case '>': case '?':
        return true;
      default:
        return false;
    }
  }

  /** Send a Unicode character as a key event. */
  void send_unicode_key_event(char c)
  {
    // This is a more complex approach - we send the Unicode value as a key event
    // Most apps should handle this, but it's less reliable than commitText()
    KeyEvent downEvent = new KeyEvent(1, 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_UNKNOWN, 0, 
        _meta_state, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 
        KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE);
    KeyEvent upEvent = new KeyEvent(1, 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_UNKNOWN, 0, 
        _meta_state, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 
        KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE);
    
    // Set the Unicode character
    downEvent = KeyEvent.changeTimeRepeat(downEvent, downEvent.getEventTime(), 0);
    upEvent = KeyEvent.changeTimeRepeat(upEvent, upEvent.getEventTime(), 0);
    
    InputConnection conn = _recv.getCurrentInputConnection();
    if (conn != null) {
      conn.sendKeyEvent(downEvent);
      conn.sendKeyEvent(upEvent);
    }
  }

  public static interface IReceiver
  {
    public void handle_event_key(KeyValue.Event ev);
    public void set_shift_state(boolean state, boolean lock);
    public void set_compose_pending(boolean pending);
    public void selection_state_changed(boolean selection_is_ongoing);
    public InputConnection getCurrentInputConnection();
    public Handler getHandler();
  }

  class Autocapitalisation_callback implements Autocapitalisation.Callback
  {
    @Override
    public void update_shift_state(boolean should_enable, boolean should_disable)
    {
      if (should_enable)
        _recv.set_shift_state(true, false);
      else if (should_disable)
        _recv.set_shift_state(false, false);
    }
  }
}
