package com.caddish_hedgehog.hedgecam2.CameraController;

import com.caddish_hedgehog.hedgecam2.MyDebug;
import com.caddish_hedgehog.hedgecam2.R;
import com.caddish_hedgehog.hedgecam2.Utils;

import android.content.res.Resources;
import android.hardware.Camera;
import android.util.Log;

/** Provides support using Android's original camera API
 *  android.hardware.Camera.
 */
@SuppressWarnings("deprecation")
public class CameraControllerManager1 extends CameraControllerManager {
	private static final String TAG = "HedgeCam/CControllerManager1";
	public int getNumberOfCameras() {
		return Camera.getNumberOfCameras();
	}

	public boolean isFrontFacing(int cameraId) {
		try {
			Camera.CameraInfo camera_info = new Camera.CameraInfo();
			Camera.getCameraInfo(cameraId, camera_info);
			return (camera_info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT);
		}
		catch(RuntimeException e) {
			// Had a report of this crashing on Galaxy Nexus - may be device specific issue, see http://stackoverflow.com/questions/22383708/java-lang-runtimeexception-fail-to-get-camera-info
			// but good to catch it anyway
			if( MyDebug.LOG )
				Log.d(TAG, "failed to set parameters");
			e.printStackTrace();
			return false;
		}
	}

	public String[] getCamerasList() {
		Resources resources = Utils.getResources();

		int numberOfCameras = Camera.getNumberOfCameras();
		String[] cameras = new String[numberOfCameras];
		for (int i = 0; i < numberOfCameras; i++) {
			Camera.CameraInfo camera_info = new Camera.CameraInfo();
			Camera.getCameraInfo(i, camera_info);
			String facing;
			switch (camera_info.facing) {
				case Camera.CameraInfo.CAMERA_FACING_FRONT:
					facing = resources.getString(R.string.front_camera);
					break;
				case Camera.CameraInfo.CAMERA_FACING_BACK:
					facing = resources.getString(R.string.back_camera);
					break;
				default:
					facing = "Unknown";
			}
			
			cameras[i] = (i+1) + " (" + facing + ")";
		}
		
		return cameras;
	}
}
