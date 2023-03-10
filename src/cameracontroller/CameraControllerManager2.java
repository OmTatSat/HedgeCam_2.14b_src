package com.caddish_hedgehog.hedgecam2.CameraController;

import com.caddish_hedgehog.hedgecam2.MyDebug;
import com.caddish_hedgehog.hedgecam2.R;
import com.caddish_hedgehog.hedgecam2.StringUtils;
import com.caddish_hedgehog.hedgecam2.Utils;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.os.Build;
import android.util.Log;
import android.util.Size;

/** Provides support using Android 5's Camera 2 API
 *  android.hardware.camera2.*.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class CameraControllerManager2 extends CameraControllerManager {
	private static final String TAG = "HedgeCam/CControllerManager2";

	private final Context context;
	private final CameraManager manager;

	public CameraControllerManager2(Context context) {
		this.context = context;
		this.manager = (CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
	}

	@Override
	public int getNumberOfCameras() {
		try {
			return manager.getCameraIdList().length;
		}
		catch(Throwable e) {
			// in theory we should only get CameraAccessException, but Google Play shows we can get a variety of exceptions
			// from some devices, e.g., AssertionError, IllegalArgumentException, RuntimeException, so just catch everything!
			// We don't want users to experience a crash just because of buggy camera2 drivers - instead the user can switch
			// back to old camera API.
			if( MyDebug.LOG )
				Log.e(TAG, "exception trying to get camera ids");
			e.printStackTrace();
		}
		return 0;
	}

	@Override
	public boolean isFrontFacing(int cameraId) {
		try {
			String cameraIdS = manager.getCameraIdList()[cameraId];
			CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraIdS);
			return characteristics.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT;
		}
		catch(Throwable e) {
			// in theory we should only get CameraAccessException, but Google Play shows we can get a variety of exceptions
			// from some devices, e.g., AssertionError, IllegalArgumentException, RuntimeException, so just catch everything!
			// We don't want users to experience a crash just because of buggy camera2 drivers - instead the user can switch
			// back to old camera API.
			if( MyDebug.LOG )
				Log.e(TAG, "exception trying to get camera characteristics");
			e.printStackTrace();
		}
		return false;
	}

	/* Returns true if the device supports the required hardware level, or better.
	 * From http://msdx.github.io/androiddoc/docs//reference/android/hardware/camera2/CameraCharacteristics.html#INFO_SUPPORTED_HARDWARE_LEVEL
	 * From Android N, higher levels than "FULL" are possible, that will have higher integer values.
	 * Also see https://sourceforge.net/p/opencamera/tickets/141/ .
	 */
	static boolean isHardwareLevelSupported(CameraCharacteristics c, int requiredLevel) {
		int deviceLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
		if( MyDebug.LOG ) {
			if( deviceLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY )
				Log.d(TAG, "Camera has LEGACY Camera2 support");
			else if( deviceLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED )
				Log.d(TAG, "Camera has LIMITED Camera2 support");
			else if( deviceLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL )
				Log.d(TAG, "Camera has FULL Camera2 support");
			else if( deviceLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 )
				Log.d(TAG, "Camera has Camera2 support level 3");
			else
				Log.d(TAG, "Camera has unknown Camera2 support: " + deviceLevel);
		}
		// Fuckin android...
		if (requiredLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
			requiredLevel = -1;
		}
		if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
			deviceLevel = -1;
		}
		return requiredLevel <= deviceLevel;
	}

	public boolean hasLevel(int cameraId, int level) {
		try {
			String cameraIdS = manager.getCameraIdList()[cameraId];
			CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraIdS);
			return isHardwareLevelSupported(characteristics, level);
		}
		catch(Throwable e) {
			// in theory we should only get CameraAccessException, but Google Play shows we can get a variety of exceptions
			// from some devices, e.g., AssertionError, IllegalArgumentException, RuntimeException, so just catch everything!
			// We don't want users to experience a crash just because of buggy camera2 drivers - instead the user can switch
			// back to old camera API.
			if( MyDebug.LOG )
				Log.e(TAG, "exception trying to get camera characteristics");
			e.printStackTrace();
		}
		return false;
	}

	public String[] getCamerasList() {
		Resources resources = Utils.getResources();

		try {
			String[] cameraIdList =  manager.getCameraIdList();
			String[] cameras = new String[cameraIdList.length];
			for (int i = 0; i < cameraIdList.length; i++) {
				CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraIdList[i]);
				String facing;
				switch (characteristics.get(CameraCharacteristics.LENS_FACING)) {
					case CameraMetadata.LENS_FACING_FRONT:
						facing = resources.getString(R.string.front_camera);
						break;
					case CameraMetadata.LENS_FACING_BACK:
						facing = resources.getString(R.string.back_camera);
						break;
					case CameraMetadata.LENS_FACING_EXTERNAL:
						facing = resources.getString(R.string.external_camera);
						break;
					default:
						facing = "Unknown";
				}
				Size size = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
				
				cameras[i] = (i+1) + " (" + facing + " " + StringUtils.getMPString(size.getWidth(), size.getHeight()) + ")";
			}

			return cameras;
		} catch(Throwable e) {
			if( MyDebug.LOG )
				Log.e(TAG, "exception trying to get cameras list");
			e.printStackTrace();
			return new String[0];
		}
	}
}
