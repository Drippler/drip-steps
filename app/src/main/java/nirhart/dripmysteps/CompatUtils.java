package nirhart.dripmysteps;

import android.content.Context;
import android.os.Build;

public class CompatUtils {

	static public int getColor(Context context, int resourceId) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			return context.getColor(resourceId);
		} else {
			//noinspection deprecation
			return context.getResources().getColor(resourceId);
		}
	}
}
