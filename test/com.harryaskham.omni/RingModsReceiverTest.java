package com.harryaskham.omni;

import org.junit.Test;
import static org.junit.Assert.*;

public class RingModsReceiverTest
{
  public RingModsReceiverTest() {}

  @Test
  public void allowsHarryaskhamAndTermuxSenders()
  {
    assertTrue(RingModsReceiver.isAllowedSenderPackage("com.harryaskham.omni"));
    assertTrue(RingModsReceiver.isAllowedSenderPackage("com.harryaskham.ringmods"));
    assertTrue(RingModsReceiver.isAllowedSenderPackage("com.harryaskham.nodterm"));
    // omni-cli's canonical Android-transport sender_package (omni-cli bd-17026f);
    // omni-cli is a host Rust binary so it sets this EXTRA explicitly. Lock it so
    // the cross-project INJECT contract can't silently regress.
    assertTrue(RingModsReceiver.isAllowedSenderPackage("com.harryaskham.omnicli"));
    assertTrue(RingModsReceiver.isAllowedSenderPackage("com.termux"));
    assertTrue(RingModsReceiver.isAllowedSenderPackage("com.termux.api"));
  }

  @Test
  public void rejectsOtherSenders()
  {
    assertFalse(RingModsReceiver.isAllowedSenderPackage("com.example.evil"));
    assertFalse(RingModsReceiver.isAllowedSenderPackage("org.harryaskham.omni"));
    assertFalse(RingModsReceiver.isAllowedSenderPackage("com.harryaskhamX.omni"));
    assertFalse(RingModsReceiver.isAllowedSenderPackage("net.termux"));
    assertFalse(RingModsReceiver.isAllowedSenderPackage("com.termuxX"));
  }

  @Test
  public void unsetSenderIsAcceptedForBackwardCompat()
  {
    // Android does not expose a reliable broadcast sender identity, so callers
    // that do not declare a sender_package (e.g. the legacy ring-mods path) are
    // accepted.
    assertTrue(RingModsReceiver.isAllowedSenderPackage(null));
    assertTrue(RingModsReceiver.isAllowedSenderPackage(""));
  }

  @Test
  public void acceptsBothOmniAndLegacyActions()
  {
    assertTrue(RingModsReceiver.isAcceptedAction("com.harryaskham.omni.INJECT"));
    assertTrue(RingModsReceiver.isAcceptedAction("com.ringmods.HID_EVENT"));
    assertEquals("com.harryaskham.omni.INJECT", RingModsReceiver.ACTION);
    assertEquals("com.ringmods.HID_EVENT", RingModsReceiver.LEGACY_ACTION);
  }

  @Test
  public void rejectsUnknownActions()
  {
    assertFalse(RingModsReceiver.isAcceptedAction("com.example.OTHER"));
    assertFalse(RingModsReceiver.isAcceptedAction(null));
  }
}
