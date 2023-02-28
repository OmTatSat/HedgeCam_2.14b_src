package com.caddish_hedgehog.hedgecam2;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentSender.SendIntentException;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.ConsumeResponseListener;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesResponseListener;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.billingclient.api.SkuDetailsResponseListener;


class Donations implements ConsumeResponseListener, PurchasesUpdatedListener {
	private static final String TAG = "HedgeCam/Donations";

	private BillingClient billingClient;

	public static abstract class DonationsListener {
		public void onReady() {}
		public void onDonationMade() {}
	}

	private final Context context;
	private DonationsListener listener;

	private final List<SkuDetails> playDonations = new ArrayList<SkuDetails>();

	private static boolean wasDonations;

	Donations(final Activity context) {
		this.context = context;
	}

	public void init(final DonationsListener listener) {
		if( MyDebug.LOG )
			Log.d(TAG, "init(" + listener + ")");

		this.listener = listener;

		billingClient = BillingClient.newBuilder(context).setListener(this).enablePendingPurchases().build();
		billingClient.startConnection(new BillingClientStateListener() {
			@Override
			public void onBillingSetupFinished(BillingResult billingResult) {
				if( MyDebug.LOG )
					Log.d(TAG, "onBillingSetupFinished");

				if (billingClient.isReady()) {
					if( MyDebug.LOG )
						Log.d(TAG, "Requesting skus list...");
					ArrayList<String> skuList = new ArrayList<String>();
					// OMFG!  Even Google Play billing has stupid bugs! We can`t use ITEM_ID_LIST with 6 items, because in this case the service will return DETAILS_LIST with 3 items only.
					// List with 4 ,5, 7, 8 and 9 items works fine. Are you sick Google?
					for (int i = 1; i <= 9; i++) {
						skuList.add("donation_" + i);
					}
					SkuDetailsParams.Builder params = SkuDetailsParams.newBuilder();
					params.setSkusList(skuList).setType(BillingClient.SkuType.INAPP);
					billingClient.querySkuDetailsAsync(params.build(), new SkuDetailsResponseListener() {
						@Override
						public void onSkuDetailsResponse(BillingResult billingResult, List<SkuDetails> skuDetailsList) {
							if( MyDebug.LOG )
								Log.d(TAG, "onSkuDetailsResponse");
							for (SkuDetails details : skuDetailsList)
								playDonations.add(details);
							
							if( MyDebug.LOG )
								Log.d(TAG, "Skus list contains " + playDonations.size() + " items");

							if (listener != null)
								listener.onReady();
						}
					});

					if( MyDebug.LOG )
						Log.d(TAG, "Requesting old donations...");
					billingClient.queryPurchasesAsync(BillingClient.SkuType.INAPP, new PurchasesResponseListener() {
						@Override
						public void onQueryPurchasesResponse(BillingResult billingResult, List<Purchase> purchases) {
							if( MyDebug.LOG )
								Log.d(TAG, "onQueryPurchasesResponse");
							for (Purchase purchase : purchases) {
								if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
									if( MyDebug.LOG )
										Log.d(TAG, "Now we have some old donation and voraciously consume it... Om-nom-nom... :D");
									consumePurchase(purchase.getPurchaseToken());
								}
							}
						}
					});
				}
			}

			@Override
			public void onBillingServiceDisconnected() {
				if( MyDebug.LOG )
					Log.d(TAG, "onServiceDisconnected");
			}
		});
	}

	public List<SkuDetails> getPlayDonations() {
		if( MyDebug.LOG )
			Log.d(TAG, "getPlayDonations()");

		if (billingClient == null || !billingClient.isReady())
			return new ArrayList<SkuDetails>();

		return playDonations;
	}

	public boolean wasThereDonations() {
		if (billingClient == null || !billingClient.isReady())
			return false;

		return wasDonations;
	}

	public void donate(String id) {
		if( MyDebug.LOG )
			Log.d(TAG, "donate()");
		if (billingClient == null || !billingClient.isReady())
			return;

		SkuDetails skuDetails = null;
		for (SkuDetails item : playDonations) {
			if (item.getSku().equals(id)) {
				skuDetails = item;
				break;
			}
		}
		if (skuDetails == null)
			return;

		if( MyDebug.LOG )
			Log.d(TAG, "skuDetails: " + skuDetails);
		BillingFlowParams purchaseParams = BillingFlowParams.newBuilder().setSkuDetails(skuDetails).build();
		// Øןאיר לארע פכמף...
		billingClient.launchBillingFlow((Activity)context, purchaseParams);
	}

	@Override
	public void onPurchasesUpdated(BillingResult billingResult, List<Purchase> purchases) {
		if( MyDebug.LOG )
			Log.d(TAG, "onPurchasesUpdated(" + billingResult + ", purchases)");

		if (purchases == null) {
			if( MyDebug.LOG )
				Log.d(TAG, "Null purchases in onPurchasesUpdated.");
			return;
		}

		for (Purchase purchase : purchases) {
			if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
				if( MyDebug.LOG )
					Log.d(TAG, "Now we have new donation and voraciously consume it... Om-nom-nom... :D");
				consumePurchase(purchase.getPurchaseToken());
			}
		}
	}

	public void consumePurchase(String purchaseToken) {
		if( MyDebug.LOG )
			Log.d(TAG, "consumePurchase(" + purchaseToken + ")");

		ConsumeParams consumeParams = ConsumeParams.newBuilder().setPurchaseToken(purchaseToken).build();
		billingClient.consumeAsync(consumeParams, this);

		wasDonations = true;

		if (listener != null)
			listener.onDonationMade();
	}

	@Override
	public void onConsumeResponse(BillingResult billingResult, String purchaseToken) {
		if( MyDebug.LOG )
			Log.d(TAG, "onConsumeResponse(" + billingResult + ", " + purchaseToken + ")");
	}
	
	public static String getLibraryVersion() {
		return com.android.billingclient.BuildConfig.VERSION_NAME;
	}

	public void onDestroy() {
		listener = null;
	}
}
