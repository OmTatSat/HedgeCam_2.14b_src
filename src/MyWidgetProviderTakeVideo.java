package com.caddish_hedgehog.hedgecam2;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.RemoteViews;

public class MyWidgetProviderTakeVideo extends AppWidgetProvider {
	private static final String TAG = "HedgeCam/MyWidgetProviderTakePho";

	// from http://developer.android.com/guide/topics/appwidgets/index.html
	public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
		if( MyDebug.LOG )
			Log.d(TAG, "onUpdate");
		if( MyDebug.LOG )
			Log.d(TAG, "length = " + appWidgetIds.length);

		// Perform this loop procedure for each App Widget that belongs to this provider
		for(int appWidgetId : appWidgetIds) {
			if( MyDebug.LOG )
				Log.d(TAG, "appWidgetId: " + appWidgetId);

			Intent intent = new Intent(context, TakeVideo.class);
			PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, 0);

			// Get the layout for the App Widget and attach an on-click listener
			// to the button
			RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout_take_video);
			views.setOnClickPendingIntent(R.id.widget_take_video, pendingIntent);

			// Tell the AppWidgetManager to perform an update on the current app widget
			appWidgetManager.updateAppWidget(appWidgetId, views);
		}
	}

	/*@Override
	public void onReceive(Context context, Intent intent) {
		if( MyDebug.LOG ) {
			Log.d(TAG, "onReceive " + intent);
		}
		if (intent.getAction().equals("com.caddish_hedgehog.hedgecam2.LAUNCH_OPEN_CAMERA")) {
			if( MyDebug.LOG )
				Log.d(TAG, "Launching MainActivity");
			final Intent activity = new Intent(context, MainActivity.class);
			activity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			context.startActivity(activity);
			if( MyDebug.LOG )
				Log.d(TAG, "done");
		}
		super.onReceive(context, intent);
	}*/
}
