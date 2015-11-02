package nirhart.dripmysteps;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

public class SensorHelper implements SensorEventListener {

	public final Context context;
	private SensorManager mSensorManager;
	private OnAngleChangedListener listener;

	public SensorHelper(Context context) {
		this.context = context.getApplicationContext();
	}

	public void start() {
		mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
		mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL);
	}

	public void destroy() {
		if (mSensorManager == null) {
			return;
		}
		mSensorManager.unregisterListener(this);
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
			updatePoint(event.values[0], event.values[1]);
		}
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {

	}

	/**
	 * Update the gravity point only if a significant change was made since the last measurement
	 *
	 * @param x - x value from accelerometer
	 * @param y - y value from accelerometer
	 */
	public void updatePoint(float x, float y) {
		if (Math.sqrt(x * x + y * y) > 1.5)
			if (listener != null)
				listener.onAngleChangedListener(getAngle(x, -y));
	}

	/**
	 * Calculate the gravity angle given two coordinates
	 *
	 * @param x - x coordinate
	 * @param y - y coordinate
	 * @return angle from origin
	 */
	public double getAngle(float x, float y) {
		double inRads = Math.atan2(y, x);

		if (inRads < 0)
			inRads = Math.abs(inRads);
		else
			inRads = 2 * Math.PI - inRads;

		return Math.toDegrees(inRads);
	}

	public void setListener(OnAngleChangedListener listener) {
		this.listener = listener;
	}

	public interface OnAngleChangedListener {
		void onAngleChangedListener(double angle);
	}
}
