package com.harryaskham.omni;

import android.util.Log;
import android.util.LogPrinter;
import android.view.inputmethod.EditorInfo;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

public final class Logs
{
  static final String TAG = "com.harryaskham.omni";

  /**
   * Bounded in-memory ring buffer of recent log lines, surfaced by the in-app
   * LogViewActivity so the operator can copy logs to file bug reports without
   * adb logcat. Always captures (independent of the debug-log toggle) so there
   * is something to copy when something goes wrong.
   */
  private static final int BUFFER_CAPACITY = 600;
  private static final ArrayDeque<String> _buffer = new ArrayDeque<>(BUFFER_CAPACITY);
  private static final SimpleDateFormat _ts =
    new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

  static LogPrinter _debug_logs = null;

  public static void set_debug_logs(boolean d)
  {
    _debug_logs = d ? new LogPrinter(Log.DEBUG, TAG) : null;
  }

  private static void record(String level, String tag, String msg)
  {
    String line = _ts.format(new Date()) + " " + level + "/" + tag + ": " + msg;
    synchronized (_buffer)
    {
      while (_buffer.size() >= BUFFER_CAPACITY)
        _buffer.pollFirst();
      _buffer.addLast(line);
    }
  }

  /** General info log: records to the buffer and emits to logcat. */
  public static void log(String tag, String msg)
  {
    Log.i(tag, msg);
    record("I", tag, msg);
  }

  /** Warning log: records to the buffer and emits to logcat. */
  public static void warn(String tag, String msg)
  {
    Log.w(tag, msg);
    record("W", tag, msg);
  }

  /** Snapshot of the recent log buffer, oldest first, newline-joined. */
  public static String getRecentLogsText()
  {
    StringBuilder sb = new StringBuilder();
    synchronized (_buffer)
    {
      for (String line : _buffer)
        sb.append(line).append('\n');
    }
    return sb.toString();
  }

  public static int bufferedLineCount()
  {
    synchronized (_buffer) { return _buffer.size(); }
  }

  public static void clearBuffer()
  {
    synchronized (_buffer) { _buffer.clear(); }
  }

  public static void debug_startup_input_view(EditorInfo info, Config conf)
  {
    if (_debug_logs == null)
      return;
    info.dump(_debug_logs, "");
    if (info.extras != null)
      _debug_logs.println("extras: "+info.extras.toString());
    _debug_logs.println("swapEnterActionKey: "+conf.swapEnterActionKey);
    _debug_logs.println("actionLabel: "+conf.actionLabel);
  }

  public static void debug_config_migration(int from_version, int to_version)
  {
    debug("Migrating config version from " + from_version + " to " + to_version);
  }

  public static void debug(String s)
  {
    record("D", TAG, s);
    if (_debug_logs != null)
      _debug_logs.println(s);
  }

  public static void exn(String msg, Exception e)
  {
    record("E", TAG, msg + ": " + e);
    Log.e(TAG, msg, e);
  }

  public static void trace()
  {
    if (_debug_logs != null)
      _debug_logs.println(Log.getStackTraceString(new Exception()));
  }
}
