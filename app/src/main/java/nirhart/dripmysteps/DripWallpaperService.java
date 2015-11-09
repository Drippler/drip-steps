package nirhart.dripmysteps;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.service.wallpaper.WallpaperService;
import android.util.TypedValue;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class DripWallpaperService extends WallpaperService implements SharedPreferences.OnSharedPreferenceChangeListener {

	final static long STEPS_CHECK_INTERVAL = TimeUnit.MINUTES.toMillis(1);
	private final static int FOLLOW_SCREEN_ROTATION_FACTOR = 40; // 1 means follow immediately, bigger number means slowly follow the screen rotation
	private static final double MAX_ANGLE_CHANGE_IN_FRAME = 1.5;
	private static final String TIDE_LEVEL = "tide_level";
	private static final String STEPS_GOAL = "steps_goal";
	private static final String STEPS_GOAL_DEFAULT = "7500";
	private static final String LOW_LEVEL_DEFAULT = "0";
	private static final String TIDE_LEVEL_DEFAULT = "3";
	private static final String LOW_LEVEL = "low_level";
	private final ExecutorService ex;
	private int stepsGoal;
	private float low, tide;
	private long lastStepsCheck;
	private Display display;

	public DripWallpaperService() {
		// Single thread pool with 1 min time of idle thread
		this.ex = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<Runnable>());
	}

	@Override
	public Engine onCreateEngine() {

		this.display = ((WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		prefs.registerOnSharedPreferenceChangeListener(this);

		// Tide and low will effect the y offset animation of the wave
		tide = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, Float.parseFloat(prefs.getString(TIDE_LEVEL, TIDE_LEVEL_DEFAULT)), getResources().getDisplayMetrics());
		low = -TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, Float.parseFloat(prefs.getString(LOW_LEVEL, LOW_LEVEL_DEFAULT)), getResources().getDisplayMetrics());

		stepsGoal = Integer.parseInt(prefs.getString(STEPS_GOAL, STEPS_GOAL_DEFAULT));

		return new DripWallpaperEngine();
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
		switch (key) {
			case STEPS_GOAL:
				stepsGoal = Integer.parseInt(sharedPreferences.getString(key, STEPS_GOAL_DEFAULT));
				lastStepsCheck = 0;
				break;
			case TIDE_LEVEL:
				tide = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, Float.parseFloat(sharedPreferences.getString(key, TIDE_LEVEL_DEFAULT)), getResources().getDisplayMetrics());
				break;
			case LOW_LEVEL_DEFAULT:
				low = -TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, Float.parseFloat(sharedPreferences.getString(key, LOW_LEVEL_DEFAULT)), getResources().getDisplayMetrics());
				break;
		}
	}

	@Override
	public void onDestroy() {
		PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).unregisterOnSharedPreferenceChangeListener(this);
		ex.shutdown();
		super.onDestroy();
	}

	/**
	 * @return screen rotation in degrees
	 */
	public int getRotation(int orientation) {
		switch (orientation) {
			case Surface.ROTATION_0:
				return 0;
			case Surface.ROTATION_90:
				return 90;
			case Surface.ROTATION_180:
				return 180;
			case Surface.ROTATION_270:
				return 270;

			default:
				return 0;
		}
	}

	public class DripWallpaperEngine extends Engine implements SensorHelper.OnAngleChangedListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, StepsHelper.OnStepsCountFetchedListener {

		private final Runnable drawRunner;
		private final Runnable backgroundRunner;
		private final Handler handler;
		private final Matrix shaderMatrix;
		private final float maskXStep;
		private float zeroLevel, topLevel;
		private float offsetY;
		private int rotation;
		private int lastOrientation = -1;
		private Point locationOfDrip;
		private double phoneAngle = 0;
		private boolean visible;
		private Drawable wave;
		private int width, height;
		private BitmapShader shader;
		private Paint paint;
		private float maskX, maskY;
		private float maskYStep;
		private float waterRotation;
		private Bitmap backgroundBitmap;
		private Rect textRect;
		private boolean redrawEverything;
		private SensorHelper sensorHelper;
		private AuthHelper authHelper;
		private StepsHelper stepsHelper;
		private double finalAngle = 0;

		public DripWallpaperEngine() {
			initPaint();

			handler = new Handler(Looper.getMainLooper());
			maskXStep = getResources().getDimension(R.dimen.mask_x_step);
			maskYStep = getResources().getDimension(R.dimen.mask_y_step);
			redrawEverything = true;
			shaderMatrix = new Matrix();

			createShader();
			this.drawRunner = new Runnable() {
				@Override
				public void run() {
					draw();
				}
			};

			// Stuff to do every frame in a background thread
			this.backgroundRunner = new Runnable() {
				@Override
				public void run() {
					checkSteps();
					// Follow screen rotation outside the UI Thread
					followScreenRotation();
					// Set the sea shader according to its x/y/rotation values
					shaderMatrix.setTranslate(maskX, maskY + offsetY);
					shaderMatrix.postRotate(waterRotation, width / 2, height / 2);
					shader.setLocalMatrix(shaderMatrix);

					// Move the wave horizontally
					maskX += maskXStep;
					if (maskX > wave.getIntrinsicWidth())
						maskX -= wave.getIntrinsicWidth();

					// Move the wave vertically
					maskY += maskYStep;
					if (maskY > tide) {
						maskYStep *= -1;
					}

					if (maskY < low) {
						maskYStep *= -1;
					}
				}
			};

			startListeners();
		}

		public void setOffsetY(float offsetY) {
			this.offsetY = offsetY - 100; // 100 is the transparent y part of wave.png
		}

		/**
		 * Check if the orientation is changed
		 * this will fetch mirror orientation as well as 90 degrees orientation change
		 */
		@SuppressWarnings("ResourceType")
		public void refreshOrientation() {
			if (lastOrientation != display.getRotation()) {
				// Enforce new steps check in order to calculate the new yOffset of sea level
				lastStepsCheck = 0;
				lastOrientation = display.getRotation();
				rotation = getRotation(lastOrientation);
			}
		}

		@Override
		public void onConnected(Bundle bundle) {
		}

		@Override
		public void onConnectionSuspended(int i) {
		}

		@Override
		public void onConnectionFailed(ConnectionResult connectionResult) {
		}

		@Override
		public void onStepsCountFetched(int count) {
			float percent = (float) count / (float) stepsGoal;

			if (percent > 1)
				percent = 1;

			// Allow a minimum fill of 5% so the drip will not be empty
			if (percent < 0.05)
				percent = 0.05f;

			// Change the sea level according to the new steps
			setOffsetY((int) (zeroLevel + percent * (topLevel - zeroLevel)));
		}

		private void initPaint() {
			this.paint = new Paint();
			this.paint.setTextAlign(Paint.Align.CENTER);
			this.paint.setTextSize(getResources().getDimension(R.dimen.drip_size));
			this.paint.setTypeface(Typeface.createFromAsset(getAssets(), "dripfont.ttf"));
		}

		@Override
		public void onVisibilityChanged(boolean visible) {
			this.visible = visible;

			if (visible) {
				startListeners();
				redrawEverything = true;
				lastStepsCheck = 0;
				doFrame();
			} else {
				stopListeners();
			}
		}

		@Override
		public void onSurfaceDestroyed(SurfaceHolder holder) {
			super.onSurfaceDestroyed(holder);

			this.visible = false;
			stopListeners();
		}

		@Override
		public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
			super.onSurfaceChanged(holder, format, width, height);

			this.width = width;
			this.height = height;
			// Enforce new steps check in order to calculate the new yOffset of sea level
			lastStepsCheck = 0;
			refreshOrientation();
			redrawEverything = true;
			doFrame();
		}

		private void draw() {
			SurfaceHolder holder = getSurfaceHolder();
			Canvas canvas = null;
			try {
				// redrawEverything means that the whole screen is dirty, reset all params and redraw everything
				if (redrawEverything) {
					initDimensParam(width, height);
					canvas = holder.lockCanvas();
				} else {
					canvas = holder.lockCanvas(textRect);
				}
				if (canvas != null) {
					draw(canvas);
				}
			} finally {
				if (canvas != null) {
					try {
						holder.unlockCanvasAndPost(canvas);
					} catch (Exception ignore) {
					}
				}
			}

			if (visible) {
				doFrame();
			}
		}

		private void doFrame() {
			handler.post(drawRunner);
			ex.execute(backgroundRunner);
		}

		/**
		 * Call for FitnessAPI if needed
		 */
		private void checkSteps() {
			if (SystemClock.uptimeMillis() - lastStepsCheck > STEPS_CHECK_INTERVAL) {
				lastStepsCheck = SystemClock.uptimeMillis();
				stepsHelper.fetchStepsCount();
			}
		}

		private void draw(Canvas canvas) {
			try {
				canvas.drawBitmap(backgroundBitmap, 0, 0, paint);
				redrawEverything = false;
			} catch (Exception ignore) {
				redrawEverything = true;
				canvas.drawColor(CompatUtils.getColor(getApplicationContext(), R.color.background_color));
			}

			// Draw the drip with the water shader
			canvas.drawText("\uE900", locationOfDrip.x, locationOfDrip.y, paint);
		}

		private void initDimensParam(int width, int height) {
			int halfWidth = width / 2;
			int halfHeight = height / 2;

			if (locationOfDrip == null)
				locationOfDrip = new Point();

			// The location in screen to draw the text
			locationOfDrip.x = halfWidth;
			locationOfDrip.y = (int) (halfHeight - (paint.descent() + paint.ascent()) / 2);

			if (textRect == null) {
				textRect = new Rect();
			}

			// Get text bounds to limit the sea level from bottom to top
			paint.getTextBounds("\uE900", 0, 1, textRect);
			textRect.offsetTo(halfWidth - textRect.width() / 2, halfHeight - textRect.height() / 2);

			zeroLevel = textRect.bottom;
			topLevel = textRect.top;

			if (offsetY == 0) {
				setOffsetY(zeroLevel);
			}

			// Redraw the bitmap if needed
			if (backgroundBitmap != null && !backgroundBitmap.isRecycled() && (backgroundBitmap.getWidth() != width || backgroundBitmap.getHeight() != height)) {
				backgroundBitmap.recycle();
			}

			buildBitmap(width, height);
		}

		/**
		 * Build the background bitmap to fit canvas width
		 *
		 * @param width  - width of the new bitmap
		 * @param height - height of the new bitmap
		 */
		private void buildBitmap(int width, int height) {
			Bitmap tmpBackgroundBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.bg);
			backgroundBitmap = scaleCenterCrop(tmpBackgroundBitmap, width, height);
			tmpBackgroundBitmap.recycle();
		}

		/**
		 * Scale and center crop a bitmap to fit new dimensions
		 *
		 * @param source     - source bitmap to scale and center crop
		 * @param destWidth  - destination width
		 * @param destHeight - destination height
		 * @return - the new scaled and centered bitmap
		 */
		public Bitmap scaleCenterCrop(Bitmap source, int destWidth, int destHeight) {
			int sourceWidth = source.getWidth();
			int sourceHeight = source.getHeight();

			float xScale = (float) destWidth / sourceWidth;
			float yScale = (float) destHeight / sourceHeight;
			float scale = Math.max(xScale, yScale);

			float scaledWidth = scale * sourceWidth;
			float scaledHeight = scale * sourceHeight;

			float left = (destWidth - scaledWidth) / 2;
			float top = (destHeight - scaledHeight) / 2;

			RectF destRect = new RectF(left, top, left + scaledWidth, top + scaledHeight);

			Bitmap dest = Bitmap.createBitmap(destWidth, destHeight, source.getConfig());
			Canvas canvas = new Canvas(dest);
			canvas.drawBitmap(source, null, destRect, null);

			return dest;
		}

		/**
		 * Create the wave shader
		 */
		private void createShader() {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
				wave = getApplicationContext().getDrawable(R.drawable.wave);
			} else {
				//noinspection deprecation
				wave = getResources().getDrawable(R.drawable.wave);
			}

			assert wave != null;

			int waveW = wave.getIntrinsicWidth();
			int waveH = wave.getIntrinsicHeight();

			Bitmap b = Bitmap.createBitmap(waveW, waveH, Bitmap.Config.RGB_565);
			Canvas c = new Canvas(b);

			c.drawColor(CompatUtils.getColor(getApplicationContext(), R.color.drip_color));

			wave.setBounds(0, 0, waveW, waveH);
			wave.draw(c);

			// The wave is repeated in x axis and transparent in top y axis
			shader = new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP);
			paint.setShader(shader);
		}

		@Override
		public void onAngleChangedListener(double angle) {
			finalAngle = angle;
		}

		private void followScreenRotation() {
			double angle = finalAngle;
			int d = (int) Math.abs(angle - phoneAngle) % 360;
			int r = d > 180 ? 360 - d : d;

			// Change the angle only when there is a certain threshold from the previous angle
			// this is in order to avoid vibration in the drip
			if (r > 2) {
				double mAngle = phoneAngle;
				double plus = angle - mAngle;
				double minus = 360 - angle + mAngle;

				// Calculate the new angle
				if (mAngle > angle) {
					plus = 360 + plus;
					minus = minus - 360;
				}
				// Find what is the shortest path to the new angle (+ or -)
				if (plus < minus) {
					mAngle += Math.min(MAX_ANGLE_CHANGE_IN_FRAME, plus / FOLLOW_SCREEN_ROTATION_FACTOR);
				} else {
					mAngle -= Math.min(MAX_ANGLE_CHANGE_IN_FRAME, minus / FOLLOW_SCREEN_ROTATION_FACTOR);
				}

				phoneAngle = mAngle;

				if (phoneAngle > 360) {
					phoneAngle = phoneAngle - 360;
				}

				if (phoneAngle < 0) {
					phoneAngle = 360 + phoneAngle;
				}

				waterRotation = 90 - (float) phoneAngle - rotation;

				refreshOrientation();
			}
		}

		private void startListeners() {
			if (sensorHelper == null) {
				sensorHelper = new SensorHelper(getApplicationContext());
			}

			if (authHelper == null) {
				authHelper = new AuthHelper();
				authHelper.buildFitnessClient(getApplicationContext(), this, this);
				stepsHelper = new StepsHelper(authHelper.getClient(), this);
			}

			sensorHelper.start();
			sensorHelper.setListener(this);

			authHelper.start();
		}

		private void stopListeners() {
			if (sensorHelper != null)
				sensorHelper.stop();
			if (authHelper != null)
				authHelper.stop();
		}
	}
}
