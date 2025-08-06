# Unexpected Keyboard (Experimental Fork)

*Note: This fork was entirely undertaken by Claude Code as part of my testing of CC - after a few false starts, humblingly, all works well enough for me to daily-drive. I've made a point of not writing a single line of this myself, besides prompting CC and parts of this README. Unless extremely brave I'd suggest not relying on this fork which is extremely tailored to how I use UK, and instead following mainline development.*

A personal fork of [Unexpected Keyboard](https://github.com/Julow/Unexpected-Keyboard) with additional features I've wanted as I've gradually increased the amount of code I'm writing on my phone (essentially testing out LLMs as a way of achiving "arbitrary dotfiles" for adding customisation to apps that don't otherwise expose it)

## Features Added

### Floating IME
- **Floating mode**: Detach keyboard from bottom of screen as a draggable floating window
  - No longer takes up screen real estate, apps flow to use the full space
  - Floating and docked modes are separate IMEs, switching back-and-forth is via system input toggle
- **Resizing**: On-the-fly resizing of the keyboard with remembered position and size for landscape and portrait
- **Fast toggle**: tapping a gap on the surface disables the keyboard, re-enabled by a small handle, passing through touches and swipes
  - Indended for use with split layouts, makes landscape mode much more usable

Floating resizable landscape with split layout:

<img src="img/split.jpg" width="720" />

Floating resizable portrait with ortho layout:

<img src="img/float.jpg" height="720" />

### Layout Loading from Storage
- Load / refresh XML keyboard layouts from device storage
- Pairs with a [Nix module](https://github.com/harryaskham/collective-public/blob/main/modules/agnostic/unexpected-keyboard/default.nix) for generating XML layouts from within Nix-on-Droid that are copied to e.g. `/storage/emulated/0/shared/unexpected_keyboard` on activation.

### Misc
- **Nord colorscheme**: Adds Nord theme as native
- **Debug mode**: On-device debug mode exposing select logs via toasts (largely for testing further UK development with Claude Code from my device when I'm remote and can't access `adb logcat`, but can still build UK remotely and ship the APK to my device via e.g. `scp`)

## Build

Added a `make {debug,release,clean}` for Claude to use to build on Nix-enabled systems, since Claude kept forgetting how to build when the conversation was compacted and `shell.nix` wasn't working when I was testing.

## TODO

### Features & Functionality

- Entire app config is possible to drive from storage as e.g. `.unexpected_keyboard.json`, not just layouts.
- Already added a `toggle_floating` key action but this just opens the IME switcher. Have it actually select the toggled floating or docked forked UK input method.
- Increase the touch height for drag/resize/re-enable handles, without increasing the visual size. Right now it is quite hard to consistently drag or resize using the handle touch area. Care is required here as previous attempts to increase the touch area without changing the appearance failed, and the handles ended up looking too tall.
- Enable re-ordering of loaded layouts in the settings list to more easily support fwd/bwd layer switching
- Support a `switch_to_layout_<layoutName>` mappable key action which switches tthat layout if it exists in the layout list, similar to e.g. existing `switch_numeric` action. The `layoutName` in the code should be the `name` field of the keyboard with spaces replaced with underscores, and only characters in `[-_a-zA-Z0-9]`.
- Make the keyboard-enable toggle in the top-right always present (right now it only appears in disabled mode after touching a gap and serves to enable the keyboard), with the behaviour of toggling the enabled state of the rest of the keyboard. Touching a keyboard gap would still disable the keyboard, but having this toggle present always would enable temporary disabling of layouts that do not have an easily-touchable gap.
- Add a `toggle_persistence` key action similar to `toggle_floating`, which toggles a new setting for 'Enable Persistence'. When Persistence is enabled, the keyboard essentially ignores the OS-level signal to dismiss the input method, meaning the keyboard will remain open even if a text field is not focused. Ensure that the setting in the app settings is correctly synced with the state as toggled by the keyboard button, such that it can be set either by mapping the key, or by editing the config. This setting would apply only in floating mode.
- Make it possible to enter system shortcuts using the keyboard (i.e. alt-tab for app switching, meta-h to show the launcher, etc, as supported natively by Android for hardware keyboards)

### Appearance
- Consistent visual appearance for drag/resize/re-enable handles (right now the widths are different and the drag handle first appears with its touched color until it is tapped once)
- Make the drag/resize/re-enable handles 80% opacity when the keyboard is active, and lower the opacity of the drag and resize handles along with the rest of the keyboard when the keyboard is disabled (the re-enable handle remains 80% opacity always)

### Bugs
- Resize is somewhat janky due to resize-units being in height/width percentage-based units; resize occurs at a different speed to the drag speed right now.
  - Add support for setting the keyboard height and width in pixels rather than screen % would enable precise resize, with the top-right resize handle acting both to shrink / grow the width and height, but also to drag the keyboard such that its position moves with the resize handle, so that the handle stays underneath the resize-drag motion.
- Stop keyboard from being possible to move logically off-screen - the keyboard always stops at the edges visually, but it's possible for its modelled position to move beyond the edge, such that dragging it back can presently require an additional initial drag (that appears to be doing nothing) to compensate for the off-screen amount, after which the keyboard starts to move.
- Loading layouts from storage adds many duplicates; add a "remove all" option for layouts and ensure refreshing layouts only re-loads those loaded layouts that are in the existing list and that also exist on-disk.
- Remove leftover "floating mode" toggle in settings (now handled via system IME switcher)
  - Ensure that no important behaviour was tied to this legacy setting and that floating mode is entirely contingent on whether it's the selected input method in the IME switcher.
