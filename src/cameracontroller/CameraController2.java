package com.caddish_hedgehog.hedgecam2.CameraController;

import com.caddish_hedgehog.hedgecam2.Matrix;
import com.caddish_hedgehog.hedgecam2.MyDebug;
import com.caddish_hedgehog.hedgecam2.ColorTemperature;
import com.caddish_hedgehog.hedgecam2.Utils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.LensShadingMap;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.params.TonemapCurve;
import android.location.Location;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Pair;
import android.util.Range;
import android.util.SizeF;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;

/** Provides support using Android 5's Camera 2 API
 *  android.hardware.camera2.*.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class CameraController2 extends CameraController {
	private static final String TAG = "HedgeCam/CameraController2";

	private final Context context;
	private CameraDevice camera;
	private String cameraIdS;
	private CameraCharacteristics characteristics;
	private Range<Integer> iso_range;
	private Range<Long> exposure_time_range;
	private List<Integer> zoom_ratios;
	private int current_zoom_value;
	private boolean supports_face_detect_mode_simple;
	private boolean supports_face_detect_mode_full;
	private boolean supports_photo_video_recording;
	private int tonemap_max_curve_points_c;
	private final ErrorCallback preview_error_cb;
	private final ErrorCallback camera_error_cb;
	private CameraCaptureSession captureSession;
	private CaptureRequest.Builder previewBuilder;
	private boolean previewIsVideoMode;
	private AutoFocusCallback autofocus_cb;
	private boolean capture_follows_autofocus_hint;
	private int autofocus_attempt = 0;
	private FaceDetectionListener face_detection_listener;
	private final Object image_reader_lock = new Object(); // lock to make sure we only handle one image being available at a time
	private final Object open_camera_lock = new Object(); // lock to wait for camera to be opened from CameraDevice.StateCallback
	private final Object create_capture_session_lock = new Object(); // lock to wait for capture session to be created from CameraCaptureSession.StateCallback
	private ImageReader imageReader;
	private ImageReader imageReaderUncompressed;
	private boolean want_expo_bracketing;
	private boolean expo_bracketing_exposure_compensation;
	private int exposure_compensation_delay = 1000;
	private List<Double> expo_bracketing_stack;
	private boolean optimise_ae_for_dro = false;
	private boolean want_burst;
	private boolean want_raw;
	private boolean want_raw_only;
	private android.util.Size raw_size;
	private ImageReader imageReaderRaw;
	private HashSet<Point> bad_pixels;
	private HashSet<Rect> bad_blocks;
	private HashSet<Point> bad_pixels_le;
	private HashSet<Rect> bad_blocks_le;
	private boolean imageReaderInUse;
	private ShutterCallback shutter_cb;
	private PictureCallback jpeg_cb;
	private int n_burst; // number of expected burst images in this capture
	private int want_burst_count = 0;
	private boolean burst_disable_filters;
	private boolean burst_single_request; // if n_burst > 1: if true then the burst images are returned in a single call to onBurstPictureTaken(), if false, then multiple calls to onPictureTaken() are made as soon as the image is available
	private final List<Photo> pending_burst_images = new ArrayList<>(); // burst images that have been captured so far, but not yet sent to the application
	private List<CaptureRequest> slow_burst_capture_requests; // the set of burst capture requests - used when not using captureBurst() (i.e., when use_fast_burst==false)
	private List<Integer> exposure_compensations;
	private long expected_capture_time = 0;
	private long capture_start_time = 0; // time when burst started (used for measuring performance of captures when not using fast burst)
	private ErrorCallback take_picture_error_cb;
	private boolean want_video_high_speed;
	private boolean is_video_high_speed; // whether we're actually recording in high speed
	private List<int[]> ae_fps_ranges;
	private List<int[]> hs_fps_ranges;
	private SurfaceTexture texture;
	private Surface surface_texture;
	private HandlerThread thread;
	private Handler handler;

	private int preview_width;
	private int preview_height;

	private int picture_width;
	private int picture_height;

	private static final int STATE_NORMAL = 0;
	private static final int STATE_WAITING_AUTOFOCUS = 1;
	private static final int STATE_WAITING_PRECAPTURE_START = 2;
	private static final int STATE_WAITING_PRECAPTURE_DONE = 3;
	private static final int STATE_WAITING_FAKE_PRECAPTURE_START = 4;
	private static final int STATE_WAITING_FAKE_PRECAPTURE_DONE = 5;
	private int state = STATE_NORMAL;
	private long precapture_state_change_time_ms = -1; // time we changed state for precapture modes
	private static final long precapture_start_timeout_c = 2000;
	private static final long precapture_done_timeout_c = 3000;
	private boolean ready_for_capture;

	private FakeFlashMode fake_flash = FakeFlashMode.None; // see CameraController.setFakeFlashMode() for details - this is the user/application setting, see fake_flash_mode for whether fake precapture is enabled (as we may do this for other purposes, e.g., front screen flash)
	private FakeFlashMode fake_flash_mode = FakeFlashMode.None; // equals to fake_flash, or FakeFlashMode.Torch if we're temporarily using fake precapture mode (e.g., for front screen flash or exposure bracketing)
	private boolean fake_precapture_torch_performed; // whether we turned on torch to do a fake precapture
	private boolean fake_precapture_torch_focus_performed; // whether we turned on torch to do an autofocus, in fake precapture mode
	private boolean fake_precapture_use_flash; // whether we decide to use flash in auto mode (if fake_precapture_use_autoflash_time_ms != -1)
	private long fake_precapture_use_flash_time_ms = -1; // when we last checked to use flash in auto mode
	private boolean take_picture_when_flash_ready;
	private boolean save_precapture_result;
	private boolean precapture_result_has_iso_exposure_time;
	private int precapture_result_iso;
	private long precapture_result_exposure_time;
	private boolean precapture_result_has_frame_duration;
	private long precapture_result_frame_duration;
	private boolean precapture_result_has_white_balance_rggb;
	private RggbChannelVector precapture_result_white_balance_rggb;

	private boolean force_iso_exposure;
	private boolean force_manual_wb;

	private ContinuousFocusMoveCallback continuous_focus_move_callback;

	private boolean has_received_frame;
	private boolean capture_result_is_ae_scanning;
	private Integer capture_result_ae; // latest ae_state, null if not available
	private boolean is_flash_required; // whether capture_result_ae suggests FLASH_REQUIRED? Or in neither FLASH_REQUIRED nor CONVERGED, this stores the last known result
	private boolean capture_result_has_iso;
	private int capture_result_iso;
	private boolean capture_result_has_exposure_time;
	private long capture_result_exposure_time;
	private boolean capture_result_has_frame_duration;
	private long capture_result_frame_duration;
	private boolean capture_result_is_awb_scanning;
	private boolean capture_result_has_white_balance_rggb;
	private RggbChannelVector capture_result_white_balance_rggb;
	private ColorTemperature.CIEColor capture_result_white_balance_xyz;
	private int capture_result_white_balance = -1;
	private boolean capture_result_is_af_scanning;
	private boolean capture_result_has_focus_distance;
	private float capture_result_focus_distance;
	private boolean capture_result_has_focus_range;
	private float capture_result_focus_distance_min;
	private float capture_result_focus_distance_max;

	private boolean use_fast_burst = true;
	private int burst_delay = 100;

	private int preview_max_exposure = 12;
	private boolean use_iso_for_expo_bracketing = true;
	private boolean exposure_over_range;

	private int smart_filter_iso = 0;
	private boolean is_filtering_blocked = false;
	private boolean filtering_capture_only = false;

	private enum RequestTag {
		PREVIEW,
		AUTOFOCUS,
		CAPTURE,
		CAPTURE_BURST_IN_PROGRESS // request is for a burst capture, but isn't the last of the burst capture sequence
	}

	private final static int min_white_balance_temperature_c = 2000;
	private final static int max_white_balance_temperature_c = 10000;

	private boolean uncompressed_photo = false;
	private boolean full_size_copy = false;
	private boolean save_exif = true;	
	private int image_reader_capacity_multiplier = 2;
	private int image_reader_max_capacity = 50;
	private boolean alt_image_holder;
	private final List<Photo> pending_photos = new ArrayList<>();
	private final List<Long> pending_photos_timestamps = new ArrayList<>();
	private boolean pending_photos_jpeg;
	private boolean pending_photos_uncompressed;
	private boolean pending_photos_raw;
	private boolean pending_photos_capture_result;

	private final static int LENS_OPTICAL_STABILIZATION_MODE_IF_NECESSARY = 2;
	private boolean optical_stabilization_if_necessary;

	private double[][] sensor_color_transform;
	private double[][] sensor_color_transform_inverse;

	private class CameraSettings {
		// keys that we need to store, to pass to the stillBuilder, but doesn't need to be passed to previewBuilder (should set sensible defaults)
		private int rotation;
		private Location location;
		private byte jpeg_quality = 90;

		// keys that we have passed to the previewBuilder, that we need to store to also pass to the stillBuilder (should set sensible defaults, or use a has_ boolean if we don't want to set a default)
		private int scene_mode = CameraMetadata.CONTROL_SCENE_MODE_DISABLED;
		private int color_effect = CameraMetadata.CONTROL_EFFECT_MODE_OFF;
		private int white_balance = CameraMetadata.CONTROL_AWB_MODE_AUTO;
		private int white_balance_temperature = 5000; // used for white_balance == CONTROL_AWB_MODE_OFF
		private float white_balance_r = 1;
		private float white_balance_g = 1;
		private float white_balance_b = 1;
		private boolean white_balance_use_rgb = false;
		private ColorTemperature.CIEColor white_balance_xyz;
		private RggbChannelVector white_balance_rggb;
		private float[] white_balance_calibration;
		private String flash_value = "flash_off";
		private boolean manual_mode;
		private boolean force_auto_preview;
		//private int ae_mode = CameraMetadata.CONTROL_AE_MODE_ON;
		//private int flash_mode = CameraMetadata.FLASH_MODE_OFF;
		private int iso;
		private long exposure_time = EXPOSURE_TIME_DEFAULT;
		private boolean manual_iso;
		private int manual_iso_value;
		private boolean manual_iso_less_or_equal;
		private float aperture = 0.0f;
		private Rect scalar_crop_region; // no need for has_scalar_crop_region, as we can set to null instead
		private boolean has_ae_exposure_compensation;
		private int ae_exposure_compensation;
		private boolean has_af_mode;
		private int af_mode = CaptureRequest.CONTROL_AF_MODE_AUTO;
		private boolean focus_mode_manual = false;
		private float focus_distance; // actual value passed to camera device (set to 0.0 if in infinity mode)
		private float focus_distance_manual; // saved setting when in manual mode
		private float focus_distance_calibration = 0;
		private boolean auto_exposure_lock;
		private boolean auto_wb_lock;
		private MeteringRectangle [] af_regions; // no need for has_scalar_crop_region, as we can set to null instead
		private MeteringRectangle [] ae_regions; // no need for has_scalar_crop_region, as we can set to null instead
		private boolean has_face_detect_mode;
		private int face_detect_mode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF;
		private boolean video_stabilization;
		private ColorSpaceTransform default_color_space_transform;
		private int default_color_correction_mode = -1;
		private String tone_mapping;
		private float tone_mapping_gamma;
		private TonemapCurve tone_mapping_curve;
		private Integer default_tonemap_mode; // since we don't know what a device's tonemap mode is, we save it so we can switch back to it
		private TonemapCurve default_tonemap_curve;
		private Range<Integer> ae_target_fps_range;
		private long sensor_frame_duration;
		private int antibanding = -1;
		private int noise_reduction = -1;
		private int edge = -1;
		private int optical_stabilization = -1;
		private int hot_pixel_correction = -1;
		private boolean raw_lens_shading = true;
		
		private int getExifOrientation() {
			int exif_orientation = ExifInterface.ORIENTATION_NORMAL;
			switch( (rotation + 360) % 360 ) {
				case 0:
					exif_orientation = ExifInterface.ORIENTATION_NORMAL;
					break;
				case 90:
					exif_orientation = ExifInterface.ORIENTATION_ROTATE_90;
					break;
				case 180:
					exif_orientation = ExifInterface.ORIENTATION_ROTATE_180;
					break;
				case 270:
					exif_orientation = ExifInterface.ORIENTATION_ROTATE_270;
					break;
				default:
					// leave exif_orientation unchanged
					if( MyDebug.LOG )
						Log.e(TAG, "unexpected rotation: " + rotation);
					break;
			}
			if( MyDebug.LOG ) {
				Log.d(TAG, "rotation: " + rotation);
				Log.d(TAG, "exif_orientation: " + exif_orientation);
			}
			return exif_orientation;
		}

		private void setupBuilder(CaptureRequest.Builder builder, boolean is_still) {
			builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);

			setSceneMode(builder);
			setColorEffect(builder);
			setWhiteBalance(builder);
			setAEMode(builder, is_still);
			setCropRegion(builder);
			setExposureCompensation(builder);
			setFocusMode(builder);
			setFocusDistance(builder);
			setAutoExposureLock(builder);
			setAutoWhiteBalanceLock(builder);
			setBlackLevelLock(builder);
			setAFRegions(builder);
			setAERegions(builder);
			setFaceDetectMode(builder);
			setRawMode(builder);
			setVideoStabilization(builder);
			setColorCorrectionTransform(builder);
			setToneMapping(builder);
			setAntibanding(builder);
			setNoiseReductionMode(builder, is_still);
			setEdgeMode(builder, is_still);
			setOpticalStabilizationMode(builder);
			setHotPixelCorrectionMode(builder);

			if( is_still ) {
				if( location != null ) {
					builder.set(CaptureRequest.JPEG_GPS_LOCATION, location);
				}
				builder.set(CaptureRequest.JPEG_ORIENTATION, rotation);
				builder.set(CaptureRequest.JPEG_QUALITY, jpeg_quality);
			}

			/*builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);
			builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF);
			builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
			if( Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M ) {
				builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_GAMMA_VALUE);
				builder.set(CaptureRequest.TONEMAP_GAMMA, 5.0f);
			}*/
			/*if( Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N ) {
				builder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, 0);
			}*/
			if( MyDebug.LOG ) {
				if( is_still ) {
					Integer nr_mode = builder.get(CaptureRequest.NOISE_REDUCTION_MODE);
					Log.d(TAG, "nr_mode: " + (nr_mode==null ? "null" : nr_mode));
					Integer edge_mode = builder.get(CaptureRequest.EDGE_MODE);
					Log.d(TAG, "edge_mode: " + (edge_mode==null ? "null" : edge_mode));
					Integer cc_mode = builder.get(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE);
					Log.d(TAG, "cc_mode: " + (cc_mode==null ? "null" : cc_mode));
					/*if( Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N ) {
						Integer raw_sensitivity_boost = builder.get(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST);
						Log.d(TAG, "raw_sensitivity_boost: " + (raw_sensitivity_boost==null ? "null" : raw_sensitivity_boost));
					}*/
				}
			}
		}

		private boolean setSceneMode(CaptureRequest.Builder builder) {
			if( MyDebug.LOG ) {
				Log.d(TAG, "setSceneMode");
				Log.d(TAG, "builder: " + builder);
			}
			/*if( builder.get(CaptureRequest.CONTROL_SCENE_MODE) == null && scene_mode == CameraMetadata.CONTROL_SCENE_MODE_DISABLED ) {
				// can leave off
			}
			else*/ if( builder.get(CaptureRequest.CONTROL_SCENE_MODE) == null || builder.get(CaptureRequest.CONTROL_SCENE_MODE) != scene_mode ) {
				if( MyDebug.LOG )
					Log.d(TAG, "setting scene mode: " + scene_mode);
				if( scene_mode == CameraMetadata.CONTROL_SCENE_MODE_DISABLED ) {
					builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
				}
				else {
					builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE);
				}
				builder.set(CaptureRequest.CONTROL_SCENE_MODE, scene_mode);
				return true;
			}
			return false;
		}

		private boolean setColorEffect(CaptureRequest.Builder builder) {
			/*if( builder.get(CaptureRequest.CONTROL_EFFECT_MODE) == null && color_effect == CameraMetadata.CONTROL_EFFECT_MODE_OFF ) {
				// can leave off
			}
			else*/ if( builder.get(CaptureRequest.CONTROL_EFFECT_MODE) == null || builder.get(CaptureRequest.CONTROL_EFFECT_MODE) != color_effect ) {
				if( MyDebug.LOG )
					Log.d(TAG, "setting color effect: " + color_effect);
				builder.set(CaptureRequest.CONTROL_EFFECT_MODE, color_effect);
				return true;
			}
			return false;
		}

		private boolean setWhiteBalance(CaptureRequest.Builder builder) {
			boolean changed = false;
			
			if( builder.get(CaptureRequest.CONTROL_AWB_MODE) == null || builder.get(CaptureRequest.CONTROL_AWB_MODE) != white_balance ) {
				if( MyDebug.LOG )
					Log.d(TAG, "setting white balance: " + white_balance);
				if (default_color_correction_mode != -1 && builder.get(CaptureRequest.CONTROL_AWB_MODE) == CameraMetadata.CONTROL_AWB_MODE_OFF)
					builder.set(CaptureRequest.COLOR_CORRECTION_MODE, default_color_correction_mode);

				builder.set(CaptureRequest.CONTROL_AWB_MODE, white_balance);
				changed = true;
			}
			if( white_balance == CameraMetadata.CONTROL_AWB_MODE_OFF && sensor_color_transform != null ) {
				double r = 2;
				double g = 1;
				double b = 2;

				if (white_balance_use_rgb) {
					r = white_balance_r;
					g = white_balance_g/2;
					b = white_balance_b;
				} else {
					if( MyDebug.LOG )
						Log.d(TAG, "setting white balance temperature: " + white_balance_temperature);
						
					if (default_color_correction_mode == -1)
						default_color_correction_mode = builder.get(CaptureRequest.COLOR_CORRECTION_MODE);

					// manual white balance
					ColorTemperature.RGBColor rgb = new ColorTemperature.CIEColor(white_balance_temperature).toRGB(sensor_color_transform);
					if (white_balance_calibration != null) {
						rgb.r /= white_balance_calibration[0];
						rgb.g /= white_balance_calibration[1];
						rgb.b /= white_balance_calibration[2];
					}
					r = 1/rgb.r;
					g = 1/rgb.g;
					b = 1/rgb.b;
				}

				double min = Math.min(r, g);
				min = Math.min(min, b);
				
				r /= min;
				g /= min;
				b /= min;
				
				white_balance_rggb = new RggbChannelVector((float)r, (float)g, (float)g, (float)b);

				builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
				builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, white_balance_rggb);
				changed = true;
			}
			return changed;
		}

		private boolean setAEMode(CaptureRequest.Builder builder, boolean is_still) {
			if( MyDebug.LOG )
				Log.d(TAG, "setAEMode");
			if( manual_mode ) {
				if( MyDebug.LOG ) {
					Log.d(TAG, "manual mode");
					Log.d(TAG, "iso: " + iso);
					Log.d(TAG, "exposure_time: " + exposure_time);
				}
				int flash_mode = CameraMetadata.FLASH_MODE_OFF;
				if (is_still) {
					if (!previewIsVideoMode && flash_value.equals("flash_on") && !want_expo_bracketing && !want_burst) {
						flash_mode = CameraMetadata.FLASH_MODE_SINGLE;
					} else {
						
					}
				} else {
					if (force_auto_preview) {
						builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
					} else {
						builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
						builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
						long actual_exposure_time = exposure_time;
						if( !is_still ) {
							// if this isn't for still capture, have a max exposure time of 1/10s
							actual_exposure_time = Math.min(exposure_time, 1000000000L/preview_max_exposure);
							if( MyDebug.LOG )
								Log.d(TAG, "actually using exposure_time of: " + actual_exposure_time);
						}
						builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, actual_exposure_time);
						if (sensor_frame_duration > 0) {
							builder.set(CaptureRequest.SENSOR_FRAME_DURATION, sensor_frame_duration);
						}
					}
				}
				
				if( flash_value.equals("flash_torch") ) {
					flash_mode = CameraMetadata.FLASH_MODE_TORCH;
				}
				builder.set(CaptureRequest.FLASH_MODE, flash_mode);

				if (aperture != 0.0f) {
					if( MyDebug.LOG )
						Log.d(TAG, "aperture: " + aperture);
					builder.set(CaptureRequest.LENS_APERTURE, aperture);
				}
			}
			else {
				if( MyDebug.LOG ) {
					Log.d(TAG, "auto mode");
					Log.d(TAG, "flash_value: " + flash_value);
				}
				if( ae_target_fps_range != null ) {
					if( MyDebug.LOG )
						Log.d(TAG, "set ae_target_fps_range: " + ae_target_fps_range);
					builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, ae_target_fps_range);
				}

				// prefer to set flash via the ae mode (otherwise get even worse results), except for torch which we can't
				switch(flash_value) {
					case "flash_off":
						builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
						builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
						break;
					case "flash_auto":
						// note we set this even in fake flash mode (where we manually turn torch on and off to simulate flash) so we
						// can read the FLASH_REQUIRED state to determine if flash is required
						builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH);
						builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
						break;
					case "flash_on":
						// see note above for "flash_auto" for why we set this even fake flash mode - arguably we don't need to know
						// about FLASH_REQUIRED in flash_on mode, but we set it for consistency...
						builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
						builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
						break;
					case "flash_torch":
						builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
						builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
						break;
					case "flash_red_eye":
						// not supported for expo bracketing or burst
						if( CameraController2.this.want_expo_bracketing || CameraController2.this.want_burst )
							builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
						else
							builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE);
						builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
						break;
					case "flash_frontscreen_auto":
					case "flash_frontscreen_on":
						builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
						builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
						break;
				}
			}
			return true;
		}

		private void setCropRegion(CaptureRequest.Builder builder) {
			if( scalar_crop_region != null ) {
				builder.set(CaptureRequest.SCALER_CROP_REGION, scalar_crop_region);
			}
		}

		private boolean setExposureCompensation(CaptureRequest.Builder builder) {
			if( !has_ae_exposure_compensation )
				return false;
			if( manual_mode ) {
				if( MyDebug.LOG )
					Log.d(TAG, "don't set exposure compensation in manual iso mode");
				return false;
			}
			if( builder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION) == null || ae_exposure_compensation != builder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION) ) {
				if( MyDebug.LOG )
					Log.d(TAG, "change exposure to " + ae_exposure_compensation);
				builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ae_exposure_compensation);
				return true;
			}
			return false;
		}

		private void setFocusMode(CaptureRequest.Builder builder) {
			if( has_af_mode ) {
				if( MyDebug.LOG )
					Log.d(TAG, "change af mode to " + af_mode);
				builder.set(CaptureRequest.CONTROL_AF_MODE, af_mode);
			}
		}
		
		private void setFocusDistance(CaptureRequest.Builder builder) {
			if( MyDebug.LOG )
				Log.d(TAG, "change focus distance to " + focus_distance);
			builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus_distance+focus_distance_calibration);
		}

		private void setAutoExposureLock(CaptureRequest.Builder builder) {
			builder.set(CaptureRequest.CONTROL_AE_LOCK, auto_exposure_lock);
		}

		private void setAutoWhiteBalanceLock(CaptureRequest.Builder builder) {
			builder.set(CaptureRequest.CONTROL_AWB_LOCK, auto_wb_lock);
		}

		private void setBlackLevelLock(CaptureRequest.Builder builder) {
			builder.set(CaptureRequest.BLACK_LEVEL_LOCK, auto_exposure_lock);
		}

		private void setAFRegions(CaptureRequest.Builder builder) {
			if( af_regions != null && characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) > 0 ) {
				builder.set(CaptureRequest.CONTROL_AF_REGIONS, af_regions);
			}
		}

		private void setAERegions(CaptureRequest.Builder builder) {
			if( ae_regions != null && characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) > 0 ) {
				builder.set(CaptureRequest.CONTROL_AE_REGIONS, ae_regions);
			}
		}

		private void setFaceDetectMode(CaptureRequest.Builder builder) {
			if( has_face_detect_mode )
				builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, face_detect_mode);
			else
				builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF);
		}
		
		private void setRawMode(CaptureRequest.Builder builder) {
			// DngCreator says "For best quality DNG files, it is strongly recommended that lens shading map output is enabled if supported"
			// docs also say "ON is always supported on devices with the RAW capability", so we don't check for STATISTICS_LENS_SHADING_MAP_MODE_ON being available
			if( want_raw && !previewIsVideoMode ) {
				builder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, raw_lens_shading ? CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON : CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_OFF);
			}
		}
		
		private void setVideoStabilization(CaptureRequest.Builder builder) {
			builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, video_stabilization ? CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON : CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
		}
		
		private void setColorCorrectionTransform(CaptureRequest.Builder builder) {
			if (default_color_space_transform != null)
				builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, default_color_space_transform);
		}

		private void setToneMapping(CaptureRequest.Builder builder) {
			if( MyDebug.LOG ) {
				Log.d(TAG, "setToneMapping");
				Log.d(TAG, "default_tonemap_mode: " + default_tonemap_mode);
			}
			if (tone_mapping_gamma > 1.0f || tone_mapping != null || tone_mapping_curve != null) {
				if( default_tonemap_mode == null ) {
					// save the default tonemap_mode
					default_tonemap_mode = builder.get(CaptureRequest.TONEMAP_MODE);
					if( MyDebug.LOG )
						Log.d(TAG, "default_tonemap_mode: " + default_tonemap_mode);
				}
			}
			if (tone_mapping != null) {
				switch (tone_mapping) {
					case "srgb":
						if( MyDebug.LOG )
							Log.d(TAG, "preset curve: TONEMAP_PRESET_CURVE_SRGB");
						builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_PRESET_CURVE);
						builder.set(CaptureRequest.TONEMAP_PRESET_CURVE, CaptureRequest.TONEMAP_PRESET_CURVE_SRGB);
						break;
					case "rec709":
						if( MyDebug.LOG )
							Log.d(TAG, "preset curve: TONEMAP_PRESET_CURVE_REC709");
						builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_PRESET_CURVE);
						builder.set(CaptureRequest.TONEMAP_PRESET_CURVE, CaptureRequest.TONEMAP_PRESET_CURVE_REC709);
						break;
				}
			} else if (tone_mapping_gamma > 1.0f) {
				double gamma = 1/tone_mapping_gamma;
				float [] values = new float[2*tonemap_max_curve_points_c];
				for(int i = 0; i < tonemap_max_curve_points_c; i++) {
					float value = ((float)i) / (float)(tonemap_max_curve_points_c-1);
					values[i*2+1] = (float)Math.pow(value, gamma);
					values[i*2] = value;
				}

				if( MyDebug.LOG ) {
					Log.d(TAG, "values: " + Arrays.toString(values));
				}
				builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);
				TonemapCurve tonemap_curve = new TonemapCurve(values, values, values);
				builder.set(CaptureRequest.TONEMAP_CURVE, tonemap_curve);
