package juloo.keyboard2.fork;

import android.util.Log;
import java.util.List;

/** 
 * Shared utility methods for layout switching functionality.
 * Contains the most up-to-date implementation from FloatingKeyboard2.
 */
public class LayoutSwitchingUtils {
  
  private static final String TAG = "juloo.keyboard2.fork";
  
  public static void switchToLayoutByName(String layoutName, Config config, LayoutSwitcher switcher) {
    if (layoutName == null || layoutName.isEmpty()) {
      Log.w(TAG, "Cannot switch to layout: name is null or empty");
      return;
    }

    Log.d(TAG, "Switching to layout by name: " + layoutName);
    Log.d(TAG, "Available layouts (" + config.layouts.size() + " total):");
    for (int j = 0; j < config.layouts.size(); j++) {
      KeyboardData layoutDebug = config.layouts.get(j);
      if (layoutDebug != null && layoutDebug.name != null) {
        Log.d(TAG, "  [" + j + "] '" + layoutDebug.name + "'");
      }
    }

    // Find matching layout in available layouts
    for (int i = 0; i < config.layouts.size(); i++) {
      KeyboardData layout = config.layouts.get(i);
      if (layout != null && layout.name != null) {
        // Normalize layout names: convert spaces to underscores, remove non-alphanumeric chars, lowercase
        String normalizedLayoutName = normalizeLayoutName(layout.name);
        String normalizedTargetName = normalizeLayoutName(layoutName);
        
        Log.d(TAG, "Comparing '" + normalizedTargetName + "' with '" + normalizedLayoutName + "' (original: '" + layout.name + "')");
        
        // Use case-insensitive comparison since we're no longer lowercasing
        if (normalizedLayoutName.equalsIgnoreCase(normalizedTargetName)) {
          Log.d(TAG, "Found exact match at index " + i + ": " + layout.name);
          switcher.setTextLayout(i);
          return;
        }
      }
    }

    Log.w(TAG, "Layout not found: " + layoutName + " (normalized: " + normalizeLayoutName(layoutName) + ")");
  }
  
  public static String normalizeLayoutName(String name) {
    if (name == null) return "";
    
    // Don't convert to lowercase, just replace spaces with underscores and remove non-alphanumeric chars (except underscores)
    String step1 = name.replaceAll("\\s+", "_");
    String step2 = step1.replaceAll("[^a-zA-Z0-9_]", "");
    
    Log.d(TAG, "Layout normalization: '" + name + "' -> '" + step1 + "' -> '" + step2 + "'");
    
    return step2;
  }
  
  public static String mapSymbolToLayout(String symbol, Config config) {
    // Try to intelligently map symbols to layouts based on available layouts
    // This is a workaround for incorrectly defined keys
    
    // First, try to find layouts that might correspond to the symbol
    for (int i = 0; i < config.layouts.size(); i++) {
      KeyboardData layout = config.layouts.get(i);
      if (layout != null && layout.name != null) {
        String layoutName = layout.name;
        
        // Simple mapping strategies:
        // 1. If symbol contains directional arrows, map to splitPG (page keys) layouts
        if ("⟺".equals(symbol) || "↔".equals(symbol) || "⥺".equals(symbol)) {
          if (layoutName.contains("splitPG") || layoutName.contains("splitPE")) {
            return layoutName;
          }
        }
        
        // 2. For left/right arrows, try left/right layouts  
        if ("←".equals(symbol) || "⟵".equals(symbol)) {
          if (layoutName.contains("lefty") || layoutName.contains("left")) {
            return layoutName;
          }
        }
        
        if ("→".equals(symbol) || "⟶".equals(symbol)) {
          if (layoutName.contains("righty") || layoutName.contains("right")) {
            return layoutName;
          }
        }
      }
    }
    
    // If no specific mapping found, try the first layout that seems like a variant
    for (int i = 0; i < config.layouts.size(); i++) {
      KeyboardData layout = config.layouts.get(i);
      if (layout != null && layout.name != null && layout.name.contains("(")) {
        return layout.name; // Return first layout with parentheses (likely a variant)
      }
    }
    
    return null; // No mapping found
  }
  
  public static void handleEventKeyWithValue(KeyValue keyValue, Config config, LayoutSwitcher switcher) {
    if (keyValue.getEvent() == KeyValue.Event.SWITCH_TO_LAYOUT) {
      String layoutName = keyValue.getLayoutName();
      String keyString = keyValue.getString();
      Log.d(TAG, "SWITCH_TO_LAYOUT event - layoutName: '" + layoutName + "', keyString: '" + keyString + "'");
      Log.d(TAG, "KeyValue details - kind: " + keyValue.getKind() + ", event: " + keyValue.getEvent() + ", flags: " + keyValue.getFlags());
      
      // If layoutName is just a symbol, this means we got the visual symbol instead of the layout name
      // This happens when keys are defined incorrectly - try to work around it
      if (layoutName.length() <= 2 && !Character.isLetterOrDigit(layoutName.charAt(0))) {
        Log.w(TAG, "Layout name is a symbol '" + layoutName + "', trying to map to layout");
        
        // Try to map common symbols to likely layouts based on available layouts
        String targetLayout = mapSymbolToLayout(layoutName, config);
        if (targetLayout != null) {
          Log.d(TAG, "Mapped symbol '" + layoutName + "' to layout '" + targetLayout + "'");
          switchToLayoutByName(targetLayout, config, switcher);
          return;
        } else {
          Log.e(TAG, "Cannot map symbol '" + layoutName + "' to any layout. Available layouts listed above.");
          return;
        }
      }
      
      switchToLayoutByName(layoutName, config, switcher);
    }
  }
  
  /** Interface for objects that can switch layouts */
  public interface LayoutSwitcher {
    void setTextLayout(int layoutIndex);
  }
}