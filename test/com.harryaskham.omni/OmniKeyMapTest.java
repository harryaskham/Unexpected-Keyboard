package com.harryaskham.omni;

import android.view.KeyEvent;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Locks the canonical semantic-name -> Android keycode/meta mapping used by the
 * Wear OS network/relay injection front-end (bd-783380). Mirrors the key names
 * in the shared Omni wire (omni-cli src/wire.rs / apple-utils OmniCore) so the
 * Android device-boundary mapping cannot silently drift from the wire.
 */
public class OmniKeyMapTest
{
  public OmniKeyMapTest() {}

  @Test
  public void mapsLettersAndDigitsCaseInsensitively()
  {
    assertEquals(KeyEvent.KEYCODE_A, OmniKeyMap.keycode("a"));
    assertEquals(KeyEvent.KEYCODE_G, OmniKeyMap.keycode("G"));
    assertEquals(KeyEvent.KEYCODE_Z, OmniKeyMap.keycode("z"));
    assertEquals(KeyEvent.KEYCODE_0, OmniKeyMap.keycode("0"));
    assertEquals(KeyEvent.KEYCODE_9, OmniKeyMap.keycode("9"));
  }

  @Test
  public void mapsDocumentedNamedKeys()
  {
    assertEquals(KeyEvent.KEYCODE_DEL, OmniKeyMap.keycode("backspace"));
    assertEquals(KeyEvent.KEYCODE_FORWARD_DEL, OmniKeyMap.keycode("delete"));
    assertEquals(KeyEvent.KEYCODE_ENTER, OmniKeyMap.keycode("enter"));
    assertEquals(KeyEvent.KEYCODE_TAB, OmniKeyMap.keycode("tab"));
    assertEquals(KeyEvent.KEYCODE_ESCAPE, OmniKeyMap.keycode("esc"));
    assertEquals(KeyEvent.KEYCODE_ESCAPE, OmniKeyMap.keycode("escape"));
    assertEquals(KeyEvent.KEYCODE_SPACE, OmniKeyMap.keycode("space"));
    assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, OmniKeyMap.keycode("left"));
    assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, OmniKeyMap.keycode("right"));
    assertEquals(KeyEvent.KEYCODE_DPAD_UP, OmniKeyMap.keycode("up"));
    assertEquals(KeyEvent.KEYCODE_DPAD_DOWN, OmniKeyMap.keycode("down"));
    assertEquals(KeyEvent.KEYCODE_MOVE_HOME, OmniKeyMap.keycode("home"));
    assertEquals(KeyEvent.KEYCODE_MOVE_END, OmniKeyMap.keycode("end"));
  }

  @Test
  public void returnsUnknownForUnrecognizedOrNull()
  {
    assertEquals(KeyEvent.KEYCODE_UNKNOWN, OmniKeyMap.keycode("nope"));
    assertEquals(KeyEvent.KEYCODE_UNKNOWN, OmniKeyMap.keycode(""));
    assertEquals(KeyEvent.KEYCODE_UNKNOWN, OmniKeyMap.keycode(null));
  }

  @Test
  public void mapsModifierNamesToMetaBits()
  {
    assertEquals(KeyEvent.META_CTRL_ON, OmniKeyMap.metaBit("ctrl"));
    assertEquals(KeyEvent.META_CTRL_ON, OmniKeyMap.metaBit("control"));
    assertEquals(KeyEvent.META_ALT_ON, OmniKeyMap.metaBit("alt"));
    assertEquals(KeyEvent.META_SHIFT_ON, OmniKeyMap.metaBit("shift"));
    assertEquals(KeyEvent.META_META_ON, OmniKeyMap.metaBit("meta"));
    assertTrue(OmniKeyMap.isModifier("ctrl"));
    assertFalse(OmniKeyMap.isModifier("a"));
    assertFalse(OmniKeyMap.isModifier(null));
    assertEquals(0, OmniKeyMap.metaBit("a"));
  }
}
