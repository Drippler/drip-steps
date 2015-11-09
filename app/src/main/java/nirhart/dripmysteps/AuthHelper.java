package nirhart.dripmysteps;

import android.content.Context;

import com.google.android.gms.common.Scopes;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.fitness.Fitness;

public class AuthHelper {

	private GoogleApiClient client;

	public AuthHelper() {

	}

	public void buildFitnessClient(final Context context, final GoogleApiClient.ConnectionCallbacks connectionCallbacks, final GoogleApiClient.OnConnectionFailedListener connectionFailedListener) {
		client = new GoogleApiClient.Builder(context.getApplicationContext()).
				addApi(Fitness.HISTORY_API).
				addScope(new Scope(Scopes.FITNESS_ACTIVITY_READ)).
				addConnectionCallbacks(connectionCallbacks).
				addOnConnectionFailedListener(connectionFailedListener)
				.build();
	}

	public void start() {
		if (client != null) {
			client.connect();
		}
	}

	public void stop() {
		if (client != null && client.isConnected()) {
			client.disconnect();
		}
	}

	public boolean isConnecting() {
		return client != null && client.isConnecting();
	}

	public boolean isConnected() {
		return client != null && client.isConnected();
	}

	public void connect() {
		if (client != null)
			client.connect();
	}

	public GoogleApiClient getClient() {
		return client;
	}
}