/*				else {
					if( MyDebug.LOG )
						Log.d(TAG, "tone_mapping_gamma: " + tone_mapping_gamma);
					builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_GAMMA_VALUE);
					builder.set(CaptureRequest.TONEMAP_GAMMA, tone_mapping_gamma);
				}*/
			} else if (tone_mapping_curve != null) {
				builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);
				builder.set(CaptureRequest.TONEMAP_CURVE, tone_mapping_curve);
			} else {
				if( default_tonemap_mode != null )
					builder.set(CaptureRequest.TONEMAP_MODE, default_tonemap_mode);
				if( default_tonemap_curve != null )
					builder.set(CaptureRequest.TONEMAP_CURVE, default_tonemap_curve);
			}
		}

		private void setAntibanding(CaptureRequest.Builder builder) {
			if (antibanding != -1) {
				builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, antibanding);
			}
		}

		private boolean setNoiseReductionMode(CaptureRequest.Builder builder, boolean is_still) {
			if (noise_reduction != -1 && (!filtering_capture_only || is_still)) {
				builder.set(CaptureRequest.NOISE_REDUCTION_MODE, noise_reduction);
				return true;
			}
			return false;
		}

		private boolean setEdgeMode(CaptureRequest.Builder builder, boolean is_still) {
			if (edge != -1 && (!filtering_capture_only || is_still)) {
				builder.set(CaptureRequest.EDGE_MODE, edge);
				return true;
			}
			return false;
		}

		private boolean setOpticalStabilizationMode(CaptureRequest.Builder builder) {
			if (optical_stabilization != -1) {
				if (optical_stabilization_if_necessary) {
					builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, previewIsVideoMode ? 
						CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON : CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
				} else {
					builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, optical_stabilization);
				}
				return true;
			}
			return false;
		}

		private boolean setHotPixelCorrectionMode(CaptureRequest.Builder builder) {
			if (hot_pixel_correction != -1) {
				builder.set(CaptureRequest.HOT_PIXEL_MODE, hot_pixel_correction);
				return true;
			}
			return false;
		}
		
		// n.b., if we add more methods, remember to update setupBuilder() above!
	}

	private double[][] matrixFromColorSpaceTransform(ColorSpaceTransform cst) {
		int [] arr = new int [18];
		cst.copyElements(arr, 0);

		double[][] matrix = new double[3][3];
		for (int y = 0; y < 3; y++) {
			for (int x = 0; x < 3; x++) {
				int arr_i = (x+y*3)*2;
				// Arr!!! Why so ugly - numerators and denominators instead of floats or doubles???
				matrix[y][x] = ((double)arr[arr_i])/((double)arr[arr_i+1]);
			}
		}
		
		return matrix;
	}

	void setCaptureResult(CaptureResult capture_result) {
		if( MyDebug.LOG )
			Log.d(TAG, "setCaptureResult()");
		synchronized( image_reader_lock ) {
			/* synchronize, as we don't want to set the capture_result, at the same time that onImageAvailable() is called, as
			 * we'll end up calling processImage() both in onImageAvailable() and here.
			 */
			final long timestamp = capture_result.get(CaptureResult.SENSOR_TIMESTAMP);
			if( MyDebug.LOG ) {
				Log.d(TAG, "CaptureResult timestamp: " + timestamp);
			}

			int index = pending_photos_timestamps.indexOf(timestamp);
			if (index >= 0) {
				Photo photo = pending_photos.get(index);
				photo.captureResult = capture_result;

				if (isPhotoReady(photo)) {
					pending_photos.remove(index);
					pending_photos_timestamps.remove(index);
					imageAvailable(photo);
				}
			} else {
				Photo photo = new Photo();
				photo.captureResult = capture_result;
				if (isPhotoReady(photo)) {
					imageAvailable(photo);
				} else {
					pending_photos_timestamps.add(timestamp);
					pending_photos.add(photo);
				}
			}
		}
	}

	private void outOfMemory() {
		if( MyDebug.LOG )
			Log.d(TAG, "outOfMmory()");
		handler.removeCallbacksAndMessages(null);
		handler.postDelayed(new Runnable() {
			private int count = 0;
			@Override
			public void run() {
				count = count+1;
				if( MyDebug.LOG )
					Log.d(TAG, "trying to clean ImageReaders: " + count);

				synchronized( image_reader_lock ) {
					try {
						Image image;
						if( imageReader != null ) {
							image = imageReader.acquireLatestImage();
							if (image != null)
								image.close();
						}
						if( imageReaderUncompressed != null ) {
							image = imageReaderUncompressed.acquireLatestImage();
							if (image != null)
								image.close();
						}
						if( imageReaderRaw != null ) {
							image = imageReaderRaw.acquireLatestImage();
							if (image != null)
								image.close();
						}

						if (jpeg_cb != null)
							jpeg_cb.onCompleted();
					} catch(IllegalStateException e) {
						if (count > 30) {
							Utils.getMainActivity().runOnUiThread(new Runnable() {
								@Override
								public void run() {
									Utils.getMainActivity().restartActivity();
								}
							});
						} else
							handler.postDelayed(this, 100);
					}
					
				}
			}
		}, 100);
	}

	private final CameraSettings camera_settings = new CameraSettings();
	private boolean push_repeating_request_when_torch_off = false;
	private String push_repeating_request_when_torch_off_value = null;
	/*private boolean push_set_ae_lock = false;
	private CaptureRequest push_set_ae_lock_id = null;*/

	private CaptureRequest fake_precapture_turn_on_torch_id = null; // the CaptureRequest used to turn on torch when starting the "fake" precapture

	@Override
	public void onError() {
		Log.e(TAG, "onError");
		if( camera != null ) {
			onError(camera);
		}
	}

	private void onError(@NonNull CameraDevice cam) {
		Log.e(TAG, "onError");
		boolean camera_already_opened = this.camera != null;
		// need to set the camera to null first, as closing the camera may take some time, and we don't want any other operations to continue (if called from main thread)
		this.camera = null;
		if( MyDebug.LOG )
			Log.d(TAG, "onError: camera is now set to null");
		cam.close();
		if( MyDebug.LOG )
			Log.d(TAG, "onError: camera is now closed");

		if( camera_already_opened ) {
			// need to communicate the problem to the application
			// n.b., as this is potentially serious error, we always log even if MyDebug.LOG is false
			Log.e(TAG, "error occurred after camera was opened");
			camera_error_cb.onError();
		}
	}

	/** Opens the camera device.
	 * @param context Application context.
	 * @param cameraId Which camera to open (must be between 0 and CameraControllerManager2.getNumberOfCameras()-1).
	 * @param preview_error_cb onError() will be called if the preview stops due to error.
	 * @param camera_error_cb onError() will be called if the camera closes due to serious error. No more calls to the CameraController2 object should be made (though a new one can be created, to try reopening the camera).
	 * @throws CameraControllerException if the camera device fails to open.
	 */
	public CameraController2(Context context, int cameraId, final ErrorCallback preview_error_cb, final ErrorCallback camera_error_cb) throws CameraControllerException {
		super(cameraId);
		if( MyDebug.LOG ) {
			Log.d(TAG, "create new CameraController2: " + cameraId);
			Log.d(TAG, "this: " + this);
		}

		this.context = context;
		this.preview_error_cb = preview_error_cb;
		this.camera_error_cb = camera_error_cb;

		thread = new HandlerThread("CameraBackground");
		thread.start();
		handler = new Handler(thread.getLooper());

		final CameraManager manager = (CameraManager)context.getSystemService(Context.CAMERA_SERVICE);

		class MyStateCallback extends CameraDevice.StateCallback {
			boolean callback_done; // must sychronize on this and notifyAll when setting to true
			boolean first_callback = true; // Google Camera says we may get multiple callbacks, but only the first indicates the status of the camera opening operation
			@Override
			public void onOpened(@NonNull CameraDevice cam) {
				if( MyDebug.LOG )
					Log.d(TAG, "camera opened, first_callback? " + first_callback);
				/*if( true ) // uncomment to test timeout code
					return;*/
				if( first_callback ) {
					first_callback = false;

					try {
						// we should be able to get characteristics at any time, but Google Camera only does so when camera opened - so do so similarly to be safe
						if( MyDebug.LOG )
							Log.d(TAG, "try to get camera characteristics");
						characteristics = manager.getCameraCharacteristics(cameraIdS);
						if( MyDebug.LOG )
							Log.d(TAG, "successfully obtained camera characteristics");

						CameraController2.this.camera = cam;

						exposure_time_range = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE); // may be null on some devices
						iso_range = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE); // may be null on some devices

						// note, this won't start the preview yet, but we create the previewBuilder in order to start setting camera parameters
						createPreviewRequest();
					}
					catch(CameraAccessException e) {
						if( MyDebug.LOG ) {
							Log.e(TAG, "failed to get camera characteristics");
							Log.e(TAG, "reason: " + e.getReason());
							Log.e(TAG, "message: " + e.getMessage());
						}
						e.printStackTrace();
						// don't throw CameraControllerException here - instead error is handled by setting callback_done to callback_done, and the fact that camera will still be null
					}

					if( MyDebug.LOG )
						Log.d(TAG, "about to synchronize to say callback done");
					synchronized( open_camera_lock ) {
						callback_done = true;
						if( MyDebug.LOG )
							Log.d(TAG, "callback done, about to notify");
						open_camera_lock.notifyAll();
						if( MyDebug.LOG )
							Log.d(TAG, "callback done, notification done");
					}
				}
			}

			@Override
			public void onClosed(@NonNull CameraDevice cam) {
				if( MyDebug.LOG )
					Log.d(TAG, "camera closed, first_callback? " + first_callback);
				// caller should ensure camera variables are set to null
				if( first_callback ) {
					first_callback = false;
				}
			}

			@Override
			public void onDisconnected(@NonNull CameraDevice cam) {
				if( MyDebug.LOG )
					Log.d(TAG, "camera disconnected, first_callback? " + first_callback);
				if( first_callback ) {
					first_callback = false;
					// must call close() if disconnected before camera was opened
					// need to set the camera to null first, as closing the camera may take some time, and we don't want any other operations to continue (if called from main thread)
					CameraController2.this.camera = null;
					if( MyDebug.LOG )
						Log.d(TAG, "onDisconnected: camera is now set to null");
					cam.close();
					if( MyDebug.LOG )
						Log.d(TAG, "onDisconnected: camera is now closed");
					if( MyDebug.LOG )
						Log.d(TAG, "about to synchronize to say callback done");
					synchronized( open_camera_lock ) {
						callback_done = true;
						if( MyDebug.LOG )
							Log.d(TAG, "callback done, about to notify");
						open_camera_lock.notifyAll();
						if( MyDebug.LOG )
							Log.d(TAG, "callback done, notification done");
					}
				}
			}

			@Override
			public void onError(@NonNull CameraDevice cam, int error) {
				// n.b., as this is potentially serious error, we always log even if MyDebug.LOG is false
				Log.e(TAG, "camera error: " + error);
				if( MyDebug.LOG ) {
					Log.d(TAG, "received camera: " + cam);
					Log.d(TAG, "actual camera: " + CameraController2.this.camera);
					Log.d(TAG, "first_callback? " + first_callback);
				}
				if( first_callback ) {
					first_callback = false;
				}
				CameraController2.this.onError(cam);
				if( MyDebug.LOG )
					Log.d(TAG, "about to synchronize to say callback done");
				synchronized( open_camera_lock ) {
					callback_done = true;
					if( MyDebug.LOG )
						Log.d(TAG, "callback done, about to notify");
					open_camera_lock.notifyAll();
					if( MyDebug.LOG )
						Log.d(TAG, "callback done, notification done");
				}
			}
		}
		final MyStateCallback myStateCallback = new MyStateCallback();

		try {
			if( MyDebug.LOG )
				Log.d(TAG, "get camera id list");
			this.cameraIdS = manager.getCameraIdList()[cameraId];
			if( MyDebug.LOG )
				Log.d(TAG, "about to open camera: " + cameraIdS);
			manager.openCamera(cameraIdS, myStateCallback, handler);
			if( MyDebug.LOG )
				Log.d(TAG, "open camera request complete");
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to open camera: CameraAccessException");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
			throw new CameraControllerException();
		}
		catch(UnsupportedOperationException e) {
			// Google Camera catches UnsupportedOperationException
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to open camera: UnsupportedOperationException");
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
			throw new CameraControllerException();
		}
		catch(SecurityException e) {
			// Google Camera catches SecurityException
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to open camera: SecurityException");
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
			throw new CameraControllerException();
		}
		catch(IllegalArgumentException e) {
			// have seen this from Google Play
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to open camera: IllegalArgumentException");
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
			throw new CameraControllerException();
		}
		catch(ArrayIndexOutOfBoundsException e) {
			// Have seen this from Google Play - even though the Preview should have checked the
			// cameraId is within the valid range! Although potentially this could happen if
			// getCameraIdList() returns an empty list.
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to open camera: ArrayIndexOutOfBoundsException");
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
			throw new CameraControllerException();
		}

		// set up a timeout - sometimes if the camera has got in a state where it can't be opened until after a reboot, we'll never even get a myStateCallback callback called
		handler.postDelayed(new Runnable() {
			@Override
			public void run() {
				if( MyDebug.LOG )
					Log.d(TAG, "check if camera has opened in reasonable time: " + this);
				synchronized( open_camera_lock ) {
					if( MyDebug.LOG ) {
						Log.d(TAG, "synchronized on open_camera_lock");
						Log.d(TAG, "callback_done: " + myStateCallback.callback_done);
					}
					if( !myStateCallback.callback_done ) {
						// n.b., as this is potentially serious error, we always log even if MyDebug.LOG is false
						Log.e(TAG, "timeout waiting for camera callback");
						myStateCallback.first_callback = true;
						myStateCallback.callback_done = true;
						open_camera_lock.notifyAll();
					}
				}
			}
		}, 10000);

		if( MyDebug.LOG )
			Log.d(TAG, "wait until camera opened...");
		// need to wait until camera is opened
		// whilst this blocks, this should be running on a background thread anyway (see Preview.openCamera()) - due to maintaining
		// compatibility with the way the old camera API works, it's easier to handle running on a background thread at a higher level,
		// rather than exiting here
		synchronized( open_camera_lock ) {
			while( !myStateCallback.callback_done ) {
				try {
					// release the lock, and wait until myStateCallback calls notifyAll()
					open_camera_lock.wait();
				}
				catch(InterruptedException e) {
					if( MyDebug.LOG )
						Log.d(TAG, "interrupted while waiting until camera opened");
					e.printStackTrace();
				}
			}
		}
		if( camera == null ) {
			// n.b., as this is potentially serious error, we always log even if MyDebug.LOG is false
			Log.e(TAG, "camera failed to open");
			throw new CameraControllerException();
		}
		if( MyDebug.LOG )
			Log.d(TAG, "camera now opened: " + camera);

		/*{
			// test error handling
			final Handler handler = new Handler();
			handler.postDelayed(new Runnable() {
				@Override
				public void run() {
					if( MyDebug.LOG )
						Log.d(TAG, "test camera error");
					myStateCallback.onError(camera, CameraDevice.StateCallback.ERROR_CAMERA_DEVICE);
				}
			}, 5000);
		}*/
		
	}

	@Override
	public void release() {
		if( MyDebug.LOG )
			Log.d(TAG, "release: " + this);
		if( captureSession != null ) {
			captureSession.close();
			captureSession = null;
			//pending_request_when_ready = null;
		}
		previewBuilder = null;
		previewIsVideoMode = false;
		if( camera != null ) {
			camera.close();
			camera = null;
		}
		closePictureImageReader();
		/*if( previewImageReader != null ) {
			previewImageReader.close();
			previewImageReader = null;
		}*/
		if( thread != null ) {
			// should only close thread after closing the camera, otherwise we get messages "sending message to a Handler on a dead thread"
			// see https://sourceforge.net/p/opencamera/discussion/general/thread/32c2b01b/?limit=25
			thread.quitSafely();
			try {
				thread.join();
				thread = null;
				handler = null;
			}
			catch(InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	private void closePictureImageReader() {
		if( MyDebug.LOG )
			Log.d(TAG, "closePictureImageReader()");
		if( imageReader != null ) {
			if (!alt_image_holder || !imageReaderInUse)
				imageReader.close();
			imageReader = null;
		}
		if( imageReaderUncompressed != null ) {
			if (!alt_image_holder || !imageReaderInUse)
				imageReaderUncompressed.close();
			imageReaderUncompressed = null;
		}
		if( imageReaderRaw != null ) {
			if (!imageReaderInUse)
				imageReaderRaw.close();
			imageReaderRaw = null;
		}
	}

	private List<String> convertFocusModesToValues(int [] supported_focus_modes_arr, float minimum_focus_distance) {
		if( MyDebug.LOG )
			Log.d(TAG, "convertFocusModesToValues()");
		if( supported_focus_modes_arr.length == 0 )
			return null;
		List<Integer> supported_focus_modes = new ArrayList<>();
		for(Integer supported_focus_mode : supported_focus_modes_arr)
			supported_focus_modes.add(supported_focus_mode);
		List<String> output_modes = new ArrayList<>();
		// also resort as well as converting
		if( supported_focus_modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ) {
			output_modes.add("focus_mode_auto");
			if( MyDebug.LOG ) {
				Log.d(TAG, " supports focus_mode_auto");
			}
		}
		if( supported_focus_modes.contains(CaptureRequest.CONTROL_AF_MODE_MACRO) ) {
			output_modes.add("focus_mode_macro");
			if( MyDebug.LOG )
				Log.d(TAG, " supports focus_mode_macro");
		}
		if( supported_focus_modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ) {
			output_modes.add("focus_mode_locked");
			if( MyDebug.LOG ) {
				Log.d(TAG, " supports focus_mode_locked");
			}
		}
		if( supported_focus_modes.contains(CaptureRequest.CONTROL_AF_MODE_OFF) ) {
			output_modes.add("focus_mode_infinity");
			if( minimum_focus_distance > 0.0f ) {
				output_modes.add("focus_mode_manual2");
				if( MyDebug.LOG ) {
					Log.d(TAG, " supports focus_mode_manual2");
				}
			}
		}
		if( supported_focus_modes.contains(CaptureRequest.CONTROL_AF_MODE_EDOF) ) {
			output_modes.add("focus_mode_edof");
			if( MyDebug.LOG )
				Log.d(TAG, " supports focus_mode_edof");
		}
		if( supported_focus_modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ) {
			output_modes.add("focus_mode_continuous_picture");
			if( MyDebug.LOG )
				Log.d(TAG, " supports focus_mode_continuous_picture");
		}
		if( supported_focus_modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) ) {
			output_modes.add("focus_mode_continuous_video");
			if( MyDebug.LOG )
				Log.d(TAG, " supports focus_mode_continuous_video");
		}
		return output_modes;
	}

	public String getAPI() {
		return "Camera2 (Android L)";
	}

	@Override
	public CameraFeatures getCameraFeatures() throws CameraControllerException {
		if( MyDebug.LOG )
			Log.d(TAG, "getCameraFeatures()");
		CameraFeatures camera_features = new CameraFeatures();
		{
			int hardware_level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
			if( hardware_level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY ) {
				if( MyDebug.LOG ) Log.d(TAG, "Hardware Level: LEGACY");
				camera_features.hardware_level = "legacy";
			} else if( hardware_level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED ) {
				if( MyDebug.LOG ) Log.d(TAG, "Hardware Level: LIMITED");
				camera_features.hardware_level = "limited";
			} else if( hardware_level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL ) {
				if( MyDebug.LOG ) Log.d(TAG, "Hardware Level: FULL");
				camera_features.hardware_level = "full";
			} else if( hardware_level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 ) {
				if( MyDebug.LOG ) Log.d(TAG, "Hardware Level: 3");
				camera_features.hardware_level = "3";
			} else {
				if( MyDebug.LOG ) Log.e(TAG, "Unknown Hardware Level!");
				camera_features.hardware_level = "unknown";
			}
		}

		float max_zoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
		camera_features.is_zoom_supported = max_zoom > 0.0f;
		if( MyDebug.LOG )
			Log.d(TAG, "max_zoom: " + max_zoom);
		if( camera_features.is_zoom_supported ) {
			// set 20 steps per 2x factor
			final int steps_per_2x_factor = 50;
			//final double scale_factor = Math.pow(2.0, 1.0/(double)steps_per_2x_factor);
			int n_steps =(int)( (steps_per_2x_factor * Math.log(max_zoom + 1.0e-11)) / Math.log(2.0));
			final double scale_factor = Math.pow(max_zoom, 1.0/(double)n_steps);
			if( MyDebug.LOG ) {
				Log.d(TAG, "n_steps: " + n_steps);
				Log.d(TAG, "scale_factor: " + scale_factor);
			}
			camera_features.zoom_ratios = new ArrayList<>();
			camera_features.zoom_ratios.add(100);
			double zoom = 1.0;
			for(int i=0;i<n_steps-1;i++) {
				zoom *= scale_factor;
				camera_features.zoom_ratios.add((int)(zoom*100));
			}
			camera_features.zoom_ratios.add((int)(max_zoom*100));
			camera_features.max_zoom = camera_features.zoom_ratios.size()-1;
			this.zoom_ratios = camera_features.zoom_ratios;
		}
		else {
			this.zoom_ratios = null;
		}

		int [] face_modes = characteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
		camera_features.supports_face_detection = false;
		supports_face_detect_mode_simple = false;
		supports_face_detect_mode_full = false;
		for(int face_mode : face_modes) {
			if( MyDebug.LOG )
				Log.d(TAG, "face detection mode: " + face_mode);
			// we currently only make use of the "SIMPLE" features, documented as:
			// "Return face rectangle and confidence values only."
			// note that devices that support STATISTICS_FACE_DETECT_MODE_FULL (e.g., Nexus 6) don't return
			// STATISTICS_FACE_DETECT_MODE_SIMPLE in the list, so we have check for either
			if( face_mode == CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_SIMPLE ) {
				camera_features.supports_face_detection = true;
				supports_face_detect_mode_simple = true;
				if( MyDebug.LOG )
					Log.d(TAG, "supports simple face detection mode");
			}
			else if( face_mode == CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_FULL ) {
				camera_features.supports_face_detection = true;
				supports_face_detect_mode_full = true;
				if( MyDebug.LOG )
					Log.d(TAG, "supports full face detection mode");
			}
		}
		if( camera_features.supports_face_detection ) {
			int face_count = characteristics.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT);
			if( face_count <= 0 ) {
				if( MyDebug.LOG )
					Log.d(TAG, "can't support face detection, as zero max face count");
				camera_features.supports_face_detection = false;
				supports_face_detect_mode_simple = false;
				supports_face_detect_mode_full = false;
			}
		}
		if( camera_features.supports_face_detection ) {
			// check we have scene mode CONTROL_SCENE_MODE_FACE_PRIORITY
			int [] values2 = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES);
			boolean has_face_priority = false;
			for(int value2 : values2) {
				if( value2 == CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY ) {
					has_face_priority = true;
					break;
				}
			}
			if( MyDebug.LOG )
				Log.d(TAG, "has_face_priority: " + has_face_priority);
			if( !has_face_priority ) {
				if( MyDebug.LOG )
					Log.d(TAG, "can't support face detection, as no CONTROL_SCENE_MODE_FACE_PRIORITY");
				camera_features.supports_face_detection = false;
				supports_face_detect_mode_simple = false;
				supports_face_detect_mode_full = false;
			}
		}

		if( MyDebug.LOG ) {
			int[] ois_modes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION); // may be null on some devices
			if (ois_modes != null) {
				for (int ois_mode : ois_modes) {
					if (MyDebug.LOG)
						Log.d(TAG, "ois mode: " + ois_mode);
					if (ois_mode == CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) {
						if (MyDebug.LOG)
							Log.d(TAG, "supports ois");
					}
				}
			}
		}

		int [] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
		boolean capabilities_raw = false;
		boolean capabilities_high_speed_video = false;
		for(int capability : capabilities) {
			if( capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW ) {
				capabilities_raw = true;
			}
			else if( capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) {
				// we test for at least Android M just to be safe (this is needed for createConstrainedHighSpeedCaptureSession())
				capabilities_high_speed_video = true;
			}
		}
		if( MyDebug.LOG ) {
			Log.d(TAG, "capabilities_raw?: " + capabilities_raw);
			Log.d(TAG, "capabilities_high_speed_video?: " + capabilities_high_speed_video);
		}

		StreamConfigurationMap configs;
		try {
			configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
		}
		catch(IllegalArgumentException | NullPointerException e) {
			// have had IllegalArgumentException crashes from Google Play - unclear what the cause is, but at least fail gracefully
			// similarly for NullPointerException - note, these aren't from characteristics being null, but from
			// com.android.internal.util.Preconditions.checkArrayElementsNotNull (Preconditions.java:395) - all are from
			// Nexus 7 (2013)s running Android 8.1, but again better to fail gracefully
			e.printStackTrace();
			throw new CameraControllerException();
		}

		camera_features.picture_sizes = new ArrayList<>();
		android.util.Size [] camera_picture_sizes = configs.getOutputSizes(ImageFormat.JPEG);
		if (camera_picture_sizes != null) {
			for(android.util.Size camera_size : camera_picture_sizes) {
				if( MyDebug.LOG )
					Log.d(TAG, "picture size: " + camera_size.getWidth() + " x " + camera_size.getHeight());
				camera_features.picture_sizes.add(new CameraController.Size(camera_size.getWidth(), camera_size.getHeight()));
			}
		}
		if( Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M ) {
			camera_picture_sizes = configs.getHighResolutionOutputSizes(ImageFormat.JPEG);
			if (camera_picture_sizes != null) {
				for(android.util.Size camera_size : camera_picture_sizes) {
					if( MyDebug.LOG )
						Log.d(TAG, "hi-res picture size: " + camera_size.getWidth() + " x " + camera_size.getHeight());
					camera_features.picture_sizes.add(new CameraController.Size(camera_size.getWidth(), camera_size.getHeight()));
				}
			}
		}

		raw_size = null;
		if( capabilities_raw ) {
			android.util.Size [] raw_camera_picture_sizes = configs.getOutputSizes(ImageFormat.RAW_SENSOR);
			if( raw_camera_picture_sizes == null ) {
				if( MyDebug.LOG )
					Log.d(TAG, "RAW not supported, failed to get RAW_SENSOR sizes");
				want_raw = false; // just in case it got set to true somehow
			}
			else {
				for(android.util.Size size : raw_camera_picture_sizes) {
					if( raw_size == null || size.getWidth()*size.getHeight() > raw_size.getWidth()*raw_size.getHeight() ) {
						raw_size = size;
					}
				}
				if( Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M ) {
					raw_camera_picture_sizes = configs.getHighResolutionOutputSizes(ImageFormat.RAW_SENSOR);
					if (raw_camera_picture_sizes != null) {
						for(android.util.Size size : raw_camera_picture_sizes) {
							if( raw_size == null || size.getWidth()*size.getHeight() > raw_size.getWidth()*raw_size.getHeight() ) {
								raw_size = size;
							}
						}
					}
				}
				if( raw_size == null ) {
					if( MyDebug.LOG )
						Log.d(TAG, "RAW not supported, failed to find a raw size");
					want_raw = false; // just in case it got set to true somehow
				}
				else {
					if( MyDebug.LOG )
						Log.d(TAG, "raw supported, raw size: " + raw_size.getWidth() + " x " + raw_size.getHeight());
					camera_features.supports_raw = true;
				}
			}
		}
		else {
			if( MyDebug.LOG )
				Log.d(TAG, "RAW capability not supported");
			want_raw = false; // just in case it got set to true somehow
		}
		
		ae_fps_ranges = new ArrayList<>();
		for (Range<Integer> r : characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)) {
			ae_fps_ranges.add(new int[] {r.getLower(), r.getUpper()});
		}
		Collections.sort(ae_fps_ranges, new CameraController.RangeSorter());
		if( MyDebug.LOG ) {
			Log.d(TAG, "Supported AE video fps ranges: ");
			for (int[] f : ae_fps_ranges) {
				Log.d(TAG, "   ae range: [" + f[0] + "-" + f[1] + "]");
			}
		}

		android.util.Size [] camera_video_sizes = configs.getOutputSizes(MediaRecorder.class);
		camera_features.video_sizes = new ArrayList<>();
		int min_fps = 9999;
		for(int[] r : this.ae_fps_ranges) {
			min_fps = Math.min(min_fps, r[0]);
		}
		if (camera_video_sizes != null) {
			for(android.util.Size camera_size : camera_video_sizes) {
				if( MyDebug.LOG )
					Log.d(TAG, "video size: " + camera_size.getWidth() + " x " + camera_size.getHeight());
				if( camera_size.getWidth() > 4096 || camera_size.getHeight() > 2160 )
					continue; // Nexus 6 returns these, even though not supported?!
				long mfd = configs.getOutputMinFrameDuration(MediaRecorder.class, camera_size);
				int  max_fps = (int)((1.0 / mfd) * 1000000000L);
				ArrayList<int[]> fr = new ArrayList<>();
				fr.add(new int[] {min_fps, max_fps});
				CameraController.Size normal_video_size = new CameraController.Size(camera_size.getWidth(), camera_size.getHeight(), fr, false);
				camera_features.video_sizes.add(normal_video_size);
				if( MyDebug.LOG ) {
					Log.d(TAG, "normal video size: " + normal_video_size);
				}
			}
			Collections.sort(camera_features.video_sizes, new CameraController.SizeSorter());
		}

		if( capabilities_high_speed_video ) {
			hs_fps_ranges = new ArrayList<>();
			camera_features.video_sizes_high_speed = new ArrayList<>();

			for (Range<Integer> r : configs.getHighSpeedVideoFpsRanges()) {
				hs_fps_ranges.add(new int[] {r.getLower(), r.getUpper()});
			}
			Collections.sort(hs_fps_ranges, new CameraController.RangeSorter());
			if( MyDebug.LOG ) {
				Log.d(TAG, "Supported high speed video fps ranges: ");
				for (int[] f : hs_fps_ranges) {
					Log.d(TAG, "   hs range: [" + f[0] + "-" + f[1] + "]");
				}
			}

			android.util.Size[] camera_video_sizes_high_speed = configs.getHighSpeedVideoSizes();
			for (android.util.Size camera_size : camera_video_sizes_high_speed) {
				ArrayList<int[]> fr = new ArrayList<>();
				for (Range<Integer> r : configs.getHighSpeedVideoFpsRangesFor(camera_size)) {
					fr.add(new int[] { r.getLower(), r.getUpper()});
				}
				if (camera_size.getWidth() > 4096 || camera_size.getHeight() > 2160)
					continue; // just in case? see above
				CameraController.Size hs_video_size = new CameraController.Size(camera_size.getWidth(), camera_size.getHeight(), fr, true);
				if (MyDebug.LOG) {
					Log.d(TAG, "high speed video size: " + hs_video_size);
			}
				camera_features.video_sizes_high_speed.add(hs_video_size);
			}
			Collections.sort(camera_features.video_sizes_high_speed, new CameraController.SizeSorter());
		}

		android.util.Size [] camera_preview_sizes = configs.getOutputSizes(SurfaceTexture.class);
		camera_features.preview_sizes = new ArrayList<>();
		for(android.util.Size camera_size : camera_preview_sizes) {
			if( MyDebug.LOG )
				Log.d(TAG, "preview size: " + camera_size.getWidth() + " x " + camera_size.getHeight());
			camera_features.preview_sizes.add(new CameraController.Size(camera_size.getWidth(), camera_size.getHeight()));
		}

		if( characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ) {
			camera_features.supported_flash_values = new ArrayList<>();
			camera_features.supported_flash_values.add("flash_off");
			camera_features.supported_flash_values.add("flash_auto");
			camera_features.supported_flash_values.add("flash_on");
			camera_features.supported_flash_values.add("flash_torch");
			if( fake_flash == FakeFlashMode.None ) {
				camera_features.supported_flash_values.add("flash_red_eye");
			}
		}
		else if( isFrontFacing() ) {
			camera_features.supported_flash_values = new ArrayList<>();
			camera_features.supported_flash_values.add("flash_off");
			camera_features.supported_flash_values.add("flash_frontscreen_auto");
			camera_features.supported_flash_values.add("flash_frontscreen_on");
		}

		Float minimum_focus_distance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE); // may be null on some devices
		if( minimum_focus_distance != null ) {
			camera_features.minimum_focus_distance = minimum_focus_distance;
			if( MyDebug.LOG )
				Log.d(TAG, "minimum_focus_distance: " + camera_features.minimum_focus_distance);
		}
		else {
			camera_features.minimum_focus_distance = 0.0f;
		}

		int [] supported_focus_modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES); // Android format
		camera_features.supported_focus_values = convertFocusModesToValues(supported_focus_modes, camera_features.minimum_focus_distance); // convert to our format (also resorts)
		camera_features.max_num_focus_areas = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
		camera_features.max_num_metering_areas = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);

		camera_features.is_auto_adjustment_lock_supported = true;
		
		camera_features.is_video_stabilization_supported = false;
		int [] supported_video_stabilization_modes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
		if( supported_video_stabilization_modes != null ) {
			for(int supported_video_stabilization_mode : supported_video_stabilization_modes) {
				if( supported_video_stabilization_mode == CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON ) {
					camera_features.is_video_stabilization_supported = true;
					break;
				}
			}
		}
		if( MyDebug.LOG )
			Log.d(TAG, "is_video_stabilization_supported: " + camera_features.is_video_stabilization_supported);

		// although we currently require at least LIMITED to offer Camera2, we explicitly check here in case we do ever support
		// LEGACY devices
		camera_features.is_photo_video_recording_supported = CameraControllerManager2.isHardwareLevelSupported(characteristics, CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED);
		supports_photo_video_recording = camera_features.is_photo_video_recording_supported;

		int [] white_balance_modes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
		if( white_balance_modes != null ) {
			for(int value : white_balance_modes) {
				if( value == CameraMetadata.CONTROL_AWB_MODE_OFF && allowManualWB() ) {
					camera_features.supports_white_balance_temperature = true;
					camera_features.min_temperature = min_white_balance_temperature_c;
					camera_features.max_temperature = max_white_balance_temperature_c;
					
					if( MyDebug.LOG ) {
						Log.d(TAG, "SENSOR_REFERENCE_ILLUMINANT1: " + characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1));
						Log.d(TAG, "SENSOR_COLOR_TRANSFORM1: " + Arrays.deepToString(matrixFromColorSpaceTransform(characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1))));
						Log.d(TAG, "SENSOR_CALIBRATION_TRANSFORM1: " + Arrays.deepToString(matrixFromColorSpaceTransform(characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1))));
						Log.d(TAG, "SENSOR_FORWARD_MATRIX1: " + Arrays.deepToString(matrixFromColorSpaceTransform(characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1))));
						Log.d(TAG, "SENSOR_REFERENCE_ILLUMINANT2: " + characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2));
						Log.d(TAG, "SENSOR_COLOR_TRANSFORM2: " + Arrays.deepToString(matrixFromColorSpaceTransform(characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2))));
						Log.d(TAG, "SENSOR_CALIBRATION_TRANSFORM2: " + Arrays.deepToString(matrixFromColorSpaceTransform(characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2))));
						Log.d(TAG, "SENSOR_FORWARD_MATRIX2: " + Arrays.deepToString(matrixFromColorSpaceTransform(characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2))));
					}

					ColorSpaceTransform cst = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1);

					if (cst != null) {
						double[][] matrix;
						ColorSpaceTransform ccst = characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1);
						if (ccst != null) {
							matrix = Matrix.multiply(matrixFromColorSpaceTransform(cst), matrixFromColorSpaceTransform(ccst));
						} else {
							matrix = matrixFromColorSpaceTransform(cst);
						}
						
						if (sensor_color_transform == null || !Arrays.deepEquals(sensor_color_transform, matrix)) {
							sensor_color_transform = matrix;
							sensor_color_transform_inverse = Matrix.inverse(matrix);
							if( MyDebug.LOG ) {
								Log.d(TAG, "sensor_color_transform: " + Arrays.deepToString(sensor_color_transform));
								Log.d(TAG, "sensor_color_transform_inverse: " + Arrays.deepToString(sensor_color_transform_inverse));
							}
						}
					}
					break;
				}
			}
		}

		if( iso_range != null ) {
			camera_features.supports_iso_range = true;
			camera_features.min_iso = iso_range.getLower();
			camera_features.max_iso = iso_range.getUpper();
			// we only expose exposure_time if iso_range is supported
			if( exposure_time_range != null ) {
				camera_features.supports_exposure_time = true;
				camera_features.min_exposure_time = exposure_time_range.getLower();
				camera_features.max_exposure_time = exposure_time_range.getUpper();
			}

			/*if( Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N ) {
				Range<Integer> sensitivity_boost_range = characteristics.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE); // may be null on some devices
				if( sensitivity_boost_range != null ) {
					Log.d(TAG, "sensitivity_boost_range min: " + sensitivity_boost_range.getLower());
					Log.d(TAG, "sensitivity_boost_range max: " + sensitivity_boost_range.getUpper());
				}
			}*/
		}

		camera_features.supports_expo_bracketing = true;

		Range<Integer> exposure_range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
		camera_features.min_exposure = exposure_range.getLower();
		camera_features.max_exposure = exposure_range.getUpper();
		camera_features.exposure_step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP).floatValue();

		camera_features.can_disable_shutter_sound = true;

		Integer tonemap_max_curve_points = characteristics.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS);
		if( tonemap_max_curve_points != null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "tonemap_max_curve_points: " + tonemap_max_curve_points);
			camera_features.tonemap_max_curve_points = tonemap_max_curve_points;
			camera_features.supports_tonemap_curve = true;
			tonemap_max_curve_points_c = tonemap_max_curve_points;
		}
		else {
			if( MyDebug.LOG )
				Log.d(TAG, "tonemap_max_curve_points is null");
		}
		if( MyDebug.LOG )
			Log.d(TAG, "supports_tonemap_curve?: " + camera_features.supports_tonemap_curve);

		float [] apertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
