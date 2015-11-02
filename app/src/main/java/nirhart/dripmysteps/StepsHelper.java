package nirhart.dripmysteps;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.fitness.Fitness;
import com.google.android.gms.fitness.data.Bucket;
import com.google.android.gms.fitness.data.DataPoint;
import com.google.android.gms.fitness.data.DataSet;
import com.google.android.gms.fitness.data.DataType;
import com.google.android.gms.fitness.data.Field;
import com.google.android.gms.fitness.request.DataReadRequest;
import com.google.android.gms.fitness.result.DataReadResult;

import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class StepsHelper {

	private final GoogleApiClient client;
	private final OnStepsCountFetchedListener listener;
	private final ExecutorService ex;

	public StepsHelper(GoogleApiClient client, OnStepsCountFetchedListener listener) {
		this.client = client;
		this.ex = Executors.newCachedThreadPool();
		this.listener = listener;
	}

	public void fetchStepsCount() {
		ex.execute(new Runnable() {
			@Override
			public void run() {
				// Find steps from Fitness API
				DataReadRequest r = queryFitnessData();
				DataReadResult dataReadResult = Fitness.HistoryApi.readData(client, r).await(1, TimeUnit.MINUTES);
				boolean stepsFetched = false;
				if (dataReadResult.getBuckets().size() > 0) {
					Bucket bucket = dataReadResult.getBuckets().get(0);
					DataSet ds = bucket.getDataSet(DataType.TYPE_STEP_COUNT_DELTA);
					if (ds != null) {
						for (DataPoint dp : ds.getDataPoints()) {
							for (Field field : dp.getDataType().getFields()) {
								if (field.getName().equals("steps")) {
									stepsFetched = true;
									listener.onStepsCountFetched(dp.getValue(field).asInt());
								}
							}
						}
					}
				}

				if (!stepsFetched) {
					// No steps today yet or no fitness data available
					listener.onStepsCountFetched(0);
				}
			}
		});
	}

	/**
	 * Query to get the num of steps since 00:00 until now
	 */
	private DataReadRequest queryFitnessData() {
		Calendar cal = Calendar.getInstance();
		Date now = new Date();
		cal.setTime(now);
		long endTime = cal.getTimeInMillis();
		cal.set(Calendar.HOUR_OF_DAY, 0);
		cal.set(Calendar.MINUTE, 0);
		cal.set(Calendar.SECOND, 0);
		cal.set(Calendar.MILLISECOND, 0);
		long startTime = cal.getTimeInMillis();

		return new DataReadRequest.Builder()
				.aggregate(DataType.TYPE_STEP_COUNT_DELTA, DataType.AGGREGATE_STEP_COUNT_DELTA)
				.bucketByTime(1, TimeUnit.DAYS)
				.setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
				.build();
	}

	public interface OnStepsCountFetchedListener {
		void onStepsCountFetched(int count);
	}
}

