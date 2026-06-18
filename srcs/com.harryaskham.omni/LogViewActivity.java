package com.harryaskham.omni;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * In-app, copyable view of Omni's recent log buffer (see {@link Logs}) so the
 * operator can grab logs to file bug reports without adb logcat. Built
 * programmatically to avoid coupling to a particular settings theme.
 */
public class LogViewActivity extends Activity
{
  private TextView _logView;

  @Override
  protected void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    setTitle("Omni logs");

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);

    LinearLayout buttons = new LinearLayout(this);
    buttons.setOrientation(LinearLayout.HORIZONTAL);
    buttons.addView(button("Copy", v -> copyLogs()), equalWeight());
    buttons.addView(button("Share", v -> shareLogs()), equalWeight());
    buttons.addView(button("Refresh", v -> loadLogs()), equalWeight());
    buttons.addView(button("Clear", v -> { Logs.clearBuffer(); loadLogs(); }), equalWeight());
    root.addView(buttons);

    _logView = new TextView(this);
    _logView.setTextIsSelectable(true);
    _logView.setTypeface(Typeface.MONOSPACE);
    _logView.setTextSize(11f);
    _logView.setPadding(16, 16, 16, 16);

    ScrollView scroll = new ScrollView(this);
    scroll.addView(_logView);
    root.addView(scroll, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

    setContentView(root);
  }

  @Override
  protected void onResume()
  {
    super.onResume();
    loadLogs();
  }

  private Button button(String text, View.OnClickListener onClick)
  {
    Button b = new Button(this);
    b.setText(text);
    b.setOnClickListener(onClick);
    return b;
  }

  private LinearLayout.LayoutParams equalWeight()
  {
    return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
  }

  private void loadLogs()
  {
    String logs = Logs.getRecentLogsText();
    _logView.setText(logs.isEmpty() ? "(no logs captured yet)" : logs);
  }

  private void copyLogs()
  {
    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
    if (cm != null)
    {
      cm.setPrimaryClip(ClipData.newPlainText("Omni logs", Logs.getRecentLogsText()));
      Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show();
    }
  }

  private void shareLogs()
  {
    Intent i = new Intent(Intent.ACTION_SEND);
    i.setType("text/plain");
    i.putExtra(Intent.EXTRA_SUBJECT, "Omni logs");
    i.putExtra(Intent.EXTRA_TEXT, Logs.getRecentLogsText());
    startActivity(Intent.createChooser(i, "Share Omni logs"));
  }
}
