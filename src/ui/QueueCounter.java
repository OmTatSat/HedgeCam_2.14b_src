package com.caddish_hedgehog.hedgecam2.UI;

import com.caddish_hedgehog.hedgecam2.R;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import android.widget.TextView;

public class QueueCounter {
	private static final String TAG = "HedgeCam/QueueCounter";

	private static final int MSG_RESET = 1;
	private static final int MSG_INCREASE = 2;
	private static final int MSG_DECREASE = 3;
	private static final int MSG_USE_VIEW = 4;
	private static final int MSG_USE_NOTIFICATION = 5;
	private static final int MSG_CANCEL_NOTIFICATION = 6;

	private final String CHANNEL_ID = "hedgecam_channel";
	private final int NOTIFICATION_ID = 1;

	private final Context context;
	private final Resources resources;
	private final TextView view;
	private final Handler handler;

	public QueueCounter(Context c, TextView v) {
		context = c;
		resources = context.getResources();
		view = v;
		handler = new Handler() {
			private int counter = 0;
			private boolean use_notification;

			@Override
			public void handleMessage(Message message) {
				switch (message.what) {
					case MSG_RESET:
						if (counter != 0) {
							counter = 0;
							if (use_notification) {
								cancelNotification();
							} else {
								if (view.getVisibility() == View.VISIBLE) {
									view.setText("");
									view.setVisibility(View.GONE);
								}
							}
						}
						break;
					case MSG_INCREASE:
						counter++;
						if (use_notification) {
							createNotification();
						} else {
							if (counter > 1) {
								view.setText(Integer.toString(counter-1));
								if (view.getVisibility() != View.VISIBLE);
									view.setVisibility(View.VISIBLE);
							}
						}
						break;
					case MSG_DECREASE:
						if (counter > 0) {
							counter--;
							if (use_notification) {
								if (counter == 0)
									cancelNotification();
								else
									createNotification();
							} else {
								if (counter > 1)
									view.setText(Integer.toString(counter-1));
								else if (view.getVisibility() == View.VISIBLE) {
									view.setText("");
									view.setVisibility(View.GONE);
								}
							}
						}
						break;
					case MSG_USE_VIEW:
						if (use_notification && counter > 0) {
							cancelNotification();
							if (counter > 1) {
								view.setText(Integer.toString(counter-1));
								view.setVisibility(View.VISIBLE);
							}

							use_notification = false;
						}
						break;
					case MSG_USE_NOTIFICATION:
						if (!use_notification && counter > 0) {
							createNotification();
							if (counter > 1) {
								view.setText("");
								view.setVisibility(View.GONE);
							}

							use_notification = true;
						}
						break;
					case MSG_CANCEL_NOTIFICATION:
						if (use_notification)
							cancelNotification();
						break;
				}
			}

			private void createNotification() {
				Notification.Builder builder;
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					builder = new Notification.Builder(context, CHANNEL_ID);
				} else {
					builder = new Notification.Builder(context);
				}
				builder.setSmallIcon(R.drawable.ic_photo_camera);
				builder.setContentTitle(resources.getString(R.string.app_name));
				builder.setContentText(String.format(resources.getString(R.string.notification_image_processing), counter));
				builder.setOngoing(true);
				
				NotificationManager notificationManager = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
				notificationManager.notify(NOTIFICATION_ID, builder.build());
			}
			
			private void cancelNotification() {
				NotificationManager notificationManager = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
				notificationManager.cancel(NOTIFICATION_ID);
			}
		};

		if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ) {
			CharSequence name = "HedgeCam Image Saving";
			String description = "Notification channel for processing and saving images in the background";
			int importance = NotificationManager.IMPORTANCE_LOW;
			NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
			channel.setDescription(description);
			// Register the channel with the system; you can't change the importance
			// or other notification behaviors after this
			NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
			notificationManager.createNotificationChannel(channel);
		}
	}

	public void reset() {
		handler.sendEmptyMessage(MSG_RESET);
	}

	public void increase() {
		handler.sendEmptyMessage(MSG_INCREASE);
	}

	public void decrease() {
		handler.sendEmptyMessage(MSG_DECREASE);
	}

	public void onPause() {
		handler.sendEmptyMessage(MSG_USE_NOTIFICATION);
	}

	public void onResume() {
		handler.sendEmptyMessage(MSG_USE_VIEW);
	}

	public void onDestroy() {
		handler.sendEmptyMessage(MSG_CANCEL_NOTIFICATION);
	}

}
