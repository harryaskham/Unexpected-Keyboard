package juloo.keyboard2.fork;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;

/**
 * Receives HID events from ring-mods (or any app) via broadcast and injects
 * them through the keyboard's InputConnection. This allows ring-mods gestures
 * to work even when Unexpected Keyboard is the active IME.
 *
 * Intent action: "com.ringmods.HID_EVENT"
 * Extras:
 *   "type"       = "key" | "text" | "scroll"
 *   "keycode"    = int    (for type=key)
 *   "meta_state" = int    (for type=key, optional, default 0)
 *   "text"       = String (for type=text)
 *   "amount"     = int    (for type=scroll, positive=down negative=up)
 */
public class RingModsReceiver extends BroadcastReceiver
{
  static final String TAG = "RingModsRecv";
  static final String ACTION = "com.ringmods.HID_EVENT";

  @Override
  public void onReceive(Context context, Intent intent)
  {
    if (intent == null || !ACTION.equals(intent.getAction()))
      return;

    Keyboard2 kbd = Keyboard2.instance;
    if (kbd == null)
    {
      Log.w(TAG, "Keyboard2 instance not available");
      return;
    }

    InputConnection ic = kbd.getCurrentInputConnection();
    if (ic == null)
    {
      Log.w(TAG, "No InputConnection available");
      return;
    }

    String type = intent.getStringExtra("type");
    if (type == null)
      type = "key";

    switch (type)
    {
      case "key":
        int keycode = intent.getIntExtra("keycode", 0);
        int metaState = intent.getIntExtra("meta_state", 0);
        if (keycode != 0)
        {
          sendKeyDownUp(ic, keycode, metaState);
          Log.d(TAG, "Injected key: " + keycode + " meta: " + metaState);
        }
        break;

      case "text":
        String text = intent.getStringExtra("text");
        if (text != null && !text.isEmpty())
        {
          ic.commitText(text, 1);
          Log.d(TAG, "Injected text: " + text);
        }
        break;

      case "scroll":
        int amount = intent.getIntExtra("amount", 0);
        if (amount != 0)
        {
          // Send as DPAD key events — amount is number of presses
          int keyCode = amount > 0 ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP;
          int count = Math.abs(amount);
          for (int i = 0; i < count && i < 20; i++) // cap at 20 to prevent abuse
            sendKeyDownUp(ic, keyCode, 0);
          Log.d(TAG, "Injected scroll: " + amount + " (" + count + "x " + (amount > 0 ? "DOWN" : "UP") + ")");
        }
        break;

      default:
        Log.w(TAG, "Unknown HID event type: " + type);
    }
  }

  private void sendKeyDownUp(InputConnection ic, int keyCode, int metaState)
  {
    long now = SystemClock.uptimeMillis();
    ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0,
        metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
        KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
    ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0,
        metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
        KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
  }
}
