package com.harryaskham.omni;

import android.view.KeyEvent;

/**
 * OmniKeyMap: the canonical semantic-name -> Android {@link KeyEvent} mapping for
 * the Omni injection contract (android-utils bd-783380).
 *
 * The cross-platform Omni injection wire (NDJSON InjectionCommand; source of
 * truth apple-utils OmniCore/InjectionCommand.swift and omni-cli src/wire.rs,
 * schema bead bd-44dac8) carries semantic key NAMES (e.g. "enter", "backspace")
 * and combo strings (e.g. "ctrl,a,g"). On Android there are two paths:
 *
 *  - adb / local broadcast path: omni-cli maps names -> keycodes CLI-side and
 *    posts the native com.harryaskham.omni.INJECT broadcast with a numeric
 *    keycode + meta_state. {@link RingModsReceiver} consumes that directly and
 *    does NOT need this class.
 *  - Wear OS network / relay path (PUSH/LISTEN front-ends): the device receives
 *    the bare NDJSON InjectionCommand and must map names -> keycodes at the
 *    device boundary itself. That mapping lives here, so the network path stays
 *    byte-compatible with omni-cli's broadcast path.
 *
 * Keep this table diffed against omni-cli's transport key table (src/wire.rs);
 * names cover the documented vocab plus single ASCII letters/digits.
 */
public final class OmniKeyMap
{
  private OmniKeyMap() {}

  /**
   * Map a semantic key name (case-insensitive) to an Android KeyEvent keycode.
   * Returns {@link KeyEvent#KEYCODE_UNKNOWN} (0) when unrecognized.
   */
  public static int keycode(String name)
  {
    if (name == null)
      return KeyEvent.KEYCODE_UNKNOWN;
    String n = name.trim().toLowerCase();
    if (n.length() == 1)
    {
      char c = n.charAt(0);
      if (c >= 'a' && c <= 'z')
        return KeyEvent.KEYCODE_A + (c - 'a');
      if (c >= '0' && c <= '9')
        return KeyEvent.KEYCODE_0 + (c - '0');
    }
    switch (n)
    {
      case "backspace": case "bs":        return KeyEvent.KEYCODE_DEL;
      case "delete":    case "del":       return KeyEvent.KEYCODE_FORWARD_DEL;
      case "enter":     case "return":    return KeyEvent.KEYCODE_ENTER;
      case "tab":                         return KeyEvent.KEYCODE_TAB;
      case "esc":       case "escape":    return KeyEvent.KEYCODE_ESCAPE;
      case "space":                       return KeyEvent.KEYCODE_SPACE;
      case "left":                        return KeyEvent.KEYCODE_DPAD_LEFT;
      case "right":                       return KeyEvent.KEYCODE_DPAD_RIGHT;
      case "up":                          return KeyEvent.KEYCODE_DPAD_UP;
      case "down":                        return KeyEvent.KEYCODE_DPAD_DOWN;
      case "home":                        return KeyEvent.KEYCODE_MOVE_HOME;
      case "end":                         return KeyEvent.KEYCODE_MOVE_END;
      case "pageup":    case "pgup":      return KeyEvent.KEYCODE_PAGE_UP;
      case "pagedown":  case "pgdn":      return KeyEvent.KEYCODE_PAGE_DOWN;
      default:                            return KeyEvent.KEYCODE_UNKNOWN;
    }
  }

  /** True when the token names a modifier (ctrl/alt/shift/meta/fn and aliases). */
  public static boolean isModifier(String name)
  {
    return metaBit(name) != 0;
  }

  /**
   * Map a modifier name to its KeyEvent meta_state bit, or 0 when the token is
   * not a modifier.
   */
  public static int metaBit(String name)
  {
    if (name == null)
      return 0;
    switch (name.trim().toLowerCase())
    {
      case "ctrl":  case "control":               return KeyEvent.META_CTRL_ON;
      case "alt":   case "option":                return KeyEvent.META_ALT_ON;
      case "shift":                               return KeyEvent.META_SHIFT_ON;
      case "meta":  case "cmd": case "super": case "win": return KeyEvent.META_META_ON;
      case "fn":                                  return KeyEvent.META_FUNCTION_ON;
      default:                                    return 0;
    }
  }
}
