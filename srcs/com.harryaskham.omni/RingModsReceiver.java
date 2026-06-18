package com.harryaskham.omni;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;

/**
 * Omni injection command server.
 *
 * Receives HID/injection events via broadcast and injects them through the
 * keyboard's InputConnection. This allows external apps (ring-mods, Termux,
 * and any other Omni-family tool) to drive the focused field even when Omni is
 * the active IME. Works with both Keyboard2 (docked) and FloatingKeyboard2
 * (floating) — whichever IME instance is currently available.
 *
 * Intent actions (either is accepted):
 *   "com.harryaskham.omni.INJECT"  (canonical Omni action)
 *   "com.ringmods.HID_EVENT"       (legacy ring-mods action, kept for compat)
 *
 * Extras:
 *   "type"           = "key" | "text" | "scroll"
 *   "keycode"        = int    (for type=key)
 *   "meta_state"     = int    (for type=key, optional, default 0)
 *   "text"           = String (for type=text)
 *   "amount"         = int    (for type=scroll, positive=down negative=up)
 *   "sender_package" = String (optional) the sending app's package id. When
 *                      provided it is checked against the allowlist below;
 *                      senders from com.harryaskham.* or com.termux.* are
 *                      accepted, anything else is rejected. When omitted the
 *                      broadcast is accepted for backward compatibility, since
 *                      Android does not expose a reliable sender identity to a
 *                      BroadcastReceiver. Trusted Omni-family senders SHOULD set
 *                      this extra so the gate is meaningful.
 */
public class RingModsReceiver extends BroadcastReceiver
{
  static final String TAG = "OmniInjectRecv";

  /** Canonical Omni injection action. */
  public static final String ACTION = "com.harryaskham.omni.INJECT";
  /** Legacy ring-mods action, still accepted for backward compatibility. */
  public static final String LEGACY_ACTION = "com.ringmods.HID_EVENT";

  /** Optional extra naming the sending package, checked against the allowlist. */
  public static final String EXTRA_SENDER_PACKAGE = "sender_package";

  /** Base package ids whose family (the exact id, or id + ".sub") may inject. */
  static final String[] ALLOWED_SENDER_PACKAGES = {
    "com.harryaskham",
    "com.termux",
  };

  /**
   * Build the IntentFilter covering every action this receiver accepts. Used by
   * Keyboard2 and FloatingKeyboard2 when registering the receiver so both the
   * canonical Omni action and the legacy ring-mods action are handled.
   */
  public static IntentFilter buildIntentFilter()
  {
    IntentFilter filter = new IntentFilter();
    filter.addAction(ACTION);
    filter.addAction(LEGACY_ACTION);
    return filter;
  }

  /** True for an action this receiver injects on. */
  static boolean isAcceptedAction(String action)
  {
    return ACTION.equals(action) || LEGACY_ACTION.equals(action);
  }

  /**
   * Sender-package allowlist. A null/empty package is treated as "unknown" and
   * accepted (callers that do not declare a sender_package are allowed for
   * backward compatibility). A declared package is accepted only when it begins
   * with one of {@link #ALLOWED_SENDER_PREFIXES}.
   */
  static boolean isAllowedSenderPackage(String pkg)
  {
    if (pkg == null || pkg.isEmpty())
      return true;
    for (String base : ALLOWED_SENDER_PACKAGES)
      if (pkg.equals(base) || pkg.startsWith(base + "."))
        return true;
    return false;
  }

  @Override
  public void onReceive(Context context, Intent intent)
  {
    if (intent == null || !isAcceptedAction(intent.getAction()))
      return;

    String senderPackage = intent.getStringExtra(EXTRA_SENDER_PACKAGE);
    if (!isAllowedSenderPackage(senderPackage))
    {
      Log.w(TAG, "Rejected injection from disallowed sender_package=" + senderPackage);
      return;
    }

    String type = intent.getStringExtra("type");
    Log.i(TAG, "Received injection: type=" + type + " action=" + intent.getAction()
        + " sender=" + (senderPackage == null ? "<unset>" : senderPackage));

    // Try to find an active IME — check both docked and floating
    InputConnection ic = null;
    String source = null;

    if (Keyboard2.instance != null)
    {
      ic = Keyboard2.instance.getCurrentInputConnection();
      if (ic != null) source = "Keyboard2";
    }
    if (ic == null && FloatingKeyboard2.instance != null)
    {
      ic = FloatingKeyboard2.instance.getCurrentInputConnection();
      if (ic != null) source = "FloatingKeyboard2";
    }

    if (ic == null)
    {
      Log.w(TAG, "No InputConnection available (Keyboard2.instance="
          + (Keyboard2.instance != null) + " FloatingKeyboard2.instance="
          + (FloatingKeyboard2.instance != null) + ")");
      return;
    }

    Log.d(TAG, "Using InputConnection from " + source);

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
          Log.d(TAG, "Injected key: " + keycode + " meta: " + metaState + " via " + source);
        }
        break;

      case "text":
        String text = intent.getStringExtra("text");
        if (text != null && !text.isEmpty())
        {
          ic.commitText(text, 1);
          Log.d(TAG, "Injected text: " + text + " via " + source);
        }
        break;

      case "scroll":
        int amount = intent.getIntExtra("amount", 0);
        if (amount != 0)
        {
          int keyCode = amount > 0 ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP;
          int count = Math.abs(amount);
          for (int i = 0; i < count && i < 20; i++)
            sendKeyDownUp(ic, keyCode, 0);
          Log.d(TAG, "Injected scroll: " + amount + " via " + source);
        }
        break;

      default:
        Log.w(TAG, "Unknown injection event type: " + type);
    }
  }

  private void sendKeyDownUp(InputConnection ic, int keyCode, int metaState)
  {
    long now = SystemClock.uptimeMillis();
    ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0,
        metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
        KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
    ic.sendKeyEvent(new KeyEvent(now, now + 1, KeyEvent.ACTION_UP, keyCode, 0,
        metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
        KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
  }
}
