package nirhart.dripmysteps;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.api.GoogleApiClient;

public class MainActivity extends Activity implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

	private static final String SHOW_DIALOG = "show_dialog";
	private static final int REQUEST_OAUTH = 1;
	private static final String AUTH_PENDING = "auth_state_pending";
	private static final String DIALOG_SHOWN = "dialog_shown";
	private AuthHelper authHelper;
	private boolean dialogShown = false;
	private boolean authInProgress = false;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		PreferenceManager.setDefaultValues(this, R.xml.prefs_fragment, false);
		authHelper = new AuthHelper();
		authHelper.buildFitnessClient(this, this, this);

		if (savedInstanceState == null) {
			final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
			if (prefs.getBoolean(SHOW_DIALOG, true)) {
				showGoogleFitDialog(prefs);
			} else {
				dialogShowed();
			}
		} else {
			authInProgress = savedInstanceState.getBoolean(AUTH_PENDING);
			dialogShown = savedInstanceState.getBoolean(DIALOG_SHOWN);
		}
	}

	private void showGoogleFitDialog(final SharedPreferences prefs) {
		new AlertDialog.Builder(this)
				.setTitle(R.string.dialog_title)
				.setMessage(R.string.dialog_message)
				.setNegativeButton(R.string.dont_show_again, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						prefs.edit().putBoolean(SHOW_DIALOG, false).apply();
						dialogShowed();
					}
				})
				.setPositiveButton(R.string.dialog_button, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialogShowed();
					}
				}).setOnCancelListener(new DialogInterface.OnCancelListener() {
			@Override
			public void onCancel(DialogInterface dialog) {
				dialogShowed();
			}
		}).show();
	}

	private void dialogShowed() {
		dialogShown = true;
		authHelper.start();
	}

	@Override
	protected void onStart() {
		super.onStart();
		if (dialogShown) {
			authHelper.start();
		}
	}

	@Override
	protected void onStop() {
		super.onStop();
		authHelper.stop();
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (requestCode == REQUEST_OAUTH) {
			authInProgress = false;
			if (resultCode == RESULT_OK) {
				// Make sure the app is not already connected or attempting to connect
				if (!authHelper.isConnecting() && !authHelper.isConnected()) {
					authHelper.connect();
				}
			} else {
				Toast.makeText(MainActivity.this, R.string.connection_failed, Toast.LENGTH_LONG).show();
				finish();
			}
		}
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putBoolean(AUTH_PENDING, authInProgress);
		outState.putBoolean(DIALOG_SHOWN, dialogShown);
	}

	@Override
	public void onConnected(Bundle bundle) {
		Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
		intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(MainActivity.this, DripWallpaperService.class));
		startActivity(intent);
		finish();
	}

	@Override
	public void onConnectionSuspended(int i) {
		Toast.makeText(MainActivity.this, R.string.connection_failed, Toast.LENGTH_LONG).show();
		finish();
	}

	@Override
	public void onConnectionFailed(ConnectionResult result) {
		if (!result.hasResolution()) {
			GooglePlayServicesUtil.getErrorDialog(result.getErrorCode(), MainActivity.this, 0).show();
			return;
		}

		if (!authInProgress) {
			try {
				authInProgress = true;
				result.startResolutionForResult(MainActivity.this, REQUEST_OAUTH);
			} catch (IntentSender.SendIntentException e) {
				Toast.makeText(MainActivity.this, R.string.connection_failed, Toast.LENGTH_LONG).show();
				finish();
			}
		}
	}
}