/* for testing
		float [] apertures = {
			1.8f,
			2.0f,
			2.2f
		};
*/
		if (apertures != null) {
			if (apertures.length > 1) {
				camera_features.supports_apertures = true;
				camera_features.apertures = new ArrayList<>();
			}
			if( MyDebug.LOG )
				Log.d(TAG, "CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES:");
			for (float aperture : apertures) {
				if( MyDebug.LOG )
					Log.d(TAG, "	" + aperture);
				if (camera_features.supports_apertures)
					camera_features.apertures.add(aperture);
			}
		}

		{
			// Calculate view angles
			// Note this is an approximation (see http://stackoverflow.com/questions/39965408/what-is-the-android-camera2-api-equivalent-of-camera-parameters-gethorizontalvie ).
			// Potentially we could do better, taking into account the aspect ratio of the current resolution.
			// Note that we'd want to distinguish between the field of view of the preview versus the photo (or view) (for example,
			// DrawPreview would want the preview's field of view).
			// Also if we wanted to do this, we'd need to make sure that this was done after the caller had set the desired preview
			// and photo/video resolutions.
			SizeF physical_size = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
			float [] focal_lengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
			camera_features.view_angle_x = (float)Math.toDegrees(2.0 * Math.atan2(physical_size.getWidth(), (2.0 * focal_lengths[0])));
			camera_features.view_angle_y = (float)Math.toDegrees(2.0 * Math.atan2(physical_size.getHeight(), (2.0 * focal_lengths[0])));
			if( MyDebug.LOG ) {
				Log.d(TAG, "view_angle_x: " + camera_features.view_angle_x);
				Log.d(TAG, "view_angle_y: " + camera_features.view_angle_y);
			}
		}

		return camera_features;
	}

	public boolean shouldCoverPreview() {
//		return !has_received_frame;
		return false;
	}

	private String convertSceneMode(int value2) {
		String value;
		switch( value2 ) {
		case CameraMetadata.CONTROL_SCENE_MODE_ACTION:
			value = "action";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_BARCODE:
			value = "barcode";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_BEACH:
			value = "beach";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_CANDLELIGHT:
			value = "candlelight";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_DISABLED:
			value = "auto";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_FIREWORKS:
			value = "fireworks";
			break;
		// "hdr" no longer available in Camera2
		/*case CameraMetadata.CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO:
			// new for Camera2
			value = "high-speed-video";
			break;*/
		case CameraMetadata.CONTROL_SCENE_MODE_LANDSCAPE:
			value = "landscape";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_NIGHT:
			value = "night";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT:
			value = "night-portrait";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_PARTY:
			value = "party";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT:
			value = "portrait";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_SNOW:
			value = "snow";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_SPORTS:
			value = "sports";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_STEADYPHOTO:
			value = "steadyphoto";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_SUNSET:
			value = "sunset";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_THEATRE:
			value = "theatre";
			break;
		default:
			if( MyDebug.LOG )
				Log.d(TAG, "unknown scene mode: " + value2);
			value = null;
			break;
		}
		return value;
	}

	@Override
	public SupportedValues setSceneMode(String value) {
		if( MyDebug.LOG )
			Log.d(TAG, "setSceneMode: " + value);
		// we convert to/from strings to be compatible with original Android Camera API
		String default_value = getDefaultSceneMode();
		int [] values2 = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES);
		boolean has_disabled = false;
		List<String> values = new ArrayList<>();
		if( values2 != null ) {
			// CONTROL_AVAILABLE_SCENE_MODES is supposed to always be available, but have had some (rare) crashes from Google Play due to being null
			for(int value2 : values2) {
				if( value2 == CameraMetadata.CONTROL_SCENE_MODE_DISABLED )
					has_disabled = true;
				String this_value = convertSceneMode(value2);
				if( this_value != null ) {
					values.add(this_value);
				}
			}
		}
		if( !has_disabled ) {
			values.add(0, "auto");
		}
		SupportedValues supported_values = checkModeIsSupported(values, value, default_value);
		if( supported_values != null ) {
			int selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_DISABLED;
			switch(supported_values.selected_value) {
				case "action":
					selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_ACTION;
					break;
				case "barcode":
					selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_BARCODE;
					break;
				case "beach":
					selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_BEACH;
					break;
				case "candlelight":
					selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_CANDLELIGHT;
					break;
				case "auto":
					selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_DISABLED;
					break;
				case "fireworks":
					selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_FIREWORKS;
					break;
				// "hdr" no longer available in Camera2
				case "landscape":
					selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_LANDSCAPE;
					break;
				case "night":
					selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_NIGHT;
					break;
				case "night-portrait":
					selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT;
					break;
				case "party":
					selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_PARTY;
					break;
				case "portrait":
					selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT;
					break;
				case "snow":
					selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_SNOW;
					break;
				case "sports":
					selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_SPORTS;
					break;
				case "steadyphoto":
					selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_STEADYPHOTO;
					break;
				case "sunset":
					selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_SUNSET;
					break;
				case "theatre":
					selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_THEATRE;
					break;
				default:
					if (MyDebug.LOG)
						Log.d(TAG, "unknown selected_value: " + supported_values.selected_value);
					break;
			}

			camera_settings.scene_mode = selected_value2;
			if( camera_settings.setSceneMode(previewBuilder) ) {
				try {
					setRepeatingRequest();
				}
				catch(CameraAccessException e) {
					if( MyDebug.LOG ) {
						Log.e(TAG, "failed to set scene mode");
						Log.e(TAG, "reason: " + e.getReason());
						Log.e(TAG, "message: " + e.getMessage());
					}
					e.printStackTrace();
				}
			}
		}
		return supported_values;
	}

	@Override
	public String getSceneMode() {
		if( previewBuilder.get(CaptureRequest.CONTROL_SCENE_MODE) == null )
			return null;
		int value2 = previewBuilder.get(CaptureRequest.CONTROL_SCENE_MODE);
		return convertSceneMode(value2);
	}

	@Override
	public boolean sceneModeAffectsFunctionality() {
		// Camera2 API doesn't seem to have any warnings that changing scene mode can affect available functionality
		return true;
	}

	private String convertColorEffect(int value2) {
		String value;
		switch( value2 ) {
		case CameraMetadata.CONTROL_EFFECT_MODE_AQUA:
			value = "aqua";
			break;
		case CameraMetadata.CONTROL_EFFECT_MODE_BLACKBOARD:
			value = "blackboard";
			break;
		case CameraMetadata.CONTROL_EFFECT_MODE_MONO:
			value = "mono";
			break;
		case CameraMetadata.CONTROL_EFFECT_MODE_NEGATIVE:
			value = "negative";
			break;
		case CameraMetadata.CONTROL_EFFECT_MODE_OFF:
			value = "none";
			break;
		case CameraMetadata.CONTROL_EFFECT_MODE_POSTERIZE:
			value = "posterize";
			break;
		case CameraMetadata.CONTROL_EFFECT_MODE_SEPIA:
			value = "sepia";
			break;
		case CameraMetadata.CONTROL_EFFECT_MODE_SOLARIZE:
			value = "solarize";
			break;
		case CameraMetadata.CONTROL_EFFECT_MODE_WHITEBOARD:
			value = "whiteboard";
			break;
		default:
			if( MyDebug.LOG )
				Log.d(TAG, "unknown effect mode: " + value2);
			value = null;
			break;
		}
		return value;
	}

	@Override
	public SupportedValues setColorEffect(String value) {
		if( MyDebug.LOG )
			Log.d(TAG, "setColorEffect: " + value);
		// we convert to/from strings to be compatible with original Android Camera API
		String default_value = getDefaultColorEffect();
		int [] values2 = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS);
		List<String> values = new ArrayList<>();
		if( values2 != null ) {
			for(int value2 : values2) {
				String this_value = convertColorEffect(value2);
				if( this_value != null ) {
					values.add(this_value);
				}
			}
		}
		SupportedValues supported_values = checkModeIsSupported(values, value, default_value);
		if( supported_values != null ) {
			int selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_OFF;
			switch(supported_values.selected_value) {
				case "aqua":
					selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_AQUA;
					break;
				case "blackboard":
					selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_BLACKBOARD;
					break;
				case "mono":
					selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_MONO;
					break;
				case "negative":
					selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_NEGATIVE;
					break;
				case "none":
					selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_OFF;
					break;
				case "posterize":
					selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_POSTERIZE;
					break;
				case "sepia":
					selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_SEPIA;
					break;
				case "solarize":
					selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_SOLARIZE;
					break;
				case "whiteboard":
					selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_WHITEBOARD;
					break;
				default:
					if (MyDebug.LOG)
						Log.d(TAG, "unknown selected_value: " + supported_values.selected_value);
					break;
			}

			camera_settings.color_effect = selected_value2;
			if( camera_settings.setColorEffect(previewBuilder) ) {
				try {
					setRepeatingRequest();
				}
				catch(CameraAccessException e) {
					if( MyDebug.LOG ) {
						Log.e(TAG, "failed to set color effect");
						Log.e(TAG, "reason: " + e.getReason());
						Log.e(TAG, "message: " + e.getMessage());
					}
					e.printStackTrace();
				}
			}
		}
		return supported_values;
	}

	@Override
	public String getColorEffect() {
		if( previewBuilder.get(CaptureRequest.CONTROL_EFFECT_MODE) == null )
			return null;
		int value2 = previewBuilder.get(CaptureRequest.CONTROL_EFFECT_MODE);
		return convertColorEffect(value2);
	}

	private String convertWhiteBalance(int value2) {
		String value;
		switch( value2 ) {
		case CameraMetadata.CONTROL_AWB_MODE_AUTO:
			value = "auto";
			break;
		case CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT:
			value = "cloudy-daylight";
			break;
		case CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT:
			value = "daylight";
			break;
		case CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT:
			value = "fluorescent";
			break;
		case CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT:
			value = "incandescent";
			break;
		case CameraMetadata.CONTROL_AWB_MODE_SHADE:
			value = "shade";
			break;
		case CameraMetadata.CONTROL_AWB_MODE_TWILIGHT:
			value = "twilight";
			break;
		case CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT:
			value = "warm-fluorescent";
			break;
		case CameraMetadata.CONTROL_AWB_MODE_OFF:
			value = "manual";
			break;
		default:
			if( MyDebug.LOG )
				Log.d(TAG, "unknown white balance: " + value2);
			value = null;
			break;
		}
		return value;
	}

	/** Whether we should allow manual white balance, even if the device supports CONTROL_AWB_MODE_OFF.
	 */
	private boolean allowManualWB() {
		boolean is_nexus6 = Build.MODEL.toLowerCase(Locale.US).contains("nexus 6");
		// manual white balance doesn't seem to work on Nexus 6!
		return !is_nexus6;
	}

	@Override
	public SupportedValues setWhiteBalance(String value) {
		if( MyDebug.LOG )
			Log.d(TAG, "setWhiteBalance: " + value);
		// we convert to/from strings to be compatible with original Android Camera API
		String default_value = getDefaultWhiteBalance();
		int [] values2 = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
		List<String> values = new ArrayList<>();
		if( values2 != null ) {
			for(int value2 : values2) {
				String this_value = convertWhiteBalance(value2);
				if( this_value != null ) {
					if( value2 == CameraMetadata.CONTROL_AWB_MODE_OFF && !allowManualWB() ) {
						// filter
					}
					else {
						values.add(this_value);
					}
				}
			}
		}
		{
			// re-order so that auto is first, manual is second
			boolean has_auto = values.remove("auto");
			boolean has_manual = values.remove("manual");
			if( has_manual ) {
				values.add(0, "rgb");
				values.add(0, "manual");
			}
			if( has_auto )
				values.add(0, "auto");
		}
		SupportedValues supported_values = checkModeIsSupported(values, value, default_value);
		if( supported_values != null ) {
			int selected_value2 = CameraMetadata.CONTROL_AWB_MODE_AUTO;
			switch(supported_values.selected_value) {
				case "auto":
					selected_value2 = CameraMetadata.CONTROL_AWB_MODE_AUTO;
					break;
				case "cloudy-daylight":
					selected_value2 = CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT;
					break;
				case "daylight":
					selected_value2 = CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT;
					break;
				case "fluorescent":
					selected_value2 = CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT;
					break;
				case "incandescent":
					selected_value2 = CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT;
					break;
				case "shade":
					selected_value2 = CameraMetadata.CONTROL_AWB_MODE_SHADE;
					break;
				case "twilight":
					selected_value2 = CameraMetadata.CONTROL_AWB_MODE_TWILIGHT;
					break;
				case "warm-fluorescent":
					selected_value2 = CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT;
					break;
				case "manual":
					selected_value2 = CameraMetadata.CONTROL_AWB_MODE_OFF;
					camera_settings.white_balance_use_rgb = false;
					break;
				case "rgb":
					selected_value2 = CameraMetadata.CONTROL_AWB_MODE_OFF;
					camera_settings.white_balance_use_rgb = true;
					break;
				default:
					if (MyDebug.LOG)
						Log.d(TAG, "unknown selected_value: " + supported_values.selected_value);
					break;
			}

			
			camera_settings.white_balance = selected_value2;
			if( camera_settings.setWhiteBalance(previewBuilder) ) {
				try {
					setRepeatingRequest();
				}
				catch(CameraAccessException e) {
					if( MyDebug.LOG ) {
						Log.e(TAG, "failed to set white balance");
						Log.e(TAG, "reason: " + e.getReason());
						Log.e(TAG, "message: " + e.getMessage());
					}
					e.printStackTrace();
				}
			}
		}
		return supported_values;
	}

	@Override
	public String getWhiteBalance() {
		if (camera_settings.white_balance == CameraMetadata.CONTROL_AWB_MODE_OFF && camera_settings.white_balance_use_rgb) {
			return "rgb";
		}
		return convertWhiteBalance(camera_settings.white_balance);
	}

	@Override
	// Returns whether white balance temperature was modified
	public boolean setWhiteBalanceTemperature(int temperature) {
		if( MyDebug.LOG )
			Log.d(TAG, "setWhiteBalanceTemperature: " + temperature);
		if( camera_settings.white_balance_temperature == temperature ) {
			if( MyDebug.LOG )
				Log.d(TAG, "already set");
			return false;
		}
		try {
			temperature = Math.max(temperature, min_white_balance_temperature_c);
			temperature = Math.min(temperature, max_white_balance_temperature_c);
			camera_settings.white_balance_temperature = temperature;
			camera_settings.white_balance_xyz = null;
			camera_settings.white_balance_use_rgb = false;

			if( camera_settings.setWhiteBalance(previewBuilder) ) {
				setRepeatingRequest();
			}
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to set white balance temperature");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
		return true;
	}

	@Override
	// Returns whether white balance temperature was modified
	public boolean setWhiteBalanceRGB(float r, float g, float b) {
		if( MyDebug.LOG )
			Log.d(TAG, "setWhiteBalanceRGB: " + r + ", " + g + ", " + b);
		if(
			camera_settings.white_balance_use_rgb &&
			camera_settings.white_balance_r == r &&
			camera_settings.white_balance_g == g &&
			camera_settings.white_balance_b == b
		) {
			if( MyDebug.LOG )
				Log.d(TAG, "already set");
			return false;
		}
		try {
			camera_settings.white_balance_r = r;
			camera_settings.white_balance_g = g;
			camera_settings.white_balance_b = b;
			camera_settings.white_balance_use_rgb = true;

			ColorTemperature.RGBColor rgb = new ColorTemperature.RGBColor(1 / r, 1 / g, 1 / b);

			if (camera_settings.white_balance_calibration != null) {
				rgb.r *= camera_settings.white_balance_calibration[0];
				rgb.g *= camera_settings.white_balance_calibration[1];
				rgb.b *= camera_settings.white_balance_calibration[2];
			}

			camera_settings.white_balance_xyz = rgb.toXYZ(sensor_color_transform_inverse);
			camera_settings.white_balance_temperature = camera_settings.white_balance_xyz.getTemperature();

			if( camera_settings.setWhiteBalance(previewBuilder) ) {
				setRepeatingRequest();
			}
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to set white balance temperature");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
		return true;
	}

	@Override
	public int getWhiteBalanceTemperature() {
		return camera_settings.white_balance_temperature;
	}

	@Override
	public void setWhiteBalanceCalibration(float[] calibration) {
		camera_settings.white_balance_calibration = calibration;
	}

	@Override
	public SupportedValues setISO(String value) {
		// not supported for CameraController2 - but Camera2 devices that don't support manual ISO can call this,
		// so assume this is for auto ISO
		this.setManualISO(false, 0, false);
		return null;
	}

	@Override
	public String getISOKey() {
		return "";
	}

	@Override
	public void setManualISO(boolean manual, int iso, boolean less_or_equal) {
		if( MyDebug.LOG )
			Log.d(TAG, "setManualISO" + manual);
		exposure_over_range = false;
		try {
			camera_settings.manual_iso = false;
			if( manual ) {
				if( MyDebug.LOG )
					Log.d(TAG, "switch to iso: " + iso);
				if( iso_range == null ) {
					if( MyDebug.LOG )
						Log.d(TAG, "iso not supported");
					return;
				}
				if( MyDebug.LOG )
					Log.d(TAG, "iso range from " + iso_range.getLower() + " to " + iso_range.getUpper());

				iso = Math.max(iso, iso_range.getLower());
				iso = Math.min(iso, iso_range.getUpper());
				if (exposure_time_range != null) {
					camera_settings.manual_mode = false;
					camera_settings.manual_iso = true;
					camera_settings.manual_iso_value = iso;
					camera_settings.manual_iso_less_or_equal = less_or_equal;
				} else {
					camera_settings.manual_mode = true;
					camera_settings.iso = iso;
				}
			}
			else {
				camera_settings.manual_mode = false;
				camera_settings.iso = 0;
			}

			if( camera_settings.setAEMode(previewBuilder, !camera_settings.manual_mode) ) {
				setRepeatingRequest();
			}
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to set ISO");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
	}

	@Override
	// Returns whether ISO was modified
	// N.B., use setManualISO() to switch between auto and manual mode
	public boolean setISO(int iso) {
		if( MyDebug.LOG )
			Log.d(TAG, "setISO: " + iso);

		exposure_over_range = false;

		if( camera_settings.iso == iso ) {
			if( MyDebug.LOG )
				Log.d(TAG, "already set");
			return false;
		}
		try {
			camera_settings.iso = iso;
			if( camera_settings.setAEMode(previewBuilder, false) ) {
				setRepeatingRequest();
			}
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to set ISO");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
		return true;
	}

	@Override
	public void setManualMode(boolean mode, boolean force_auto_preview) {
		if( MyDebug.LOG )
			Log.d(TAG, "setManualMode" + mode);

		exposure_over_range = false;
		try {
			camera_settings.manual_iso = false;
			camera_settings.manual_mode = mode;
			camera_settings.force_auto_preview = force_auto_preview;
			if( camera_settings.setAEMode(previewBuilder, false) ) {
				setRepeatingRequest();
			}
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to set exposure time");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
	}

	@Override
	// Returns whether exposure time was modified
	// N.B., use setISO(String) to switch between auto and manual mode
	public boolean setExposureTime(long exposure_time) {
		if( MyDebug.LOG ) {
			Log.d(TAG, "setExposureTime: " + exposure_time);
			Log.d(TAG, "current exposure time: " + camera_settings.exposure_time);
		}
		if( camera_settings.exposure_time == exposure_time ) {
			if( MyDebug.LOG )
				Log.d(TAG, "already set");
			return false;
		}
		try {
			camera_settings.exposure_time = exposure_time;
			if( camera_settings.setAEMode(previewBuilder, false) ) {
				setRepeatingRequest();
			}
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to set exposure time");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
		return true;
	}

	@Override
	public boolean setAperture(float value) {
		if( MyDebug.LOG )
			Log.d(TAG, "setAperture: " + value);

		if (camera_settings.manual_mode) {
			camera_settings.aperture = value;
			if( camera_settings.setAEMode(previewBuilder, false) ) {
				try {
					setRepeatingRequest();
					return true;
				}
				catch(CameraAccessException e) {
					if( MyDebug.LOG ) {
						Log.e(TAG, "failed to set aperture");
						Log.e(TAG, "reason: " + e.getReason());
						Log.e(TAG, "message: " + e.getMessage());
					}
					e.printStackTrace();
				}
			}
		}
		return false;
	}

	@Override
	public float getAperture() {
		if (!camera_settings.manual_mode || previewBuilder.get(CaptureRequest.LENS_APERTURE) == null)
			return 0.0f;
		return previewBuilder.get(CaptureRequest.LENS_APERTURE);
	}

	@Override
	public void setupImageReader(int multiplier, int max_capacity, boolean alt_holder) {
		if (captureSession != null) {
			try {
				stopPreview();
				image_reader_capacity_multiplier = multiplier;
				image_reader_max_capacity = max_capacity;
				alt_image_holder = alt_holder;
				startPreview();
			} catch(CameraControllerException e) {
				e.printStackTrace();
			}
		} else {
			image_reader_capacity_multiplier = multiplier;
			image_reader_max_capacity = max_capacity;
			alt_image_holder = alt_holder;
		}
	}

	@Override
	public Size getPictureSize() {
		return new Size(picture_width, picture_height);
	}

	@Override
	public void setPictureSize(int width, int height) {
		if( MyDebug.LOG )
			Log.d(TAG, "setPictureSize: " + width + " x " + height);
		if( camera == null ) {
			if( MyDebug.LOG )
				Log.e(TAG, "no camera");
			return;
		}
		if( captureSession != null ) {
			// can only call this when captureSession not created - as the surface of the imageReader we create has to match the surface we pass to the captureSession
			if( MyDebug.LOG )
				Log.e(TAG, "can't set picture size when captureSession running!");
			throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
		}
		this.picture_width = width;
		this.picture_height = height;
	}

	@Override
	public void setRaw(boolean want_raw, boolean raw_only) {
		if( MyDebug.LOG )
			Log.d(TAG, "setRaw(" + want_raw + ", " + raw_only + ")");
		if( camera == null ) {
			if( MyDebug.LOG )
				Log.e(TAG, "no camera");
			return;
		}
		if( this.want_raw == want_raw && this.want_raw_only == raw_only ) {
			return;
		}
		if( want_raw && this.raw_size == null ) {
			if( MyDebug.LOG )
				Log.e(TAG, "can't set raw when raw not supported");
			return;
		}
		if( captureSession != null ) {
			// can only call this when captureSession not created - as it affects how we create the imageReader
			if( MyDebug.LOG )
				Log.e(TAG, "can't set raw when captureSession running!");
			throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
		}
		this.want_raw = want_raw;
		this.want_raw_only = raw_only;
	}

	@Override
	public void setRawLensShading(boolean value) {
		if( MyDebug.LOG )
			Log.d(TAG, "setRawLensShading(" + value + ")");
			
		camera_settings.raw_lens_shading = value;
	}

	@Override
	public void setRawBadPixels(HashSet<Point> pixels, HashSet<Rect> blocks, HashSet<Point> pixels_le, HashSet<Rect> blocks_le) {
		this.bad_pixels = pixels;
		this.bad_blocks = blocks;
		this.bad_pixels_le = pixels_le;
		this.bad_blocks_le = blocks_le;
	};

	@Override
	public void setVideoHighSpeed(boolean want_video_high_speed) {
		if( MyDebug.LOG )
			Log.d(TAG, "setVideoHighSpeed: " + want_video_high_speed);
		if( camera == null ) {
			if( MyDebug.LOG )
				Log.e(TAG, "no camera");
			return;
		}
		if( this.want_video_high_speed == want_video_high_speed ) {
			return;
		}
		if( captureSession != null ) {
			// can only call this when captureSession not created - as it affects how we create the session
			if( MyDebug.LOG )
				Log.e(TAG, "can't set high speed when captureSession running!");
			throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
		}
		this.want_video_high_speed = want_video_high_speed;
		this.is_video_high_speed = false; // reset just to be safe
	}

	@Override
	public void setExpoBracketing(boolean want_expo_bracketing) {
		if( MyDebug.LOG )
			Log.d(TAG, "setExpoBracketing: " + want_expo_bracketing);
		if( camera == null ) {
			if( MyDebug.LOG )
				Log.e(TAG, "no camera");
			return;
		}
		if( this.want_expo_bracketing == want_expo_bracketing ) {
			return;
		}

		boolean start_preview = false;
		if (captureSession != null) {
			start_preview = true;
			stopPreview();
		}
		this.want_expo_bracketing = want_expo_bracketing;
		updateUseFakePrecaptureMode(camera_settings.flash_value);
		camera_settings.setAEMode(previewBuilder, false); // need to set the ae mode, as flash is disabled for expo mode
		
		if (want_expo_bracketing) {
			expo_bracketing_exposure_compensation = exposure_time_range == null;
		}
		
		if (start_preview) {
			try {
				startPreview();
			} catch(CameraControllerException e) {
				e.printStackTrace();
			}
		}
		
//		if (uncompressed_photo) setUncompressedPhoto(true);
	}

	@Override
	public void setExpoBracketingStack(double stops_up, int images_up, double stops_down, int images_down) {
		this.expo_bracketing_stack = new ArrayList<>();
		if (images_down > 0) {
			for (int i = images_down; i > 0; i--)
				this.expo_bracketing_stack.add(-(stops_down * i));
		}
		this.expo_bracketing_stack.add(0.0d);
		if (images_up > 0) {
			for (int i = 1; i <= images_up; i++)
				this.expo_bracketing_stack.add(stops_up * i);
		}
		
		if (captureSession != null && want_burst_count != this.expo_bracketing_stack.size()) {
			try {
				stopPreview();
				want_burst_count = this.expo_bracketing_stack.size();
				startPreview();
			} catch(CameraControllerException e) {
				e.printStackTrace();
			}
		} else {
			want_burst_count = this.expo_bracketing_stack.size();
		}
	}

	@Override
	public void setExposureCompensationDelay(int delay) {
		this.exposure_compensation_delay = delay;
	}

	@Override
	public void setOptimiseAEForDRO(boolean optimise_ae_for_dro) {
		if( MyDebug.LOG )
			Log.d(TAG, "setOptimiseAEForDRO: " + optimise_ae_for_dro);
		boolean is_oneplus = Build.MANUFACTURER.toLowerCase(Locale.US).contains("oneplus");
		if( is_oneplus ) {
			// OnePlus 3T has preview corruption / camera freezing problems when using manual shutter speeds
			// So best not to modify auto-exposure for DRO
			this.optimise_ae_for_dro = false;
			if( MyDebug.LOG )
				Log.d(TAG, "don't modify ae for OnePlus");
		}
		else {
			this.optimise_ae_for_dro = optimise_ae_for_dro;
		}
	}

	@Override
	public void setWantBurst(boolean want_burst) {
		if( MyDebug.LOG )
			Log.d(TAG, "setWantBurst: " + want_burst);
		if( camera == null ) {
			if( MyDebug.LOG )
				Log.e(TAG, "no camera");
			return;
		}
		if( this.want_burst == want_burst ) {
			return;
		}
		if( captureSession != null ) {
			// can only call this when captureSession not created - as it affects how we create the imageReader
			if( MyDebug.LOG )
				Log.e(TAG, "can't set burst when captureSession running!");
			throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
		}
		this.want_burst = want_burst;
		updateUseFakePrecaptureMode(camera_settings.flash_value);
		camera_settings.setAEMode(previewBuilder, false); // need to set the ae mode, as flash is disabled for burst mode
	}

	@Override
	public void setWantBurstCount(int count) {
		if (want_burst_count != count) {
			if (captureSession != null && (want_raw || alt_image_holder)) {
				try {
					stopPreview();
					want_burst_count = count;
					startPreview();
				} catch(CameraControllerException e) {
					e.printStackTrace();
				}
			} else {
				want_burst_count = count;
			}
		}
	}

	@Override
	public void setDisableBurstFilters(boolean disable) {
		burst_disable_filters = disable;
	}

	@Override
	public void setUncompressedPhoto(boolean state) {
		if (uncompressed_photo != state) {
			uncompressed_photo = state;
			
			if( captureSession != null ) {
				try {
					stopPreview();
					startPreview();
				} catch(CameraControllerException e) {
					e.printStackTrace();
				}
			}
		}
	}

	public void setFullSizeCopy(boolean state) {
		full_size_copy = state;
	}

	public void setSaveExif(boolean state) {
		save_exif = state;
	}

	@Override
	public void setFakeFlashMode(FakeFlashMode fake_flash_mode) {
		if( MyDebug.LOG )
			Log.d(TAG, "setFakeFlashMode: " + fake_flash_mode);
		if( camera == null ) {
			if( MyDebug.LOG )
				Log.e(TAG, "no camera");
			return;
		}
		if( this.fake_flash == fake_flash_mode ) {
			return;
		}
		this.fake_flash = fake_flash_mode;
		this.fake_flash_mode = fake_flash_mode;
		// no need to call updateUseFakePrecaptureMode(), as this method should only be called after first creating camera controller
	}

	@Override
	public FakeFlashMode getFakeFlashMode() {
		return this.fake_flash;
	}

	@Override
	public void setForceIsoExposure(boolean value) {
		if( MyDebug.LOG )
			Log.d(TAG, "setForceIsoExposure: " + value);
		if( camera == null ) {
			if( MyDebug.LOG )
				Log.e(TAG, "no camera");
			return;
		}
		this.force_iso_exposure = value;
	}

	@Override
	public void setForceManualWB(boolean value) {
		if( MyDebug.LOG )
			Log.d(TAG, "setForceManualWB: " + value);
		if( camera == null ) {
			if( MyDebug.LOG )
				Log.e(TAG, "no camera");
			return;
		}
		this.force_manual_wb = value;
	}

	@Override
	public String getAntibanding() {
		if( previewBuilder.get(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE) == null )
			return null;
		int value = previewBuilder.get(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE);
		switch (value) {
			case CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO:
				return "auto";
			case CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ:
				return "50hz";
			case CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ:
				return "60hz";
			case CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF:
				return "off";
			default:
				return null;
		}
	}

	@Override
	public boolean setAntibanding(String value) {
		int ab_value = -1;
		switch (value) {
			case "auto":
				ab_value = CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO;
				break;
			case "50hz":
				ab_value = CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ;
				break;
			case "60hz":
				ab_value = CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ;
				break;
			case "off":
				ab_value = CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF;
				break;
		}
		
		if (ab_value != -1) {
			camera_settings.antibanding = ab_value;
			camera_settings.setAntibanding(previewBuilder);
			try {
				setRepeatingRequest();
			}
			catch(CameraAccessException e) {
				if( MyDebug.LOG ) {
					Log.e(TAG, "failed to set antibanding");
					Log.e(TAG, "reason: " + e.getReason());
					Log.e(TAG, "message: " + e.getMessage());
				}
				e.printStackTrace();
			}
		}
		return true;
	}

	private String convertNoiseReductionMode(int value2) {
		String value;
		switch( value2 ) {
		case CameraMetadata.NOISE_REDUCTION_MODE_OFF:
			value = "off";
			break;
		case CameraMetadata.NOISE_REDUCTION_MODE_FAST:
			value = "fast";
			break;
		case CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY:
			value = "high_quality";
			break;
		case CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL:
			value = "minimal";
			break;
		case CameraMetadata.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG:
			value = "zero_shutter_lag";
			break;
		default:
			if( MyDebug.LOG )
				Log.d(TAG, "unknown noise reduction mode: " + value2);
			value = null;
			break;
		}
		return value;
	}

	@Override
	public boolean setNoiseReductionMode(String value) {
		if( MyDebug.LOG )
			Log.d(TAG, "setNoiseReductionMode: " + value);

		if( getAvailableNoiseReductionModes().contains(value) ) {
			int selected_value2 = -1;
			switch(value) {
				case "off":
					selected_value2 = CameraMetadata.NOISE_REDUCTION_MODE_OFF;
					break;
				case "fast":
					selected_value2 = CameraMetadata.NOISE_REDUCTION_MODE_FAST;
					break;
				case "high_quality":
					selected_value2 = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY;
					break;
				case "minimal":
					selected_value2 = CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL;
					break;
				case "zero_shutter_lag":
					selected_value2 = CameraMetadata.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG;
					break;
				default:
					if (MyDebug.LOG)
						Log.d(TAG, "unknown selected_value: " + value);
					break;
			}

			if (selected_value2 != -1) {
				camera_settings.noise_reduction = selected_value2;
				if( camera_settings.setNoiseReductionMode(previewBuilder, false) ) {
					try {
						setRepeatingRequest();
						return true;
					}
					catch(CameraAccessException e) {
						if( MyDebug.LOG ) {
							Log.e(TAG, "failed to set noise reduction mode");
							Log.e(TAG, "reason: " + e.getReason());
							Log.e(TAG, "message: " + e.getMessage());
						}
						e.printStackTrace();
					}
				}
			}
		}
		return false;
	}

	@Override
	public String getNoiseReductionMode() {
		if( previewBuilder.get(CaptureRequest.NOISE_REDUCTION_MODE) == null )
			return null;
		int value2 = previewBuilder.get(CaptureRequest.NOISE_REDUCTION_MODE);
		return convertNoiseReductionMode(value2);
	}

	@Override
	public List<String> getAvailableNoiseReductionModes() {
		List<String> values = new ArrayList<>();
		int [] values2 = characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
		if (values2 != null) {
			for(int value2 : values2) {
				String this_value = convertNoiseReductionMode(value2);
				if( this_value != null ) {
					values.add(this_value);
				}
			}
		}
		return values;
	}

	private String convertEdgeMode(int value2) {
		String value;
		switch( value2 ) {
		case CameraMetadata.EDGE_MODE_OFF:
			value = "off";
			break;
		case CameraMetadata.EDGE_MODE_FAST:
			value = "fast";
			break;
		case CameraMetadata.EDGE_MODE_HIGH_QUALITY:
			value = "high_quality";
			break;
		case CameraMetadata.EDGE_MODE_ZERO_SHUTTER_LAG:
			value = "zero_shutter_lag";
			break;
		default:
			if( MyDebug.LOG )
				Log.d(TAG, "unknown edge mode: " + value2);
			value = null;
			break;
		}
		return value;
	}

	@Override
	public boolean setEdgeMode(String value) {
		if( MyDebug.LOG )
			Log.d(TAG, "setEdgeMode: " + value);

		if( getAvailableEdgeModes().contains(value) ) {
			int selected_value2 = -1;
			switch(value) {
				case "off":
					selected_value2 = CameraMetadata.EDGE_MODE_OFF;
					break;
				case "fast":
					selected_value2 = CameraMetadata.EDGE_MODE_FAST;
					break;
				case "high_quality":
					selected_value2 = CameraMetadata.EDGE_MODE_HIGH_QUALITY;
					break;
				case "zero_shutter_lag":
					selected_value2 = CameraMetadata.EDGE_MODE_ZERO_SHUTTER_LAG;
					break;
				default:
					if (MyDebug.LOG)
						Log.d(TAG, "unknown selected_value: " + value);
					break;
			}

			if (selected_value2 != -1) {
				camera_settings.edge = selected_value2;
				if( camera_settings.setEdgeMode(previewBuilder, false) ) {
					try {
						setRepeatingRequest();
						return true;
					}
					catch(CameraAccessException e) {
						if( MyDebug.LOG ) {
							Log.e(TAG, "failed to set edge mode");
							Log.e(TAG, "reason: " + e.getReason());
							Log.e(TAG, "message: " + e.getMessage());
						}
						e.printStackTrace();
					}
				}
			}
		}
		return false;
	}

	@Override
	public String getEdgeMode() {
		if( previewBuilder.get(CaptureRequest.EDGE_MODE) == null )
			return null;
		int value2 = previewBuilder.get(CaptureRequest.EDGE_MODE);
		return convertEdgeMode(value2);
	}

	@Override
	public List<String> getAvailableEdgeModes() {
		List<String> values = new ArrayList<>();
		int [] values2 = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES);
		if (values2 != null) {
			for(int value2 : values2) {
				String this_value = convertEdgeMode(value2);
				if( this_value != null ) {
					values.add(this_value);
				}
			}
		}
		return values;
	}

	private String convertOpticalStabilizationMode(int value2) {
		String value;
		switch( value2 ) {
		case CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF:
			value = "off";
			break;
		case CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON:
			value = "on";
			break;
		case LENS_OPTICAL_STABILIZATION_MODE_IF_NECESSARY:
			value = "if_necessary";
			break;
		default:
			if( MyDebug.LOG )
				Log.d(TAG, "unknown optical stabilization mode: " + value2);
			value = null;
			break;
		}
		return value;
	}

	@Override
	public boolean setOpticalStabilizationMode(String value) {
		if( MyDebug.LOG )
			Log.d(TAG, "setOpticalStabilizationMode: " + value);

		if( getAvailableOpticalStabilizationModes().contains(value) ) {
			int selected_value2 = -1;
			switch(value) {
				case "off":
					selected_value2 = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF;
					break;
				case "on":
					selected_value2 = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON;
					break;
				case "if_necessary":
					selected_value2 = LENS_OPTICAL_STABILIZATION_MODE_IF_NECESSARY;
					break;
				default:
					if (MyDebug.LOG)
						Log.d(TAG, "unknown selected_value: " + value);
					break;
			}

			if (selected_value2 != -1) {
				optical_stabilization_if_necessary = selected_value2 == LENS_OPTICAL_STABILIZATION_MODE_IF_NECESSARY;
				camera_settings.optical_stabilization = selected_value2;
				if( camera_settings.setOpticalStabilizationMode(previewBuilder) ) {
					try {
						setRepeatingRequest();
						return true;
					}
					catch(CameraAccessException e) {
						if( MyDebug.LOG ) {
							Log.e(TAG, "failed to set optical stabilization mode");
							Log.e(TAG, "reason: " + e.getReason());
							Log.e(TAG, "message: " + e.getMessage());
						}
						e.printStackTrace();
					}
				}
			}
		}
		return false;
	}

	@Override
	public String getOpticalStabilizationMode() {
		if( previewBuilder.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE) == null )
			return null;
		int value2;
		if (optical_stabilization_if_necessary)
			value2 = LENS_OPTICAL_STABILIZATION_MODE_IF_NECESSARY;
		else
			value2 = previewBuilder.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE);
		return convertOpticalStabilizationMode(value2);
	}

	@Override
	public List<String> getAvailableOpticalStabilizationModes() {
		List<String> values = new ArrayList<>();
		int [] values2 = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
		if (values2 != null) {
			// Some stupid devices returns two equal values in this array.
			if (values2.length == 2 && values2[0] == values2[1]) {
				values2[0] = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF;
				values2[1] = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON;
			} else if (values2.length == 1 && previewBuilder.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE) != null) {
				values2 = new int[] {
					CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
					CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
				};
			}
			for(int value2 : values2) {
				String this_value = convertOpticalStabilizationMode(value2);
				if( this_value != null ) {
					values.add(this_value);
				}
			}
			if (values2.length == 2) {
				values.add(convertOpticalStabilizationMode(LENS_OPTICAL_STABILIZATION_MODE_IF_NECESSARY));
			}
		}
		return values;
	}

	private String convertHotPixelCorrectionMode(int value2) {
		String value;
		switch( value2 ) {
		case CameraMetadata.HOT_PIXEL_MODE_OFF:
			value = "off";
			break;
		case CameraMetadata.HOT_PIXEL_MODE_FAST:
			value = "fast";
			break;
		case CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY:
			value = "high_quality";
			break;
		default:
			if( MyDebug.LOG )
				Log.d(TAG, "unknown hot pixel correction mode: " + value2);
			value = null;
			break;
		}
		return value;
	}

	@Override
	public boolean setHotPixelCorrectionMode(String value) {
		if( MyDebug.LOG )
			Log.d(TAG, "setHotPixelCorrectionMode: " + value);

		if( getAvailableHotPixelCorrectionModes().contains(value) ) {
			int selected_value2 = -1;
			switch(value) {
				case "off":
					selected_value2 = CameraMetadata.HOT_PIXEL_MODE_OFF;
					break;
				case "fast":
					selected_value2 = CameraMetadata.HOT_PIXEL_MODE_FAST;
					break;
				case "high_quality":
					selected_value2 = CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY;
					break;
				default:
					if (MyDebug.LOG)
						Log.d(TAG, "unknown selected_value: " + value);
					break;
			}

			if (selected_value2 != -1) {
				camera_settings.hot_pixel_correction = selected_value2;
				if( camera_settings.setHotPixelCorrectionMode(previewBuilder) ) {
					try {
						setRepeatingRequest();
						return true;
					}
					catch(CameraAccessException e) {
						if( MyDebug.LOG ) {
							Log.e(TAG, "failed to set hot pixel correction mode");
							Log.e(TAG, "reason: " + e.getReason());
							Log.e(TAG, "message: " + e.getMessage());
						}
						e.printStackTrace();
					}
				}
			}
		}
		return false;
	}

	@Override
	public String getHotPixelCorrectionMode() {
		if( previewBuilder.get(CaptureRequest.HOT_PIXEL_MODE) == null )
			return null;
		int value2 = previewBuilder.get(CaptureRequest.HOT_PIXEL_MODE);
		return convertHotPixelCorrectionMode(value2);
	}

	@Override
	public List<String> getAvailableHotPixelCorrectionModes() {
		List<String> values = new ArrayList<>();
		int [] values2 = characteristics.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES);
		if (values2 != null) {
			for(int value2 : values2) {
				String this_value = convertHotPixelCorrectionMode(value2);
				if( this_value != null ) {
					values.add(this_value);
				}
			}
		}
		return values;
	}

	private void createPictureImageReader() {
		if( MyDebug.LOG )
			Log.d(TAG, "createPictureImageReader");
		if( captureSession != null ) {
			// can only call this when captureSession not created - as the surface of the imageReader we create has to match the surface we pass to the captureSession
			if( MyDebug.LOG )
				Log.e(TAG, "can't create picture image reader when captureSession running!");
			throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
		}
		closePictureImageReader();
		if( picture_width == 0 || picture_height == 0 ) {
			if( MyDebug.LOG )
				Log.e(TAG, "application needs to call setPictureSize()");
			throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
		}
		
		pending_photos_raw = want_raw && raw_size != null && !previewIsVideoMode;
		pending_photos_jpeg = !(pending_photos_raw && want_raw_only) && (!uncompressed_photo || save_exif);
		pending_photos_uncompressed = !(pending_photos_raw && want_raw_only) && uncompressed_photo;
		pending_photos_capture_result = pending_photos_raw;

		int capacity = image_reader_capacity_multiplier;
		if (alt_image_holder && (want_burst || want_expo_bracketing)) {
			capacity = want_burst_count*image_reader_capacity_multiplier;
		}
		capacity = Math.min(capacity, image_reader_max_capacity);
		
		if (pending_photos_jpeg) {
			imageReader = ImageReader.newInstance(uncompressed_photo && !full_size_copy ? 320 : picture_width, uncompressed_photo && !full_size_copy ? 240 : picture_height, ImageFormat.JPEG, capacity);
			if( MyDebug.LOG ) {
				Log.d(TAG, "created new imageReader: " + imageReader.toString());
				Log.d(TAG, "imageReader surface: " + imageReader.getSurface().toString());
			}
			imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
				@Override
				public void onImageAvailable(ImageReader reader) {
					if( MyDebug.LOG )
						Log.d(TAG, "new still image available");
					if( jpeg_cb == null ) {
						if( MyDebug.LOG )
							Log.d(TAG, "no picture callback available");
						return;
					}
					synchronized( image_reader_lock ) {
						/* Whilst in theory the two setOnImageAvailableListener methods (for JPEG and RAW) seem to be called separately, I don't know if this is always true;
						 * also, we may process the RAW image when the capture result is available (see
						 * OnRawImageAvailableListener.setCaptureResult()), which may be in a separate thread.
						 */
						Image image = reader.acquireNextImage();
						final long timestamp = image.getTimestamp();
						if( MyDebug.LOG )
							Log.d(TAG, "image timestamp: " + timestamp);
						
						int index = pending_photos_timestamps.indexOf(timestamp);
						Photo photo;
						if (index >= 0) {
							photo = pending_photos.get(index);
						} else {
							photo = new Photo();
						}
						if (alt_image_holder) {
							photo.jpegImage = image;
						} else {
							photo.setJpegFromImage(image);
							image.close();
						}
						if (index >= 0) {							
							if (isPhotoReady(photo)) {
								pending_photos.remove(index);
								pending_photos_timestamps.remove(index);
								
								imageAvailable(photo);
							}
						} else {
							if (isPhotoReady(photo)) {
								imageAvailable(photo);
							} else {
								pending_photos_timestamps.add(timestamp);
								pending_photos.add(photo);
							}
						}
					}
					if( MyDebug.LOG )
						Log.d(TAG, "done onImageAvailable");
				}
			}, null);
		}
		if (pending_photos_uncompressed) {
			imageReaderUncompressed = ImageReader.newInstance(picture_width, picture_height, ImageFormat.YUV_420_888, capacity);
			if( MyDebug.LOG ) {
				Log.d(TAG, "created new imageReaderUncompressed: " + imageReaderUncompressed.toString());
				Log.d(TAG, "imageReaderUncompressed surface: " + imageReaderUncompressed.getSurface().toString());
			}
			imageReaderUncompressed.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
				@Override
				public void onImageAvailable(ImageReader reader) {
					if( MyDebug.LOG )
						Log.d(TAG, "new uncompressed image available");
					if( jpeg_cb == null ) {
						if( MyDebug.LOG )
							Log.d(TAG, "no picture callback available");
						return;
					}
					synchronized( image_reader_lock ) {
						Image image = reader.acquireNextImage();
						final long timestamp = image.getTimestamp();
						if( MyDebug.LOG )
							Log.d(TAG, "uncompressed image timestamp: " + image.getTimestamp());
							
						int index = pending_photos_timestamps.indexOf(timestamp);
						Photo photo;
						if (index >= 0) {
							photo = pending_photos.get(index);
						} else {
							photo = new Photo();
						}
						if (alt_image_holder) {
							photo.yuvImage = image;
						} else {
							photo.setYuvFromImage(image);
							image.close();
						}
						if (index >= 0) {
							if (isPhotoReady(photo)) {
								pending_photos.remove(index);
								pending_photos_timestamps.remove(index);
								imageAvailable(photo);
							}
						} else {
							if (isPhotoReady(photo)) {
								imageAvailable(photo);
							} else {
								pending_photos_timestamps.add(timestamp);
								pending_photos.add(photo);
							}
						}
					}
				}
			}, null);
		}
		if (pending_photos_raw) {
			capacity = image_reader_capacity_multiplier;
			if (want_burst || want_expo_bracketing)
				capacity = want_burst_count*image_reader_capacity_multiplier;
			capacity = Math.min(capacity, image_reader_max_capacity);
			
			imageReaderRaw = ImageReader.newInstance(raw_size.getWidth(), raw_size.getHeight(), ImageFormat.RAW_SENSOR, capacity);
			if( MyDebug.LOG ) {
				Log.d(TAG, "created new imageReaderRaw: " + imageReaderRaw.toString());
				Log.d(TAG, "imageReaderRaw surface: " + imageReaderRaw.getSurface().toString());
			}
			imageReaderRaw.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
				@Override
				public void onImageAvailable(ImageReader reader) {
					if( MyDebug.LOG )
						Log.d(TAG, "new still raw image available");
					synchronized( image_reader_lock ) {
						// see comment above in setCaptureResult() for why we sychonize
						try {
							Image image = reader.acquireNextImage();
							if( !pending_photos_raw ) {
								if( MyDebug.LOG )
									Log.d(TAG, "no picture callback available");
								
								image.close();
								
								return;
							}

							final long timestamp = image.getTimestamp();
							if( MyDebug.LOG ) {
								Log.d(TAG, "image timestamp: " + timestamp);
							}

							int index = pending_photos_timestamps.indexOf(timestamp);
							if (index >= 0) {
								Photo photo = pending_photos.get(index);
								photo.rawImage = image;

								if (isPhotoReady(photo)) {
									pending_photos.remove(index);
									pending_photos_timestamps.remove(index);
									imageAvailable(photo);
								}
							} else {
								Photo photo = new Photo();
								photo.rawImage = image;
								if (isPhotoReady(photo)) {
									imageAvailable(photo);
								} else {
									pending_photos_timestamps.add(timestamp);
									pending_photos.add(photo);
								}
							}
							
							imageReaderInUse = true;
						} catch(IllegalStateException e) {
							outOfMemory();
						}
					}
					if( MyDebug.LOG )
						Log.d(TAG, "done onImageAvailable");
				}
			}, null);
		}
	}

	private boolean isPhotoReady(final Photo photo) {
		if (pending_photos_jpeg && !photo.hasJpeg())
			return false;

		if (pending_photos_uncompressed && !photo.hasYuv())
			return false;

		if (pending_photos_raw && !photo.hasRaw())
			return false;

		if (pending_photos_capture_result && photo.captureResult == null)
			return false;

		return true;
	}

	private void imageAvailable(final Photo photo) {
		if (photo.hasYuv() || photo.hasRaw())
			photo.orientation = camera_settings.getExifOrientation();

		if (photo.captureResult != null) {
			photo.characteristics = characteristics;
			photo.location = camera_settings.location;

			if (photo.hasRaw()) {
				photo.badPixels = this.bad_pixels;
				photo.badPixelBlocks = this.bad_blocks;
				
				if (bad_pixels_le != null || bad_blocks_le != null) {
					Long exposure_time = photo.captureResult.get(CaptureResult.SENSOR_EXPOSURE_TIME);
					if (exposure_time != null && exposure_time >= 500000000L) {
						if (bad_pixels_le != null)
							photo.badPixels = bad_pixels_le;
						if (bad_blocks_le != null)
							photo.badPixelBlocks = bad_blocks_le;
					}
				}
			}
		}

		if( burst_single_request && n_burst > 1 ) {
			pending_burst_images.add(photo);
			if( pending_burst_images.size() >= n_burst ) { // shouldn't ever be greater, but just in case
				if( MyDebug.LOG )
					Log.d(TAG, "all burst images available");
				if( pending_burst_images.size() > n_burst ) {
					Log.e(TAG, "pending_burst_images size " + pending_burst_images.size() + " is greater than n_burst " + n_burst);
				}
				if (exposure_compensations != null) {
					camera_settings.has_ae_exposure_compensation = true;
					camera_settings.ae_exposure_compensation = exposure_compensations.get(0);
					if( camera_settings.setExposureCompensation(previewBuilder) ) {
						try {
							setRepeatingRequest();
						}
						catch(CameraAccessException e) {
							if( MyDebug.LOG ) {
								Log.e(TAG, "failed to set exposure compensation");
								Log.e(TAG, "reason: " + e.getReason());
								Log.e(TAG, "message: " + e.getMessage());
							}
							e.printStackTrace();
						}
					}
					exposure_compensations = null;
				}
				// need to set jpeg_cb etc to null before calling onCompleted, as that may reenter CameraController to take another photo (if in burst mode) - see testTakePhotoBurst()
				PictureCallback cb = jpeg_cb;
				jpeg_cb = null;
				// take a copy, so that we can clear pending_burst_images
				List<Photo> images = new ArrayList<>(pending_burst_images);
				cb.onBurstPictureTaken(images);
				pending_burst_images.clear();
				cb.onCompleted();
			}
			else {
				if( MyDebug.LOG )
					Log.d(TAG, "number of burst images is now: " + pending_burst_images.size());
				if (exposure_compensations != null) {
					camera_settings.has_ae_exposure_compensation = true;
					camera_settings.ae_exposure_compensation = exposure_compensations.get(pending_burst_images.size());
					if( camera_settings.setExposureCompensation(previewBuilder) ) {
						try {
							setRepeatingRequest();
						}
						catch(CameraAccessException e) {
							if( MyDebug.LOG ) {
								Log.e(TAG, "failed to set exposure compensation");
								Log.e(TAG, "reason: " + e.getReason());
								Log.e(TAG, "message: " + e.getMessage());
							}
							e.printStackTrace();
						}
					}

					try {
						Thread.sleep(exposure_compensation_delay);
					}
					catch(InterruptedException e) {}

					try {
						CaptureRequest.Builder stillBuilder = camera.createCaptureRequest(previewIsVideoMode ? CameraDevice.TEMPLATE_VIDEO_SNAPSHOT : CameraDevice.TEMPLATE_STILL_CAPTURE);
						stillBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE);
						camera_settings.setupBuilder(stillBuilder, true);
						if( imageReader != null )
							stillBuilder.addTarget(imageReader.getSurface());
						if( imageReaderUncompressed != null )
							stillBuilder.addTarget(imageReaderUncompressed.getSurface());

						stillBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
						stillBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);

						is_filtering_blocked = false;
						if (smart_filter_iso != 0) {
							int actual_iso = getIso();
							if (actual_iso > 0 && actual_iso <= smart_filter_iso) {
								stillBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);
								stillBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
								is_filtering_blocked = true;
							}
						}
						captureSession.capture(stillBuilder.build(), previewCaptureCallback, handler);
					}
					catch(CameraAccessException e) {
						if( MyDebug.LOG ) {
							Log.e(TAG, "failed to take picture expo burst");
							Log.e(TAG, "reason: " + e.getReason());
							Log.e(TAG, "message: " + e.getMessage());
						}
						e.printStackTrace();
						jpeg_cb = null;
						if( take_picture_error_cb != null ) {
							take_picture_error_cb.onError();
							take_picture_error_cb = null;
						}
					}
				} else if( slow_burst_capture_requests != null ) {
					if( MyDebug.LOG ) {
						Log.d(TAG, "need to execute the next capture");
						Log.d(TAG, "time since start: " + (System.currentTimeMillis() - capture_start_time));
					}
					try {
						captureSession.capture(slow_burst_capture_requests.get(pending_burst_images.size()), previewCaptureCallback, handler);
					}
					catch(CameraAccessException e) {
						if( MyDebug.LOG ) {
							Log.e(TAG, "failed to take next burst");
							Log.e(TAG, "reason: " + e.getReason());
							Log.e(TAG, "message: " + e.getMessage());
						}
						e.printStackTrace();
						jpeg_cb = null;
						if( take_picture_error_cb != null ) {
							take_picture_error_cb.onError();
							take_picture_error_cb = null;
						}
					}
				}
			}
		}
		else {
			jpeg_cb.onPictureTaken(photo);
			n_burst--;
			if( MyDebug.LOG )
				Log.d(TAG, "n_burst is now " + n_burst);
			if( n_burst == 0 ) {
				// need to set jpeg_cb etc to null before calling onCompleted, as that may reenter CameraController to take another photo (if in auto-repeat burst mode) - see testTakePhotoBurst()
				PictureCallback cb = jpeg_cb;
				jpeg_cb = null;
				if( MyDebug.LOG )
					Log.d(TAG, "all image callbacks now completed");
				cb.onCompleted();
			}
		}
	}
	private void clearPending() {
		if( MyDebug.LOG )
			Log.d(TAG, "clearPending");
		pending_burst_images.clear();
		slow_burst_capture_requests = null;
		exposure_compensations = null;
		n_burst = 0;
		burst_single_request = false;
		capture_start_time = 0;
	}

	@Override
	public Size getPreviewSize() {
		return new Size(preview_width, preview_height);
	}

	@Override
	public void setPreviewSize(int width, int height) {
		if( MyDebug.LOG )
			Log.d(TAG, "setPreviewSize: " + width + " , " + height);
		/*if( texture != null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "set size of preview texture");
			texture.setDefaultBufferSize(width, height);
		}*/
		preview_width = width;
		preview_height = height;
		/*if( previewImageReader != null ) {
			previewImageReader.close();
		}
		previewImageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2);
		*/
	}

	@Override
	public void setVideoStabilization(boolean enabled) {
		camera_settings.video_stabilization = enabled;
		camera_settings.setVideoStabilization(previewBuilder);
		try {
			setRepeatingRequest();
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to set video stabilization");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
	}

	@Override
	public boolean getVideoStabilization() {
		return camera_settings.video_stabilization;
	}

	@Override
	public void setDefaultCorrection() {
		if( MyDebug.LOG )
			Log.d(TAG, "setDefaultCorrection");
		camera_settings.default_color_space_transform = new ColorSpaceTransform(new int[]{
			255, 255, 0, 255, 0, 255,
			0, 255, 255, 255, 0, 255,
			0, 255, 0, 255, 255, 255
		});
		camera_settings.setColorCorrectionTransform(previewBuilder);

		camera_settings.default_tonemap_mode = CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE;
		float [] values = new float []{0.0f, 0.0f, 1.0f, 1.0f};
		camera_settings.default_tonemap_curve = new TonemapCurve(values, values, values);
	}

	@Override
	public void setToneMapping(String tone_mapping) {
		if( MyDebug.LOG ) {
			Log.d(TAG, "setToneMapping(" + tone_mapping + ")");
		}
		if( Build.VERSION.SDK_INT < Build.VERSION_CODES.M )
			return; // not supported
		if (
			(tone_mapping != null
			&& camera_settings.tone_mapping != null
			&& camera_settings.tone_mapping.equals(tone_mapping))
		)
			return; // no change
		camera_settings.tone_mapping_gamma = 1.0f;
		camera_settings.tone_mapping = tone_mapping;
		camera_settings.tone_mapping_curve = null;
		camera_settings.setToneMapping(previewBuilder);
		try {
			setRepeatingRequest();
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to set tone mapping");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
	}

	@Override
	public void setToneMappingGamma(float tone_mapping_gamma) {
		if( MyDebug.LOG ) {
			Log.d(TAG, "setToneMappingGamma(" + tone_mapping_gamma + ")");
		}
		if( camera_settings.tone_mapping_gamma == tone_mapping_gamma)
			return; // no change
		camera_settings.tone_mapping = null;
		camera_settings.tone_mapping_gamma = tone_mapping_gamma;
		camera_settings.tone_mapping_curve = null;
		camera_settings.setToneMapping(previewBuilder);
		try {
			setRepeatingRequest();
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to set tone mapping");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
	}

	@Override
	public void setToneMappingCurve(float[] levelsR, float[] levelsG, float[] levelsB) {
		if( MyDebug.LOG ) {
			Log.d(TAG, "setToneMappingCurve(" + Arrays.toString(levelsR) + ", " + Arrays.toString(levelsG) + ", " + Arrays.toString(levelsB) + ")");
		}
		
		TonemapCurve curve = new TonemapCurve(levelsR, levelsG, levelsB);
		if (camera_settings.tone_mapping_curve != null && camera_settings.tone_mapping_curve.equals(curve))
			return; // no change

		camera_settings.tone_mapping = null;
		camera_settings.tone_mapping_gamma = 1.0f;
		camera_settings.tone_mapping_curve = curve;
		camera_settings.setToneMapping(previewBuilder);
		try {
			setRepeatingRequest();
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to set tone mapping");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
	}

	@Override
	public boolean hasToneMapping() {
		return camera_settings.tone_mapping_gamma > 1.0f || camera_settings.tone_mapping != null || camera_settings.tone_mapping_curve != null;
	}

	@Override
	public int getJpegQuality() {
		return this.camera_settings.jpeg_quality;
	}

	@Override
	public void setJpegQuality(int quality) {
		if( quality < 0 || quality > 100 ) {
			if( MyDebug.LOG )
				Log.e(TAG, "invalid jpeg quality" + quality);
			throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
		}
		this.camera_settings.jpeg_quality = (byte)quality;
	}

	@Override
	public int getZoom() {
		return this.current_zoom_value;
	}

	@Override
	public void setZoom(int value) {
		if( zoom_ratios == null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "zoom not supported");
			return;
		}
		if( value < 0) {
			value = 0;
		} else if(value >= zoom_ratios.size() ) {
			value = zoom_ratios.size() - 1;
		}
		float zoom = zoom_ratios.get(value)/100.0f;
		Rect sensor_rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
		int left = sensor_rect.width()/2;
		int right = left;
		int top = sensor_rect.height()/2;
		int bottom = top;
		int hwidth = (int)(sensor_rect.width() / (2.0*zoom));
		int hheight = (int)(sensor_rect.height() / (2.0*zoom));
		left -= hwidth;
		right += hwidth;
		top -= hheight;
		bottom += hheight;
		if( MyDebug.LOG ) {
			Log.d(TAG, "zoom: " + zoom);
			Log.d(TAG, "hwidth: " + hwidth);
			Log.d(TAG, "hheight: " + hheight);
			Log.d(TAG, "sensor_rect left: " + sensor_rect.left);
			Log.d(TAG, "sensor_rect top: " + sensor_rect.top);
			Log.d(TAG, "sensor_rect right: " + sensor_rect.right);
			Log.d(TAG, "sensor_rect bottom: " + sensor_rect.bottom);
			Log.d(TAG, "left: " + left);
			Log.d(TAG, "top: " + top);
			Log.d(TAG, "right: " + right);
			Log.d(TAG, "bottom: " + bottom);
			/*Rect current_rect = previewBuilder.get(CaptureRequest.SCALER_CROP_REGION);
			Log.d(TAG, "current_rect left: " + current_rect.left);
			Log.d(TAG, "current_rect top: " + current_rect.top);
			Log.d(TAG, "current_rect right: " + current_rect.right);
			Log.d(TAG, "current_rect bottom: " + current_rect.bottom);*/
		}
		camera_settings.scalar_crop_region = new Rect(left, top, right, bottom);
		camera_settings.setCropRegion(previewBuilder);
		this.current_zoom_value = value;
		try {
			setRepeatingRequest();
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to set zoom");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
	}

	@Override
	public int getExposureCompensation() {
		if( previewBuilder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION) == null )
			return 0;
		return previewBuilder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION);
	}

	@Override
	// Returns whether exposure was modified
	public boolean setExposureCompensation(int new_exposure) {
		camera_settings.has_ae_exposure_compensation = true;
		camera_settings.ae_exposure_compensation = new_exposure;
		if( camera_settings.setExposureCompensation(previewBuilder) ) {
			try {
				setRepeatingRequest();
			}
			catch(CameraAccessException e) {
				if( MyDebug.LOG ) {
					Log.e(TAG, "failed to set exposure compensation");
					Log.e(TAG, "reason: " + e.getReason());
					Log.e(TAG, "message: " + e.getMessage());
				}
				e.printStackTrace();
			}
			return true;
		}
		return false;
	}

	@Override
	public void setPreviewFpsRange(int min, int max) {
		if( MyDebug.LOG )
			Log.d(TAG, "setPreviewFpsRange: " + min +"-" + max);
		camera_settings.ae_target_fps_range = new Range<>(min / 1000, max / 1000);
//		Frame duration is in nanoseconds.  Using min to be safe.
		camera_settings.sensor_frame_duration = (long)(1.0 / (min / 1000.0) * 1000000000L);

		try {
			if( camera_settings.setAEMode(previewBuilder, false) ) {
				setRepeatingRequest();
			}
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to set preview fps range to " + min +"-" + max);
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
	}

	@Override
	public void clearPreviewFpsRange() {
		if( MyDebug.LOG )
			Log.d(TAG, "clearPreviewFpsRange");
		// needed e.g. on Nokia 8 when switching back from slow motion to regular speed, in order to reset to the regular
		// frame rate
		if( camera_settings.ae_target_fps_range != null || camera_settings.sensor_frame_duration != 0 ) {
			// set back to default
			camera_settings.ae_target_fps_range = null;
			camera_settings.sensor_frame_duration = 0;
			createPreviewRequest();
			// createPreviewRequest() needed so that the values in the previewBuilder reset to default values, for
			// CONTROL_AE_TARGET_FPS_RANGE and SENSOR_FRAME_DURATION

			try {
				if( camera_settings.setAEMode(previewBuilder, false) ) {
					setRepeatingRequest();
				}
			}
			catch(CameraAccessException e) {
				if( MyDebug.LOG ) {
					Log.e(TAG, "failed to clear preview fps range");
					Log.e(TAG, "reason: " + e.getReason());
					Log.e(TAG, "message: " + e.getMessage());
				}
				e.printStackTrace();
			}
		}
	}

	@Override
	public List<int[]> getSupportedPreviewFpsRange() {
		if( MyDebug.LOG )
			Log.d(TAG, "getSupportedPreviewFpsRange");
		List<int[]> l = new ArrayList<>();

		if( MyDebug.LOG )
			Log.d(TAG, "   using " + (want_video_high_speed ? "high speed" : "ae")  + " preview fps ranges");

		List<int[]> rr = want_video_high_speed ? hs_fps_ranges : ae_fps_ranges;
		for (int[] r : rr) {
			int[] ir = { r[0] * 1000, r[1] * 1000 };
			l.add( ir );
			if( MyDebug.LOG )
				Log.d(TAG, "	" + r[0] + "-" + r[1]);
		}

		return l;
	}

	@Override
	// note, responsibility of callers to check that this is within the valid min/max range
	public long getDefaultExposureTime() {
		return 1000000000L/30;
	}

	public void setMinIso(int iso) {
		if (iso_range != null) {
			iso_range = new Range(iso, iso_range.getUpper());
		}
	}

	public void setMaxIso(int iso) {
		if (iso_range != null) {
			iso_range = new Range(iso_range.getLower(), iso);
		}
	}

	public void setMinExposureTime(long time) {
		if (exposure_time_range != null) {
			exposure_time_range = new Range(time, exposure_time_range.getUpper());
		}
	}

	public void setMaxExposureTime(long time) {
		if (exposure_time_range != null) {
			exposure_time_range = new Range(exposure_time_range.getLower(), time);
		}
	}

	@Override
	public void setFocusValue(String focus_value) {
		if( MyDebug.LOG )
			Log.d(TAG, "setFocusValue: " + focus_value);
		int focus_mode;
		camera_settings.focus_mode_manual = false;
		switch(focus_value) {
			case "focus_mode_auto":
			case "focus_mode_locked":
				focus_mode = CaptureRequest.CONTROL_AF_MODE_AUTO;
				break;
			case "focus_mode_infinity":
				focus_mode = CaptureRequest.CONTROL_AF_MODE_OFF;
				camera_settings.focus_distance = 0;
				break;
			case "focus_mode_manual2":
				focus_mode = CaptureRequest.CONTROL_AF_MODE_OFF;
				camera_settings.focus_distance = camera_settings.focus_distance_manual;
				camera_settings.focus_mode_manual = true;
				break;
			case "focus_mode_macro":
				focus_mode = CaptureRequest.CONTROL_AF_MODE_MACRO;
				break;
			case "focus_mode_edof":
				focus_mode = CaptureRequest.CONTROL_AF_MODE_EDOF;
				break;
			case "focus_mode_continuous_picture":
				focus_mode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
				break;
			case "focus_mode_continuous_video":
				focus_mode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
				break;
			default:
				if (MyDebug.LOG)
					Log.d(TAG, "setFocusValue() received unknown focus value " + focus_value);
				return;
		}
		camera_settings.has_af_mode = true;
		camera_settings.af_mode = focus_mode;
		camera_settings.setFocusMode(previewBuilder);
		camera_settings.setFocusDistance(previewBuilder); // also need to set distance, in case changed between infinity, manual or other modes
		try {
			setRepeatingRequest();
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to set focus mode");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
	}

	private String convertFocusModeToValue(int focus_mode) {
		if( MyDebug.LOG )
			Log.d(TAG, "convertFocusModeToValue: " + focus_mode);
		if( focus_mode == CaptureRequest.CONTROL_AF_MODE_AUTO ) {
			return "focus_mode_auto";
		}
		else if( focus_mode == CaptureRequest.CONTROL_AF_MODE_MACRO ) {
			return "focus_mode_macro";
		}
		else if( focus_mode == CaptureRequest.CONTROL_AF_MODE_EDOF ) {
			return "focus_mode_edof";
		}
		else if( focus_mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE ) {
			return "focus_mode_continuous_picture";
		}
		else if( focus_mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO ) {
			return "focus_mode_continuous_video";
		}
		else if( focus_mode == CaptureRequest.CONTROL_AF_MODE_OFF ) {
			if (camera_settings.focus_mode_manual)
				return "focus_mode_manual2";
			else
				return "focus_mode_infinity";
		}
		return "";
	}

	@Override
	public String getFocusValue() {
		int focus_mode = previewBuilder.get(CaptureRequest.CONTROL_AF_MODE) != null ?
				previewBuilder.get(CaptureRequest.CONTROL_AF_MODE) : CaptureRequest.CONTROL_AF_MODE_AUTO;
		return convertFocusModeToValue(focus_mode);
	}

	@Override
	public boolean setFocusDistance(float focus_distance) {
		if( MyDebug.LOG )
			Log.d(TAG, "setFocusDistance: " + focus_distance);
		if( camera_settings.focus_distance == focus_distance ) {
			if( MyDebug.LOG )
				Log.d(TAG, "already set");
			return false;
		}
		camera_settings.focus_distance = focus_distance;
		camera_settings.focus_distance_manual = focus_distance;
		camera_settings.setFocusDistance(previewBuilder);
		try {
			setRepeatingRequest();
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to set focus distance");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
		return true;
	}

	@Override
	public void setFocusDistanceCalibration(float value) {
		camera_settings.focus_distance_calibration = value;
		if (previewBuilder.get(CaptureRequest.CONTROL_AF_MODE) != null && previewBuilder.get(CaptureRequest.CONTROL_AF_MODE) == CaptureRequest.CONTROL_AF_MODE_OFF) {
			camera_settings.setFocusDistance(previewBuilder);
		}
	}

	/** Decides whether we should be using fake precapture mode.
	 */
	private void updateUseFakePrecaptureMode(String flash_value) {
		if( MyDebug.LOG )
			Log.d(TAG, "useFakePrecaptureMode: " + flash_value);
		boolean frontscreen_flash = flash_value.equals("flash_frontscreen_auto") || flash_value.equals("flash_frontscreen_on");
		if( frontscreen_flash ) {
			fake_flash_mode = FakeFlashMode.Torch;
		}
		else if( this.want_expo_bracketing || this.want_burst )
			fake_flash_mode = FakeFlashMode.Torch;
		else {
			fake_flash_mode = fake_flash;
		}
		if( MyDebug.LOG )
			Log.d(TAG, "fake_flash_mode set to: " + fake_flash_mode);
	}

	@Override
	public void setFlashValue(String flash_value) {
		if( MyDebug.LOG )
			Log.d(TAG, "setFlashValue: " + flash_value);
		if( camera_settings.flash_value.equals(flash_value) ) {
			if( MyDebug.LOG )
				Log.d(TAG, "flash value already set");
			return;
		}

		try {
			updateUseFakePrecaptureMode(flash_value);
			
			if( camera_settings.flash_value.equals("flash_torch") && !flash_value.equals("flash_off") ) {
				// hack - if switching to something other than flash_off, we first need to turn torch off, otherwise torch remains on (at least on Nexus 6)
				camera_settings.flash_value = "flash_off";
				camera_settings.setAEMode(previewBuilder, false);
				setRepeatingRequest();

				// need to wait until torch actually turned off
				push_repeating_request_when_torch_off = true;
				push_repeating_request_when_torch_off_value = flash_value;
			}
			else {
				camera_settings.flash_value = flash_value;
				if( camera_settings.setAEMode(previewBuilder, false) ) {
					setRepeatingRequest();
				}
			}
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to set flash mode");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
	}

	@Override
	public String getFlashValue() {
		// returns "" if flash isn't supported
		if( !characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ) {
			return "";
		}
		return camera_settings.flash_value;
	}

	@Override
	public void setAutoExposureLock(boolean enabled) {
		camera_settings.auto_exposure_lock = enabled;
		camera_settings.setAutoExposureLock(previewBuilder);
		camera_settings.setBlackLevelLock(previewBuilder);

		try {
			setRepeatingRequest();
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to set auto exposure lock");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
	}

	@Override
	public void setAutoWhiteBalanceLock(boolean enabled) {
		camera_settings.auto_wb_lock = enabled;
		camera_settings.setAutoWhiteBalanceLock(previewBuilder);

		try {
			setRepeatingRequest();
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to set auto wb lock");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
	}

	@Override
	public void setAutoAdjustmentLock(boolean enabled) {
		camera_settings.auto_exposure_lock = enabled;
		camera_settings.auto_wb_lock = enabled;
		camera_settings.setAutoExposureLock(previewBuilder);
		camera_settings.setAutoWhiteBalanceLock(previewBuilder);
		camera_settings.setBlackLevelLock(previewBuilder);

		try {
			setRepeatingRequest();
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to set auto adjustment lock");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
	}

	@Override
	public void setRotation(int rotation) {
		this.camera_settings.rotation = rotation;
	}

	@Override
	public void setLocationInfo(Location location) {
		if( MyDebug.LOG )
			Log.d(TAG, "setLocationInfo: " + location.getLongitude() + " , " + location.getLatitude());
		this.camera_settings.location = location;
	}

	@Override
	public void removeLocationInfo() {
		this.camera_settings.location = null;
	}

	@Override
	public void setUseFastBurst(boolean value) {
		use_fast_burst = value;
	}

	@Override
	public void setBurstDelay(int value) {
		burst_delay = value;
	}

	@Override
	public void setPreviewMaxExposure(int value) {
		preview_max_exposure = value;
	}

	@Override
	public void useIsoForExpoBracketing(boolean value) {
		use_iso_for_expo_bracketing = value;
	}

	/** Returns the viewable rect - this is crop region if available.
	 *  We need this as callers will pass in (or expect returned) CameraController.Area values that
	 *  are relative to the current view (i.e., taking zoom into account) (the old Camera API in
	 *  CameraController1 always works in terms of the current view, whilst Camera2 works in terms
	 *  of the full view always). Similarly for the rect field in CameraController.Face.
	 */
	private Rect getViewableRect() {
		if( previewBuilder != null ) {
			Rect crop_rect = previewBuilder.get(CaptureRequest.SCALER_CROP_REGION);
			if( crop_rect != null ) {
				return crop_rect;
			}
		}
		Rect sensor_rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
		sensor_rect.right -= sensor_rect.left;
		sensor_rect.left = 0;
		sensor_rect.bottom -= sensor_rect.top;
		sensor_rect.top = 0;
		return sensor_rect;
	}

	private Rect convertRectToCamera2(Rect crop_rect, Rect rect) {
		// CameraController.Area is always [-1000, -1000] to [1000, 1000] for the viewable region
		// but for CameraController2, we must convert to be relative to the crop region
		double left_f = (rect.left+1000)/2000.0;
		double top_f = (rect.top+1000)/2000.0;
		double right_f = (rect.right+1000)/2000.0;
		double bottom_f = (rect.bottom+1000)/2000.0;
		int left = (int)(crop_rect.left + left_f * (crop_rect.width()-1));
		int right = (int)(crop_rect.left + right_f * (crop_rect.width()-1));
		int top = (int)(crop_rect.top + top_f * (crop_rect.height()-1));
		int bottom = (int)(crop_rect.top + bottom_f * (crop_rect.height()-1));
		left = Math.max(left, crop_rect.left);
		right = Math.max(right, crop_rect.left);
		top = Math.max(top, crop_rect.top);
		bottom = Math.max(bottom, crop_rect.top);
		left = Math.min(left, crop_rect.right);
		right = Math.min(right, crop_rect.right);
		top = Math.min(top, crop_rect.bottom);
		bottom = Math.min(bottom, crop_rect.bottom);

		return new Rect(left, top, right, bottom);
	}

	private MeteringRectangle convertAreaToMeteringRectangle(Rect sensor_rect, Area area) {
		Rect camera2_rect = convertRectToCamera2(sensor_rect, area.rect);
		return new MeteringRectangle(camera2_rect, area.weight);
	}

	private Rect convertRectFromCamera2(Rect crop_rect, Rect camera2_rect) {
		// inverse of convertRectToCamera2()
		double left_f = (camera2_rect.left-crop_rect.left)/(double)(crop_rect.width()-1);
		double top_f = (camera2_rect.top-crop_rect.top)/(double)(crop_rect.height()-1);
		double right_f = (camera2_rect.right-crop_rect.left)/(double)(crop_rect.width()-1);
		double bottom_f = (camera2_rect.bottom-crop_rect.top)/(double)(crop_rect.height()-1);
		int left = (int)(left_f * 2000) - 1000;
		int right = (int)(right_f * 2000) - 1000;
		int top = (int)(top_f * 2000) - 1000;
		int bottom = (int)(bottom_f * 2000) - 1000;

		left = Math.max(left, -1000);
		right = Math.max(right, -1000);
		top = Math.max(top, -1000);
		bottom = Math.max(bottom, -1000);
		left = Math.min(left, 1000);
		right = Math.min(right, 1000);
		top = Math.min(top, 1000);
		bottom = Math.min(bottom, 1000);

		return new Rect(left, top, right, bottom);
	}

	private Area convertMeteringRectangleToArea(Rect sensor_rect, MeteringRectangle metering_rectangle) {
		Rect area_rect = convertRectFromCamera2(sensor_rect, metering_rectangle.getRect());
		return new Area(area_rect, metering_rectangle.getMeteringWeight());
	}

	private CameraController.Face convertFromCameraFace(Rect sensor_rect, android.hardware.camera2.params.Face camera2_face) {
		Rect area_rect = convertRectFromCamera2(sensor_rect, camera2_face.getBounds());
		return new CameraController.Face(camera2_face.getScore(), area_rect);
	}

	@Override
	public boolean setFocusAndMeteringArea(List<Area> areas, boolean focus_area, boolean metering_area) {
		Rect sensor_rect = getViewableRect();
		if( MyDebug.LOG )
			Log.d(TAG, "sensor_rect: " + sensor_rect.left + " , " + sensor_rect.top + " x " + sensor_rect.right + " , " + sensor_rect.bottom);
		boolean has_focus = false;
		boolean has_metering = false;
		if( focus_area && characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) > 0 ) {
			has_focus = true;
			camera_settings.af_regions = new MeteringRectangle[areas.size()];
			int i = 0;
			for(CameraController.Area area : areas) {
				camera_settings.af_regions[i++] = convertAreaToMeteringRectangle(sensor_rect, area);
			}
			camera_settings.setAFRegions(previewBuilder);
		}
		else
			camera_settings.af_regions = null;
		if( metering_area && characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) > 0 ) {
			has_metering = true;
			camera_settings.ae_regions = new MeteringRectangle[areas.size()];
			int i = 0;
			for(CameraController.Area area : areas) {
				camera_settings.ae_regions[i++] = convertAreaToMeteringRectangle(sensor_rect, area);
			}
			camera_settings.setAERegions(previewBuilder);
		}
		else
			camera_settings.ae_regions = null;
		if( has_focus || has_metering ) {
			try {
				setRepeatingRequest();
			}
			catch(CameraAccessException e) {
				if( MyDebug.LOG ) {
					Log.e(TAG, "failed to set focus and/or metering regions");
					Log.e(TAG, "reason: " + e.getReason());
					Log.e(TAG, "message: " + e.getMessage());
				}
				e.printStackTrace();
			}
		}
		return has_focus || has_metering;
	}

	@Override
	public void clearFocusAndMetering() {
		Rect sensor_rect = getViewableRect();
		boolean has_focus = false;
		boolean has_metering = false;
		if( sensor_rect.width() <= 0 || sensor_rect.height() <= 0 ) {
			// had a crash on Google Play due to creating a MeteringRectangle with -ve width/height ?!
			camera_settings.af_regions = null;
			camera_settings.ae_regions = null;
		}
		else {
			if( characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) > 0 ) {
				has_focus = true;
				camera_settings.af_regions = new MeteringRectangle[1];
				camera_settings.af_regions[0] = new MeteringRectangle(0, 0, sensor_rect.width()-1, sensor_rect.height()-1, 0);
				camera_settings.setAFRegions(previewBuilder);
			}
			else
				camera_settings.af_regions = null;
			if( characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) > 0 ) {
				has_metering = true;
				camera_settings.ae_regions = new MeteringRectangle[1];
				camera_settings.ae_regions[0] = new MeteringRectangle(0, 0, sensor_rect.width()-1, sensor_rect.height()-1, 0);
				camera_settings.setAERegions(previewBuilder);
			}
			else
				camera_settings.ae_regions = null;
		}
		if( has_focus || has_metering ) {
			try {
				setRepeatingRequest();
			}
			catch(CameraAccessException e) {
				if( MyDebug.LOG ) {
					Log.e(TAG, "failed to clear focus and metering regions");
					Log.e(TAG, "reason: " + e.getReason());
					Log.e(TAG, "message: " + e.getMessage());
				}
				e.printStackTrace();
			}
		}
	}

	@Override
	public List<Area> getFocusAreas() {
		if( characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) == 0 )
			return null;
		MeteringRectangle [] metering_rectangles = previewBuilder.get(CaptureRequest.CONTROL_AF_REGIONS);
		if( metering_rectangles == null )
			return null;
		Rect sensor_rect = getViewableRect();
		camera_settings.af_regions[0] = new MeteringRectangle(0, 0, sensor_rect.width()-1, sensor_rect.height()-1, 0);
		if( metering_rectangles.length == 1 && metering_rectangles[0].getRect().left == 0 && metering_rectangles[0].getRect().top == 0 && metering_rectangles[0].getRect().right == sensor_rect.width()-1 && metering_rectangles[0].getRect().bottom == sensor_rect.height()-1 ) {
			// for compatibility with CameraController1
			return null;
		}
		List<Area> areas = new ArrayList<>();
		for(MeteringRectangle metering_rectangle : metering_rectangles) {
			areas.add(convertMeteringRectangleToArea(sensor_rect, metering_rectangle));
		}
		return areas;
	}

	@Override
	public List<Area> getMeteringAreas() {
		if( characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) == 0 )
			return null;
		MeteringRectangle [] metering_rectangles = previewBuilder.get(CaptureRequest.CONTROL_AE_REGIONS);
		if( metering_rectangles == null )
			return null;
		Rect sensor_rect = getViewableRect();
		if( metering_rectangles.length == 1 && metering_rectangles[0].getRect().left == 0 && metering_rectangles[0].getRect().top == 0 && metering_rectangles[0].getRect().right == sensor_rect.width()-1 && metering_rectangles[0].getRect().bottom == sensor_rect.height()-1 ) {
			// for compatibility with CameraController1
			return null;
		}
		List<Area> areas = new ArrayList<>();
		for(MeteringRectangle metering_rectangle : metering_rectangles) {
			areas.add(convertMeteringRectangleToArea(sensor_rect, metering_rectangle));
		}
		return areas;
	}

	@Override
	public boolean supportsAutoFocus() {
		if( previewBuilder.get(CaptureRequest.CONTROL_AF_MODE) == null )
			return false;
		int focus_mode = previewBuilder.get(CaptureRequest.CONTROL_AF_MODE);
		if( focus_mode == CaptureRequest.CONTROL_AF_MODE_AUTO || focus_mode == CaptureRequest.CONTROL_AF_MODE_MACRO )
			return true;
		return false;
	}

	@Override
	public boolean focusIsContinuous() {
		if( previewBuilder == null || previewBuilder.get(CaptureRequest.CONTROL_AF_MODE) == null )
			return false;
		int focus_mode = previewBuilder.get(CaptureRequest.CONTROL_AF_MODE);
		if( focus_mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE || focus_mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO )
			return true;
		return false;
	}

	@Override
	public boolean focusIsVideo() {
		if( previewBuilder.get(CaptureRequest.CONTROL_AF_MODE) == null )
			return false;
		int focus_mode = previewBuilder.get(CaptureRequest.CONTROL_AF_MODE);
		if( focus_mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO ) {
			return true;
		}
		return false;
	}

	@Override
	public void setPreviewDisplay(SurfaceHolder holder) throws CameraControllerException {
		if( MyDebug.LOG ) {
			Log.d(TAG, "setPreviewDisplay");
			Log.e(TAG, "SurfaceHolder not supported for CameraController2!");
			Log.e(TAG, "Should use setPreviewTexture() instead");
		}
		throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
	}

	@Override
	public void setPreviewTexture(SurfaceTexture texture) throws CameraControllerException {
		if( MyDebug.LOG )
			Log.d(TAG, "setPreviewTexture");
		if( this.texture != null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "preview texture already set");
			throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
		}
		this.texture = texture;
	}

	CaptureRequest previous_request;
	// Ultimate hack for fuckin buggy qualcomm shit: set repeating request after every frame
	private void repeatRepeatingRequest() throws CameraAccessException {
		if( MyDebug.LOG )
			Log.d(TAG, "setRepeatingRequest");
		if( camera == null || captureSession == null || previous_request == null ) {
			return;
		}
		try {
			captureSession.setRepeatingRequest(previous_request, previewCaptureCallback, handler);
		} catch(IllegalStateException e) {
			e.printStackTrace();
		}
	}

	private void setRepeatingRequest() throws CameraAccessException {
		previous_request = previewBuilder.build();
		setRepeatingRequest(previous_request);
	}

	private void setRepeatingRequest(CaptureRequest request) throws CameraAccessException {
		if( MyDebug.LOG )
			Log.d(TAG, "setRepeatingRequest");
		if( camera == null || captureSession == null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "no camera or capture session");
			return;
		}
		try {
			if( is_video_high_speed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) {
				CameraConstrainedHighSpeedCaptureSession captureSessionHighSpeed = (CameraConstrainedHighSpeedCaptureSession) captureSession;
				List<CaptureRequest> mPreviewBuilderBurst = captureSessionHighSpeed.createHighSpeedRequestList(request);
				captureSessionHighSpeed.setRepeatingBurst(mPreviewBuilderBurst, previewCaptureCallback, handler);
			}
			else {
				captureSession.setRepeatingRequest(request, previewCaptureCallback, handler);
			}
			if( MyDebug.LOG )
				Log.d(TAG, "setRepeatingRequest done");
		}
		catch(IllegalStateException e) {
			if( MyDebug.LOG )
				Log.d(TAG, "captureSession already closed!");
			e.printStackTrace();
			// got this as a Google Play exception (from onCaptureCompleted->processCompleted) - this means the capture session is already closed
		}
	}

	private void capture() throws CameraAccessException {
		capture(previewBuilder.build());
	}

	private void capture(CaptureRequest request) throws CameraAccessException {
		if( MyDebug.LOG )
			Log.d(TAG, "capture");
		if( camera == null || captureSession == null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "no camera or capture session");
			return;
		}
		captureSession.capture(request, previewCaptureCallback, handler);
	}

	private void createPreviewRequest() {
		if( MyDebug.LOG )
			Log.d(TAG, "createPreviewRequest");
		if( camera == null  ) {
			if( MyDebug.LOG )
				Log.d(TAG, "camera not available!");
			return;
		}
		if( MyDebug.LOG )
			Log.d(TAG, "camera: " + camera);
		try {
			previewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
			previewIsVideoMode = false;
			previewBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW);
			previewBuilder.setTag(RequestTag.PREVIEW);
			camera_settings.setupBuilder(previewBuilder, false);
			if( MyDebug.LOG )
				Log.d(TAG, "successfully created preview request");
		}
		catch(CameraAccessException e) {
			//captureSession = null;
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to create capture request");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
	}

	private Surface getPreviewSurface() {
		return surface_texture;
	}

	private void createCaptureSession(final MediaRecorder video_recorder, boolean want_photo_video_recording) throws CameraControllerException {
		if( MyDebug.LOG )
			Log.d(TAG, "create capture session");
		
		if( previewBuilder == null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "previewBuilder not present!");
			throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
		}
		if( camera == null ) {
			if( MyDebug.LOG )
				Log.e(TAG, "no camera");
			return;
		}

		if( captureSession != null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "close old capture session");
			captureSession.close();
			captureSession = null;
			//pending_request_when_ready = null;
		}

		try {
			if( video_recorder != null ) {
				if( supports_photo_video_recording && !want_video_high_speed && want_photo_video_recording ) {
					createPictureImageReader();
				}
				else {
					closePictureImageReader();
				}
			}
			else {
				// in some cases need to recreate picture imageReader and the texture default buffer size (e.g., see test testTakePhotoPreviewPaused())
				createPictureImageReader();
			}
			if( texture != null ) {
				// need to set the texture size
				if( MyDebug.LOG )
					Log.d(TAG, "set size of preview texture");
				if( preview_width == 0 || preview_height == 0 ) {
					if( MyDebug.LOG )
						Log.e(TAG, "application needs to call setPreviewSize()");
					throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
				}
				texture.setDefaultBufferSize(preview_width, preview_height);
				// also need to create a new surface for the texture, in case the size has changed - but make sure we remove the old one first!
				if( surface_texture != null ) {
					if( MyDebug.LOG )
						Log.d(TAG, "remove old target: " + surface_texture);
					previewBuilder.removeTarget(surface_texture);
				}
				this.surface_texture = new Surface(texture);
				if( MyDebug.LOG )
					Log.d(TAG, "created new target: " + surface_texture);
			}
			if( video_recorder != null ) {
				if( MyDebug.LOG )
					Log.d(TAG, "creating capture session for video recording");
			}
			else {
				if( MyDebug.LOG ) {
					if (imageReader != null)
						Log.d(TAG, "picture size: " + imageReader.getWidth() + " x " + imageReader.getHeight());
					else if (imageReaderUncompressed != null)
						Log.d(TAG, "picture size: " + imageReaderUncompressed.getWidth() + " x " + imageReaderUncompressed.getHeight());
				}
			}
			/*if( MyDebug.LOG )
			Log.d(TAG, "preview size: " + previewImageReader.getWidth() + " x " + previewImageReader.getHeight());*/
			if( MyDebug.LOG )
				Log.d(TAG, "preview size: " + this.preview_width + " x " + this.preview_height);

			class MyStateCallback extends CameraCaptureSession.StateCallback {
				private boolean callback_done; // must sychronize on this and notifyAll when setting to true
				@Override
				public void onConfigured(@NonNull CameraCaptureSession session) {
					if( MyDebug.LOG ) {
						Log.d(TAG, "onConfigured: " + session);
						Log.d(TAG, "captureSession was: " + captureSession);
					}
					if( camera == null ) {
						if( MyDebug.LOG ) {
							Log.d(TAG, "camera is closed");
						}
						synchronized( create_capture_session_lock ) {
							callback_done = true;
							create_capture_session_lock.notifyAll();
						}
						return;
					}
					captureSession = session;
					Surface surface = getPreviewSurface();
					previewBuilder.addTarget(surface);
					if( video_recorder != null )
						previewBuilder.addTarget(video_recorder.getSurface());
					try {
						setRepeatingRequest();
					}
					catch(CameraAccessException e) {
						if( MyDebug.LOG ) {
							Log.e(TAG, "failed to start preview");
							Log.e(TAG, "reason: " + e.getReason());
							Log.e(TAG, "message: " + e.getMessage());
						}
						e.printStackTrace();
						// we indicate that we failed to start the preview by setting captureSession back to null
						// this will cause a CameraControllerException to be thrown below
						captureSession = null;
					}
					synchronized( create_capture_session_lock ) {
						callback_done = true;
						create_capture_session_lock.notifyAll();
					}
				}

				@Override
				public void onConfigureFailed(@NonNull CameraCaptureSession session) {
					if( MyDebug.LOG ) {
						Log.d(TAG, "onConfigureFailed: " + session);
						Log.d(TAG, "captureSession was: " + captureSession);
					}
					synchronized( create_capture_session_lock ) {
						callback_done = true;
						create_capture_session_lock.notifyAll();
					}
					// don't throw CameraControllerException here, as won't be caught - instead we throw CameraControllerException below
				}

				/*@Override
				public void onReady(CameraCaptureSession session) {
					if( MyDebug.LOG )
						Log.d(TAG, "onReady: " + session);
					if( pending_request_when_ready != null ) {
						if( MyDebug.LOG )
							Log.d(TAG, "have pending_request_when_ready: " + pending_request_when_ready);
						CaptureRequest request = pending_request_when_ready;
						pending_request_when_ready = null;
						try {
							captureSession.capture(request, previewCaptureCallback, handler);
						}
						catch(CameraAccessException e) {
							if( MyDebug.LOG ) {
								Log.e(TAG, "failed to take picture");
								Log.e(TAG, "reason: " + e.getReason());
								Log.e(TAG, "message: " + e.getMessage());
							}
							e.printStackTrace();
							jpeg_cb = null;
							if( take_picture_error_cb != null ) {
								take_picture_error_cb.onError();
								take_picture_error_cb = null;
							}
						}
					}
				}*/
			}
			final MyStateCallback myStateCallback = new MyStateCallback();

			Surface preview_surface = getPreviewSurface();
			List<Surface> surfaces = new ArrayList<>();
			surfaces.add(preview_surface);
			
			if( video_recorder != null ) {
				surfaces.add(video_recorder.getSurface());
				if( supports_photo_video_recording && !want_video_high_speed && want_photo_video_recording ) {
					surfaces.add(imageReader.getSurface());
				}
				// n.b., raw not supported for photo snapshots while video recording
			} else {
				if (imageReader != null)
					surfaces.add(imageReader.getSurface());
				if (imageReaderUncompressed != null)
					surfaces.add(imageReaderUncompressed.getSurface());
				if( imageReaderRaw != null )
					surfaces.add(imageReaderRaw.getSurface());
			}
			if( MyDebug.LOG ) {
				Log.d(TAG, "texture: " + texture);
				Log.d(TAG, "preview_surface: " + preview_surface);
				if( video_recorder == null ) {
					if (imageReader != null) {
						Log.d(TAG, "imageReader: " + imageReader);
						Log.d(TAG, "imageReader: " + imageReader.getWidth());
						Log.d(TAG, "imageReader: " + imageReader.getHeight());
						Log.d(TAG, "imageReader: " + imageReader.getImageFormat());
					}
					if (imageReaderUncompressed != null) {
						Log.d(TAG, "imageReaderUncompressed: " + imageReaderUncompressed);
						Log.d(TAG, "imageReaderUncompressed: " + imageReaderUncompressed.getWidth());
						Log.d(TAG, "imageReaderUncompressed: " + imageReaderUncompressed.getHeight());
						Log.d(TAG, "imageReaderUncompressed: " + imageReaderUncompressed.getImageFormat());
					}
					if( imageReaderRaw != null ) {
						Log.d(TAG, "imageReaderRaw: " + imageReaderRaw);
						Log.d(TAG, "imageReaderRaw: " + imageReaderRaw.getWidth());
						Log.d(TAG, "imageReaderRaw: " + imageReaderRaw.getHeight());
						Log.d(TAG, "imageReaderRaw: " + imageReaderRaw.getImageFormat());
					}
				}
			}
			if( video_recorder != null && want_video_high_speed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) {
				camera.createConstrainedHighSpeedCaptureSession(surfaces,
					myStateCallback,
					handler);
				is_video_high_speed = true;
			}
			else {
				try {
					camera.createCaptureSession(surfaces,
						myStateCallback,
						handler);
					is_video_high_speed = false;
				}
				catch(NullPointerException e) {
					// have had this from some devices on Google Play, from deep within createCaptureSession
					// note, we put the catch here rather than below, so as to not mask nullpointerexceptions
					// from my code
					if( MyDebug.LOG ) {
						Log.e(TAG, "NullPointerException trying to create capture session");
						Log.e(TAG, "message: " + e.getMessage());
					}
					e.printStackTrace();
					throw new CameraControllerException();
				}
			}
			if( MyDebug.LOG )
				Log.d(TAG, "wait until session created...");
			synchronized( create_capture_session_lock ) {
				while( !myStateCallback.callback_done ) {
					try {
						// release the lock, and wait until myStateCallback calls notifyAll()
						create_capture_session_lock.wait();
					}
					catch(InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
			if( MyDebug.LOG ) {
				Log.d(TAG, "created captureSession: " + captureSession);
			}
			if( captureSession == null ) {
				if( MyDebug.LOG )
					Log.e(TAG, "failed to create capture session");
				throw new CameraControllerException();
			}
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "CameraAccessException trying to create capture session");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
			throw new CameraControllerException();
		}
	}

	@Override
	public void startPreview() throws CameraControllerException {
		if( MyDebug.LOG )
			Log.d(TAG, "startPreview");
		if( captureSession != null ) {
			try {
				setRepeatingRequest();
			}
			catch(CameraAccessException e) {
				if( MyDebug.LOG ) {
					Log.e(TAG, "failed to start preview");
					Log.e(TAG, "reason: " + e.getReason());
					Log.e(TAG, "message: " + e.getMessage());
				}
				e.printStackTrace();
				// do via CameraControllerException instead of preview_error_cb, so caller immediately knows preview has failed
				throw new CameraControllerException();
			}
			return;
		}
		createCaptureSession(null, false);
	}

	@Override
	public void stopPreview() {
		if( MyDebug.LOG )
			Log.d(TAG, "stopPreview: " + this);
		if( camera == null || captureSession == null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "no camera or capture session");
			return;
		}
		try {
			//pending_request_when_ready = null;

			try {
				captureSession.stopRepeating();
			}
			catch(IllegalStateException e) {
				if( MyDebug.LOG )
					Log.d(TAG, "captureSession already closed!");
				e.printStackTrace();
				// got this as a Google Play exception
				// we still call close() below, as it has no effect if captureSession is already closed
			}
			// although stopRepeating() alone will pause the preview, seems better to close captureSession altogether - this allows the app to make changes such as changing the picture size
			if( MyDebug.LOG )
				Log.d(TAG, "close capture session");
			captureSession.close();
			captureSession = null;
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to stop repeating");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
		// simulate CameraController1 behaviour where face detection is stopped when we stop preview
		if( camera_settings.has_face_detect_mode ) {
			if( MyDebug.LOG )
				Log.d(TAG, "cancel face detection");
			camera_settings.has_face_detect_mode = false;
			camera_settings.setFaceDetectMode(previewBuilder);
			// no need to call setRepeatingRequest(), we're just setting the camera_settings for when we restart the preview
		}
	}

	@Override
	public boolean startFaceDetection() {
		if( MyDebug.LOG )
			Log.d(TAG, "startFaceDetection");
		if( previewBuilder.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE) != null && previewBuilder.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE) != CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF ) {
			if( MyDebug.LOG )
				Log.d(TAG, "face detection already enabled");
			return false;
		}
		if( supports_face_detect_mode_full ) {
			if( MyDebug.LOG )
				Log.d(TAG, "use full face detection");
			camera_settings.has_face_detect_mode = true;
			camera_settings.face_detect_mode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL;
		}
		else if( supports_face_detect_mode_simple ) {
			if( MyDebug.LOG )
				Log.d(TAG, "use simple face detection");
			camera_settings.has_face_detect_mode = true;
			camera_settings.face_detect_mode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE;
		}
		else {
			Log.e(TAG, "startFaceDetection() called but face detection not available");
			return false;
		}
		camera_settings.setFaceDetectMode(previewBuilder);
		try {
			setRepeatingRequest();
			return false;
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to start face detection");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
		return true;
	}

	@Override
	public void setFaceDetectionListener(final FaceDetectionListener listener) {
		this.face_detection_listener = listener;
	}

	/* If do_af_trigger_for_continuous is false, doing an autoFocus() in continuous focus mode just
	   means we call the autofocus callback the moment focus is not scanning (as with old Camera API).
	   If do_af_trigger_for_continuous is true, we set CONTROL_AF_TRIGGER_START, and wait for
	   CONTROL_AF_STATE_FOCUSED_LOCKED or CONTROL_AF_STATE_NOT_FOCUSED_LOCKED, similar to other focus
	   methods.
	   do_af_trigger_for_continuous==true has advantages:
		 - On Nexus 6 for flash auto, it means ae state is set to FLASH_REQUIRED if it is required
		   when it comes to taking the photo. If do_af_trigger_for_continuous==false, sometimes
		   it's set to CONTROL_AE_STATE_CONVERGED even for dark scenes, so we think we can skip
		   the precapture, causing photos to come out dark (or we can force always doing precapture,
		   but that makes things slower when flash isn't needed)
		 - On OnePlus 3T, with do_af_trigger_for_continuous==false photos come out with blue tinge
		   if the scene is not dark (but still dark enough that you'd want flash).
		   do_af_trigger_for_continuous==true fixes this for cases where the flash fires for autofocus.
		   Note that the problem is still not fixed for flash on where the scene is bright enough to
		   not need flash (and so we don't fire flash for autofocus).
	   do_af_trigger_for_continuous==true has disadvantage:
		 - On both Nexus 6 and OnePlus 3T, taking photos with flash is longer, as we have flash firing
		   for autofocus and precapture. Though note this is the case with autofocus mode anyway.
	   Note for fake flash mode, we still can use do_af_trigger_for_continuous==false (and doing the
	   af trigger for fake flash mode can sometimes mean flash fires for too long and we get a worse
	   result).
	 */
	//private final static boolean do_af_trigger_for_continuous = false;
	private final static boolean do_af_trigger_for_continuous = true;

	@Override
	public void autoFocus(final AutoFocusCallback cb, boolean capture_follows_autofocus_hint) {
		if( MyDebug.LOG ) {
			Log.d(TAG, "autoFocus");
			Log.d(TAG, "capture_follows_autofocus_hint? " + capture_follows_autofocus_hint);
		}
		fake_precapture_torch_focus_performed = false;
		if( camera == null || captureSession == null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "no camera or capture session");
			// should call the callback, so the application isn't left waiting (e.g., when we autofocus before trying to take a photo)
			cb.onAutoFocus(false);
			return;
		}
		Integer focus_mode = previewBuilder.get(CaptureRequest.CONTROL_AF_MODE);
		if( focus_mode == null ) {
			// we preserve the old Camera API where calling autoFocus() on a device without autofocus immediately calls the callback
			// (unclear if Open Camera needs this, but just to be safe and consistent between camera APIs)
			cb.onAutoFocus(true);
			return;
		}
		else if( (!do_af_trigger_for_continuous || fake_flash_mode != FakeFlashMode.None) && focus_mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE ) {
			// See note above for do_af_trigger_for_continuous
			this.capture_follows_autofocus_hint = capture_follows_autofocus_hint;
			this.autofocus_cb = cb;
			return;
		}
		else if( is_video_high_speed ) {
			// CONTROL_AF_TRIGGER_IDLE/CONTROL_AF_TRIGGER_START not supported for high speed video
			cb.onAutoFocus(true);
			return;
		}
		/*if( state == STATE_WAITING_AUTOFOCUS ) {
			if( MyDebug.LOG )
				Log.d(TAG, "already waiting for an autofocus");
			// need to update the callback!
			this.capture_follows_autofocus_hint = capture_follows_autofocus_hint;
			this.autofocus_cb = cb;
			return;
		}*/
		CaptureRequest.Builder afBuilder = previewBuilder;
		if( MyDebug.LOG ) {
			{
				MeteringRectangle [] areas = afBuilder.get(CaptureRequest.CONTROL_AF_REGIONS);
				for(int i=0;areas != null && i<areas.length;i++) {
					Log.d(TAG, i + " focus area: " + areas[i].getX() + " , " + areas[i].getY() + " : " + areas[i].getWidth() + " x " + areas[i].getHeight() + " weight " + areas[i].getMeteringWeight());
				}
			}
			{
				MeteringRectangle [] areas = afBuilder.get(CaptureRequest.CONTROL_AE_REGIONS);
				for(int i=0;areas != null && i<areas.length;i++) {
					Log.d(TAG, i + " metering area: " + areas[i].getX() + " , " + areas[i].getY() + " : " + areas[i].getWidth() + " x " + areas[i].getHeight() + " weight " + areas[i].getMeteringWeight());
				}
			}
		}
		state = STATE_WAITING_AUTOFOCUS;
		precapture_state_change_time_ms = -1;
		this.capture_follows_autofocus_hint = capture_follows_autofocus_hint;
		this.autofocus_cb = cb;
		try {
			if( fake_flash_mode != FakeFlashMode.None && !camera_settings.manual_mode ) {
				boolean want_flash = false;
				if( camera_settings.flash_value.equals("flash_auto") || camera_settings.flash_value.equals("flash_frontscreen_auto") ) {
					// calling fireAutoFlash() also caches the decision on whether to flash - otherwise if the flash fires now, we'll then think the scene is bright enough to not need the flash!
					if( fireAutoFlash() )
						want_flash = true;
				}
				else if( camera_settings.flash_value.equals("flash_on") ) {
					want_flash = true;
				}
				if( want_flash ) {
					if( MyDebug.LOG )
						Log.d(TAG, "turn on torch for fake flash");
					afBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
					afBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
					test_fake_flash_focus++;
					fake_precapture_torch_focus_performed = true;
					setRepeatingRequest(afBuilder.build());
					// We sleep for a short time as on some devices (e.g., OnePlus 3T), the torch will turn off when autofocus
					// completes even if we don't want that (because we'll be taking a photo).
					// Note that on other devices such as Nexus 6, this problem doesn't occur even if we don't have a separate
					// setRepeatingRequest.
					// Update for 1.37: now we do need this for Nexus 6 too, after switching to setting CONTROL_AE_MODE_ON_AUTO_FLASH
					// or CONTROL_AE_MODE_ON_ALWAYS_FLASH even for fake flash (see note in CameraSettings.setAEMode()) - and we
					// needed to increase to 200ms! Otherwise photos come out too dark for flash on if doing touch to focus then
					// quickly taking a photo. (It also work to previously switch to CONTROL_AE_MODE_ON/FLASH_MODE_OFF first,
					// but then the same problem shows up on OnePlus 3T again!)
					try {
						Thread.sleep(200);
					}
					catch(InterruptedException e) {
						e.printStackTrace();
					}
				}
			}

			// Camera2Basic sets a trigger with capture
			// Google Camera sets to idle with a repeating request, then sets af trigger to start with a capture
			afBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
			setRepeatingRequest(afBuilder.build());
			afBuilder.setTag(RequestTag.AUTOFOCUS);
			afBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
			CaptureRequest afRequest = afBuilder.build();
			afBuilder.setTag(RequestTag.PREVIEW);
			capture(afRequest);
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to autofocus");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
			state = STATE_NORMAL;
			precapture_state_change_time_ms = -1;
			autofocus_cb.onAutoFocus(false);
			autofocus_cb = null;
			this.capture_follows_autofocus_hint = false;
		}
		afBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE); // ensure set back to idle
	}

	@Override
	public void setCaptureFollowAutofocusHint(boolean capture_follows_autofocus_hint) {
		if( MyDebug.LOG ) {
			Log.d(TAG, "setCaptureFollowAutofocusHint");
			Log.d(TAG, "capture_follows_autofocus_hint? " + capture_follows_autofocus_hint);
		}
		this.capture_follows_autofocus_hint = capture_follows_autofocus_hint;
	}

	@Override
	public void cancelAutoFocus() {
		if( MyDebug.LOG )
			Log.d(TAG, "cancelAutoFocus");
		if( camera == null || captureSession == null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "no camera or capture session");
			return;
		}

		if( is_video_high_speed ) {
			if( MyDebug.LOG )
				Log.d(TAG, "video is high speed");
			return;
		}

		previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
		// Camera2Basic does a capture then sets a repeating request - do the same here just to be safe
		try {
			capture();
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to cancel autofocus [capture]");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
		previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
		this.autofocus_cb = null;
		this.capture_follows_autofocus_hint = false;
		state = STATE_NORMAL;
		precapture_state_change_time_ms = -1;
		try {
			setRepeatingRequest();
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to set repeating request after cancelling autofocus");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
	}

	@Override
	public void setContinuousFocusMoveCallback(ContinuousFocusMoveCallback cb) {
		if( MyDebug.LOG )
			Log.d(TAG, "setContinuousFocusMoveCallback");
		this.continuous_focus_move_callback = cb;
	}

	static public double getScaleForExposureTime(long exposure_time, long fixed_exposure_time, long scaled_exposure_time, double full_exposure_time_scale) {
		if( MyDebug.LOG )
			Log.d(TAG, "getScaleForExposureTime");
		double alpha = (exposure_time - fixed_exposure_time) / (double) (scaled_exposure_time - fixed_exposure_time);
		if( alpha < 0.0 )
			alpha = 0.0;
		else if( alpha > 1.0 )
			alpha = 1.0;
		if( MyDebug.LOG ) {
			Log.d(TAG, "exposure_time: " + exposure_time);
			Log.d(TAG, "alpha: " + alpha);
		}
		// alpha==0 means exposure_time_scale==1; alpha==1 means exposure_time_scale==full_exposure_time_scale
		return (1.0 - alpha) + alpha * full_exposure_time_scale;
	}

	/** Sets up a builder to have manual exposure time, if supported. The exposure time will be
	 *  clamped to the allowed values, and manual ISO will also be set based on the current ISO value.
	 */
	private void setManualExposureTime(CaptureRequest.Builder stillBuilder, long exposure_time) {
		if( MyDebug.LOG )
			Log.d(TAG, "setManualExposureTime: " + exposure_time);
		if( exposure_time_range != null && iso_range != null ) {
			long min_exposure_time = exposure_time_range.getLower();
			long max_exposure_time = exposure_time_range.getUpper();
			if( exposure_time < min_exposure_time )
				exposure_time = min_exposure_time;
			if( exposure_time > max_exposure_time )
				exposure_time = max_exposure_time;
			if (MyDebug.LOG) {
				Log.d(TAG, "exposure_time: " + exposure_time);
			}
			stillBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
			{
				// set ISO
				int iso = 800;
				if (camera_settings.manual_mode)
					iso = camera_settings.iso;
				else if (camera_settings.manual_iso && (!camera_settings.manual_iso_less_or_equal || (capture_result_has_iso && capture_result_iso > camera_settings.manual_iso_value)))
					iso = camera_settings.manual_iso_value;
				else if( capture_result_has_iso )
					iso = capture_result_iso;
				// see https://sourceforge.net/p/opencamera/tickets/321/ - some devices may have auto ISO that's
				// outside of the allowed manual iso range!
				iso = Math.max(iso, iso_range.getLower());
				iso = Math.min(iso, iso_range.getUpper());
				stillBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso );
			}
			if( capture_result_has_frame_duration  )
				stillBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, capture_result_frame_duration);
			else
				stillBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, 1000000000L/30);
			stillBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure_time);
		}
	}

	private void takePictureAfterPrecapture() {
		if( MyDebug.LOG )
			Log.d(TAG, "takePictureAfterPrecapture");
		if( !previewIsVideoMode ) {
			// special burst modes not supported for photo snapshots when recording video
			if( want_expo_bracketing ) {
				takePictureBurstExpoBracketing();
				return;
			}
			else if( want_burst ) {
				takePictureBurst();
				return;
			}
		}
		if( camera == null || captureSession == null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "no camera or capture session");
			return;
		}
		try {
			CaptureRequest.Builder stillBuilder = camera.createCaptureRequest(previewIsVideoMode ? CameraDevice.TEMPLATE_VIDEO_SNAPSHOT : CameraDevice.TEMPLATE_STILL_CAPTURE);
			stillBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE);
			stillBuilder.setTag(RequestTag.CAPTURE);
			camera_settings.setupBuilder(stillBuilder, true);
			is_filtering_blocked = false;
			expected_capture_time = 0;
			if (smart_filter_iso != 0) {
				int actual_iso = getIso();
				if (actual_iso > 0 && actual_iso <= smart_filter_iso) {
					stillBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);
					stillBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
					is_filtering_blocked = true;
				}
			}
			if( fake_flash_mode != FakeFlashMode.None && fake_precapture_torch_performed ) {
				if( MyDebug.LOG )
					Log.d(TAG, "setting torch for capture");
				if( !camera_settings.manual_mode )
					stillBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
				stillBuilder.set(CaptureRequest.FLASH_MODE, fake_flash_mode == FakeFlashMode.Torch ? CameraMetadata.FLASH_MODE_TORCH : CameraMetadata.FLASH_MODE_SINGLE);
				test_fake_flash_photo++;
			}
			if (precapture_result_has_iso_exposure_time) {
				if( MyDebug.LOG )
					Log.d(TAG, "setting capture parameters from precapture result");
				precapture_result_has_iso_exposure_time = false;
				
				stillBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);

				if (camera_settings.manual_iso && (!camera_settings.manual_iso_less_or_equal || (capture_result_has_iso && capture_result_iso > camera_settings.manual_iso_value))) {

					stillBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, camera_settings.manual_iso_value);

					long exposure_time = (long)(precapture_result_exposure_time * ((float)precapture_result_iso / (float)camera_settings.manual_iso_value));

					long min_exposure_time = exposure_time_range.getLower();
					long max_exposure_time = exposure_time_range.getUpper();
					
					if (exposure_time < min_exposure_time) {
						exposure_time = min_exposure_time;
					} else if (exposure_time > max_exposure_time) {
						exposure_time = max_exposure_time;
					}
					stillBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure_time);
					expected_capture_time = exposure_time/1000000L;
				} else {
					stillBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, precapture_result_iso);
					stillBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, precapture_result_exposure_time);
					expected_capture_time = precapture_result_exposure_time/1000000L;
				}

				if (precapture_result_has_frame_duration) {
					precapture_result_has_frame_duration = false;
					stillBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, precapture_result_frame_duration);
				} else {
					stillBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, 1000000000L/30);
				}
			}
			else if(!camera_settings.manual_mode && capture_result_has_exposure_time) {
				if( MyDebug.LOG )
					Log.d(TAG, "setting capture parameters from capture result");
				long exposure_time = 0;

				if (camera_settings.manual_iso && (!camera_settings.manual_iso_less_or_equal || (capture_result_has_iso && capture_result_iso > camera_settings.manual_iso_value))) {
					exposure_time = getActualExposureTime();
				}
				if (this.optimise_ae_for_dro && (camera_settings.flash_value.equals("flash_off") || camera_settings.flash_value.equals("flash_auto") || camera_settings.flash_value.equals("flash_frontscreen_auto") ) ) {
					final double full_exposure_time_scale = Math.pow(2.0, -0.5);
					final long fixed_exposure_time = 1000000000L/60; // we only scale the exposure time at all if it's less than this value
					final long scaled_exposure_time = 1000000000L/120; // we only scale the exposure time by the full_exposure_time_scale if the exposure time is less than this value
					if (exposure_time == 0) exposure_time = capture_result_exposure_time;
					if( exposure_time <= fixed_exposure_time ) {
						double exposure_time_scale = getScaleForExposureTime(exposure_time, fixed_exposure_time, scaled_exposure_time, full_exposure_time_scale);
						exposure_time *= exposure_time_scale;
						if( MyDebug.LOG ) {
							Log.d(TAG, "reduce exposure shutter speed further, was: " + exposure_time);
							Log.d(TAG, "exposure_time_scale: " + exposure_time_scale);
						}
					}
				}
				if (exposure_time > 0) {
					setManualExposureTime(stillBuilder, exposure_time);
					expected_capture_time = exposure_time/1000000L;
				} else {
					expected_capture_time = capture_result_exposure_time/1000000L;
				}
			} else if (camera_settings.manual_mode) {
				if( MyDebug.LOG )
					Log.d(TAG, "setting capture parameters from manual mode settings");
				setManualExposureTime(stillBuilder, camera_settings.exposure_time);
				expected_capture_time = camera_settings.exposure_time/1000000L;
			}
			
			if( MyDebug.LOG )
				Log.d(TAG, "expected_capture_time: " + expected_capture_time);
				
			if (precapture_result_has_white_balance_rggb) {
				precapture_result_has_white_balance_rggb = false;

				stillBuilder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
				stillBuilder.set(CaptureRequest.COLOR_CORRECTION_GAINS, precapture_result_white_balance_rggb);
			}

			if (!previewIsVideoMode && optical_stabilization_if_necessary && expected_capture_time > 1000L/30) {
				if( MyDebug.LOG )
					Log.d(TAG, "optical stabilization enabled for capture session");
				stillBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
			}

			clearPending();
			// shouldn't add preview surface as a target - no known benefit to doing so
			if( imageReader != null )
				stillBuilder.addTarget(imageReader.getSurface());
			if( imageReaderUncompressed != null )
				stillBuilder.addTarget(imageReaderUncompressed.getSurface());
			if( imageReaderRaw != null )
				stillBuilder.addTarget(imageReaderRaw.getSurface());

			n_burst = 1;
			burst_single_request = false;
			if( !previewIsVideoMode ) {
				// need to stop preview before capture (as done in Camera2Basic; otherwise we get bugs such as flash remaining on after taking a photo with flash)
				// but don't do this in video mode - if we're taking photo snapshots while video recording, we don't want to pause video!
				captureSession.stopRepeating();
			}

			capture_start_time = System.currentTimeMillis();

			if( jpeg_cb != null ) {
				if( MyDebug.LOG )
					Log.d(TAG, "call onStarted() in callback");
				jpeg_cb.onStarted();
			}
			if( MyDebug.LOG )
				Log.d(TAG, "capture with stillBuilder");
				
			
			captureSession.capture(stillBuilder.build(), previewCaptureCallback, handler);

			shutter_cb.onShutter();
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to take picture");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
			jpeg_cb = null;
			if( take_picture_error_cb != null ) {
				take_picture_error_cb.onError();
				take_picture_error_cb = null;
			}
		}
	}

	private void takePictureBurstExpoBracketing() {
		if( MyDebug.LOG )
			Log.d(TAG, "takePictureBurstExpBracketing");
		if( MyDebug.LOG && !want_expo_bracketing ) {
			Log.e(TAG, "takePictureBurstExpoBracketing called but want_expo_bracketing is false");
		}
		if( camera == null || captureSession == null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "no camera or capture session");
			return;
		}
		try {
			CaptureRequest.Builder stillBuilder = camera.createCaptureRequest(previewIsVideoMode ? CameraDevice.TEMPLATE_VIDEO_SNAPSHOT : CameraDevice.TEMPLATE_STILL_CAPTURE);
			stillBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE);
			// n.b., don't set RequestTag.CAPTURE here - we only do it for the last of the burst captures (see below)
			
			camera_settings.setupBuilder(stillBuilder, true);
			clearPending();
			// shouldn't add preview surface as a target - see note in takePictureAfterPrecapture()
			// but also, adding the preview surface causes the dark/light exposures to be visible, which we don't want
			if( imageReader != null )
				stillBuilder.addTarget(imageReader.getSurface());
			if( imageReaderUncompressed != null )
				stillBuilder.addTarget(imageReaderUncompressed.getSurface());
			if( imageReaderRaw != null )
				stillBuilder.addTarget(imageReaderRaw.getSurface());

			expected_capture_time = 0;

			List<CaptureRequest> requests = new ArrayList<>();

			is_filtering_blocked = false;
			if (smart_filter_iso != 0) {
				int actual_iso = getIso();
				if (actual_iso > 0 && actual_iso <= smart_filter_iso) {
					stillBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);
					stillBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
					is_filtering_blocked = true;
				}
			}

			if (expo_bracketing_exposure_compensation) {
				stillBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
				stillBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);

				final float exposure_step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP).floatValue();
				final Range<Integer> exposure_range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);

				exposure_compensations = new ArrayList<>();
				
				exposure_compensations.add(camera_settings.ae_exposure_compensation);

				for (double ev : expo_bracketing_stack) {
					if (ev != 0.0d) {
						int steps = (int)(ev/exposure_step+(ev > 0 ? 0.5d : -0.5d));
						int value = camera_settings.ae_exposure_compensation+steps;
						exposure_compensations.add(Math.max(Math.min(value, exposure_range.getUpper()), exposure_range.getLower()));
					}
				}
			} else {
				stillBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
				if( fake_flash_mode != FakeFlashMode.None && fake_precapture_torch_performed ) {
					if( MyDebug.LOG )
						Log.d(TAG, "setting torch for capture");
					stillBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
					test_fake_flash_photo++;
				}
				// else don't turn torch off, as user may be in torch on mode

				int iso = -1;
				if( iso_range == null ) {
					Log.e(TAG, "takePictureBurstExpoBracketing called but null iso_range");
				}
				else {
					iso = 800;
					// set ISO
					// obtain current ISO/etc settings from the capture result - but if we're in manual ISO mode,
					// might as well use the settings the user has actually requested (also useful for workaround for
					// OnePlus 3T bug where the reported ISO and exposure_time are wrong in dark scenes)
					if (camera_settings.manual_mode)
						iso = camera_settings.iso;
					else if (camera_settings.manual_iso && (!camera_settings.manual_iso_less_or_equal || (capture_result_has_iso && capture_result_iso > camera_settings.manual_iso_value)))
						iso = camera_settings.manual_iso_value;
					else if (capture_result_has_iso)
						iso = capture_result_iso;
				}
				if( capture_result_has_frame_duration  )
					stillBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, capture_result_frame_duration);
				else
					stillBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, 1000000000L/30);

				long base_exposure_time = 1000000000L/30;
				if (camera_settings.manual_mode)
					base_exposure_time = camera_settings.exposure_time;
				else if (camera_settings.manual_iso && (!camera_settings.manual_iso_less_or_equal || (capture_result_has_iso && capture_result_iso > camera_settings.manual_iso_value)))
					base_exposure_time = getActualExposureTime();
				else if (capture_result_has_exposure_time)
					base_exposure_time = capture_result_exposure_time;

				long min_exposure_time = base_exposure_time;
				long max_exposure_time = base_exposure_time;
				if( exposure_time_range != null ) {
					min_exposure_time = exposure_time_range.getLower();
					max_exposure_time = exposure_time_range.getUpper();
				}
				
				if (iso > 0) {
					// see https://sourceforge.net/p/opencamera/tickets/321/ - some devices may have auto ISO that's
					// outside of the allowed manual iso range!
					if (iso < iso_range.getLower()) {
						base_exposure_time = Math.max((long)((double)base_exposure_time*((double)iso/iso_range.getLower())), min_exposure_time);
						iso = iso_range.getLower();
					} else if (iso > iso_range.getUpper()) {
						base_exposure_time = Math.min((long)((double)base_exposure_time*((double)iso/iso_range.getUpper())), max_exposure_time);
						iso = iso_range.getUpper();
					}
					
					stillBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
				}

				if( MyDebug.LOG ) {
					Log.d(TAG, "taking expo bracketing with n_images: " + want_burst_count);
					Log.d(TAG, "ISO: " + stillBuilder.get(CaptureRequest.SENSOR_SENSITIVITY));
					Log.d(TAG, "Frame duration: " + stillBuilder.get(CaptureRequest.SENSOR_FRAME_DURATION));
					Log.d(TAG, "Base exposure time: " + base_exposure_time);
					Log.d(TAG, "Min exposure time: " + min_exposure_time);
					Log.d(TAG, "Max exposure time: " + max_exposure_time);
				}

				if (!previewIsVideoMode && optical_stabilization_if_necessary && base_exposure_time > 1000000000L/30) {
					if( MyDebug.LOG )
						Log.d(TAG, "optical stabilization enabled for capture session");
					stillBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
				}

				for (int i = 0; i < expo_bracketing_stack.size(); i++) {
					double ev = expo_bracketing_stack.get(i);
					long exposure_time = base_exposure_time;
					int current_iso = iso;
					double scale = 1;
					if (ev < 0.0d) {
						// darker images
						scale = Math.pow(2.0, -ev);
						if( exposure_time_range != null ) {
							if (use_iso_for_expo_bracketing && iso > 0) {
								current_iso /= scale;
								if (current_iso < iso_range.getLower()) {
									current_iso = iso_range.getLower();
									if (current_iso == iso) exposure_time /= scale;
									else exposure_time /= scale/((double)iso/(double)current_iso);
								}
							}
							else {
								exposure_time /= scale;
							}

							if( exposure_time < min_exposure_time )
								exposure_time = min_exposure_time;
						}
					} else if (ev > 0.0d) {
						// lighter images
						scale = Math.pow(2.0, ev);
						if( exposure_time_range != null ) {
							exposure_time *= scale;
							if( exposure_time > max_exposure_time ) {
								exposure_time = max_exposure_time;
							}
						}
					}
					if( MyDebug.LOG ) {
						Log.d(TAG, "add burst request for " + (i+1) + "th image:");
						Log.d(TAG, "	scale: " + scale);
						Log.d(TAG, "	exposure_time: " + exposure_time);
						if (use_iso_for_expo_bracketing && iso > 0) Log.d(TAG, "	current_iso: " + current_iso);
					}
					stillBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure_time);
					if (use_iso_for_expo_bracketing && iso > 0) stillBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, current_iso);
					if (i == expo_bracketing_stack.size()-1) {
						// RequestTag.CAPTURE should only be set for the last request, otherwise we'll may do things like turning
						// off torch (for fake flash) before all images are received
						// More generally, doesn't seem a good idea to be doing the post-capture commands (resetting ae state etc)
						// multiple times, and before all captures are complete!
						if( MyDebug.LOG )
							Log.d(TAG, "set RequestTag.CAPTURE for last burst request");
						stillBuilder.setTag(RequestTag.CAPTURE);
					} else {
						stillBuilder.setTag(RequestTag.CAPTURE_BURST_IN_PROGRESS);
						expected_capture_time += 10L;
					}
					requests.add(stillBuilder.build());

					expected_capture_time += exposure_time/1000000L;
				}
			}

			if (expo_bracketing_exposure_compensation) {
				n_burst = exposure_compensations.size();
			} else {
				n_burst = requests.size();
			}
			burst_single_request = true;
			if( MyDebug.LOG )
				Log.d(TAG, "n_burst: " + n_burst);

			if( !previewIsVideoMode /*&& !camera_settings.flash_value.equals("flash_off")*/ ) {
				captureSession.stopRepeating(); // see note under takePictureAfterPrecapture()
			}

			capture_start_time = System.currentTimeMillis();

			if( jpeg_cb != null ) {
				if( MyDebug.LOG )
					Log.d(TAG, "call onStarted() in callback");
				jpeg_cb.onStarted();
			}

			if (expo_bracketing_exposure_compensation) {
				if( MyDebug.LOG )
					Log.d(TAG, "using exposure compensation");
				captureSession.capture(stillBuilder.build(), previewCaptureCallback, handler);
			} else if( use_fast_burst ) {
				if( MyDebug.LOG )
					Log.d(TAG, "using fast burst");
				int sequenceId = captureSession.captureBurst(requests, previewCaptureCallback, handler);
				if( MyDebug.LOG )
					Log.d(TAG, "sequenceId: " + sequenceId);
			} else {
				if( MyDebug.LOG )
					Log.d(TAG, "using slow burst");
				slow_burst_capture_requests = requests;
				captureSession.capture(requests.get(0), previewCaptureCallback, handler);
			}

			shutter_cb.onShutter();
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to take picture expo burst");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
			jpeg_cb = null;
			if( take_picture_error_cb != null ) {
				take_picture_error_cb.onError();
				take_picture_error_cb = null;
			}
		}
	}

	private void takePictureBurst() {
		if( MyDebug.LOG )
			Log.d(TAG, "takePictureBurst");
		if( MyDebug.LOG && !want_burst ) {
			Log.e(TAG, "takePictureBurst called but want_burst is false");
		}
		if( camera == null || captureSession == null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "no camera or capture session");
			return;
		}
		try {
			if( MyDebug.LOG ) {
				if (imageReader != null) {
					Log.d(TAG, "imageReader: " + imageReader.toString());
					Log.d(TAG, "imageReader surface: " + imageReader.getSurface().toString());
				}
			}

			expected_capture_time = 0;

			CaptureRequest.Builder stillBuilder = camera.createCaptureRequest(previewIsVideoMode ? CameraDevice.TEMPLATE_VIDEO_SNAPSHOT : CameraDevice.TEMPLATE_STILL_CAPTURE);
			stillBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE);
			// n.b., don't set RequestTag.CAPTURE here - we only do it for the last of the burst captures (see below)
			camera_settings.setupBuilder(stillBuilder, true);
			if( fake_flash_mode != FakeFlashMode.None && fake_precapture_torch_performed ) {
				if( MyDebug.LOG )
					Log.d(TAG, "setting torch for capture");
				if( !camera_settings.manual_mode )
					stillBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
				stillBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
				test_fake_flash_photo++;
			}

			stillBuilder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF);

			clearPending();
			// shouldn't add preview surface as a target - see note in takePictureAfterPrecapture()
			if( imageReader != null )
				stillBuilder.addTarget(imageReader.getSurface());
			if( imageReaderUncompressed != null )
				stillBuilder.addTarget(imageReaderUncompressed.getSurface());
			if( imageReaderRaw != null )
				stillBuilder.addTarget(imageReaderRaw.getSurface());

			if( fake_flash_mode != FakeFlashMode.None && fake_precapture_torch_performed ) {
				stillBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
				test_fake_flash_photo++;
			}
			// else don't turn torch off, as user may be in torch on mode

			n_burst = 3;
			burst_single_request = false;
			if (want_burst_count != 0)
				n_burst = want_burst_count;
			if( MyDebug.LOG )
				Log.d(TAG, "n_burst: " + n_burst);

			int iso = 0;
			long exposure_time = 0L;
			if(camera_settings.manual_mode) {
				iso = camera_settings.iso;
				exposure_time = camera_settings.exposure_time;
			} else if (camera_settings.manual_iso && (!camera_settings.manual_iso_less_or_equal || (capture_result_has_iso && capture_result_iso > camera_settings.manual_iso_value))) {
				iso = camera_settings.manual_iso_value;
				exposure_time = getActualExposureTime();
			}

			if (exposure_time != 0)
				setManualExposureTime(stillBuilder, exposure_time);

			expected_capture_time = getExposureTime();
			if (expected_capture_time > 0) {
				expected_capture_time = expected_capture_time/1000000L*n_burst+(use_fast_burst ? 10L : burst_delay)*(n_burst-1);
			}

			if (!previewIsVideoMode && optical_stabilization_if_necessary && expected_capture_time > 1000L/30) {
				if( MyDebug.LOG )
					Log.d(TAG, "optical stabilization enabled for capture session");
				stillBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
			}

			is_filtering_blocked = false;
			if (burst_disable_filters || (smart_filter_iso != 0 && iso > 0 && iso <= smart_filter_iso)) {
				stillBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);
				stillBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
				is_filtering_blocked = true;
			}

			stillBuilder.setTag(RequestTag.CAPTURE_BURST_IN_PROGRESS);
			final CaptureRequest request = stillBuilder.build();
			stillBuilder.setTag(RequestTag.CAPTURE);
			final CaptureRequest last_request = stillBuilder.build();

			if( !previewIsVideoMode ) {
				captureSession.stopRepeating(); // see note under takePictureAfterPrecapture()
			}

			capture_start_time = System.currentTimeMillis();

			if( jpeg_cb != null ) {
				if( MyDebug.LOG )
					Log.d(TAG, "call onStarted() in callback");
				jpeg_cb.onStarted();
			}

			if( use_fast_burst ) {
				List<CaptureRequest> requests = new ArrayList<>();
				for(int i=0;i<n_burst-1;i++)
					requests.add(request);
				requests.add(last_request);
				if( MyDebug.LOG )
					Log.d(TAG, "captureBurst");
				int sequenceId = captureSession.captureBurst(requests, previewCaptureCallback, handler);
				if( MyDebug.LOG )
					Log.d(TAG, "sequenceId: " + sequenceId);
			}
			else {
				final int delay = burst_delay;
				new Runnable() {
					int n_remaining = n_burst;

					@Override
					public void run() {
						if( MyDebug.LOG ) {
							Log.d(TAG, "takePictureBurst runnable");
							if( n_remaining == 1 ) {
								Log.d(TAG, "	is last request");
							}
						}
						try {
							captureSession.capture(n_remaining == 1 ? last_request : request, previewCaptureCallback, handler);
							n_remaining--;
							if( MyDebug.LOG )
								Log.d(TAG, "takePictureBurst n_remaining: " + n_remaining);
							if( n_remaining > 0 ) {
								handler.postDelayed(this, delay);
							}
						}
						catch(CameraAccessException e) {
							if( MyDebug.LOG ) {
								Log.e(TAG, "failed to take picture burst");
								Log.e(TAG, "reason: " + e.getReason());
								Log.e(TAG, "message: " + e.getMessage());
							}
							e.printStackTrace();
							jpeg_cb = null;
							if( take_picture_error_cb != null ) {
								take_picture_error_cb.onError();
								take_picture_error_cb = null;
							}
						}
					}
				}.run();
			}

			shutter_cb.onShutter();
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to take picture burst");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
			jpeg_cb = null;
			if( take_picture_error_cb != null ) {
				take_picture_error_cb.onError();
				take_picture_error_cb = null;
			}
		}
	}

	private void runPrecapture() {
		if( MyDebug.LOG )
			Log.d(TAG, "runPrecapture");
		// first run precapture sequence
		if( MyDebug.LOG ) {
			if( fake_flash_mode != FakeFlashMode.None )
				Log.e(TAG, "shouldn't be doing standard precapture when using fake flash mode");
			else if( want_expo_bracketing || want_burst )
				Log.e(TAG, "shouldn't be doing precapture for want_expo_bracketing/want_burst - should be using fake precapture!");
		}
		try {
			// use a separate builder for precapture - otherwise have problem that if we take photo with flash auto/on of dark scene, then point to a bright scene, the autoexposure isn't running until we autofocus again
			final CaptureRequest.Builder precaptureBuilder = camera.createCaptureRequest(previewIsVideoMode ? CameraDevice.TEMPLATE_VIDEO_SNAPSHOT : CameraDevice.TEMPLATE_STILL_CAPTURE);
			precaptureBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE);

			camera_settings.setupBuilder(precaptureBuilder, false);
			precaptureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
			precaptureBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);

			precaptureBuilder.addTarget(getPreviewSurface());

			state = STATE_WAITING_PRECAPTURE_START;
			precapture_state_change_time_ms = System.currentTimeMillis();

			// first set precapture to idle - this is needed, otherwise we hang in state STATE_WAITING_PRECAPTURE_START, because precapture already occurred whilst autofocusing, and it doesn't occur again unless we first set the precapture trigger to idle
			if( MyDebug.LOG )
				Log.d(TAG, "capture with precaptureBuilder");
			captureSession.capture(precaptureBuilder.build(), previewCaptureCallback, handler);
			captureSession.setRepeatingRequest(precaptureBuilder.build(), previewCaptureCallback, handler);

			// now set precapture
			precaptureBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
			captureSession.capture(precaptureBuilder.build(), previewCaptureCallback, handler);
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to precapture");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
			jpeg_cb = null;
			if( take_picture_error_cb != null ) {
				take_picture_error_cb.onError();
				take_picture_error_cb = null;
			}
		}
	}

	private void runFakePrecapture() {
		if( MyDebug.LOG )
			Log.d(TAG, "runFakePrecapture");
		switch(camera_settings.flash_value) {
			case "flash_auto":
			case "flash_on":
				if(MyDebug.LOG)
					Log.d(TAG, "turn on torch");
				previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
				previewBuilder.set(CaptureRequest.FLASH_MODE, fake_flash_mode == FakeFlashMode.Torch ? CameraMetadata.FLASH_MODE_TORCH : CameraMetadata.FLASH_MODE_SINGLE);
				test_fake_flash_precapture++;
				fake_precapture_torch_performed = true;
				break;
			case "flash_frontscreen_auto":
			case "flash_frontscreen_on":
				if(jpeg_cb != null) {
					if(MyDebug.LOG)
						Log.d(TAG, "request screen turn on for frontscreen flash");
					jpeg_cb.onFrontScreenTurnOn();
				}
				else {
					if (MyDebug.LOG)
						Log.e(TAG, "can't request screen turn on for frontscreen flash, as no jpeg_cb");
				}
				break;
			default:
				if(MyDebug.LOG)
					Log.e(TAG, "runFakePrecapture called with unexpected flash value: " + camera_settings.flash_value);
				break;
		}
		state = STATE_WAITING_FAKE_PRECAPTURE_START;
		precapture_state_change_time_ms = System.currentTimeMillis();
		fake_precapture_turn_on_torch_id = null;
		try {
			CaptureRequest request = previewBuilder.build();
			if( fake_precapture_torch_performed ) {
				fake_precapture_turn_on_torch_id = request;
				if( MyDebug.LOG )
					Log.d(TAG, "fake_precapture_turn_on_torch_id: " + request);
			}
			setRepeatingRequest(request);
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to start fake precapture");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
			jpeg_cb = null;
			if( take_picture_error_cb != null ) {
				take_picture_error_cb.onError();
				take_picture_error_cb = null;
			}
		}
	}

	/** Used in fake precapture mode when flash is auto, this returns whether we fire the flash.
	 *  If the decision was recently calculated, we return that same decision - used to fix problem that if
	 *  we fire flash during autofocus (for autofocus mode), we don't then want to decide the scene is too
	 *  bright to not need flash for taking photo!
	 */
	private boolean fireAutoFlash() {
		if( MyDebug.LOG )
			Log.d(TAG, "fireAutoFlash");
		long time_now = System.currentTimeMillis();
		if( MyDebug.LOG && fake_precapture_use_flash_time_ms != -1 ) {
			Log.d(TAG, "fake_precapture_use_flash_time_ms: " + fake_precapture_use_flash_time_ms);
			Log.d(TAG, "time_now: " + time_now);
			Log.d(TAG, "time since last flash auto decision: " + (time_now - fake_precapture_use_flash_time_ms));
		}
		final long cache_time_ms = 3000; // needs to be at least the time of a typical autoflash, see comment for this function above
		if( fake_precapture_use_flash_time_ms != -1 && time_now - fake_precapture_use_flash_time_ms < cache_time_ms ) {
			if( MyDebug.LOG )
				Log.d(TAG, "use recent decision: " + fake_precapture_use_flash);
			fake_precapture_use_flash_time_ms = time_now;
			return fake_precapture_use_flash;
		}
		switch(camera_settings.flash_value) {
			case "flash_auto":
				fake_precapture_use_flash = is_flash_required;
				break;
			case "flash_frontscreen_auto":
				// iso_threshold fine-tuned for Nexus 6 - front camera ISO never goes above 805, but a threshold of 700 is too low
				int iso_threshold = camera_settings.flash_value.equals("flash_frontscreen_auto") ? 750 : 1000;
				fake_precapture_use_flash = capture_result_has_iso && capture_result_iso >= iso_threshold;
				if(MyDebug.LOG)
					Log.d(TAG, "	ISO was: " + capture_result_iso);
				break;
			default:
				// shouldn't really be calling this function if not flash auto...
				fake_precapture_use_flash = false;
				break;
		}
		if( MyDebug.LOG )
			Log.d(TAG, "fake_precapture_use_flash: " + fake_precapture_use_flash);
		// We only cache the result if we decide to turn on torch, as that mucks up our ability to tell if we need the flash (since once the torch
		// is on, the ae_state thinks it's bright enough to not need flash!)
		// But if we don't turn on torch, this problem doesn't occur, so no need to cache - and good that the next time we should make an up-to-date
		// decision.
		if( fake_precapture_use_flash ) {
			fake_precapture_use_flash_time_ms = time_now;
		}
		else {
			fake_precapture_use_flash_time_ms = -1;
		}
		return fake_precapture_use_flash;
	}

	@Override
	public void takePicture(final ShutterCallback shutter, final PictureCallback picture, final ErrorCallback error) {
		if( MyDebug.LOG )
			Log.d(TAG, "takePicture");
		if( camera == null || captureSession == null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "no camera or capture session");
			error.onError();
			return;
		}
		this.shutter_cb = shutter;
		// we store as two identical callbacks, so we can independently set each to null as the two callbacks occur
		this.jpeg_cb = picture;

		this.take_picture_error_cb = error;
		this.fake_precapture_torch_performed = false; // just in case still on?
		if( !ready_for_capture ) {
			if( MyDebug.LOG )
				Log.e(TAG, "takePicture: not ready for capture!");
			//throw new RuntimeException(); // debugging
		}

		{
			if( MyDebug.LOG ) {
				Log.d(TAG, "current flash value: " + camera_settings.flash_value);
				Log.d(TAG, "fake_flash_mode: " + fake_flash_mode);
			}
			// Don't need precapture if flash off or torch
			// And currently has_iso manual mode doesn't support flash - but just in case that's changed later, we still probably don't want to be doing a precapture...
			if( camera_settings.manual_mode || camera_settings.flash_value.equals("flash_off") || camera_settings.flash_value.equals("flash_torch") ) {
				takePictureAfterPrecapture();
			}
			else if( fake_flash_mode != FakeFlashMode.None ) {
				// fake flash auto/on mode
				// fake precapture works by turning on torch (or using a "front screen flash"), so we can't use the camera's own decision for flash auto
				// instead we check the current ISO value
				boolean auto_flash = camera_settings.flash_value.equals("flash_auto") || camera_settings.flash_value.equals("flash_frontscreen_auto");
				Integer flash_mode = previewBuilder.get(CaptureRequest.FLASH_MODE);
				if( MyDebug.LOG )
					Log.d(TAG, "flash_mode: " + flash_mode);
				if( auto_flash && !fireAutoFlash() ) {
					if( MyDebug.LOG )
						Log.d(TAG, "fake precapture flash auto: seems bright enough to not need flash");
					takePictureAfterPrecapture();
				}
				else if( flash_mode != null && flash_mode == CameraMetadata.FLASH_MODE_TORCH && fake_flash_mode == FakeFlashMode.Torch ) {
					if( MyDebug.LOG )
						Log.d(TAG, "fake precapture flash: torch already on (presumably from autofocus)");
					// On some devices (e.g., OnePlus 3T), if we've already turned on torch for an autofocus immediately before
					// taking the photo, ae convergence may have already occurred - so if we called runFakePrecapture(), we'd just get
					// stuck waiting for CONTROL_AE_STATE_SEARCHING which will never happen, until we hit the timeout - it works,
					// but it means taking photos is slower as we have to wait until the timeout
					// Instead we assume that ae scanning has already started, so go straight to STATE_WAITING_FAKE_PRECAPTURE_DONE,
					// which means wait until we're no longer CONTROL_AE_STATE_SEARCHING.
					// (Note, we don't want to go straight to takePictureAfterPrecapture(), as it might be that ae scanning is still
					// taking place.)
					// An alternative solution would be to switch torch off and back on again to cause ae scanning to start - but
					// at worst this is tricky to get working, and at best, taking photos would be slower.
					fake_precapture_torch_performed = true; // so we know to fire the torch when capturing
					test_fake_flash_precapture++; // for testing, should treat this same as if we did do the precapture
					state = STATE_WAITING_FAKE_PRECAPTURE_DONE;
					precapture_state_change_time_ms = System.currentTimeMillis();
				}
				else {
					runFakePrecapture();
				}
			}
			else {
				// standard flash, flash auto or on
				// note that we don't call needsFlash() (or use is_flash_required) - as if ae state is neither CONVERGED nor FLASH_REQUIRED, we err on the side
				// of caution and don't skip the precapture
				//boolean needs_flash = capture_result_ae != null && capture_result_ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED;
				boolean needs_flash = capture_result_ae != null && capture_result_ae != CaptureResult.CONTROL_AE_STATE_CONVERGED;
				if( camera_settings.flash_value.equals("flash_auto") && !needs_flash ) {
					// if we call precapture anyway, flash wouldn't fire - but we tend to have a pause
					// so skipping the precapture if flash isn't going to fire makes this faster
					if( MyDebug.LOG )
						Log.d(TAG, "flash auto, but we don't need flash");
					takePictureAfterPrecapture();
				}
				else {
					runPrecapture();
				}
			}
		}

		/*camera_settings.setupBuilder(previewBuilder, false);
		previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
		state = STATE_WAITING_AUTOFOCUS;
		precapture_started = -1;
		//capture();
		setRepeatingRequest();*/
	}

	@Override
	public void cancelCapture() {
		if( MyDebug.LOG )
			Log.d(TAG, "cancelCapture");
		if( captureSession != null ) {
			try {
				captureSession.abortCaptures();
			}
			catch(CameraAccessException e) {
				if( MyDebug.LOG ) {
					Log.e(TAG, "failed to cancel capture");
					Log.e(TAG, "reason: " + e.getReason());
					Log.e(TAG, "message: " + e.getMessage());
				}
				e.printStackTrace();
			}
		}
		if (state == STATE_WAITING_AUTOFOCUS && autofocus_cb != null) {
			autofocus_cb.onAutoFocus(false);
			autofocus_cb = null;
			
		}
		autofocus_attempt = 0;
		state = STATE_NORMAL;
	}

	@Override
	public void setDisplayOrientation(int degrees) {
		// for CameraController2, the preview display orientation is handled via the TextureView's transform
		if( MyDebug.LOG )
			Log.d(TAG, "setDisplayOrientation not supported by this API");
		throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
	}

	@Override
	public int getDisplayOrientation() {
		if( MyDebug.LOG )
			Log.d(TAG, "getDisplayOrientation not supported by this API");
		throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
	}

	@Override
	public int getCameraOrientation() {
		return characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
	}

	@Override
	public boolean isFrontFacing() {
		return characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT;
	}

	@Override
	public void unlock() {
		// do nothing at this stage
	}

	@Override
	public void initVideoRecorderPrePrepare(MediaRecorder video_recorder) {
	}

	@Override
	public void initVideoRecorderPostPrepare(MediaRecorder video_recorder, boolean want_photo_video_recording) throws CameraControllerException {
		if( MyDebug.LOG )
			Log.d(TAG, "initVideoRecorderPostPrepare");
		if( camera == null ) {
			Log.e(TAG, "no camera");
			throw new CameraControllerException();
		}
		try {
			if( MyDebug.LOG )
				Log.d(TAG, "obtain video_recorder surface");
			previewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
			if( MyDebug.LOG )
				Log.d(TAG, "done");
			previewIsVideoMode = true;
			previewBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD);
			camera_settings.setupBuilder(previewBuilder, false);
			createCaptureSession(video_recorder, want_photo_video_recording);
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to create capture request for video");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
			throw new CameraControllerException();
		}
	}

	@Override
	public void reconnect() throws CameraControllerException {
		if( MyDebug.LOG )
			Log.d(TAG, "reconnect");
		createPreviewRequest();
		createCaptureSession(null, false);
		/*if( MyDebug.LOG )
			Log.d(TAG, "add preview surface to previewBuilder");
		Surface surface = getPreviewSurface();
		previewBuilder.addTarget(surface);*/
		//setRepeatingRequest();
	}

	@Override
	public String getParametersString() {
		return null;
	}

	@Override
	public boolean captureResultIsAEScanning() {
		return capture_result_is_ae_scanning;
	}

	@Override
	public boolean needsFlash() {
		//boolean needs_flash = capture_result_ae != null && capture_result_ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED;
		//return needs_flash;
		return is_flash_required;
	}

	@Override
	public boolean canReportNeedsFlash() {
		return characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) &&
			characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) != CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY;
	}

	@Override
	public int getActualWhiteBalanceTemperature() {
		if (camera_settings.white_balance == CameraMetadata.CONTROL_AWB_MODE_OFF) {
			return camera_settings.white_balance_temperature;
		} else if (capture_result_has_white_balance_rggb) {
			if (capture_result_white_balance == -1) {
				ColorTemperature.RGBColor rgb = new ColorTemperature.RGBColor(
					1 / capture_result_white_balance_rggb.getRed(),
					1 / (capture_result_white_balance_rggb.getGreenEven()/2 + capture_result_white_balance_rggb.getGreenOdd()/2),
					1 / capture_result_white_balance_rggb.getBlue()
				);

				if (camera_settings.white_balance_calibration != null) {
					rgb.r *= camera_settings.white_balance_calibration[0];
					rgb.g *= camera_settings.white_balance_calibration[1];
					rgb.b *= camera_settings.white_balance_calibration[2];
				}

				capture_result_white_balance_xyz = rgb.toXYZ(sensor_color_transform_inverse);
				capture_result_white_balance = capture_result_white_balance_xyz.getTemperature();
			}
			
			return capture_result_white_balance;
		}

		return -1;
	}

	@Override
	public ColorTemperature.CIECoordinates getActualWhiteBalanceXY() {
		ColorTemperature.CIEColor xyz = null;

		if (camera_settings.white_balance == CameraMetadata.CONTROL_AWB_MODE_OFF) {
			if (camera_settings.white_balance_xyz == null)
				camera_settings.white_balance_xyz = new ColorTemperature.CIEColor(camera_settings.white_balance_temperature);
			xyz = camera_settings.white_balance_xyz;
		} else {
			xyz = capture_result_white_balance_xyz;
		}

		if (xyz != null) {
			return xyz.toXY();
		}

		return null;
	}

	@Override
	public boolean captureResultIsAWBScanning() {
		return capture_result_is_awb_scanning;
	}

	@Override
	public boolean captureResultHasIso() {
		return capture_result_has_iso;
	}

	@Override
	public int captureResultIso() {
		return capture_result_iso;
	}

	@Override
	public boolean captureResultHasExposureTime() {
		return capture_result_has_exposure_time;
	}

	@Override
	public long captureResultExposureTime() {
		return capture_result_exposure_time;
	}

	@Override
	public int getIso() {
		if (camera_settings.manual_mode) {
			return camera_settings.iso;
		} else if (camera_settings.manual_iso && capture_result_has_iso && capture_result_has_exposure_time
				&& (!camera_settings.manual_iso_less_or_equal || (capture_result_iso > camera_settings.manual_iso_value))) {
			return camera_settings.manual_iso_value;
		} else if (capture_result_has_iso) {
			return capture_result_iso;
		} else return -1;
	}

	@Override
	public long getExposureTime() {
		if (camera_settings.manual_mode) {
			return camera_settings.exposure_time;
		} else if (camera_settings.manual_iso && capture_result_has_iso && capture_result_has_exposure_time
				&& (!camera_settings.manual_iso_less_or_equal || (capture_result_iso > camera_settings.manual_iso_value))) {
			return getActualExposureTime();
		} else if (capture_result_has_exposure_time) {
			return capture_result_exposure_time;
		} else return -1;
	}

	// Used for progress indication
	@Override
	public long getExpectedCaptureTime() {
		// In milliseconds
		return expected_capture_time;
	}

	@Override
	public long getCaptureStartTime() {
		return capture_start_time;
	}

	@Override
	public long getApproximatelyTotalExposureTime() {
		long exposure_time = getExposureTime()/1000000L;
		if (exposure_time <= 0)
			exposure_time = 100L;
		if (want_expo_bracketing) {
			exposure_time *= want_burst_count;
			if (expo_bracketing_exposure_compensation)
				exposure_time += exposure_compensation_delay*(want_burst_count-1);
		} else if (want_burst) {
			exposure_time *= want_burst_count;
		}
		return exposure_time;
	}

	private long getActualExposureTime() {
		long result = (long)(capture_result_exposure_time * ((float)capture_result_iso / (float)camera_settings.manual_iso_value));

		long min_exposure_time = exposure_time_range.getLower();
		long max_exposure_time = exposure_time_range.getUpper();
		
		exposure_over_range = false;
		if (result < min_exposure_time) {
			result = min_exposure_time;
			exposure_over_range = true;
		} else if (result > max_exposure_time) {
			result = max_exposure_time;
			exposure_over_range = true;
		}
		return result;
	}

	@Override
	public boolean isExposureOverRange() {
		return exposure_over_range;
	}

	@Override
	public void setSmartFilterISO(int iso) {
		smart_filter_iso = iso;
	}

	@Override
	public boolean isFilteringBlocked() {
		return is_filtering_blocked;
	}

	@Override
	public void setFilteringCaptureOnly(boolean value) {
		filtering_capture_only = value;
	}

	/*
	@Override
	public boolean captureResultHasFrameDuration() {
		return capture_result_has_frame_duration;
	}

	@Override
	public long captureResultFrameDuration() {
		return capture_result_frame_duration;
	}
	*/

	@Override
	public boolean captureResultIsAFScanning() {
		return capture_result_is_af_scanning;
	}

	@Override
	public boolean hasFocusDistance() {
		return camera_settings.af_mode == CaptureRequest.CONTROL_AF_MODE_OFF || capture_result_has_focus_distance;
	}

	@Override
	public float getFocusDistance() {
		return camera_settings.af_mode == CaptureRequest.CONTROL_AF_MODE_OFF ? camera_settings.focus_distance : capture_result_focus_distance;
	}

	@Override
	public boolean captureResultHasFocusRange() {
		return capture_result_has_focus_range;
	}

	@Override
	public float captureResultFocusDistanceMin() {
		return capture_result_focus_distance_min;
	}

	@Override
	public float captureResultFocusDistanceMax() {
		return capture_result_focus_distance_max;
	}

	private final CameraCaptureSession.CaptureCallback previewCaptureCallback = new CameraCaptureSession.CaptureCallback() {
		private long last_process_frame_number = 0;
		private int last_af_state = -1;

		@Override
		public void onCaptureBufferLost(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull Surface target, long frameNumber) {
			if( MyDebug.LOG )
				Log.d(TAG, "onCaptureBufferLost: " + frameNumber);
			super.onCaptureBufferLost(session, request, target, frameNumber);
		}

		@Override
		public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "onCaptureFailed: " + failure);
				Log.d(TAG, "reason: " + failure.getReason());
				Log.d(TAG, "was image captured?: " + failure.wasImageCaptured());
				Log.d(TAG, "sequenceId: " + failure.getSequenceId());
			}
			super.onCaptureFailed(session, request, failure); // API docs say this does nothing, but call it just to be safe
		}

		@Override
		public void onCaptureSequenceAborted(@NonNull CameraCaptureSession session, int sequenceId) {
			if( MyDebug.LOG ) {
				Log.d(TAG, "onCaptureSequenceAborted");
				Log.d(TAG, "sequenceId: " + sequenceId);
			}
			super.onCaptureSequenceAborted(session, sequenceId); // API docs say this does nothing, but call it just to be safe
		}

		@Override
		public void onCaptureSequenceCompleted(@NonNull CameraCaptureSession session, int sequenceId, long frameNumber) {
			if( MyDebug.LOG ) {
				Log.d(TAG, "onCaptureSequenceCompleted");
				Log.d(TAG, "sequenceId: " + sequenceId);
				Log.d(TAG, "frameNumber: " + frameNumber);
			}
			super.onCaptureSequenceCompleted(session, sequenceId, frameNumber); // API docs say this does nothing, but call it just to be safe
		}

		@Override
		public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
			if( request.getTag() == RequestTag.CAPTURE ) {
				if( MyDebug.LOG ) {
					Log.d(TAG, "onCaptureStarted: capture");
					Log.d(TAG, "frameNumber: " + frameNumber);
					Log.d(TAG, "exposure time: " + request.get(CaptureRequest.SENSOR_EXPOSURE_TIME));
				}
				// n.b., we don't play the shutter sound here, as it typically sounds "too late"
				// (if ever we changed this, would also need to fix for burst, where we only set the RequestTag.CAPTURE for the last image)
			}
			/*else {
				if( MyDebug.LOG ) {
					Log.d(TAG, "onCaptureStarted:");
					Log.d(TAG, "frameNumber: " + frameNumber);
					Log.d(TAG, "exposure time: " + request.get(CaptureRequest.SENSOR_EXPOSURE_TIME));
				}
			}*/
			super.onCaptureStarted(session, request, timestamp, frameNumber);
		}

		@Override
		public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
			/*if( MyDebug.LOG )
				Log.d(TAG, "onCaptureProgressed");*/
			//process(request, partialResult);
			// Note that we shouldn't try to process partial results - or if in future we decide to, remember that it's documented that
			// not all results may be available. E.g., OnePlus 3T on Android 7 (OxygenOS 4.0.2) reports null for AF_STATE from this method.
			// We'd also need to fix up the discarding of old frames in process(), as we probably don't want to be discarding the
			// complete results from onCaptureCompleted()!
			super.onCaptureProgressed(session, request, partialResult); // API docs say this does nothing, but call it just to be safe (as with Google Camera)
		}

		@Override
		public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
			/*if( MyDebug.LOG )
				Log.d(TAG, "onCaptureCompleted");*/
			if( request.getTag() != RequestTag.PREVIEW ) {
				if( MyDebug.LOG ) {
					if (request.getTag() != null)
						Log.d(TAG, "onCaptureCompleted: " + request.getTag().toString());
					Log.d(TAG, "sequenceId: " + result.getSequenceId());
					Log.d(TAG, "frameNumber: " + result.getFrameNumber());
					Log.d(TAG, "exposure time: " + request.get(CaptureRequest.SENSOR_EXPOSURE_TIME));
					Log.d(TAG, "frame duration: " + request.get(CaptureRequest.SENSOR_FRAME_DURATION));
				}
				//return;
			}
			/*else {
				if( MyDebug.LOG ) {
					Log.d(TAG, "onCaptureCompleted:");
					Log.d(TAG, "sequenceId: " + result.getSequenceId());
					Log.d(TAG, "frameNumber: " + result.getFrameNumber());
					Log.d(TAG, "exposure time: " + request.get(CaptureRequest.SENSOR_EXPOSURE_TIME));
					Log.d(TAG, "frame duration: " + request.get(CaptureRequest.SENSOR_FRAME_DURATION));
				}
			}*/
			process(request, result);
			processCompleted(request, result);
			super.onCaptureCompleted(session, request, result); // API docs say this does nothing, but call it just to be safe (as with Google Camera)
		}

		/** Processes either a partial or total result.
		 */
		private void process(CaptureRequest request, CaptureResult result) {
			/*if( MyDebug.LOG )
			Log.d(TAG, "process, state: " + state);*/
			if( result.getFrameNumber() < last_process_frame_number ) {
				/*if( MyDebug.LOG )
					Log.d(TAG, "processAF discarded outdated frame " + result.getFrameNumber() + " vs " + last_process_frame_number);*/
				return;
			}
			last_process_frame_number = result.getFrameNumber();

			/*Integer flash_mode = result.get(CaptureResult.FLASH_MODE);
			if( MyDebug.LOG ) {
				if( flash_mode == null )
					Log.d(TAG, "FLASH_MODE is null");
				else if( flash_mode == CaptureResult.FLASH_MODE_OFF )
					Log.d(TAG, "FLASH_MODE = FLASH_MODE_OFF");
				else if( flash_mode == CaptureResult.FLASH_MODE_SINGLE )
					Log.d(TAG, "FLASH_MODE = FLASH_MODE_SINGLE");
				else if( flash_mode == CaptureResult.FLASH_MODE_TORCH )
					Log.d(TAG, "FLASH_MODE = FLASH_MODE_TORCH");
				else
					Log.d(TAG, "FLASH_MODE = " + flash_mode);
			}*/

			// use Integer instead of int, so can compare to null: Google Play crashes confirmed that this can happen; Google Camera also ignores cases with null af state
			Integer af_state = result.get(CaptureResult.CONTROL_AF_STATE);
			/*if( MyDebug.LOG ) {
				if( af_state == null )
					Log.d(TAG, "CONTROL_AF_STATE is null");
				else if( af_state == CaptureResult.CONTROL_AF_STATE_INACTIVE )
					Log.d(TAG, "CONTROL_AF_STATE = CONTROL_AF_STATE_INACTIVE");
				else if( af_state == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN )
					Log.d(TAG, "CONTROL_AF_STATE = CONTROL_AF_STATE_PASSIVE_SCAN");
				else if( af_state == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED )
					Log.d(TAG, "CONTROL_AF_STATE = CONTROL_AF_STATE_PASSIVE_FOCUSED");
				else if( af_state == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN )
					Log.d(TAG, "CONTROL_AF_STATE = CONTROL_AF_STATE_ACTIVE_SCAN");
				else if( af_state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED )
					Log.d(TAG, "CONTROL_AF_STATE = CONTROL_AF_STATE_FOCUSED_LOCKED");
				else if( af_state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED )
					Log.d(TAG, "CONTROL_AF_STATE = CONTROL_AF_STATE_NOT_FOCUSED_LOCKED");
				else if( af_state == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED )
					Log.d(TAG, "CONTROL_AF_STATE = CONTROL_AF_STATE_PASSIVE_UNFOCUSED");
				else
					Log.d(TAG, "CONTROL_AF_STATE = " + af_state);
			}*/

			// CONTROL_AE_STATE can be null on some devices, so as with af_state, use Integer
			Integer ae_state = result.get(CaptureResult.CONTROL_AE_STATE);
			/*if( MyDebug.LOG ) {
				if( ae_state == null )
					Log.d(TAG, "CONTROL_AE_STATE is null");
				else if( ae_state == CaptureResult.CONTROL_AE_STATE_INACTIVE )
					Log.d(TAG, "CONTROL_AE_STATE = CONTROL_AE_STATE_INACTIVE");
				else if( ae_state == CaptureResult.CONTROL_AE_STATE_SEARCHING )
					Log.d(TAG, "CONTROL_AE_STATE = CONTROL_AE_STATE_SEARCHING");
				else if( ae_state == CaptureResult.CONTROL_AE_STATE_CONVERGED )
					Log.d(TAG, "CONTROL_AE_STATE = CONTROL_AE_STATE_CONVERGED");
				else if( ae_state == CaptureResult.CONTROL_AE_STATE_LOCKED )
					Log.d(TAG, "CONTROL_AE_STATE = CONTROL_AE_STATE_LOCKED");
				else if( ae_state == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED )
					Log.d(TAG, "CONTROL_AE_STATE = CONTROL_AE_STATE_FLASH_REQUIRED");
				else if( ae_state == CaptureResult.CONTROL_AE_STATE_PRECAPTURE )
					Log.d(TAG, "CONTROL_AE_STATE = CONTROL_AE_STATE_PRECAPTURE");
				else
					Log.d(TAG, "CONTROL_AE_STATE = " + ae_state);
			}*/
			Integer flash_mode = result.get(CaptureResult.FLASH_MODE);
			if( fake_flash_mode != FakeFlashMode.None && ( fake_precapture_torch_focus_performed || fake_precapture_torch_performed ) && flash_mode != null && flash_mode == CameraMetadata.FLASH_MODE_TORCH ) {
				// don't change ae state while torch is on for fake flash
			}
			else if( ae_state == null ) {
				capture_result_ae = null;
				is_flash_required = false;
			}
			else if( !ae_state.equals(capture_result_ae) ) {
				// need to store this before calling the autofocus callbacks below
				if( MyDebug.LOG )
					Log.d(TAG, "CONTROL_AE_STATE changed from " + capture_result_ae + " to " + ae_state);
				capture_result_ae = ae_state;
				// capture_result_ae should always be non-null here, as we've already handled ae_state separately
				if( capture_result_ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED && !is_flash_required ) {
					is_flash_required = true;
					if( MyDebug.LOG )
						Log.d(TAG, "flash now required");
				}
				else if( capture_result_ae == CaptureResult.CONTROL_AE_STATE_CONVERGED && is_flash_required ) {
					is_flash_required = false;
					if( MyDebug.LOG )
						Log.d(TAG, "flash no longer required");
				}
			}

			if( af_state != null && af_state == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN ) {
				/*if( MyDebug.LOG )
					Log.d(TAG, "not ready for capture: " + af_state);*/
				ready_for_capture = false;
			}
			else {
				/*if( MyDebug.LOG )
					Log.d(TAG, "ready for capture: " + af_state);*/
				ready_for_capture = true;
				if( autofocus_cb != null && (!do_af_trigger_for_continuous || fake_flash_mode != FakeFlashMode.None) && focusIsContinuous() ) {
					Integer focus_mode = previewBuilder.get(CaptureRequest.CONTROL_AF_MODE);
					if( focus_mode != null && focus_mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE ) {
						if( MyDebug.LOG )
							Log.d(TAG, "call autofocus callback, as continuous mode and not focusing: " + af_state);
						// need to check af_state != null, I received Google Play crash in 1.33 where it was null
						boolean focus_success = af_state != null && ( af_state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af_state == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED );
						if( MyDebug.LOG ) {
							if( focus_success )
								Log.d(TAG, "autofocus success");
							else
								Log.d(TAG, "autofocus failed");
							if( af_state == null )
								Log.e(TAG, "continuous focus mode but af_state is null");
							else
								Log.d(TAG, "af_state: " + af_state);
						}
						if( af_state == null ) {
							test_af_state_null_focus++;
						}
						autofocus_cb.onAutoFocus(focus_success);
						autofocus_cb = null;
						capture_follows_autofocus_hint = false;
						
						autofocus_attempt = 0;
					}
				}
			}

			/*if( MyDebug.LOG ) {
				if( autofocus_cb == null ) {
					if( af_state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED )
						Log.d(TAG, "processAF: autofocus success but no callback set");
					else if( af_state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED )
						Log.d(TAG, "processAF: autofocus failed but no callback set");
				}
			}*/

			if( ae_state != null && ae_state == CaptureResult.CONTROL_AE_STATE_SEARCHING ) {
				/*if( MyDebug.LOG && !capture_result_is_ae_scanning )
					Log.d(TAG, "ae_state now searching");*/
				capture_result_is_ae_scanning = true;
			}
			else {
				/*if( MyDebug.LOG && capture_result_is_ae_scanning )
					Log.d(TAG, "ae_state stopped searching");*/
				capture_result_is_ae_scanning = false;
			}

			/*Integer awb_state = result.get(CaptureResult.CONTROL_AWB_STATE);
			if( MyDebug.LOG ) {
				if( awb_state == null )
					Log.d(TAG, "CONTROL_AWB_STATE is null");
				else if( awb_state == CaptureResult.CONTROL_AWB_STATE_INACTIVE )
					Log.d(TAG, "CONTROL_AWB_STATE = CONTROL_AWB_STATE_INACTIVE");
				else if( awb_state == CaptureResult.CONTROL_AWB_STATE_SEARCHING )
					Log.d(TAG, "CONTROL_AWB_STATE = CONTROL_AWB_STATE_SEARCHING");
				else if( awb_state == CaptureResult.CONTROL_AWB_STATE_CONVERGED )
					Log.d(TAG, "CONTROL_AWB_STATE = CONTROL_AWB_STATE_CONVERGED");
				else if( awb_state == CaptureResult.CONTROL_AWB_STATE_LOCKED )
					Log.d(TAG, "CONTROL_AWB_STATE = CONTROL_AWB_STATE_LOCKED");
				else
					Log.d(TAG, "CONTROL_AWB_STATE = " + awb_state);
			}*/

			if( fake_precapture_turn_on_torch_id != null && fake_precapture_turn_on_torch_id == request ) {
				if( MyDebug.LOG )
					Log.d(TAG, "torch turned on for fake precapture");
				fake_precapture_turn_on_torch_id = null;
			}

			if( state == STATE_NORMAL ) {
				// do nothing
			}
			else if( state == STATE_WAITING_AUTOFOCUS ) {
				if( af_state == null ) {
					// autofocus shouldn't really be requested if af not available, but still allow this rather than getting stuck waiting for autofocus to complete
					if( MyDebug.LOG )
						Log.e(TAG, "waiting for autofocus but af_state is null");
					test_af_state_null_focus++;
					state = STATE_NORMAL;
					precapture_state_change_time_ms = -1;
					if( autofocus_cb != null ) {
						autofocus_cb.onAutoFocus(false);
						autofocus_cb = null;
						
					}
					autofocus_attempt = 0;
					capture_follows_autofocus_hint = false;
				}
				else if( af_state != last_af_state ) {
					// check for autofocus completing
					// need to check that af_state != last_af_state, except for continuous focus mode where if we're already focused, should return immediately
					if( af_state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af_state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED /*||
							af_state == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED || af_state == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED*/
							) {
						boolean focus_success = af_state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af_state == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED;
						if( MyDebug.LOG ) {
							if( focus_success )
								Log.d(TAG, "onCaptureCompleted: autofocus success");
							else
								Log.d(TAG, "onCaptureCompleted: autofocus failed");
							Log.d(TAG, "af_state: " + af_state);
						}
						state = STATE_NORMAL;
						precapture_state_change_time_ms = -1;
						if( fake_flash_mode != FakeFlashMode.None && fake_precapture_torch_focus_performed ) {
							fake_precapture_torch_focus_performed = false;
							if( !capture_follows_autofocus_hint ) {
								// If we're going to be taking a photo immediately after the autofocus, it's better for the fake flash
								// mode to leave the torch on. If we don't do this, one of the following issues can happen:
								// - On OnePlus 3T, the torch doesn't get turned off, but because we've switched off the torch flag
								//   in previewBuilder, we go ahead with the precapture routine instead of
								if( MyDebug.LOG )
									Log.d(TAG, "turn off torch after focus (fake precapture code)");

								// same hack as in setFlashValue() - for fake precapture we need to turn off the torch mode that was set, but
								// at least on Nexus 6, we need to turn to flash_off to turn off the torch!
								String saved_flash_value = camera_settings.flash_value;
								camera_settings.flash_value = "flash_off";
								camera_settings.setAEMode(previewBuilder, false);
								try {
									capture();
								}
								catch(CameraAccessException e) {
									if( MyDebug.LOG ) {
										Log.e(TAG, "failed to do capture to turn off torch after autofocus");
										Log.e(TAG, "reason: " + e.getReason());
										Log.e(TAG, "message: " + e.getMessage());
									}
									e.printStackTrace();
								}

								// now set the actual (should be flash auto or flash on) mode
								camera_settings.flash_value = saved_flash_value;
								camera_settings.setAEMode(previewBuilder, false);
								try {
									setRepeatingRequest();
								}
								catch(CameraAccessException e) {
									if( MyDebug.LOG ) {
										Log.e(TAG, "failed to set repeating request to turn off torch after autofocus");
										Log.e(TAG, "reason: " + e.getReason());
										Log.e(TAG, "message: " + e.getMessage());
									}
									e.printStackTrace();
								}
							}
							else {
								if( MyDebug.LOG )
									Log.d(TAG, "torch was enabled for autofocus, leave it on for capture (fake precapture code)");
							}
						}
						if (af_state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED && autofocus_attempt < 2) {
							autofocus_attempt++;
							autoFocus(autofocus_cb, capture_follows_autofocus_hint);
						} else {
							if( autofocus_cb != null ) {
								autofocus_cb.onAutoFocus(focus_success);
								autofocus_cb = null;
								
							}
							autofocus_attempt = 0;
							capture_follows_autofocus_hint = false;
						}
					}
				} else if (request.get(CaptureRequest.CONTROL_SCENE_MODE) != null
						&& request.get(CaptureRequest.CONTROL_SCENE_MODE) != CameraMetadata.CONTROL_SCENE_MODE_DISABLED
						&& request.getTag() == RequestTag.AUTOFOCUS && af_state == CaptureResult.CONTROL_AF_STATE_INACTIVE) {
					if( autofocus_cb != null ) {
						autofocus_cb.onAutoFocus(false);
						autofocus_cb = null;
						
					}
					autofocus_attempt = 0;
					capture_follows_autofocus_hint = false;
				}
				
				
			}
			else if( state == STATE_WAITING_PRECAPTURE_START ) {
				if( MyDebug.LOG )
					Log.d(TAG, "waiting for precapture start...");
				if( MyDebug.LOG ) {
					if( ae_state != null )
						Log.d(TAG, "CONTROL_AE_STATE = " + ae_state);
					else
						Log.d(TAG, "CONTROL_AE_STATE is null");
				}
				if( ae_state == null || ae_state == CaptureResult.CONTROL_AE_STATE_PRECAPTURE /*|| ae_state == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED*/ ) {
					// we have to wait for CONTROL_AE_STATE_PRECAPTURE; if we allow CONTROL_AE_STATE_FLASH_REQUIRED, then on Nexus 6 at least we get poor quality results with flash:
					// varying levels of brightness, sometimes too bright or too dark, sometimes with blue tinge, sometimes even with green corruption
					// similarly photos with flash come out too dark on OnePlus 3T
					if( MyDebug.LOG ) {
						Log.d(TAG, "precapture started after: " + (System.currentTimeMillis() - precapture_state_change_time_ms));
					}
					state = STATE_WAITING_PRECAPTURE_DONE;
					precapture_state_change_time_ms = System.currentTimeMillis();
				}
				else if( precapture_state_change_time_ms != -1 && System.currentTimeMillis() - precapture_state_change_time_ms > precapture_start_timeout_c ) {
					// hack - give up waiting - sometimes we never get a CONTROL_AE_STATE_PRECAPTURE so would end up stuck
					// always log error, so we can look for it when manually testing with logging disabled
					Log.e(TAG, "precapture start timeout");
					count_precapture_timeout++;
					state = STATE_WAITING_PRECAPTURE_DONE;
					precapture_state_change_time_ms = System.currentTimeMillis();
				}
			}
			else if( state == STATE_WAITING_PRECAPTURE_DONE ) {
				if( MyDebug.LOG )
					Log.d(TAG, "waiting for precapture done...");
				if( MyDebug.LOG ) {
					if( ae_state != null )
						Log.d(TAG, "CONTROL_AE_STATE = " + ae_state);
					else
						Log.d(TAG, "CONTROL_AE_STATE is null");
				}
				if( ae_state == null || ae_state != CaptureResult.CONTROL_AE_STATE_PRECAPTURE ) {
					if( MyDebug.LOG ) {
						Log.d(TAG, "precapture completed after: " + (System.currentTimeMillis() - precapture_state_change_time_ms));
					}
					state = STATE_NORMAL;
					precapture_state_change_time_ms = -1;
					takePictureAfterPrecapture();
				}
				else if( precapture_state_change_time_ms != -1 && System.currentTimeMillis() - precapture_state_change_time_ms > precapture_done_timeout_c ) {
					// just in case
					// always log error, so we can look for it when manually testing with logging disabled
					Log.e(TAG, "precapture done timeout");
					count_precapture_timeout++;
					state = STATE_NORMAL;
					precapture_state_change_time_ms = -1;
					takePictureAfterPrecapture();
				}
			}
			else if( state == STATE_WAITING_FAKE_PRECAPTURE_START ) {
				if( MyDebug.LOG )
					Log.d(TAG, "waiting for fake precapture start...");
				if( MyDebug.LOG ) {
					if( ae_state != null )
						Log.d(TAG, "CONTROL_AE_STATE = " + ae_state);
					else
						Log.d(TAG, "CONTROL_AE_STATE is null");
				}
				if( fake_precapture_turn_on_torch_id != null ) {
					if( MyDebug.LOG )
						Log.d(TAG, "still waiting for torch to come on for fake precapture");
				}

				if( fake_precapture_turn_on_torch_id == null && (ae_state == null || ae_state == CaptureResult.CONTROL_AE_STATE_SEARCHING) ) {
					if( MyDebug.LOG ) {
						Log.d(TAG, "fake precapture started after: " + (System.currentTimeMillis() - precapture_state_change_time_ms));
					}
					state = STATE_WAITING_FAKE_PRECAPTURE_DONE;
					precapture_state_change_time_ms = System.currentTimeMillis();
				}
				else if( precapture_state_change_time_ms != -1 && System.currentTimeMillis() - precapture_state_change_time_ms > precapture_start_timeout_c ) {
					// just in case
					// always log error, so we can look for it when manually testing with logging disabled
					Log.e(TAG, "fake precapture start timeout");
					count_precapture_timeout++;
					state = STATE_WAITING_FAKE_PRECAPTURE_DONE;
					precapture_state_change_time_ms = System.currentTimeMillis();
					fake_precapture_turn_on_torch_id = null;
				}
			}
			else if( state == STATE_WAITING_FAKE_PRECAPTURE_DONE ) {
				if( MyDebug.LOG )
					Log.d(TAG, "waiting for fake precapture done...");
				if( MyDebug.LOG ) {
					if( ae_state != null )
						Log.d(TAG, "CONTROL_AE_STATE = " + ae_state);
					else
						Log.d(TAG, "CONTROL_AE_STATE is null");
					Log.d(TAG, "ready_for_capture? " + ready_for_capture);
				}
				// wait for af and ae scanning to end (need to check af too, as in continuous focus mode, a focus may start again after switching torch on for the fake precapture)
				if( ready_for_capture && ( ae_state == null || ae_state != CaptureResult.CONTROL_AE_STATE_SEARCHING)  ) {
					if( MyDebug.LOG ) {
						Log.d(TAG, "fake precapture completed after: " + (System.currentTimeMillis() - precapture_state_change_time_ms));
					}
					state = STATE_NORMAL;
					precapture_state_change_time_ms = -1;
					if (fake_flash_mode == FakeFlashMode.Torch) {
						takePictureAfterPrecapture();
					} else {
						previewBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
						try {
							setRepeatingRequest();
						}
						catch(CameraAccessException e) {
							if( MyDebug.LOG ) {
								Log.e(TAG, "failed to set repeating request to turn off torch after autofocus");
								Log.e(TAG, "reason: " + e.getReason());
								Log.e(TAG, "message: " + e.getMessage());
							}
							e.printStackTrace();
						}
						save_precapture_result = true;
						take_picture_when_flash_ready = true;
					}
				}
				else if( precapture_state_change_time_ms != -1 && System.currentTimeMillis() - precapture_state_change_time_ms > precapture_done_timeout_c ) {
					// sometimes camera can take a while to stop ae/af scanning, better to just go ahead and take photo
					// always log error, so we can look for it when manually testing with logging disabled
					Log.e(TAG, "fake precapture done timeout");
					count_precapture_timeout++;
					state = STATE_NORMAL;
					precapture_state_change_time_ms = -1;
					if (fake_flash_mode == FakeFlashMode.Torch) {
						takePictureAfterPrecapture();
					} else {
						previewBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
						try {
							setRepeatingRequest();
						}
						catch(CameraAccessException e) {
							if( MyDebug.LOG ) {
								Log.e(TAG, "failed to set repeating request to turn off torch after autofocus");
								Log.e(TAG, "reason: " + e.getReason());
								Log.e(TAG, "message: " + e.getMessage());
							}
							e.printStackTrace();
						}
						save_precapture_result = true;
						take_picture_when_flash_ready = true;
					}
				}
			}

			capture_result_is_af_scanning = false;
			if (af_state != null) {
				if (af_state == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN && af_state != last_af_state) {
					if( MyDebug.LOG )
						Log.d(TAG, "continuous focusing started");
					if( continuous_focus_move_callback != null ) {
						continuous_focus_move_callback.onContinuousFocusMove(true);
					}
				}
				else if (last_af_state == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN && af_state != last_af_state) {
					if( MyDebug.LOG )
						Log.d(TAG, "continuous focusing stopped");
					if( continuous_focus_move_callback != null ) {
						continuous_focus_move_callback.onContinuousFocusMove(false);
					}
				}

				if (af_state != last_af_state) {
					if( MyDebug.LOG )
						Log.d(TAG, "CONTROL_AF_STATE changed from " + last_af_state + " to " + af_state);
					last_af_state = af_state;
				}
				
				if (af_state == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN || af_state == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN)
					capture_result_is_af_scanning = true;
			}
			
			Integer awb_state = result.get(CaptureResult.CONTROL_AWB_STATE);
			if( awb_state != null && awb_state == CaptureResult.CONTROL_AWB_STATE_SEARCHING ) {
				capture_result_is_awb_scanning = true;
			}
			else {
				capture_result_is_awb_scanning = false;
			}
		}
		
		/** Processes a total result.
		 */
		private void processCompleted(CaptureRequest request, CaptureResult result) {
			/*if( MyDebug.LOG )
				Log.d(TAG, "processCompleted");*/

			if( !has_received_frame ) {
				has_received_frame = true;
				if( MyDebug.LOG )
					Log.d(TAG, "has_received_frame now set to true");
			}

			if( result.get(CaptureResult.SENSOR_SENSITIVITY) != null ) {
				capture_result_has_iso = true;
				capture_result_iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
				/*if( MyDebug.LOG )
					Log.d(TAG, "capture_result_iso: " + capture_result_iso);*/
				if(
					(camera_settings.manual_mode
					&& request.get(CaptureRequest.CONTROL_AE_MODE) == CaptureRequest.CONTROL_AE_MODE_OFF
					&& (force_iso_exposure || Math.abs(camera_settings.iso - capture_result_iso) > 10)) ||
					(force_manual_wb && camera_settings.white_balance == CameraMetadata.CONTROL_AWB_MODE_OFF)
				) {
					// ugly hack: problem (on Nexus 6 at least) that when we start recording video (video_recorder.start() call), this often causes the ISO setting to reset to the wrong value!
					// seems to happen more often with shorter exposure time
					// seems to happen on other camera apps with Camera2 API too
					// update: allow some tolerance, as on OnePlus 3T it's normal to have some slight difference between requested and actual
					// this workaround still means a brief flash with incorrect ISO, but is best we can do for now!
/*					if( MyDebug.LOG ) {
						Log.d(TAG, "ISO " + capture_result_iso + " different to requested ISO " + camera_settings.iso);
						Log.d(TAG, "	requested ISO was: " + request.get(CaptureRequest.SENSOR_SENSITIVITY));
						Log.d(TAG, "	requested AE mode was: " + request.get(CaptureRequest.CONTROL_AE_MODE));
					}*/
					try {
						repeatRepeatingRequest();
					}
					catch(CameraAccessException e) {
						if( MyDebug.LOG ) {
							Log.e(TAG, "failed to set repeating request after ISO hack");
							Log.e(TAG, "reason: " + e.getReason());
							Log.e(TAG, "message: " + e.getMessage());
						}
						e.printStackTrace();
					}
				}
			}
			else {
				capture_result_has_iso = false;
			}
			if( result.get(CaptureResult.SENSOR_EXPOSURE_TIME) != null ) {
				capture_result_has_exposure_time = true;
				capture_result_exposure_time = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
			}
			else {
				capture_result_has_exposure_time = false;
			}
			if( result.get(CaptureResult.SENSOR_FRAME_DURATION) != null ) {
				capture_result_has_frame_duration = true;
				capture_result_frame_duration = result.get(CaptureResult.SENSOR_FRAME_DURATION);
			}
			else {
				capture_result_has_frame_duration = false;
			}

			if (!camera_settings.focus_mode_manual) {
				if( result.get(CaptureResult.LENS_FOCUS_DISTANCE) != null ) {
					capture_result_has_focus_distance = true;
					capture_result_focus_distance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)-camera_settings.focus_distance_calibration;
				} else capture_result_has_focus_distance = false;
			} else capture_result_has_focus_distance = false;

			if( result.get(CaptureResult.LENS_FOCUS_RANGE) != null ) {
				Pair<Float, Float> focus_range = result.get(CaptureResult.LENS_FOCUS_RANGE);
				capture_result_has_focus_range = true;
				capture_result_focus_distance_min = focus_range.first-camera_settings.focus_distance_calibration;
				capture_result_focus_distance_max = focus_range.second-camera_settings.focus_distance_calibration;
			}
			else {
				capture_result_has_focus_range = false;
			}
			{
				RggbChannelVector vector = result.get(CaptureResult.COLOR_CORRECTION_GAINS);
				if( vector != null ) {
					capture_result_has_white_balance_rggb = true;
					// Android bug: we can't get gains back, because the value of blue gain will always be equal to green (Seen on LG G4 LS991 with zvb firmware).
					// So we use pre-saved gains. At the moment it is not used anywhere, but it was needed for tests.
					if (camera_settings.white_balance == CameraMetadata.CONTROL_AWB_MODE_OFF) {
						capture_result_white_balance_rggb = camera_settings.white_balance_rggb;
					} else {
						capture_result_white_balance_rggb = vector;
					}
					capture_result_white_balance = -1;
				}
			}

			if( face_detection_listener != null && previewBuilder != null && previewBuilder.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE) != null && previewBuilder.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE) != CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF ) {
				Rect sensor_rect = getViewableRect();
				android.hardware.camera2.params.Face [] camera_faces = result.get(CaptureResult.STATISTICS_FACES);
				if( camera_faces != null ) {
					CameraController.Face [] faces = new CameraController.Face[camera_faces.length];
					for(int i=0;i<camera_faces.length;i++) {
						faces[i] = convertFromCameraFace(sensor_rect, camera_faces[i]);
					}
					face_detection_listener.onFaceDetection(faces);
				}
			}

			if( push_repeating_request_when_torch_off || take_picture_when_flash_ready ) {
				if( MyDebug.LOG )
					Log.d(TAG, "received push_repeating_request_when_torch_off");
				Integer flash_state = result.get(CaptureResult.FLASH_STATE);
				if( MyDebug.LOG ) {
					if( flash_state != null )
						Log.d(TAG, "flash_state: " + flash_state);
					else
						Log.d(TAG, "flash_state is null");
				}
				if( flash_state != null && flash_state == CaptureResult.FLASH_STATE_READY ) {
					if (push_repeating_request_when_torch_off) {
						push_repeating_request_when_torch_off = false;
						try {
							camera_settings.flash_value = push_repeating_request_when_torch_off_value;
							if( camera_settings.setAEMode(previewBuilder, false) ) {
								setRepeatingRequest();
							}
						}
						catch(CameraAccessException e) {
							if( MyDebug.LOG ) {
								Log.e(TAG, "failed to set flash [from torch/flash off hack]");
								Log.e(TAG, "reason: " + e.getReason());
								Log.e(TAG, "message: " + e.getMessage());
							}
							e.printStackTrace();
						}
					}
					if (take_picture_when_flash_ready) {
						take_picture_when_flash_ready = false;
						takePictureAfterPrecapture();
					}
				}
			}			
			/*if( push_set_ae_lock && push_set_ae_lock_id == request ) {
				if( MyDebug.LOG )
					Log.d(TAG, "received push_set_ae_lock");
				// hack - needed to fix bug on Nexus 6 where auto-exposure sometimes locks when taking a photo of bright scene with flash on!
				// this doesn't completely resolve the issue, but seems to make it far less common; also when it does happen, taking another photo usually fixes it
				push_set_ae_lock = false;
				push_set_ae_lock_id = null;
				camera_settings.setAutoExposureLock(previewBuilder);
				try {
					setRepeatingRequest();
				}
				catch(CameraAccessException e) {
					if( MyDebug.LOG ) {
						Log.e(TAG, "failed to set ae lock [from ae lock hack]");
						Log.e(TAG, "reason: " + e.getReason());
						Log.e(TAG, "message: " + e.getMessage());
					}
					e.printStackTrace();
				}
			}*/
			
			if (save_precapture_result) {
				save_precapture_result = false;

				if (capture_result_has_iso && capture_result_has_exposure_time) {
					precapture_result_iso = capture_result_iso;
					precapture_result_exposure_time = capture_result_exposure_time;
					precapture_result_has_iso_exposure_time = true;
				} else {
					precapture_result_has_iso_exposure_time = false;
				}

				if (capture_result_has_frame_duration) {
					precapture_result_frame_duration = capture_result_frame_duration;
					precapture_result_has_frame_duration = true;
				}
				else {
					precapture_result_has_frame_duration = false;
				}
				
				if (capture_result_has_white_balance_rggb) {
					precapture_result_white_balance_rggb = capture_result_white_balance_rggb;
					precapture_result_has_white_balance_rggb = true;
				} else {
					precapture_result_has_white_balance_rggb = false;
				}
			}
			
			if( request.getTag() == RequestTag.CAPTURE || request.getTag() == RequestTag.CAPTURE_BURST_IN_PROGRESS ) {
				if (pending_photos_capture_result) {
					if( test_wait_capture_result ) {
						// for RAW capture, we require the capture result before creating DngCreator
						// but for testing purposes, we need to test the possibility where onImageAvailable() for
						// the RAW image is called before we receive the capture result here
						try {
							if( MyDebug.LOG )
								Log.d(TAG, "test_wait_capture_result: waiting...");
							Thread.sleep(500); // 200ms is enough to test the problem on Nexus 6, but use 500ms to be sure
						}
						catch(InterruptedException e) {
							e.printStackTrace();
						}
					}
					if( MyDebug.LOG && pending_photos_raw ) {
						LensShadingMap map = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
						if (map != null) {
							Log.d(TAG, "LENS_SHADING_CORRECTION_MAP size: " + map.getColumnCount() + " x " + map.getRowCount());
//							Log.d(TAG, "LENS_SHADING_CORRECTION_MAP: " + map.toString());
						}
					}
					setCaptureResult(result);
				}
			}

			if( request.getTag() == RequestTag.CAPTURE ) {
				if( MyDebug.LOG )
					Log.d(TAG, "capture request completed");
				test_capture_results++;
				// actual parsing of image data is done in the imageReader's OnImageAvailableListener()
				// need to cancel the autofocus, and restart the preview after taking the photo
				// Camera2Basic does a capture then sets a repeating request - do the same here just to be safe
				previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
				if( MyDebug.LOG )
					Log.d(TAG, "### reset ae mode");
				String saved_flash_value = camera_settings.flash_value;
				if( fake_flash_mode != FakeFlashMode.None && fake_precapture_torch_performed ) {
					// same hack as in setFlashValue() - for fake precapture we need to turn off the torch mode that was set, but
					// at least on Nexus 6, we need to turn to flash_off to turn off the torch!
					camera_settings.flash_value = "flash_off";
				}
				// if not using fake precapture, not sure if we need to set the ae mode, but the AE mode is set again in Camera2Basic
				camera_settings.setAEMode(previewBuilder, false);
				// n.b., if capture/setRepeatingRequest throw exception, we don't call the take_picture_error_cb.onError() callback, as the photo should have been taken by this point
				try {
					capture();
				}
				catch(CameraAccessException e) {
					if( MyDebug.LOG ) {
						Log.e(TAG, "failed to cancel autofocus after taking photo");
						Log.e(TAG, "reason: " + e.getReason());
						Log.e(TAG, "message: " + e.getMessage());
					}
					e.printStackTrace();
				}
				if( fake_flash_mode != FakeFlashMode.None && fake_precapture_torch_performed ) {
					// now set up the request to switch to the correct flash value
					camera_settings.flash_value = saved_flash_value;
					camera_settings.setAEMode(previewBuilder, false);
				}
				previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE); // ensure set back to idle
				try {
					setRepeatingRequest();
				}
				catch(CameraAccessException e) {
					if( MyDebug.LOG ) {
						Log.e(TAG, "failed to start preview after taking photo");
						Log.e(TAG, "reason: " + e.getReason());
						Log.e(TAG, "message: " + e.getMessage());
					}
					e.printStackTrace();
					preview_error_cb.onError();
				}
				fake_precapture_torch_performed = false;
			}
		}
	};
}
