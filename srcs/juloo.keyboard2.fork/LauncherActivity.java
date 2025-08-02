package juloo.keyboard2.fork;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Animatable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

public class LauncherActivity extends Activity implements Handler.Callback
{
  /** Text is replaced when receiving key events. */
  TextView _tryhere_text;
  EditText _tryhere_area;
  /** Periodically restart the animations. */
  List<Animatable> _animations;
  Handler _handler;

  @Override
  public void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.launcher_activity);
    _tryhere_text = (TextView)findViewById(R.id.launcher_tryhere_text);
    _tryhere_area = (EditText)findViewById(R.id.launcher_tryhere_area);
    if (VERSION.SDK_INT >= 28)
      _tryhere_area.addOnUnhandledKeyEventListener(
          this.new Tryhere_OnUnhandledKeyEventListener());
    _handler = new Handler(getMainLooper(), this);
    
    // Check and request storage permissions for directory layout loading
    checkStoragePermissions();
  }
  
  private void checkStoragePermissions() {
    if (VERSION.SDK_INT >= 30) { // Android 11+
      if (!Environment.isExternalStorageManager()) {
        showStoragePermissionDialog();
      }
    } else if (VERSION.SDK_INT >= 23) { // Android 6+
      if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) 
          != PackageManager.PERMISSION_GRANTED) {
        requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 100);
      }
    }
  }
  
  private void showStoragePermissionDialog() {
    new AlertDialog.Builder(this)
      .setTitle("Storage Permission")
      .setMessage("This app's directory layout loading feature requires storage access permission. Would you like to enable it now?")
      .setPositiveButton("Enable", new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
          try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
          } catch (Exception e) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
            startActivity(intent);
          }
        }
      })
      .setNegativeButton("Skip", null)
      .show();
  }
  
  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == 100) {
      if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        Toast.makeText(this, "Storage permission granted", Toast.LENGTH_SHORT).show();
      } else {
        Toast.makeText(this, "Storage permission denied - directory layout loading will not work", Toast.LENGTH_LONG).show();
      }
    }
  }

  @Override
  public void onStart()
  {
    super.onStart();
    _animations = new ArrayList<Animatable>();
    _animations.add(find_anim(R.id.launcher_anim_swipe));
    _animations.add(find_anim(R.id.launcher_anim_round_trip));
    _animations.add(find_anim(R.id.launcher_anim_circle));
    _handler.removeMessages(0);
    _handler.sendEmptyMessageDelayed(0, 500);
  }

  @Override
  public boolean handleMessage(Message _msg)
  {
    for (Animatable anim : _animations)
      anim.start();
    _handler.sendEmptyMessageDelayed(0, 3000);
    return true;
  }

  @Override
  public final boolean onCreateOptionsMenu(Menu menu)
  {
    getMenuInflater().inflate(R.menu.launcher_menu, menu);
    return true;
  }

  @Override
  public final boolean onOptionsItemSelected(MenuItem item)
  {
    if (item.getItemId() == R.id.btnLaunchSettingsActivity)
    {
      Intent intent = new Intent(LauncherActivity.this, SettingsActivity.class);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      startActivity(intent);
    }
    return super.onOptionsItemSelected(item);
  }

  public void launch_imesettings(View _btn)
  {
    startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
  }

  public void launch_imepicker(View v)
  {
    InputMethodManager imm =
      (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
    imm.showInputMethodPicker();
  }

  Animatable find_anim(int id)
  {
    ImageView img = (ImageView)findViewById(id);
    return (Animatable)img.getDrawable();
  }

  @TargetApi(28)
  final class Tryhere_OnUnhandledKeyEventListener implements View.OnUnhandledKeyEventListener
  {
    public boolean onUnhandledKeyEvent(View v, KeyEvent ev)
    {
      // Don't handle the back key
      if (ev.getKeyCode() == KeyEvent.KEYCODE_BACK)
        return false;
      // Key release of modifiers would erase interesting data
      if (KeyEvent.isModifierKey(ev.getKeyCode()))
        return false;
      StringBuilder s = new StringBuilder();
      if (ev.isAltPressed()) s.append("Alt+");
      if (ev.isShiftPressed()) s.append("Shift+");
      if (ev.isCtrlPressed()) s.append("Ctrl+");
      if (ev.isMetaPressed()) s.append("Meta+");
      // s.append(ev.getDisplayLabel());
      String kc = KeyEvent.keyCodeToString(ev.getKeyCode());
      s.append(kc.replaceFirst("^KEYCODE_", ""));
      _tryhere_text.setText(s.toString());
      return false;
    }
  }
}
