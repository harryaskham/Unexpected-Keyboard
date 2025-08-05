# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a personal fork of Unexpected Keyboard - an Android virtual keyboard app with significant floating keyboard enhancements. The project implements a dual InputMethodService (IME) architecture with both traditional docked and innovative floating keyboard modes.

## Build System & Commands

**Primary build commands:**
- `make debug` - Build debug APK using Nix environment
- `make release` - Build release APK  
- `make clean` - Clean build artifacts

**Test commands:**
- `nix-shell -p openjdk17 --command "./gradlew test"` - Run unit tests
- `nix-shell -p openjdk17 --command "./gradlew testDebug"` - Run debug tests only

**Development environment:**
- Uses Nix for reproducible builds with OpenJDK 17
- Android SDK located at `./android-sdk` 
- Gradle wrapper handles Android build tasks
- Package name: `juloo.keyboard2.fork`

## Architecture Overview

### Dual IME System
The project implements two separate InputMethodService implementations:

1. **`Keyboard2.java`** - Traditional docked keyboard IME
   - Standard Android keyboard behavior
   - Bottom-docked positioning
   - Full InputConnection handling

2. **`FloatingKeyboard2.java`** - Floating keyboard IME  
   - Detached floating window using `TYPE_APPLICATION_OVERLAY`
   - Draggable, resizable with touch handles
   - Advanced passthrough mode for gap touches
   - Separate overlay windows for UI elements

Both IMEs are registered in `AndroidManifest.xml` and can be switched between via system IME picker or programmatic switching.

### Key Components

**Configuration System (`Config.java`)**
- Centralized settings management via SharedPreferences
- Handles layout selection, appearance, behavior settings
- Floating-specific configs: `floatingKeyboardHeightPercent`, `floatingKeyboardWidthPercent`
- Dynamic screen size and orientation handling

**Layout System** 
- XML-based layouts in `srcs/layouts/` (80+ keyboard layouts)
- Dynamic layout loading from device storage directories
- KeyboardData parsing and caching
- Layout switching based on locale/user selection

**Rendering (`Keyboard2View.java`)**
- Custom View with Canvas-based key rendering
- Touch event handling and key detection
- Theme support with custom styling
- Scaling support for floating mode

**Touch Handling Architecture**
- Multi-layered touch event processing
- Gap detection using `getKeyAtPosition()` 
- Floating mode: Container intercepts touches before keyboard view
- Passthrough mode: Dynamic window flag manipulation

### Floating Keyboard Specific Architecture

**Window Management**
- Primary floating window: Contains keyboard + drag/resize handles
- Secondary overlay window: Toggle button for passthrough mode
- WindowManager.LayoutParams manipulation for behavior control

**Touch Passthrough System**
1. Gap detection in `onInterceptTouchEvent()`
2. Mode switching: Normal ↔ Passthrough
3. Separate overlay ensures toggle button remains touchable
4. `FLAG_NOT_TOUCHABLE` allows touches to reach underlying apps

**Handle System**
- **Drag Handle**: Top-center, enables window positioning  
- **Resize Handle**: Top-right, modifies keyboard dimensions via config system
- **Toggle Handle**: Top-left, exits passthrough mode (separate window)
- All handles use Nord theme colors: `0xFF5E81AC` inactive, `0xFFD8DEE9` active

## Critical Implementation Details

### Floating Window Lifecycle
- `createFloatingKeyboard()` - Sets up overlay with handles
- `removeFloatingKeyboard()` - Cleanup including separate windows
- Position persistence via SharedPreferences
- Proper lifecycle handling in `onFinishInputView()`

### IME Switching Pattern
```java
InputMethodManager imm = get_imm();
String targetImeId = getPackageName() + "/.FloatingKeyboard2"; 
imm.setInputMethod(getConnectionToken(), targetImeId);
```

### Configuration-Based Scaling
Floating keyboard uses config-driven approach rather than view scaling:
- Dimensions set via `floatingKeyboardWidthPercent`/`floatingKeyboardHeightPercent`  
- `refreshFloatingKeyboard()` triggers full redraw with new dimensions
- Resize handles modify config values, not view transform

### Theme Integration
- Nord colorscheme: Arctic-inspired blues and grays
- Custom `Theme.Computed` constructor with floating mode parameter
- Handle colors consistent across all floating UI elements
- Opacity controls for passthrough mode visual feedback

## Testing

Unit tests focus on floating keyboard logic isolation:
- `FloatingKeyboardTest.java` - Core floating functionality without Android deps
- Helper classes abstract Android-specific behavior
- Tests cover scaling, touch regions, handle positioning, window configuration

## Important Files

**Core IME Implementation:**
- `srcs/juloo.keyboard2.fork/Keyboard2.java` - Docked keyboard IME
- `srcs/juloo.keyboard2.fork/FloatingKeyboard2.java` - Floating keyboard IME

**Configuration & Data:**
- `srcs/juloo.keyboard2.fork/Config.java` - Settings and configuration management
- `srcs/juloo.keyboard2.fork/KeyboardData.java` - Layout data structures
- `srcs/layouts/*.xml` - Keyboard layout definitions

**UI & Rendering:**
- `srcs/juloo.keyboard2.fork/Keyboard2View.java` - Keyboard rendering and touch handling
- `srcs/juloo.keyboard2.fork/Theme.java` - Styling and appearance

**Build & Manifest:**
- `AndroidManifest.xml` - Dual IME service registration, permissions
- `build.gradle` - Android build configuration
- `Makefile` - Nix-based build commands

## Common Patterns

**Adding new floating features:**
1. Extend `ResizableFloatingContainer` inner class
2. Add config parameters in `Config.java`
3. Update settings XML in `res/xml/settings.xml`
4. Handle lifecycle in `createFloatingKeyboard()`/`removeFloatingKeyboard()`

**Touch event handling:**
1. Container-level interception in `onInterceptTouchEvent()`
2. Handle-specific touch listeners for UI elements  
3. Gap detection using keyboard view's `getKeyAtPosition()`
4. Mode-specific behavior in touch event processing

**Window management:**
1. Separate windows for different UI concerns
2. Proper cleanup in removal methods
3. Position/state persistence via SharedPreferences
4. Flag manipulation for behavior control