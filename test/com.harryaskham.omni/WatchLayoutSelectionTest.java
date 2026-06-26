package com.harryaskham.omni;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Locks the watch-tuned default-layout selection (bd-783380) at the decision
 * level, with no emulator and no Android framework — deterministic plain JUnit.
 *
 * Why this exists: live validation of the rendered keyboard on a headless Wear
 * OS emulator proved untrustworthy (Gboard masquerades as the active IME / the
 * InputMethodService window owner can differ from mCurMethodId), so the two
 * pure decision helpers on {@link Keyboard2} were extracted to be asserted
 * directly. This test pins both, using the shared name constants so it can
 * never drift from the runtime comparison:
 *
 *  - localeDefaultLayoutName(isWatch, subtypeDefaultLayout): RESOURCE-name space.
 *    On a watch, the generic phone QWERTY default (null or latn_qwerty_us) is
 *    swapped for the compact watch layout; an explicit non-phone subtype layout
 *    is preserved; phones are unaffected.
 *  - preferWatchLayout(isWatch, resolvedConfigLayoutName): DISPLAY-name space.
 *    The current_layout precedence — on a watch, when the resolved config slot
 *    is the phone default display name ("QWERTY (US)"), prefer the watch layout;
 *    explicit user layout choices (any other name) still win; never on a phone.
 */
public class WatchLayoutSelectionTest
{
  public WatchLayoutSelectionTest() {}

  // --- localeDefaultLayoutName: resource-name space (latn_*) -----------------

  @Test
  public void watch_noSubtypeDefault_picksWatchLayout()
  {
    assertEquals(Keyboard2.LAYOUT_RES_WATCH,
        Keyboard2.localeDefaultLayoutName(true, null));
  }

  @Test
  public void watch_phoneDefaultSubtype_swapsToWatchLayout()
  {
    assertEquals(Keyboard2.LAYOUT_RES_WATCH,
        Keyboard2.localeDefaultLayoutName(true, Keyboard2.LAYOUT_RES_PHONE_DEFAULT));
  }

  @Test
  public void watch_explicitNonPhoneSubtype_isPreserved()
  {
    // An explicit non-phone subtype default on a watch must NOT be overridden.
    assertEquals("latn_dvorak",
        Keyboard2.localeDefaultLayoutName(true, "latn_dvorak"));
  }

  @Test
  public void phone_noSubtypeDefault_picksPhoneDefault()
  {
    assertEquals(Keyboard2.LAYOUT_RES_PHONE_DEFAULT,
        Keyboard2.localeDefaultLayoutName(false, null));
  }

  @Test
  public void phone_phoneDefaultSubtype_unchanged()
  {
    assertEquals(Keyboard2.LAYOUT_RES_PHONE_DEFAULT,
        Keyboard2.localeDefaultLayoutName(false, Keyboard2.LAYOUT_RES_PHONE_DEFAULT));
  }

  @Test
  public void phone_explicitSubtype_unchanged()
  {
    assertEquals("latn_dvorak",
        Keyboard2.localeDefaultLayoutName(false, "latn_dvorak"));
  }

  // --- preferWatchLayout: display-name space ("QWERTY (US)") ------------------

  @Test
  public void watch_resolvedPhoneDefault_prefersWatch()
  {
    assertTrue(Keyboard2.preferWatchLayout(true, Keyboard2.LAYOUT_DISPLAY_PHONE_DEFAULT));
  }

  @Test
  public void watch_explicitUserLayout_doesNotPreferWatch()
  {
    assertFalse(Keyboard2.preferWatchLayout(true, "Dvorak"));
  }

  @Test
  public void watch_nullResolved_doesNotPreferWatch()
  {
    assertFalse(Keyboard2.preferWatchLayout(true, null));
  }

  @Test
  public void phone_resolvedPhoneDefault_neverPrefersWatch()
  {
    assertFalse(Keyboard2.preferWatchLayout(false, Keyboard2.LAYOUT_DISPLAY_PHONE_DEFAULT));
  }

  @Test
  public void phone_anyLayout_neverPrefersWatch()
  {
    assertFalse(Keyboard2.preferWatchLayout(false, "Dvorak"));
    assertFalse(Keyboard2.preferWatchLayout(false, null));
  }

  // --- guards: the constants the runtime + this test share -------------------

  @Test
  public void sharedConstants_areStable()
  {
    // If anyone renames these, both the runtime comparison and this test move
    // together — that's the point of asserting against the shared constants.
    assertEquals("latn_qwerty_us", Keyboard2.LAYOUT_RES_PHONE_DEFAULT);
    assertEquals("latn_qwerty_watch", Keyboard2.LAYOUT_RES_WATCH);
    assertEquals("QWERTY (US)", Keyboard2.LAYOUT_DISPLAY_PHONE_DEFAULT);
  }
}
