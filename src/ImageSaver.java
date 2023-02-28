package com.caddish_hedgehog.hedgecam2;

import com.caddish_hedgehog.hedgecam2.CameraController.CameraController;
//import com.caddish_hedgehog.hedgecam2.JniBitmap;
import com.caddish_hedgehog.hedgecam2.Prefs;
import com.caddish_hedgehog.hedgecam2.UI.QueueCounter;
import com.caddish_hedgehog.hedgecam2.Utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint.Align;
import android.graphics.Paint;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.params.BlackLevelPattern;
import android.location.Location;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.RSInvalidStateException;
import android.renderscript.Script;
import android.renderscript.Type;
import android.support.annotation.RequiresApi;
import android.support.media.ExifInterface;
import android.util.Log;
import android.widget.TextView;

/** Handles the saving (and any required processing) of photos.
 */
public class ImageSaver {
	private static final String TAG = "HedgeCam/ImageSaver";

	private final Paint p = new Paint();

	private final MainActivity main_activity;
	private final HDRProcessor hdrProcessor;
	private NRProcessor nrProcessor;

	private final String software_name;

	/* We use a separate count n_images_to_save, rather than just relying on the queue size, so we can take() an image from queue,
	 * but only decrement the count when we've finished saving the image.
	 * In general, n_images_to_save represents the number of images still to process, including ones currently being processed.
	 * Therefore we should always have n_images_to_save >= queue.size().
	 */
	private int n_images_to_save = 0;
	private final List<Request> queue = new ArrayList<>(); // since we remove from the queue and then process in the saver thread, in practice the number of background photos - including the one being processed - is one more than the length of this queue
	private volatile Thread saving_thread;
	private final QueueCounter queueCounter;

	static class ProcessingSettings {
		public enum AutoLevelMode {
			DEFAULT,
			LANDSCAPE,
			PORTRAIT,
		}

		public boolean save_base;
		public boolean save_exif;
		public String hdr_tonemapping;
		public String hdr_local_contrast;
		public String hdr_n_tiles;
		public boolean hdr_deghost;
		public String hdr_unsharp_mask;
		public String hdr_unsharp_mask_radius;

		public boolean do_auto_level;
		public double level_angle;
		public AutoLevelMode auto_level_mode = AutoLevelMode.DEFAULT;
		public boolean auto_level_fixed_size = false;
		public double auto_level_aspect_ratio = 0.0;
		public boolean mirror;
		public int adjust_levels;
		public double histogram_level;
		public boolean post_processing;
		public float saturation_r;
		public float saturation_g;
		public float saturation_b;
		public boolean stamp;
		public String stamp_text;
		public String stamp_dateformat;
		public String stamp_timeformat;
		public String stamp_gpsformat;
		public boolean stamp_store_address;
		public boolean stamp_store_altitude;
		
		public String align;
		
		public ExifInterface.DNGSettings dng_settings;
		
		ProcessingSettings () {
			save_base = false;
			save_exif = true;
			adjust_levels = 0;
			histogram_level = 0.0d;
			saturation_r = 1.0f;
			saturation_g = 1.0f;
			saturation_b = 1.0f;
			align = "none";
		}
	}

	static class Metadata {
		public String author;
		public String comment;
		public boolean comment_as_file;
	}

	static class GPSData {
		boolean store_location;
		Location location;
		boolean store_geo_direction;
		double geo_direction;
		boolean store_altitude;
		boolean store_speed;
	}

	public static class Request {
		public enum Type {
			JPEG,
			PNG,
			WEBP,
			RAW,
		}
		Type type = Type.JPEG;
		final Prefs.PhotoMode photo_mode; // for jpeg
		final List<CameraController.Photo> images;
		final String yuv_conversion;
		final boolean image_capture_intent;
		final Uri image_capture_intent_uri;
		final boolean using_camera2;
		final int image_quality;
		final boolean allow_rotation;
		final ProcessingSettings processing_settings;
		final Metadata metadata;
		final GPSData gps_data;
		final boolean is_front_facing;
		final String prefix;
		final Date current_date;
		final int image_number;
		int sample_factor = 1; // sampling factor for thumbnail, higher means lower quality
		
		Request(Type type,
			Prefs.PhotoMode photo_mode,
			List<CameraController.Photo> images,
			String yuv_conversion,
			boolean image_capture_intent, Uri image_capture_intent_uri,
			boolean using_camera2, int image_quality,
			boolean allow_rotation,
			ProcessingSettings processing_settings,
			Metadata metadata,
			GPSData gps_data,
			boolean is_front_facing,
			String prefix, Date current_date, int image_number,
			int sample_factor) {
			this.type = type;
			this.photo_mode = photo_mode;
			this.images = images;
			this.yuv_conversion = yuv_conversion;
			this.image_capture_intent = image_capture_intent;
			this.image_capture_intent_uri = image_capture_intent_uri;
			this.using_camera2 = using_camera2;
			this.image_quality = image_quality;
			this.allow_rotation = allow_rotation;
			this.processing_settings = processing_settings;
			this.metadata = metadata;
			this.gps_data = gps_data;
			this.is_front_facing = is_front_facing;
			this.prefix = prefix;
			this.current_date = current_date;
			this.image_number = image_number;
			this.sample_factor = sample_factor;
		}
	}

	ImageSaver(MainActivity main_activity) {
		if( MyDebug.LOG )
			Log.d(TAG, "ImageSaver");
		this.main_activity = main_activity;
		this.hdrProcessor = new HDRProcessor(main_activity);
		this.queueCounter = new QueueCounter((Context)main_activity, (TextView)main_activity.findViewById(R.id.queue_count));

		ExifInterface.setCommentCharset(main_activity.getResources().getString(R.string.charset));

		String version = null;
		try {
			PackageInfo pInfo = main_activity.getPackageManager().getPackageInfo(main_activity.getPackageName(), 0);
			version = pInfo.versionName;
		} catch(NameNotFoundException e) {}
		this.software_name = "HedgeCam" + (version != null ? " " + version : "");

		p.setAntiAlias(true);
	}

	/** Saves a photo.
	 *  If do_in_background is true, the photo will be saved in a background thread. If the queue is full, the function will wait
	 *  until it isn't full. Otherwise it will return immediately. The function always returns true for background saving.
	 *  If do_in_background is false, the photo is saved on the current thread, and the function returns whether the photo was saved
	 *  successfully.
	 */
	boolean savePhoto(boolean do_in_background,
			Request.Type format,
			Prefs.PhotoMode photo_mode,
			List<CameraController.Photo> images,
			String yuv_conversion,
			boolean image_capture_intent, Uri image_capture_intent_uri,
			boolean using_camera2, int image_quality,
			boolean allow_rotation,
			ProcessingSettings processing_settings,
			Metadata metadata,
			GPSData gps_data,
			boolean is_front_facing,
			String prefix, Date current_date, int image_number,
			int sample_factor) {
		if( MyDebug.LOG ) {
			Log.d(TAG, "savePhoto");
			Log.d(TAG, "do_in_background? " + do_in_background);
			Log.d(TAG, "number of images: " + images.size());
		}
		Request request = new Request(format,
				photo_mode,
				images,
				yuv_conversion,
				image_capture_intent, image_capture_intent_uri,
				using_camera2, image_quality,
				allow_rotation,
				processing_settings,
				metadata,
				gps_data,
				is_front_facing,
				prefix, current_date, image_number,
				sample_factor);

		boolean success;
		if( do_in_background ) {
			if( MyDebug.LOG )
				Log.d(TAG, "add background request");
			addRequest(request);
			success = true; // always return true when done in background
		}
		else {
			success = saveImageNow(request, false);
		}
		if( MyDebug.LOG )
			Log.d(TAG, "success: " + success);
		return success;
	}

	/** Adds a request to the background queue, blocking if the queue is already full
	 */
	private void addRequest(Request request) {
		if( MyDebug.LOG )
			Log.d(TAG, "addRequest");
		// this should not be synchronized on "this": BlockingQueue is thread safe, and if it's blocking in queue.put(), we'll hang because
		// the saver queue will need to synchronize on "this" in order to notifyAll() the main thread

		synchronized( this ) {
			if( MyDebug.LOG )
				Log.d(TAG, "ImageSaver thread adding to queue, size: " + queue.size());
			// see above for why we don't synchronize the queue.put call
			// but we synchronize modification to avoid risk of problems related to compiler optimisation (local caching or reordering)
			// also see FindBugs warning due to inconsistent synchronisation
			n_images_to_save++; // increment before adding to the queue, just to make sure the main thread doesn't think we're all done
			queue.add(request);
		}
		if( MyDebug.LOG ) {
			synchronized( this ) { // keep FindBugs happy
				Log.d(TAG, "ImageSaver thread added to queue, size is now: " + queue.size());
				Log.d(TAG, "images still to save is now: " + n_images_to_save);
			}
		}
		
		queueCounter.increase();
		if (request.processing_settings != null && request.processing_settings.save_base) {
			for (int i = 0; i < request.images.size(); i++)
				queueCounter.increase();
		}

		if (saving_thread != null)
			return;
		
		saving_thread = new Thread() {
			@Override
			public void run() {
				int queue_size;
				synchronized( this ) {
					queue_size = queue.size();
				}
				while (queue_size > 0) {
					if( MyDebug.LOG )
						Log.d(TAG, "ImageSaver thread reading from queue, size: " + queue_size);
						
					Request request;
					synchronized( this ) {
						request = queue.get(0);
						queue.remove(0);
					}

					boolean success = false;
					try {
						success = saveImageNow(request, true);
					}
					catch(Throwable e) {
						String message = Utils.getResources().getString(R.string.failed_to_save_photo) + ":\n" + e.getClass().getSimpleName();
						StackTraceElement[] stack = e.getStackTrace();
						if (stack != null && stack.length > 0) {
							message +=  "\n" + stack[0].getFileName() + ":" + stack[0].getLineNumber();
							String this_class = Utils.getMainActivity().getPackageName() + ".ImageSaver";
							if (!this_class.equals(stack[0].getClassName())) {
								for (int i = 1; i < stack.length; i++) {
									if (this_class.equals(stack[i].getClassName())) {
										message += "\n" + stack[i].getFileName() + ":" + stack[i].getLineNumber();
										break;
									}
								}
							}
							
						}
						Utils.showToast(null, message);
						e.printStackTrace();
					}
					
					if( MyDebug.LOG ) {
						if( success )
							Log.d(TAG, "ImageSaver thread successfully saved image");
						else
							Log.e(TAG, "ImageSaver thread failed to save image");
					}
					synchronized( this ) {
						n_images_to_save--;
						if( MyDebug.LOG )
							Log.d(TAG, "ImageSaver thread processed new request from queue, images to save is now: " + n_images_to_save);
						if( MyDebug.LOG && n_images_to_save < 0 ) {
							Log.e(TAG, "images to save has become negative");
							throw new RuntimeException();
						}
						queue_size = queue.size();
					}
					queueCounter.decrease();
				}
				main_activity.savingImage(false);
				queueCounter.reset();
				saving_thread = null;
				System.gc();
			}
		};
		saving_thread.setPriority(1);
		
		main_activity.savingImage(true);
		saving_thread.start();

	}

	/** Loads a single jpeg as a Bitmaps.
	 * @param mutable Whether the bitmap should be mutable. Note that when converting to bitmaps
	 *				for the image post-processing (auto-stabilise etc), in general we need the
	 *				bitmap to be mutable (for photostamp to work).
	 */
	@SuppressWarnings("deprecation")
	private Bitmap loadBitmap(CameraController.Photo photo, String yuv_conversion, boolean mutable) {
		if( MyDebug.LOG ) {
			Log.d(TAG, "loadBitmap");
			Log.d(TAG, "mutable?: " + mutable);
		}
		long time_s = System.currentTimeMillis();
		if (photo.hasYuv()) {
			Allocation alloc = loadYUV(photo, yuv_conversion);
			Bitmap bitmap = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888);
			
			alloc.copyTo(bitmap);
			
			if( MyDebug.LOG )
				Log.d(TAG, "time after alloc.copyTo: " + (System.currentTimeMillis() - time_s));

			return bitmap;
		} else {
			BitmapFactory.Options options = new BitmapFactory.Options();
			if( MyDebug.LOG )
				Log.d(TAG, "options.inMutable is: " + options.inMutable);
			options.inMutable = mutable;
			if( Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT ) {
				// setting is ignored in Android 5 onwards
				options.inPurgeable = true;
			}
			Bitmap bitmap = BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.length, options);
			if( bitmap == null ) {
				Log.e(TAG, "failed to decode bitmap");
			}
			if( MyDebug.LOG )
				Log.d(TAG, "time after BitmapFactory.decodeByteArray: " + (System.currentTimeMillis() - time_s));
			return bitmap;
		}
	}

	private Allocation loadAllocation(CameraController.Photo photo, String yuv_conversion) {
		if (photo.hasYuv()) {
			return loadYUV(photo, yuv_conversion);
		} else {
			Bitmap bitmap = loadBitmap(photo, yuv_conversion, false);
			return Allocation.createFromBitmap(main_activity.getRenderScript(), bitmap);
		}
	}

	private Allocation loadYUV(CameraController.Photo photo, String conversion) {
		long time_s = System.currentTimeMillis();

		RenderScript rs = main_activity.getRenderScript();
		
		ScriptC_yuv yuvScript = new ScriptC_yuv(rs);
		if( MyDebug.LOG ) {
			Log.d(TAG, "YUV performance: time after new ScriptC_yuv: " + (System.currentTimeMillis() - time_s));
		}

		yuvScript.set_y_pixel_stride(photo.pixelStrideY);
		int row_space = Prefs.getRowSpaceYPref();
		yuvScript.set_y_row_stride(row_space >= 0 ? photo.width+row_space : photo.rowStrideY);

		yuvScript.set_uv_pixel_stride(photo.pixelStrideUV);
		row_space = Prefs.getRowSpaceUVPref();
		yuvScript.set_uv_row_stride(row_space >= 0 ? photo.width+row_space : photo.rowStrideUV);

		Type.Builder builder = new Type.Builder(rs, Element.U8(rs));
		builder.setX(photo.width).setY(photo.height);
		Allocation in_y = Allocation.createTyped(rs, builder.create());
		in_y.copyFrom(photo.y);
		yuvScript.set_inY(in_y);

		builder = new Type.Builder(rs, Element.U8(rs));
		builder.setX(photo.u.length);
		Allocation in_u = Allocation.createTyped(rs, builder.create());
		in_u.copyFrom(photo.u);
		yuvScript.set_inU(in_u);

		Allocation in_v = Allocation.createTyped(rs, builder.create());
		in_v.copyFrom(photo.v);
		yuvScript.set_inV(in_v);

		if( MyDebug.LOG ) {
			Log.d(TAG, "YUV performance: time after creating YUV allocations: " + (System.currentTimeMillis() - time_s));
		}

		builder = new Type.Builder(rs, Element.RGBA_8888(rs));
		builder.setX(photo.width);
		builder.setY(photo.height);
		Allocation out = Allocation.createTyped(rs, builder.create());

		if( MyDebug.LOG ) {
			Log.d(TAG, "YUV performance: time after creating out allocation: " + (System.currentTimeMillis() - time_s));
		}
		
		switch(conversion) {
			case "wide_range":
				yuvScript.forEach_YUV420ToRGB_wide_range(out);
				break;
			case "saturated":
				yuvScript.forEach_YUV420ToRGB_saturated(out);
				break;
			default:
				yuvScript.forEach_YUV420ToRGB(out);
		}
		
		if( MyDebug.LOG ) {
			Log.d(TAG, "YUV performance: time after converting to RGB: " + (System.currentTimeMillis() - time_s));
		}

		return out;
	}

	/** Helper class for loadBitmaps().
	 */
	private class LoadBitmapThread extends Thread {
		Bitmap bitmap;
		final BitmapFactory.Options options;
		final CameraController.Photo photo;
		final String yuv_conversion;
		LoadBitmapThread(BitmapFactory.Options options, CameraController.Photo photo, String yuv_conversion) {
			this.options = options;
			this.photo = photo;
			this.yuv_conversion = yuv_conversion;
		}

		public void run() {
			if (photo.hasYuv()) {
				Allocation alloc = loadYUV(photo, yuv_conversion);
				this.bitmap = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888);
				
				alloc.copyTo(bitmap);
			} else {
				this.bitmap = BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.length, options);
			}
		}
	}

	/** Converts the array of jpegs to Bitmaps. The bitmap with index mutable_id will be marked as mutable (or set to -1 to have no mutable bitmaps).
	 */
	@SuppressWarnings("deprecation")
	private List<Bitmap> loadBitmaps(List<CameraController.Photo> images, int mutable_id, String yuv_conversion) {
		if( MyDebug.LOG ) {
			Log.d(TAG, "loadBitmaps");
			Log.d(TAG, "mutable_id: " + mutable_id);
		}
		BitmapFactory.Options mutable_options = new BitmapFactory.Options();
		mutable_options.inMutable = true; // bitmap that needs to be writable
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inMutable = false; // later bitmaps don't need to be writable
		if( Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT ) {
			// setting is ignored in Android 5 onwards
			mutable_options.inPurgeable = true;
			options.inPurgeable = true;
		}
		LoadBitmapThread [] threads = new LoadBitmapThread[images.size()];
		for(int i=0;i<images.size();i++) {
			CameraController.Photo photo = images.get(i);
			photo.getDataFromImages();
			threads[i] = new LoadBitmapThread( i==mutable_id ? mutable_options : options, photo, yuv_conversion );
		}
		// start threads
		if( MyDebug.LOG )
			Log.d(TAG, "start threads");
		for(int i=0;i<images.size();i++) {
			threads[i].start();
		}
		// wait for threads to complete
		boolean ok = true;
		if( MyDebug.LOG )
			Log.d(TAG, "wait for threads to complete");
		try {
			for(int i=0;i<images.size();i++) {
				threads[i].join();
			}
		}
		catch(InterruptedException e) {
			if( MyDebug.LOG )
				Log.e(TAG, "threads interrupted");
			e.printStackTrace();
			ok = false;
		}
		if( MyDebug.LOG )
			Log.d(TAG, "threads completed");

		List<Bitmap> bitmaps = new ArrayList<>();
		for(int i=0;i<images.size() && ok;i++) {
			Bitmap bitmap = threads[i].bitmap;
			if( bitmap == null ) {
				Log.e(TAG, "failed to decode bitmap in thread: " + i);
				ok = false;
			}
			else {
				if( MyDebug.LOG )
					Log.d(TAG, "bitmap " + i + ": " + bitmap + " is mutable? " + bitmap.isMutable());
			}
			bitmaps.add(bitmap);
		}
		
		if( !ok ) {
			if( MyDebug.LOG )
				Log.d(TAG, "cleanup from failure");
			for(int i=0;i<images.size();i++) {
				if( threads[i].bitmap != null ) {
					threads[i].bitmap.recycle();
					threads[i].bitmap = null;
				}
			}
			bitmaps.clear();
			System.gc();
			return null;
		}

		return bitmaps;
	}

	/** May be run in saver thread or picture callback thread (depending on whether running in background).
	 */
	private boolean saveImageNow(final Request request, final boolean in_background) {
		if( MyDebug.LOG )
			Log.d(TAG, "saveImageNow");

		if( request.images.size() == 0 ) {
			if( MyDebug.LOG )
				Log.d(TAG, "saveImageNow called with zero images");
			// throw runtime exception, as this is a programming error
			throw new RuntimeException();
		}
		
		if (!in_background) main_activity.savingImage(true);

		boolean success = false;
		if( request.photo_mode == Prefs.PhotoMode.NoiseReduction ) {
			if( MyDebug.LOG ) {
				Log.d(TAG, "average");
				Log.d(TAG, "processing image #" + request.image_number);
			}

			CameraController.Photo photo = request.images.get(0);
			photo.getDataFromImages();
			
			if (!photo.hasJpeg() && !photo.hasYuv()) {
				if( MyDebug.LOG )
					Log.d(TAG, "saveImageNow called with unsupported image format");
				// throw runtime exception, as this is a programming error
				throw new RuntimeException();
			}

			success = true;

			if (request.image_number == 1) {
				if (!request.image_capture_intent && request.processing_settings.save_base)
					saveSingleImageNow(request, photo, null, null, "", false, false);

				Bitmap bitmap = loadBitmap(photo, request.yuv_conversion, false);
				boolean align = false;
				boolean crop_aligned = false;
				switch (request.processing_settings.align) {
					case "align":
						align = true;
						break;
					case "align_crop":
						align = true;
						crop_aligned = true;
						break;
				}
				nrProcessor = new NRProcessor(main_activity, main_activity.getRenderScript(), bitmap, align, crop_aligned);
				if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ) {
					bitmap.recycle();
				}
			} else {
				if (nrProcessor != null) {
					Allocation alloc = loadAllocation(photo, request.yuv_conversion);
					nrProcessor.addAllocation(alloc);
				}
			}

			if (request.sample_factor != 0) {
				if( MyDebug.LOG )
					Log.d(TAG, "saving NR image");
				Allocation nr_alloc = nrProcessor.finish(request.processing_settings.adjust_levels, request.processing_settings.histogram_level);
				nrProcessor = null;
				request.processing_settings.adjust_levels = Prefs.ADJUST_LEVELS_NONE;
				
				String suffix = "_NR";
				success = saveSingleImageNow(request, photo, null, nr_alloc, suffix, true, true);
				if( MyDebug.LOG && !success )
					Log.e(TAG, "saveSingleImageNow failed for nr image");
			}

			System.gc();
		}
		else if( request.photo_mode == Prefs.PhotoMode.HDR || request.photo_mode == Prefs.PhotoMode.DRO ) {
			if( MyDebug.LOG )
				Log.d(TAG, "hdr");

			if (!request.images.get(0).hasJpeg() && !request.images.get(0).hasYuv()) {
				if( MyDebug.LOG )
					Log.d(TAG, "saveImageNow called with unsupported image format");
				// throw runtime exception, as this is a programming error
				throw new RuntimeException();
			}

			if( request.images.size() != 1 && request.images.size() != 3 ) {
				if( MyDebug.LOG )
					Log.d(TAG, "saveImageNow expected either 1 or 3 images for hdr, not " + request.images.size());
				// throw runtime exception, as this is a programming error
				throw new RuntimeException();
			}

			long time_s = System.currentTimeMillis();
			if( !request.image_capture_intent && request.processing_settings.save_base ) {
				// if there's only 1 image, we're in DRO mode, and shouldn't save the base image
				if( MyDebug.LOG )
					Log.d(TAG, "save base images");
				
				for(int i=0;i<request.images.size();i++) {
					// note, even if one image fails, we still try saving the other images - might as well give the user as many images as we can...
					CameraController.Photo image = request.images.get(i);
					String filename_suffix = request.images.size() == 1 ? "" : "_EXP" + i;
					if (image.hasRaw()) {
						if( MyDebug.LOG )
							Log.d(TAG, "Saving base RAW image " + i);
						saveSingleRawImageNow(image, request.prefix, filename_suffix, request.current_date,
								request.gps_data, request.metadata, request.processing_settings.dng_settings, false);
					} else if (image.hasJpeg() || image.hasYuv()) {
						// don't update the thumbnails, only do this for the final image - so user doesn't think it's complete, click gallery, then wonder why the final image isn't there
						// also don't mark these images as being shared
						if( !saveSingleImageNow(request, image, null, null, filename_suffix, false, false) ) {
							if( MyDebug.LOG )
								Log.e(TAG, "saveSingleImageNow failed for exposure image");
							// we don't set success to false here - as for deciding whether to pause preview or not (which is all we use the success return for), all that matters is whether we saved the final HDR image
						}
					}
					queueCounter.decrease();
				}
				if( MyDebug.LOG )
					Log.d(TAG, "HDR performance: time after saving base exposures: " + (System.currentTimeMillis() - time_s));
			}

			// note, even if we failed saving some of the expo images, still try to save the HDR image
			if( MyDebug.LOG )
				Log.d(TAG, "create HDR image");

			// see documentation for HDRProcessor.processHDR() - because we're using release_bitmaps==true, we need to make sure that
			// the bitmap that will hold the output HDR image is mutable (in case of options like photo stamp)
			// see test testTakePhotoHDRPhotoStamp.
			int base_bitmap = (request.images.size()-1)/2;
			if( MyDebug.LOG )
				Log.d(TAG, "base_bitmap: " + base_bitmap);
			List<Bitmap> bitmaps = loadBitmaps(request.images, base_bitmap, request.yuv_conversion);
			if( bitmaps == null ) {
				if( MyDebug.LOG )
					Log.e(TAG, "failed to load bitmaps");
				if (!in_background) main_activity.savingImage(false);
				return false;
			}
			if( MyDebug.LOG ) {
				Log.d(TAG, "HDR performance: time after decompressing base exposures: " + (System.currentTimeMillis() - time_s));
			}
			if( MyDebug.LOG )
				Log.d(TAG, "before HDR first bitmap: " + bitmaps.get(0) + " is mutable? " + bitmaps.get(0).isMutable());
			Allocation out_alloc = null;
			try {
				if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT ) {
					int local_contrast;
					try {local_contrast = Integer.parseInt(request.processing_settings.hdr_local_contrast);}
					catch (NumberFormatException e) {local_contrast = 5;}
					if (local_contrast < 0 || local_contrast > 10) local_contrast = 5;

					int unsharp_mask;
					try {unsharp_mask = Integer.parseInt(request.processing_settings.hdr_unsharp_mask);}
					catch (NumberFormatException e) {unsharp_mask = 5;}
					if (unsharp_mask < 0 || unsharp_mask > 10) unsharp_mask = 5;

					int unsharp_mask_radius;
					try {unsharp_mask_radius = Integer.parseInt(request.processing_settings.hdr_unsharp_mask_radius);}
					catch (NumberFormatException e) {unsharp_mask_radius = 5;}
					if (unsharp_mask_radius < 0 || unsharp_mask_radius > 20) unsharp_mask_radius = 5;

					int n_tiles;
					try {n_tiles = Integer.parseInt(request.processing_settings.hdr_n_tiles);}
					catch (NumberFormatException e) {n_tiles = 4;}

					HDRProcessor.TonemappingAlgorithm tonemapping_algorithm = HDRProcessor.TonemappingAlgorithm.TONEMAPALGORITHM_REINHARD;
					boolean align = false;
					boolean crop_aligned = false;
					if (request.photo_mode == Prefs.PhotoMode.HDR) {
						switch (request.processing_settings.hdr_tonemapping) {
							case "clamp":
								tonemapping_algorithm = HDRProcessor.TonemappingAlgorithm.TONEMAPALGORITHM_CLAMP;
								break;
							case "exponential":
								tonemapping_algorithm = HDRProcessor.TonemappingAlgorithm.TONEMAPALGORITHM_EXPONENTIAL;
								break;
							case "filmic":
								tonemapping_algorithm = HDRProcessor.TonemappingAlgorithm.TONEMAPALGORITHM_FILMIC;
								break;
							case "aces":
								tonemapping_algorithm = HDRProcessor.TonemappingAlgorithm.TONEMAPALGORITHM_ACES;
								break;
							case "reinhard_new":
								tonemapping_algorithm = HDRProcessor.TonemappingAlgorithm.TONEMAPALGORITHM_REINHARD_NEW;
								break;
						}
						
						switch (request.processing_settings.align) {
							case "align":
								align = true;
								break;
							case "align_crop":
								align = true;
								crop_aligned = true;
								break;
						}
					}
					out_alloc = hdrProcessor.processHDR(bitmaps, true, null, align, crop_aligned, (float)local_contrast/10, n_tiles, (float)unsharp_mask/10, unsharp_mask_radius, tonemapping_algorithm, request.processing_settings.hdr_deghost);
				}
				else {
					Log.e(TAG, "shouldn't have offered HDR as an option if not on Android 5");
					throw new RuntimeException();
				}
			}
			catch(HDRProcessorException e) {
				Log.e(TAG, "HDRProcessorException from processHDR: " + e.getCode());
				e.printStackTrace();
				if( e.getCode() == HDRProcessorException.UNEQUAL_SIZES ) {
					// this can happen on OnePlus 3T with old camera API with front camera, seems to be a bug that resolution changes when exposure compensation is set!
					Utils.showToast(null, R.string.failed_to_process_hdr);
					Log.e(TAG, "UNEQUAL_SIZES");
					bitmaps.clear();
					System.gc();
					if (!in_background) main_activity.savingImage(false);
					return false;
				}
				else {
					// throw RuntimeException, as we shouldn't ever get the error INVALID_N_IMAGES, if we do it's a programming error
					throw new RuntimeException();
				}
			}
			if( MyDebug.LOG ) {
				Log.d(TAG, "HDR performance: time after creating HDR image: " + (System.currentTimeMillis() - time_s));
			}
//			if( MyDebug.LOG )
//				Log.d(TAG, "after HDR first bitmap: " + bitmaps.get(0) + " is mutable? " + bitmaps.get(0).isMutable());
			Bitmap hdr_bitmap = bitmaps.get(base_bitmap);
//			if( MyDebug.LOG )
//				Log.d(TAG, "hdr_bitmap: " + hdr_bitmap + " is mutable? " + hdr_bitmap.isMutable());
			bitmaps.clear();
			System.gc();

			if( MyDebug.LOG )
				Log.d(TAG, "save HDR image");
			int base_image_id = ((request.images.size()-1)/2);
			if( MyDebug.LOG )
				Log.d(TAG, "base_image_id: " + base_image_id);
			String suffix = request.images.size() == 1 ? "_DRO" : "_HDR";
			success = saveSingleImageNow(request, request.images.get(base_image_id), hdr_bitmap, out_alloc, suffix, true, true);
			if( MyDebug.LOG && !success )
				Log.e(TAG, "saveSingleImageNow failed for hdr image");
			if( MyDebug.LOG ) {
				Log.d(TAG, "HDR performance: time after saving HDR image: " + (System.currentTimeMillis() - time_s));
			}
			hdr_bitmap.recycle();
			System.gc();
		}
		else {
			if( request.images.size() > 1 ) {
				if( MyDebug.LOG )
					Log.d(TAG, "saveImageNow called with " + request.images.size() + " images");
				int mid_image = request.images.size()/2;
				success = true;
				for(int i=0;i<request.images.size();i++) {
					// note, even if one image fails, we still try saving the other images - might as well give the user as many images as we can...
					CameraController.Photo image = request.images.get(i);
					String filename_suffix = "_EXP" + (i);
					boolean share_image = i == mid_image;
					if (image.hasRaw()) {
						if( MyDebug.LOG )
							Log.d(TAG, "Saving expo RAW image " + i);
						success = saveSingleRawImageNow(image, request.prefix, filename_suffix, request.current_date,
								request.gps_data, request.metadata, request.processing_settings.dng_settings, request.type == Request.Type.RAW);
					}
					if (image.hasJpeg() || image.hasYuv()) {
						if( MyDebug.LOG )
							Log.d(TAG, "Saving expo image " + i);
						
						success = saveSingleImageNow(request, image, null, null, filename_suffix, true, share_image);
					}
				}
			}
			else {
				CameraController.Photo image = request.images.get(0);

				String suffix = "";
				if (request.photo_mode == Prefs.PhotoMode.FastBurst)
					suffix = String.format("_B%03d", request.image_number);
				else if (request.photo_mode == Prefs.PhotoMode.FocusBracketing)
					suffix = String.format("_FB%02d", request.image_number);

				if (image.hasRaw()) {
					if( MyDebug.LOG )
						Log.d(TAG, "Saving RAW image");
					success = saveSingleRawImageNow(image, request.prefix, suffix, request.current_date,
							request.gps_data, request.metadata, request.processing_settings.dng_settings, request.type == Request.Type.RAW);
				}
				if (image.hasJpeg() || image.hasYuv()) {
					if( MyDebug.LOG )
						Log.d(TAG, "Saving image");
					success = saveSingleImageNow(request, image, null, null, suffix, true, true);
				}
			}
		}

		for(int i = 0; i < request.images.size(); i++) {
			CameraController.Photo photo = request.images.get(i);
			if (photo.rawImage != null) {
				if( MyDebug.LOG )
					Log.d(TAG, "Destroying unused RAW image. Dear programmer, you fucked up again.");
				photo.rawImage.close();
				photo.rawImage = null;
			}
		}

		if (!in_background) main_activity.savingImage(false);
		return success;
	}

	/** Performs the auto-stabilise algorithm on the image.
	 * @param data The jpeg data.
	 * @param bitmap Optional argument - the bitmap if already unpacked from the jpeg data.
	 * @param level_angle The angle in degrees to rotate the image.
	 * @param is_front_facing Whether the camera is front-facing.
	 * @return A bitmap representing the auto-stabilised jpeg.
	 */
	private Bitmap autoLevel(CameraController.Photo photo, Bitmap bitmap, ProcessingSettings ps, boolean is_front_facing) {
		double level_angle = ps.level_angle;
		if( MyDebug.LOG ) {
			Log.d(TAG, "autoLevel");
			Log.d(TAG, "level_angle: " + level_angle);
			Log.d(TAG, "is_front_facing: " + is_front_facing);
		}
		while( level_angle < -90 )
			level_angle += 180;
		while( level_angle > 90 )
			level_angle -= 180;
		if( bitmap != null ) {
			int width = bitmap.getWidth();
			int height = bitmap.getHeight();
			if( MyDebug.LOG ) {
				Log.d(TAG, "level_angle: " + level_angle);
				Log.d(TAG, "decoded bitmap size " + width + ", " + height);
				Log.d(TAG, "bitmap size: " + width*height*4);
			}
			Matrix matrix = new Matrix();
			matrix.postRotate((float)(is_front_facing ? -level_angle : level_angle));

			if (ps.auto_level_mode != ProcessingSettings.AutoLevelMode.DEFAULT) {
				int new_width;
				int new_height;
				if (ps.auto_level_aspect_ratio == 0.0) {
					ps.auto_level_aspect_ratio = (double)Math.max(width, height)/(double)Math.min(width, height);
				}
				if (ps.auto_level_mode == ProcessingSettings.AutoLevelMode.PORTRAIT) {
					ps.auto_level_aspect_ratio = 1/ps.auto_level_aspect_ratio;
				}
				if (ps.auto_level_fixed_size) {
					new_height = (int)Math.sqrt((Math.pow(Math.min(width, height), 2)/(1+Math.pow(ps.auto_level_aspect_ratio,2))));
					new_width = (int)(new_height*ps.auto_level_aspect_ratio);				
				} else {
					double alpha = Math.toDegrees(Math.atan(1.0d/ps.auto_level_aspect_ratio));
					
					new_height = (int)Math.sqrt((Math.pow(height / Math.cos(Math.toRadians(90-alpha-Math.abs(level_angle))), 2)/(1+Math.pow(ps.auto_level_aspect_ratio, 2))));
					new_width = (int)(new_height*ps.auto_level_aspect_ratio);

					int alt_width = (int)(Math.sqrt((Math.pow(width/Math.cos(Math.toRadians(alpha-Math.abs(level_angle))), 2)/(1+Math.pow(ps.auto_level_aspect_ratio,2))))*ps.auto_level_aspect_ratio);
					if (alt_width < new_width) {
						new_width = alt_width;
						new_height = (int)(new_width/ps.auto_level_aspect_ratio);
					}
				}

				if( MyDebug.LOG ) {
					Log.d(TAG, "ps.auto_level_aspect_ratio: " + ps.auto_level_aspect_ratio);
					Log.d(TAG, "new size: " + new_width + "x" + new_height);
				}

				Bitmap new_bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
				// careful, as new_bitmap is sometimes not a copy!
				if( new_bitmap != bitmap ) {
					bitmap.recycle();
					bitmap = new_bitmap;
				}
				
				int r_width = bitmap.getWidth();
				int r_height = bitmap.getHeight();
				
				if( MyDebug.LOG )
					Log.d(TAG, "rotated bitmap size: " + r_width + "x" + r_height);

				new_bitmap = Bitmap.createBitmap(bitmap, (r_width-new_width)/2, (r_height-new_height)/2, new_width, new_height);
				if( new_bitmap != bitmap ) {
					bitmap.recycle();
					bitmap = new_bitmap;
				}
			} else {
					/*for(int y=0;y<height;y++) {
						for(int x=0;x<width;x++) {
							int col = bitmap.getPixel(x, y);
							col = col & 0xffff0000; // mask out red component
							bitmap.setPixel(x, y, col);
						}
					}*/
				double level_angle_rad_abs = Math.abs( Math.toRadians(level_angle) );
				int w1 = width, h1 = height;
				double w0 = (w1 * Math.cos(level_angle_rad_abs) + h1 * Math.sin(level_angle_rad_abs));
				double h0 = (w1 * Math.sin(level_angle_rad_abs) + h1 * Math.cos(level_angle_rad_abs));
				// apply a scale so that the overall image size isn't increased
				float orig_size = w1*h1;
				float rotated_size = (float)(w0*h0);
				float scale = (float)Math.sqrt(orig_size/rotated_size);
				if( main_activity.test_low_memory ) {
					if( MyDebug.LOG ) {
						Log.d(TAG, "TESTING LOW MEMORY");
						Log.d(TAG, "scale was: " + scale);
					}
					// test 20MP on Galaxy Nexus or Nexus 7; 29MP on Nexus 6 and 36MP OnePlus 3T
					if( width*height >= 7500 )
						scale *= 1.5f;
					else
						scale *= 2.0f;
				}
				if( MyDebug.LOG ) {
					Log.d(TAG, "w0 = " + w0 + " , h0 = " + h0);
					Log.d(TAG, "w1 = " + w1 + " , h1 = " + h1);
					Log.d(TAG, "scale = sqrt " + orig_size + " / " + rotated_size + " = " + scale);
				}
				matrix.postScale(scale, scale);
				w0 *= scale;
				h0 *= scale;
				w1 *= scale;
				h1 *= scale;
				if( MyDebug.LOG ) {
					Log.d(TAG, "after scaling: w0 = " + w0 + " , h0 = " + h0);
					Log.d(TAG, "after scaling: w1 = " + w1 + " , h1 = " + h1);
				}
				Bitmap new_bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
				// careful, as new_bitmap is sometimes not a copy!
				if( new_bitmap != bitmap ) {
					bitmap.recycle();
					bitmap = new_bitmap;
				}
				System.gc();
				if( MyDebug.LOG ) {
					Log.d(TAG, "rotated and scaled bitmap size " + bitmap.getWidth() + ", " + bitmap.getHeight());
					Log.d(TAG, "rotated and scaled bitmap size: " + bitmap.getWidth()*bitmap.getHeight()*4);
				}
				double tan_theta = Math.tan(level_angle_rad_abs);
				double sin_theta = Math.sin(level_angle_rad_abs);
				double denom = ( h0/w0 + tan_theta );
				double alt_denom = ( w0/h0 + tan_theta );
				if( denom == 0.0 || denom < 1.0e-14 ) {
					if( MyDebug.LOG )
						Log.d(TAG, "zero denominator?!");
				}
				else if( alt_denom == 0.0 || alt_denom < 1.0e-14 ) {
					if( MyDebug.LOG )
						Log.d(TAG, "zero alt denominator?!");
				}
				else {
					int w2 = (int)(( h0 + 2.0*h1*sin_theta*tan_theta - w0*tan_theta ) / denom);
					int h2 = (int)(w2*h0/w0);
					int alt_h2 = (int)(( w0 + 2.0*w1*sin_theta*tan_theta - h0*tan_theta ) / alt_denom);
					int alt_w2 = (int)(alt_h2*w0/h0);
					if( MyDebug.LOG ) {
						//Log.d(TAG, "h0 " + h0 + " 2.0*h1*sin_theta*tan_theta " + 2.0*h1*sin_theta*tan_theta + " w0*tan_theta " + w0*tan_theta + " / h0/w0 " + h0/w0 + " tan_theta " + tan_theta);
						Log.d(TAG, "w2 = " + w2 + " , h2 = " + h2);
						Log.d(TAG, "alt_w2 = " + alt_w2 + " , alt_h2 = " + alt_h2);
					}
					if( alt_w2 < w2 ) {
						if( MyDebug.LOG ) {
							Log.d(TAG, "chose alt!");
						}
						w2 = alt_w2;
						h2 = alt_h2;
					}
					if( w2 <= 0 )
						w2 = 1;
					else if( w2 >= bitmap.getWidth() )
						w2 = bitmap.getWidth()-1;
					if( h2 <= 0 )
						h2 = 1;
					else if( h2 >= bitmap.getHeight() )
						h2 = bitmap.getHeight()-1;
					int x0 = (bitmap.getWidth()-w2)/2;
					int y0 = (bitmap.getHeight()-h2)/2;
					if( MyDebug.LOG ) {
						Log.d(TAG, "x0 = " + x0 + " , y0 = " + y0);
					}
					// We need the bitmap to be mutable for photostamp to work - contrary to the documentation for Bitmap.createBitmap
					// (which says it returns an immutable bitmap), we seem to always get a mutable bitmap anyway. A mutable bitmap
					// would result in an exception "java.lang.IllegalStateException: Immutable bitmap passed to Canvas constructor"
					// from the Canvas(bitmap) constructor call in the photostamp code, and I've yet to see this from Google Play.
					new_bitmap = Bitmap.createBitmap(bitmap, x0, y0, w2, h2);
					if( new_bitmap != bitmap ) {
						bitmap.recycle();
						bitmap = new_bitmap;
					}
					if( MyDebug.LOG )
						Log.d(TAG, "bitmap is mutable?: " + bitmap.isMutable());
					System.gc();
				}
			}
		}
		return bitmap;
	}

	/** Applies any photo stamp options (if they exist).
	 * @param data The jpeg data.
	 * @param bitmap Optional argument - the bitmap if already unpacked from the jpeg data.
	 * @return A bitmap representing the stamped jpeg. Will be null if the input bitmap is null and
	 *		 no photo stamp is applied.
	 */
	private Bitmap stampImage(final Request request, CameraController.Photo photo, Bitmap bitmap) {
		if( MyDebug.LOG ) {
			Log.d(TAG, "stampImage");
		}
		final MyApplicationInterface applicationInterface = main_activity.getApplicationInterface();
		boolean text_stamp = request.processing_settings.stamp_text.length() > 0;
		if( request.processing_settings.stamp || text_stamp ) {
			if( bitmap != null ) {
				if( MyDebug.LOG )
					Log.d(TAG, "stamp info to bitmap: " + bitmap);
				if( MyDebug.LOG )
					Log.d(TAG, "bitmap is mutable?: " + bitmap.isMutable());
				int width = bitmap.getWidth();
				int height = bitmap.getHeight();
				if( MyDebug.LOG ) {
					Log.d(TAG, "decoded bitmap size " + width + ", " + height);
					Log.d(TAG, "bitmap size: " + width*height*4);
				}
				Canvas canvas = new Canvas(bitmap);
				int line_count = 0;
				if( request.processing_settings.stamp ) {
					if( MyDebug.LOG )
						Log.d(TAG, "stamp date");
					// doesn't respect user preferences such as 12/24 hour - see note about in draw() about DateFormat.getTimeInstance()
					String date_stamp = StringUtils.getDateString(request.processing_settings.stamp_dateformat, request.current_date);
					String time_stamp = StringUtils.getTimeString(request.processing_settings.stamp_timeformat, request.current_date);
					if( MyDebug.LOG ) {
						Log.d(TAG, "date_stamp: " + date_stamp);
						Log.d(TAG, "time_stamp: " + time_stamp);
					}
					if( date_stamp.length() > 0 || time_stamp.length() > 0 ) {
						String datetime_stamp = "";
						if( date_stamp.length() > 0 )
							datetime_stamp += date_stamp;
						if( time_stamp.length() > 0 ) {
							if( datetime_stamp.length() > 0 )
								datetime_stamp += " ";
							datetime_stamp += time_stamp;
						}
						applicationInterface.drawTextOnPhoto(canvas, p, datetime_stamp, width, height, line_count);
						line_count++;
					}
					String gps_stamp = StringUtils.getGPSString(
						request.processing_settings.stamp_gpsformat,
						request.gps_data.store_location,
						request.gps_data.location,
						request.processing_settings.stamp_store_address,
						request.processing_settings.stamp_store_altitude,
						request.gps_data.store_geo_direction,
						request.gps_data.geo_direction
					);
					if( gps_stamp.length() > 0 ) {
						if( MyDebug.LOG )
							Log.d(TAG, "stamp with location_string: " + gps_stamp);
						applicationInterface.drawTextOnPhoto(canvas, p, gps_stamp, width, height, line_count);
						line_count++;
					}
				}
				if( text_stamp ) {
					if( MyDebug.LOG )
						Log.d(TAG, "stamp text");
					applicationInterface.drawTextOnPhoto(canvas, p, request.processing_settings.stamp_text, width, height, line_count);
					line_count++;
				}
			}
		}
		return bitmap;
	}

	/** May be run in saver thread or picture callback thread (depending on whether running in background).
	 *  The requests.images field is ignored, instead we save the supplied data or bitmap.
	 *  If bitmap is null, then the supplied jpeg data is saved. If bitmap is non-null, then the bitmap is
	 *  saved, but the supplied data is still used to read EXIF data from.
	 *  @param update_thumbnail - Whether to update the thumbnail (and show the animation).
	 *  @param share_image - Whether this image should be marked as the one to share (if multiple images can
	 *  be saved from a single shot (e.g., saving exposure images with HDR).
	 */
	@SuppressLint("SimpleDateFormat")
	@SuppressWarnings("deprecation")
	private boolean saveSingleImageNow(final Request request, CameraController.Photo photo, Bitmap bitmap, Allocation alloc, String filename_suffix, boolean update_thumbnail, boolean share_image) {
		if( MyDebug.LOG )
			Log.d(TAG, "saveSingleImageNow");

		String extension;
		switch(request.type) {
			case PNG:
				extension = "png";
				break;
			case WEBP:
				extension = "webp";
				break;
			case JPEG:
				extension = "jpg";
				break;
			default:
				if( MyDebug.LOG )
					Log.d(TAG, "saveImageNow called with unsupported image format");
				// throw runtime exception, as this is a programming error
				throw new RuntimeException();
		}

		if (!photo.hasJpeg() && !photo.hasYuv()) {
			if( MyDebug.LOG )
				Log.d(TAG, "saveSingleImageNow called with no data");
			// throw runtime exception, as this is a programming error
			throw new RuntimeException();
		}
		long time_s = System.currentTimeMillis();
		
		photo.getDataFromImages();
		
		// unpack:
		final boolean using_camera2 = request.using_camera2;
		final Date current_date = request.current_date;
		
		int orientation = ExifInterface.ORIENTATION_UNDEFINED;
		if (photo.hasYuv()) {
			orientation = photo.orientation;
		} else {
			orientation = getExifOrientation(photo.jpeg);
		}

		boolean success = false;
		final MyApplicationInterface applicationInterface = main_activity.getApplicationInterface();
		StorageUtils storageUtils = main_activity.getStorageUtils();
		
		boolean text_stamp = request.processing_settings.stamp_text.length() > 0;
		boolean need_bitmap = bitmap != null ||
			photo.hasYuv() ||
			alloc != null ||
			request.processing_settings.do_auto_level ||
			(request.allow_rotation && request.processing_settings.mirror) ||
			request.processing_settings.stamp ||
			text_stamp ||
			(request.image_capture_intent && request.image_capture_intent_uri == null) ||
			request.type != Request.Type.JPEG ||
			request.processing_settings.post_processing;

		if( need_bitmap || request.processing_settings.adjust_levels != Prefs.ADJUST_LEVELS_NONE ) {
			int rotation = getPhotoRotation(orientation);

			if (photo.hasYuv() && alloc == null) {
				alloc = loadYUV(photo, request.yuv_conversion);
			}

			if( request.processing_settings.adjust_levels != Prefs.ADJUST_LEVELS_NONE || request.processing_settings.post_processing ) {
				RenderScript rs = main_activity.getRenderScript();
				ScriptC_auto_levels script = new ScriptC_auto_levels(rs);
				if (alloc == null) {
					bitmap = loadBitmap(photo, request.yuv_conversion, request.processing_settings.stamp || text_stamp);
					alloc = Allocation.createFromBitmap(rs, bitmap);
				}
				
				if (
					request.processing_settings.saturation_r != 1.0f || 
					request.processing_settings.saturation_g != 1.0f ||
					request.processing_settings.saturation_b != 1.0f
				) {
					ScriptC_saturation sat_script = new ScriptC_saturation(rs);
					if (request.processing_settings.saturation_r == request.processing_settings.saturation_g &&
							request.processing_settings.saturation_g == request.processing_settings.saturation_b) {
						if( MyDebug.LOG )
							Log.d(TAG, "Adjusting saturation, simple mode...");
						sat_script.invoke_set_saturation(request.processing_settings.saturation_r);
						sat_script.forEach_saturate(alloc, alloc);
					} else {
						if( MyDebug.LOG )
							Log.d(TAG, "Adjusting saturation, advanced mode...");
						sat_script.invoke_set_saturation_advanced(
							request.processing_settings.saturation_r,
							request.processing_settings.saturation_g,
							request.processing_settings.saturation_b
						);
						sat_script.forEach_saturate_advanced(alloc, alloc);
					}
				}
				
				int [] max_min = new int[2];
				Allocation alloc_histogram = null;
				if (request.processing_settings.histogram_level == 0) {
					Allocation alloc_max_min = Allocation.createSized(rs, Element.U32(rs), 2);

					script.bind_max_min(alloc_max_min);
					script.invoke_init_max_min();

					if (request.processing_settings.adjust_levels == Prefs.ADJUST_LEVELS_LIGHTS_SHADOWS) {
						script.forEach_calc_min_max(alloc);
						if( MyDebug.LOG )
							Log.d(TAG, "time after script.forEach_calc_min_max: " + (System.currentTimeMillis() - time_s));
					} else if (request.processing_settings.adjust_levels == Prefs.ADJUST_LEVELS_LIGHTS) {
						script.forEach_calc_max(alloc);
						if( MyDebug.LOG )
							Log.d(TAG, "time after script.forEach_calc_max: " + (System.currentTimeMillis() - time_s));
					}

					alloc_max_min.copyTo(max_min);
				} else {
					if( MyDebug.LOG )
						Log.d(TAG, "histogram_level = " + request.processing_settings.histogram_level);

					alloc_histogram = Allocation.createSized(rs, Element.U32(rs), 256);
					script.bind_histogram_array(alloc_histogram);
					script.invoke_init_histogram();
					script.forEach_histogram(alloc);
					if( MyDebug.LOG )
						Log.d(TAG, "time after script.forEach_histogram: " + (System.currentTimeMillis() - time_s));

					int [] histogram = new int[256];
					alloc_histogram.copyTo(histogram);
					if( MyDebug.LOG )
						Log.d(TAG, "time after alloc_histogram.copyTo: " + (System.currentTimeMillis() - time_s));
						
					int histogram_height = 0;
					for (int i = 0; i < 256; i++) {
						histogram_height = Math.max(histogram_height, histogram[i]);
					}
					if( MyDebug.LOG )
						Log.d(TAG, "histogram_height = " + histogram_height + ", time after calc: " + (System.currentTimeMillis() - time_s));

					int level = (int)(((double)histogram_height)*request.processing_settings.histogram_level);
					
					for (max_min[0] = 255; max_min[0] > 0; max_min[0]--) {
						if (histogram[max_min[0]] >= level)
							break;
					}
					
					if (request.processing_settings.adjust_levels == Prefs.ADJUST_LEVELS_LIGHTS_SHADOWS) {
						for (max_min[1] = 0; max_min[1] < 256; max_min[1]++) {
							if (histogram[max_min[1]] >= level)
								break;
						}
					}

					if( MyDebug.LOG )
						Log.d(TAG, "time after calc histogram levels: " + (System.currentTimeMillis() - time_s));
				}
				
				if( MyDebug.LOG )
					Log.d(TAG, "min: " + max_min[1] + ", max: " + max_min[0]);

				if (request.processing_settings.adjust_levels == Prefs.ADJUST_LEVELS_LIGHTS_SHADOWS && (max_min[1] == 0 || max_min[1] >= max_min[0]))
					request.processing_settings.adjust_levels = Prefs.ADJUST_LEVELS_LIGHTS;

				if ((request.processing_settings.adjust_levels == Prefs.ADJUST_LEVELS_LIGHTS
						|| request.processing_settings.adjust_levels == Prefs.ADJUST_LEVELS_BOOST)
						&& (max_min[0] == 255 || max_min[0] == 0)) {
					if (!need_bitmap) {
						alloc = null;
						if (bitmap != null) {
							bitmap.recycle();
							bitmap = null;
						}
					}
				} else {
					switch (request.processing_settings.adjust_levels) {
						case Prefs.ADJUST_LEVELS_LIGHTS_SHADOWS:
							script.set_fDivider((float)(max_min[0]-max_min[1]) / 255.0f);
							script.set_fMin((float)max_min[1]);
							script.forEach_auto_ls(alloc, alloc);
							if( MyDebug.LOG )
								Log.d(TAG, "time after script.forEach_auto_ls: " + (System.currentTimeMillis() - time_s));
							break;
						case Prefs.ADJUST_LEVELS_LIGHTS:
							script.set_fDivider((float)max_min[0] / 255.0f);
							script.forEach_auto_l(alloc, alloc);
							if( MyDebug.LOG )
								Log.d(TAG, "time after script.forEach_auto_l: " + (System.currentTimeMillis() - time_s));
							break;
						case Prefs.ADJUST_LEVELS_BOOST:
							double divider = (double)max_min[0];
							double gamma = 1.0d/Math.sqrt(255.0d/(double)max_min[0]);
							if( MyDebug.LOG )
								Log.d(TAG, "gamma: " + gamma);

							int [] histogram = new int[256];
							double level_out;
							for (double level = 0; level < 256; level++) {
								level_out = Math.max(0.0d, Math.pow((level/divider), gamma)*255.0d);
								histogram[(int)level] = (int)Math.min(255.0d, level_out);
							}
							if (alloc_histogram == null) {
								alloc_histogram = Allocation.createSized(rs, Element.U32(rs), 256);
								script.bind_histogram_array(alloc_histogram);
							}
							alloc_histogram.copyFrom(histogram);
							script.forEach_apply_histogram(alloc, alloc);
							if( MyDebug.LOG )
								Log.d(TAG, "time after script.forEach_apply_histogram: " + (System.currentTimeMillis() - time_s));
							break;
					}
				}
			}

			if (alloc != null) {
				if( MyDebug.LOG )
					Log.d(TAG, "saving allocation to bitmap");

				Type type = alloc.getType();
				int width = type.getX();
				int height = type.getY();

				if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() != width || bitmap.getHeight() != height) {
					if( MyDebug.LOG ) {
						if (bitmap == null) {
							Log.d(TAG, "bitmap == null");
						} else {
							Log.d(TAG, "bitmap.isRecycled() == "+(bitmap.isRecycled() ? "true" : "false"));
						}
						Log.d(TAG, "creating new bitmap");
					}
					bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
					if( MyDebug.LOG )
						Log.d(TAG, "Save single image performance: time after creating new bitmap: " + (System.currentTimeMillis() - time_s));
				} else {
					if( MyDebug.LOG )
						Log.d(TAG, "using old bitmap");
				}
				alloc.copyTo(bitmap);

				if( MyDebug.LOG )
					Log.d(TAG, "Save single image performance: time after alloc.copyTo: " + (System.currentTimeMillis() - time_s));
			} else if( need_bitmap ) {
				bitmap = loadBitmap(photo, request.yuv_conversion,  request.processing_settings.stamp || text_stamp);
				if( MyDebug.LOG ) {
					Log.d(TAG, "Save single image performance: time after loadBitmap: " + (System.currentTimeMillis() - time_s));
				}
			}
			if (request.allow_rotation && bitmap != null && (rotation != 0 || request.processing_settings.mirror)) {
				bitmap = rotateBitmap(bitmap, rotation, request.processing_settings.mirror);
				if( MyDebug.LOG ) {
					Log.d(TAG, "Save single image performance: time after rotateBitmap: " + (System.currentTimeMillis() - time_s));
				}
				orientation = ExifInterface.ORIENTATION_NORMAL;
			}
		}
		if (!request.allow_rotation && request.processing_settings.mirror) {
			if( MyDebug.LOG ) {
				Log.d(TAG, "ExifInterface.TAG_ORIENTATION: " + orientation);
			}
			if (orientation == 0)
				orientation++;
			orientation++;
			if (orientation == 9)
				orientation = ExifInterface.ORIENTATION_TRANSPOSE;
		}
		if( request.processing_settings.do_auto_level ) {
			boolean is_front_facing = request.processing_settings.mirror ? !request.is_front_facing : request.is_front_facing;
			bitmap = autoLevel(photo, bitmap, request.processing_settings, is_front_facing);
			if( MyDebug.LOG ) {
				Log.d(TAG, "Save single image performance: time after auto-stabilise: " + (System.currentTimeMillis() - time_s));
			}
		}
		bitmap = stampImage(request, photo, bitmap);
		if( MyDebug.LOG ) {
			Log.d(TAG, "Save single image performance: time after photostamp: " + (System.currentTimeMillis() - time_s));
		}

		File picFile = null;
		Uri saveUri = null; // if non-null, then picFile is a temporary file, which afterwards we should redirect to saveUri
		OutputStream outputStream = null;
		try {
			ExifInterface exif = null;
			boolean exifUnknownCreator = false;

			if (request.processing_settings.save_exif) {
				exif = new ExifInterface(new ByteArrayInputStream(photo.jpeg));
				exifUnknownCreator = true;
				if (bitmap != null) {
					exif.setSize(bitmap.getWidth(), bitmap.getHeight());
				}
				if (exifUnknownCreator)
					setExifDateTime(exif);

				if( MyDebug.LOG )
					Log.d(TAG, "add GPS data to exif");
				setExifGpsData(exif, request.gps_data, exifUnknownCreator);
				if( MyDebug.LOG )
					Log.d(TAG, "Save single image performance: time after adding GPS direction exif info: " + (System.currentTimeMillis() - time_s));

				if ((!request.allow_rotation && request.processing_settings.mirror) || bitmap != null)
					exif.setOrientation(orientation);
					
				setExifMetadata(exif, request.metadata);
				if (share_image) {
					if (!request.metadata.comment_as_file && request.metadata.comment != null && request.metadata.comment.length() > 0) {
						exif.setComment(request.metadata.comment);
					}
				}
			} else {
				exif = new ExifInterface();
				exif.enableExif(false);
			}

			if( request.image_capture_intent ) {
				if( MyDebug.LOG )
					Log.d(TAG, "image_capture_intent");
				if( request.image_capture_intent_uri != null ) {
					// Save the bitmap to the specified URI (use a try/catch block)
					if( MyDebug.LOG )
						Log.d(TAG, "save to: " + request.image_capture_intent_uri);
					saveUri = request.image_capture_intent_uri;
				} else {
					// If the intent doesn't contain an URI, send the bitmap as a parcel
					// (it is a good idea to reduce its size to ~50k pixels before)
					if( MyDebug.LOG )
						Log.d(TAG, "sent to intent via parcel");
					if( bitmap != null ) {
						int width = bitmap.getWidth();
						int height = bitmap.getHeight();
						if( MyDebug.LOG ) {
							Log.d(TAG, "decoded bitmap size " + width + ", " + height);
							Log.d(TAG, "bitmap size: " + width*height*4);
						}
						final int small_size_c = 128;
						if( width > small_size_c ) {
							float scale = ((float)small_size_c)/(float)width;
							if( MyDebug.LOG )
								Log.d(TAG, "scale to " + scale);
							Matrix matrix = new Matrix();
							matrix.postScale(scale, scale);
							Bitmap new_bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
							// careful, as new_bitmap is sometimes not a copy!
							if( new_bitmap != bitmap ) {
								bitmap.recycle();
								bitmap = new_bitmap;
							}
						}
					}
					if( MyDebug.LOG ) {
						if( bitmap != null ) {
							Log.d(TAG, "returned bitmap size " + bitmap.getWidth() + ", " + bitmap.getHeight());
							Log.d(TAG, "returned bitmap size: " + bitmap.getWidth()*bitmap.getHeight()*4);
						}
						else {
							Log.e(TAG, "no bitmap created");
						}
					}
					if( bitmap != null )
						main_activity.setResult(Activity.RESULT_OK, new Intent("inline-data").putExtra("data", bitmap));
					main_activity.finish();
				}
			}
			else if( storageUtils.isUsingSAF() ) {
				saveUri = storageUtils.createOutputMediaFileSAF(request.prefix, filename_suffix, extension, current_date);
			}
			else {
				picFile = storageUtils.createOutputMediaFile(request.prefix, filename_suffix, extension, current_date);
				if( MyDebug.LOG )
					Log.d(TAG, "save to: " + picFile.getAbsolutePath());
			}
			
			if (picFile != null)
				outputStream = new FileOutputStream(picFile);
			else if (saveUri != null)
				outputStream = main_activity.getContentResolver().openOutputStream(saveUri);

			if( outputStream != null ) {
				long time_before_saving = System.currentTimeMillis();
				
				Bitmap.CompressFormat compressFormat = Bitmap.CompressFormat.JPEG;
				if( bitmap != null ) {
					switch(request.type) {
						case PNG:
							compressFormat = Bitmap.CompressFormat.PNG;
							break;
						case WEBP:
							compressFormat = Bitmap.CompressFormat.WEBP;
							break;
					}
				}

				if (exif == null) {
					try {
						if( bitmap != null ) {
							if( MyDebug.LOG )
								Log.d(TAG, "compress bitmap, quality " + request.image_quality);
							bitmap.compress(compressFormat, request.image_quality, outputStream);
						}
						else {
							outputStream.write(photo.jpeg);
						}
					}
					finally {
						outputStream.close();
					}
				} else {
					byte [] image_data = null;
					if( bitmap != null ) {
						ByteArrayOutputStream dataOutputStream = new ByteArrayOutputStream();
						try {
							if( MyDebug.LOG )
								Log.d(TAG, "compress bitmap, quality " + request.image_quality);
							bitmap.compress(compressFormat, request.image_quality, dataOutputStream);
						}
						finally {
							if( MyDebug.LOG ) {
								Log.d(TAG, "Save single image performance: time before dataOutputStream.toByteArray(): " + (System.currentTimeMillis() - time_s));
							}
							image_data = dataOutputStream.toByteArray();
							dataOutputStream.close();
							if( MyDebug.LOG ) {
								Log.d(TAG, "Save single image performance: time after dataOutputStream.toByteArray(): " + (System.currentTimeMillis() - time_s));
							}
						}
					} else {
						image_data = photo.jpeg;
					}
					
					try {
						exif.saveImage(new ByteArrayInputStream(image_data), outputStream);
					} finally {
						outputStream.close();
					}
				}
				if( MyDebug.LOG )
					Log.d(TAG, "saveImageNow saved photo");
				if( MyDebug.LOG ) {
					Log.d(TAG, "Save single image performance: time after saving photo: " + (System.currentTimeMillis() - time_s) + ", time for saving: " + (System.currentTimeMillis() - time_before_saving));
				}
				
				success = true;

				boolean save_comment_file = share_image && request.metadata.comment_as_file && request.metadata.comment != null && request.metadata.comment.length() > 0;
				outputStream = null;

				if( saveUri == null ) { // if saveUri is non-null, then we haven't succeeded until we've copied to the saveUri
					// broadcast for SAF is done later, when we've actually written out the file
					storageUtils.broadcastFile(picFile, true, false, update_thumbnail);
					main_activity.test_last_saved_image = picFile.getAbsolutePath();

					if (save_comment_file)
						outputStream = new FileOutputStream(storageUtils.createOutputMediaFile(request.prefix, filename_suffix, "txt", current_date));
				} else {
					// most Gallery apps don't seem to recognise the SAF-format Uri, so just clear the field
					storageUtils.clearLastMediaScanned();

					/* We still need to broadcastFile for SAF for two reasons:
						1. To call storageUtils.announceUri() to broadcast NEW_PICTURE etc.
						   Whilst in theory we could do this directly, it seems external apps that use such broadcasts typically
						   won't know what to do with a SAF based Uri (e.g, Owncloud crashes!) so better to broadcast the Uri
						   corresponding to the real file, if it exists.
						2. Whilst the new file seems to be known by external apps such as Gallery without having to call media
						   scanner, I've had reports this doesn't happen when saving to external SD cards. So better to explicitly
						   scan.
					*/
					File real_file = storageUtils.getFileFromDocumentUriSAF(saveUri, false);
					if( MyDebug.LOG )
						Log.d(TAG, "real_file: " + real_file);
					if( real_file != null ) {
						if( MyDebug.LOG )
							Log.d(TAG, "broadcast file");
						storageUtils.broadcastFile(real_file, true, false, true, real_file.length());
						main_activity.test_last_saved_image = real_file.getAbsolutePath();
					}
					else if( !request.image_capture_intent ) {
						if( MyDebug.LOG )
							Log.d(TAG, "announce SAF uri");
						// announce the SAF Uri
						// (shouldn't do this for a capture intent - e.g., causes crash when calling from Google Keep)
						storageUtils.announceUri(saveUri, true, false);
					}

					if (save_comment_file)
						outputStream = main_activity.getContentResolver().openOutputStream(storageUtils.createOutputMediaFileSAF(request.prefix, filename_suffix, "txt", current_date));
				}

				if (save_comment_file && outputStream != null) {
					OutputStreamWriter w = new OutputStreamWriter(outputStream);
					try {
						w.write(request.metadata.comment);
					} finally {
						w.close();
						outputStream.close();
					}
				}

				if( request.image_capture_intent ) {
					if( MyDebug.LOG )
						Log.d(TAG, "finish activity due to being called from intent");
					main_activity.setResult(Activity.RESULT_OK);
					main_activity.finish();
				}
			}
		}
		catch(FileNotFoundException e) {
			if( MyDebug.LOG )
				Log.e(TAG, "File not found: " + e.getMessage());
			e.printStackTrace();
			Utils.showToast(null, R.string.failed_to_save_photo);
		}
		catch(IOException e) {
			if( MyDebug.LOG )
				Log.e(TAG, "I/O error writing file: " + e.getMessage());
			e.printStackTrace();
			Utils.showToast(null, R.string.failed_to_save_photo);
		}
		catch(SecurityException e) {
			// received security exception from copyFileToUri()->openOutputStream() from Google Play
			if( MyDebug.LOG )
				Log.e(TAG, "security exception writing file: " + e.getMessage());
			e.printStackTrace();
			Utils.showToast(null, R.string.failed_to_save_photo);
		}

		if( success && saveUri == null ) {
			applicationInterface.addLastImage(picFile, share_image);
		}
		else if( success && storageUtils.isUsingSAF() ){
			applicationInterface.addLastImageSAF(saveUri, share_image);
		}

		// I have received crashes where camera_controller was null - could perhaps happen if this thread was running just as the camera is closing?
		if( success && main_activity.getPreview().getCameraController() != null && update_thumbnail && request.sample_factor != 0 ) {
			// update thumbnail - this should be done after restarting preview, so that the preview is started asap
			CameraController.Size size = main_activity.getPreview().getCameraController().getPictureSize();
			int ratio = (int) Math.ceil((double) size.width / main_activity.getPreview().getView().getWidth());
			int sample_size = Integer.highestOneBit(ratio);
			sample_size *= request.sample_factor;
			if( MyDebug.LOG ) {
				Log.d(TAG, "	picture width: " + size.width);
				Log.d(TAG, "	preview width: " + main_activity.getPreview().getView().getWidth());
				Log.d(TAG, "	ratio		: " + ratio);
				Log.d(TAG, "	sample_size  : " + sample_size);
			}
			Bitmap thumbnail;
			int rotation = getPhotoRotation(orientation);
			boolean mirror = (orientation == ExifInterface.ORIENTATION_FLIP_HORIZONTAL ||
					orientation == ExifInterface.ORIENTATION_FLIP_VERTICAL ||
					orientation == ExifInterface.ORIENTATION_TRANSVERSE ||
					orientation == ExifInterface.ORIENTATION_TRANSPOSE);

			if( bitmap == null ) {
				BitmapFactory.Options options = new BitmapFactory.Options();
				options.inMutable = false;
				if( Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT ) {
					// setting is ignored in Android 5 onwards
					options.inPurgeable = true;
				}
				options.inSampleSize = sample_size;
				thumbnail = BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.length, options);
				// now get the rotation from the Exif data
				if( MyDebug.LOG )
					Log.d(TAG, "rotate thumbnail for exif tags?");
				if (rotation != 0 || mirror) {
					thumbnail = rotateBitmap(thumbnail, rotation, mirror);
				}
			}
			else {
				int width = bitmap.getWidth();
				int height = bitmap.getHeight();
				Matrix matrix = new Matrix();
				float scale = 1.0f / (float)sample_size;
				matrix.postScale(scale, scale);
				if( MyDebug.LOG )
					Log.d(TAG, "	scale: " + scale);
				if( width > 0 && height > 0 ) {
					thumbnail = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
					if (rotation != 0 || mirror) {
						thumbnail = rotateBitmap(thumbnail, rotation, mirror);
					}
				}
				else {
					// received IllegalArgumentException on Google Play from Bitmap.createBitmap; documentation suggests this
					// means width or height are 0
					if( MyDebug.LOG )
						Log.e(TAG, "bitmap has zero width or height?!");
					thumbnail = null;
				}
				// don't need to rotate for exif, as we already did that when creating the bitmap
			}
			if( thumbnail == null ) {
				// received crashes on Google Play suggesting that thumbnail could not be created
				if( MyDebug.LOG )
					Log.e(TAG, "failed to create thumbnail bitmap");
			}
			else {
				final Bitmap thumbnail_f = thumbnail;
				main_activity.runOnUiThread(new Runnable() {
					public void run() {
						applicationInterface.updateThumbnail(thumbnail_f, false);
					}
				});
				if( MyDebug.LOG ) {
					Log.d(TAG, "Save single image performance: time after creating thumbnail: " + (System.currentTimeMillis() - time_s));
				}
			}
		}

		if( bitmap != null ) {
			bitmap.recycle();
		}

		System.gc();
		
		if( MyDebug.LOG ) {
			Log.d(TAG, "Save single image performance: total time: " + (System.currentTimeMillis() - time_s));
		}
		return success;
	}

	/** May be run in saver thread or picture callback thread (depending on whether running in background).
	 */
	@TargetApi(Build.VERSION_CODES.LOLLIPOP)
	private boolean saveSingleRawImageNow(CameraController.Photo photo, String prefix, String suffix, Date current_date,
			GPSData gps_data, Metadata metadata, ExifInterface.DNGSettings dng_settings, boolean update_thumbnail) {
		if( MyDebug.LOG )
			Log.d(TAG, "saveSingleRawImageNow");

		if( Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP ) {
			if( MyDebug.LOG )
				Log.e(TAG, "RAW requires LOLLIPOP or higher");
			return false;
		}

		final long time_ms = System.currentTimeMillis();

		StorageUtils storageUtils = main_activity.getStorageUtils();
		boolean success = false;
		
		DngCreator dngCreator = null;
		ExifInterface exif = null;

		Bitmap thumbnail = null;
		if(update_thumbnail || dng_settings.saveThumbnail) {
			thumbnail = createRawThumbnail(photo.characteristics, photo.captureResult, photo.rawImage);
		}

		if (dng_settings.customDngCreator || Prefs.test_exifinterface_dng) {
			dng_settings.badPixels = photo.badPixels;
			dng_settings.badPixelBlocks = photo.badPixelBlocks;

			exif = new ExifInterface(photo.characteristics, photo.captureResult, photo.rawImage.getWidth(), photo.rawImage.getHeight(), dng_settings);
			exif.setAttribute(ExifInterface.TAG_SOFTWARE, software_name);
			exif.setOrientation(photo.orientation);
			setExifGpsData(exif, gps_data, false);
			setExifMetadata(exif, metadata);
			if (dng_settings.saveThumbnail && thumbnail != null) {
				if( MyDebug.LOG ) {
					Log.e(TAG, "RAW thumbnail available");
					Log.e(TAG, "	width: " + thumbnail.getWidth());
					Log.e(TAG, "	width: " + thumbnail.getHeight());
				}

				exif.setThumbnailBitmap(thumbnail, photo.orientation);
			}
		}
		if (!dng_settings.customDngCreator) {
			dngCreator = new DngCreator(photo.characteristics, photo.captureResult);
			// set fields
			dngCreator.setOrientation(photo.orientation);
			if (gps_data.store_location) {
				dngCreator.setLocation(gps_data.location);
			}
			if (dng_settings.saveThumbnail && thumbnail != null) {
				if( MyDebug.LOG ) {
					Log.e(TAG, "RAW thumbnail available");
					Log.e(TAG, "	width: " + thumbnail.getWidth());
					Log.e(TAG, "	width: " + thumbnail.getHeight());
				}

				dngCreator.setThumbnail(thumbnail);
			}
		}
		if( MyDebug.LOG )
			Log.d(TAG, "time after init dng creator: " + (System.currentTimeMillis() - time_ms));

		OutputStream output = null;
		try {
			File picFile = null;
			Uri saveUri = null;

			if( storageUtils.isUsingSAF() ) {
				saveUri = storageUtils.createOutputMediaFileSAF(prefix, suffix, "dng", current_date);
				if( MyDebug.LOG )
					Log.d(TAG, "saveUri: " + saveUri);
				// When using SAF, we don't save to a temp file first (unlike for JPEGs). Firstly we don't need to modify Exif, so don't
				// need a real file; secondly copying to a temp file is much slower for RAW.
			}
			else {
				picFile = storageUtils.createOutputMediaFile(prefix, suffix, "dng", current_date);
				if( MyDebug.LOG )
					Log.d(TAG, "save to: " + picFile.getAbsolutePath());
			}

			if( picFile != null ) {
				output = new FileOutputStream(picFile);
			}
			else {
				output = main_activity.getContentResolver().openOutputStream(saveUri);
			}
			if (dng_settings.customDngCreator) {
				exif.writeDng(output, photo.rawImage);
				exif = null;
			} else {
				dngCreator.writeImage(output, photo.rawImage);
				dngCreator.close();
				dngCreator = null;
				
				if (Prefs.test_exifinterface_dng) {
					File picFileTest = null;
					Uri saveUriTest = null;

					if( storageUtils.isUsingSAF() ) {
						saveUriTest = storageUtils.createOutputMediaFileSAF(prefix, suffix + "_TEST", "dng", current_date);
					} else {
						picFileTest = storageUtils.createOutputMediaFile(prefix, suffix + "_TEST", "dng", current_date);
					}

					OutputStream outputTest = null;
					if( picFileTest != null ) {
						outputTest = new FileOutputStream(picFileTest);
					} else {
						outputTest = main_activity.getContentResolver().openOutputStream(saveUriTest);
					}
					exif.writeDng(outputTest, photo.rawImage);
					exif = null;
					outputTest.close();
					outputTest = null;

					if( saveUriTest == null ) {
						storageUtils.broadcastFile(picFileTest, true, false, false);
					}
					else {
						picFileTest = storageUtils.getFileFromDocumentUriSAF(saveUriTest, false);
						if( picFileTest != null ) {
							storageUtils.broadcastFile(picFileTest, true, false, false);
						}
						else {
							storageUtils.announceUri(saveUriTest, true, false);
						}
					}
				}
			}
			if( MyDebug.LOG )
				Log.d(TAG, "time after saving dng: " + (System.currentTimeMillis() - time_ms));

			photo.rawImage.close();
			photo.rawImage = null;
			output.close();
			output = null;

			if( saveUri == null ) {
				success = true;
				storageUtils.broadcastFile(picFile, true, false, false);
			}
			else {
				success = true;
				picFile = storageUtils.getFileFromDocumentUriSAF(saveUri, false);
				if( MyDebug.LOG )
					Log.d(TAG, "real_file: " + picFile);
				if( picFile != null ) {
					if( MyDebug.LOG )
						Log.d(TAG, "broadcast file");
					storageUtils.broadcastFile(picFile, true, false, false);
				}
				else {
					if( MyDebug.LOG )
						Log.d(TAG, "announce SAF uri");
					storageUtils.announceUri(saveUri, true, false);
				}
			}

			final MyApplicationInterface applicationInterface = main_activity.getApplicationInterface();
			if( success && saveUri == null ) {
				applicationInterface.addLastImage(picFile, false);
			}
			else if( success && storageUtils.isUsingSAF() ){
				applicationInterface.addLastImageSAF(saveUri, false);
			}

			if (update_thumbnail && thumbnail != null) {
				int rotation = getPhotoRotation(photo.orientation);
				final Bitmap t = rotation == 0 ? thumbnail : rotateBitmap(thumbnail, rotation, false);
/*				if (MyDebug.LOG && t != null) {
					File p = storageUtils.createOutputMediaFile(prefix, "_thumbnail", ".jpg", current_date);
					OutputStream o = new FileOutputStream(p);
					t.compress(Bitmap.CompressFormat.JPEG, 95, o);
					storageUtils.broadcastFile(p, true, false, false);
				}*/
				main_activity.runOnUiThread(new Runnable() {
					public void run() {
						applicationInterface.updateThumbnail(t, false);
					}
				});
			}
		}
		catch(FileNotFoundException e) {
			if( MyDebug.LOG )
				Log.e(TAG, "File not found: " + e.getMessage());
			e.printStackTrace();
			Utils.showToast(null, R.string.failed_to_save_photo_raw);
		}
		catch(IOException e) {
			if( MyDebug.LOG )
				Log.e(TAG, "ioexception writing raw image file");
			e.printStackTrace();
			Utils.showToast(null, R.string.failed_to_save_photo_raw);
		}
		finally {
			if( output != null ) {
				try {
					output.close();
				}
				catch(IOException e) {
					if( MyDebug.LOG )
						Log.e(TAG, "ioexception closing raw output");
					e.printStackTrace();
				}
			}
			if( photo.rawImage != null ) {
				photo.rawImage.close();
				photo.rawImage = null;
			}
			if( dngCreator != null ) {
				dngCreator.close();
				dngCreator = null;
			}
		}

		System.gc();

		return success;
	}

	private Bitmap createRawThumbnail(CameraCharacteristics characteristics, CaptureResult results, Image image) {
		if( MyDebug.LOG ) Log.d(TAG, "createRawThumbnail");
		final long time_ms = System.currentTimeMillis();

		final int maxSize = 256;

		int format = image.getFormat();
		if (format != ImageFormat.RAW_SENSOR) {
			throw new IllegalArgumentException("Unsupported image format " + format);
		}
		int rawWidth = image.getWidth();
		int rawHeight = image.getHeight();
		double rawAspectRatio = ((double)rawWidth) / ((double)rawHeight);
		Image.Plane[] planes = image.getPlanes();
		int pixelStride = planes[0].getPixelStride();
		int rowStride = planes[0].getRowStride();
		ByteBuffer buf = planes[0].getBuffer();
		
		int width = maxSize;
		int height = maxSize;
		int scale = rawWidth / maxSize / 2;
		if (rawWidth > rawHeight) {
			height = (int)(((double)maxSize) / rawAspectRatio);
		} else if (rawWidth < rawHeight) {
			width = (int)(((double)maxSize) * rawAspectRatio);
			scale = rawHeight / maxSize / 2;
		}
		scale *= 2;
		int marginX = ((rawWidth - width * scale) / 4) * 2;
		int marginY = ((rawHeight - height * scale) / 4) * 2;

		byte[][] channels = null;
		byte[] cfaLayout = null;
		Integer cfaEntry = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
		if (cfaEntry != null) {
			switch(cfaEntry) {
				case CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB: {
					channels = new byte[][]{{0, 0}, {2, 0}, {0, 1}, {2, 1}};
					cfaLayout = new byte[]{0, 1, 3};
					break;
				}
				case CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG: {
					channels = new byte[][]{{2, 0}, {0, 0}, {2, 1}, {0, 1}};
					cfaLayout = new byte[]{1, 0, 2};
					break;
				}
				case CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG: {
					channels = new byte[][]{{0, 1}, {0, 0}, {2, 1}, {2, 0}};
					cfaLayout = new byte[]{2, 0, 1};
					break;
				}
				case CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR: {
					channels = new byte[][]{{2, 1}, {2, 0}, {0, 1}, {0, 0}};
					cfaLayout = new byte[]{3, 1, 0};
					break;
				}
			}
		}
		
		Integer whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);

		if (channels != null && whiteLevel != null) {
			BlackLevelPattern blackLevelPattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
			int[] blackLevels = new int[4];
			blackLevelPattern.copyTo(blackLevels, 0);
			float[] whiteLevels = new float[]{
				(float)(whiteLevel-blackLevels[0]),
				(float)(whiteLevel-blackLevels[1]),
				(float)(whiteLevel-blackLevels[2]),
				(float)(whiteLevel-blackLevels[3])
			};

			android.util.Rational[] ncp = results.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
			float[] neutral = new float[]{ncp[0].floatValue(), ncp[1].floatValue(), ncp[2].floatValue()};

			if( MyDebug.LOG ) {
				Log.d(TAG, "	rawWidth: " + rawWidth);
				Log.d(TAG, "	rawHeight: " + rawHeight);
				Log.d(TAG, "	rawAspectRatio: " + rawAspectRatio);
				Log.d(TAG, "	pixelStride: " + pixelStride);
				Log.d(TAG, "	rowStride: " + rowStride);
				Log.d(TAG, "	width: " + width);
				Log.d(TAG, "	height: " + height);
				Log.d(TAG, "	scale: " + scale);
				Log.d(TAG, "	marginX: " + marginX);
				Log.d(TAG, "	marginY: " + marginY);
				Log.d(TAG, "	cfaEntry: " + cfaEntry);
				Log.d(TAG, "	channels: " + Arrays.toString(channels));
				Log.d(TAG, "	cfaLayout: " + Arrays.toString(cfaLayout));
				Log.d(TAG, "	whiteLevel: " + whiteLevel);
				Log.d(TAG, "	blackLevels: " + Arrays.toString(blackLevels));
				Log.d(TAG, "time before calculating levels: " + (System.currentTimeMillis() - time_ms));
			}
			
			float gamma = 1/2.2f;
			byte[][] levels = new byte[cfaLayout.length][1024];
			for (int i = 0; i < 1024; i++) {
				for (int j = 0; j < cfaLayout.length; j++) {
					if (i >= whiteLevel) {
						levels[j][i] = (byte)0xff;
					} else {
						int l = i - blackLevels[cfaLayout[j]];
						if (l <= 0) {
							levels[j][i] = (byte)0x00;
						} else {
							levels[j][i] = (byte)(((int)(Math.pow(Math.min((float)l / whiteLevels[cfaLayout[j]] / neutral[j], 1.0f), gamma) * 255)) & 0xff);
						}
					}
				}
			}

			if( MyDebug.LOG ) Log.d(TAG, "time after calculating levels: " + (System.currentTimeMillis() - time_ms));

			buf.rewind();
			byte[][] rows = new byte[2][rowStride];
			int[] pixel = new int[3];
			
			byte[] pixels = new byte[width * height * 4];
			int curPixel = 0;
			int scaleX = scale << 1;
			for (int y = 0; y < height; y++) {
				buf.position(((marginY + (y * scale)) * rowStride));
				buf.get(rows[0], 0, rowStride);
				buf.get(rows[1], 0, rowStride);
				
				int offset = marginX << 1;
				for (int x = 0; x < width; x++) {
					int pixelOffset = offset + channels[0][0];
					pixel[0] = (rows[channels[0][1]][pixelOffset + 1] << 8) | (rows[channels[0][1]][pixelOffset] & 0xff);

					pixelOffset = offset + channels[1][0];
					pixel[1] = (rows[channels[1][1]][pixelOffset + 1] << 8) | (rows[channels[1][1]][pixelOffset] & 0xff);

					pixelOffset = offset + channels[2][0];
					pixel[1] += (rows[channels[2][1]][pixelOffset + 1] << 8) | (rows[channels[2][1]][pixelOffset] & 0xff);

					pixelOffset = offset + channels[3][0];
					pixel[2] = (rows[channels[3][1]][pixelOffset + 1] << 8) | (rows[channels[3][1]][pixelOffset] & 0xff);

//					if( MyDebug.LOG && (pixel[1] >>> 1) > 1023 ) Log.d(TAG, "pixel " + x + "x" + y + " out of range: " + (pixel[1] >>> 1));

					pixels[curPixel] = levels[0][pixel[0]];
					pixels[curPixel+1] = levels[1][pixel[1] >>> 1];
					pixels[curPixel+2] = levels[2][pixel[2]];
					pixels[curPixel+3] = (byte)0xff;

					curPixel += 4;
					offset += scaleX;
				}
			}
			if( MyDebug.LOG ) Log.d(TAG, "time after processing pixels: " + (System.currentTimeMillis() - time_ms));

			Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
			bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));
			if( MyDebug.LOG ) Log.d(TAG, "time after creating bitmap: " + (System.currentTimeMillis() - time_ms));

			return bitmap;
		}
		
		return null;
	}

	private int getExifOrientation(byte [] jpeg) {
		if( MyDebug.LOG )
			Log.d(TAG, "getExifOrientation");
		InputStream inputStream = null;
		int exif_orientation = ExifInterface.ORIENTATION_UNDEFINED;
		try {
			ExifInterface exif;

			inputStream = new ByteArrayInputStream(jpeg);
			exif = new ExifInterface(inputStream);

			exif_orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);

			if( MyDebug.LOG )
				Log.d(TAG, "	exif orientation: " + exif_orientation);
		}
		catch(IOException exception) {
			if( MyDebug.LOG )
				Log.e(TAG, "exif orientation ioexception");
			exception.printStackTrace();
		}
		catch(NoClassDefFoundError exception) {
			// have had Google Play crashes from new ExifInterface() for Galaxy Ace4 (vivalto3g), Galaxy S Duos3 (vivalto3gvn)
			if( MyDebug.LOG )
				Log.e(TAG, "exif orientation NoClassDefFoundError");
			exception.printStackTrace();
		}
		finally {
			if( inputStream != null ) {
				try {
					inputStream.close();
				}
				catch(IOException e) {
					e.printStackTrace();
				}
				inputStream = null;
			}
		}
		return exif_orientation;
	}

	private int getPhotoRotation(int exif_orientation) {
		int rotation = 0;
		switch (exif_orientation) {
			case ExifInterface.ORIENTATION_ROTATE_180:
			case ExifInterface.ORIENTATION_FLIP_VERTICAL:
				rotation = 180;
				break;
			case ExifInterface.ORIENTATION_ROTATE_90:
			case ExifInterface.ORIENTATION_TRANSVERSE:
				rotation = 90;
				break;
			case ExifInterface.ORIENTATION_ROTATE_270:
			case ExifInterface.ORIENTATION_TRANSPOSE:
				rotation = 270;
				break;
		}
		if( MyDebug.LOG )
			Log.d(TAG, "	rotation: " + rotation);

		return rotation;
	}

	private Bitmap rotateBitmap(Bitmap bitmap, int rotation, boolean mirror) {
		if( MyDebug.LOG )
			Log.d(TAG, "	need to rotate bitmap due to exif orientation tag");
		Matrix m = new Matrix();
		if (rotation == 180) {
			if (mirror) m.postScale(1.0f, -1.0f);
			else m.postScale(-1.0f, -1.0f);
		} else {
			m.setRotate(rotation);
			if (mirror) m.postScale(-1.0f, 1.0f);
		}
		Bitmap rotated_bitmap = Bitmap.createBitmap(bitmap, 0, 0,bitmap.getWidth(), bitmap.getHeight(), m, true);
		if( rotated_bitmap != bitmap ) {
			bitmap.recycle();
			bitmap = rotated_bitmap;
		}
		
		return bitmap;
	}

	private Allocation rotateAllocation(Allocation alloc, int rotation, boolean mirror) {
		if (rotation == 0 && !mirror)
			return alloc;

		RenderScript rs = main_activity.getRenderScript();

		ScriptC_rotate script = new ScriptC_rotate(rs);

		Type type = alloc.getType();
		int width;
		int height;
		
		if (rotation == 90 || rotation == 270) {
			width = type.getY();
			height = type.getX();
			
			byte[] a = new byte[width*height*4];
			alloc.copyTo(a);
			
			Type.Builder builder = new Type.Builder(rs, Element.RGBA_8888(rs));
			builder.setX(width*height);
			alloc = Allocation.createTyped(rs, builder.create(), Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);
			alloc.copyFrom(a);
			alloc.syncAll(Allocation.USAGE_SCRIPT);
		} else {
			width = type.getX();
			height = type.getY();
		}

		Type.Builder builder = new Type.Builder(rs, Element.RGBA_8888(rs));
		builder.setX(width);
		builder.setY(height);
		Allocation alloc_new = Allocation.createTyped(rs, builder.create());
		
		script.set_alloc_in(alloc);
		script.set_max_x(width-1);
		script.set_max_y(height-1);
		
		if (mirror) {
			switch (rotation) {
				case 90:
					script.forEach_rotate90_mirror(alloc_new);
					break;
				case 180:
					script.forEach_rotate180_mirror(alloc_new);
					break;
				case 270:
					script.forEach_rotate270_mirror(alloc_new);
					break;
				default:
					script.forEach_mirror(alloc_new);
			}
		} else {
			switch (rotation) {
				case 90:
					script.forEach_rotate90(alloc_new);
					break;
				case 180:
					script.forEach_rotate180(alloc_new);
					break;
				default:
					script.forEach_rotate270(alloc_new);
			}
		}
		
		return alloc_new;
	}

	private void setExifDateTime(ExifInterface exif) {
		String exif_datetime = exif.getAttribute(ExifInterface.TAG_DATETIME);
		if( exif_datetime != null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "write datetime tags: " + exif_datetime);
			exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exif_datetime);
			exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exif_datetime);
		}
	}

	private void setExifGpsData(ExifInterface exif, GPSData gps_data, boolean unknownCreator) {
		if (gps_data.store_location || gps_data.store_geo_direction || gps_data.store_altitude || gps_data.store_speed) {
			exif.setGpsData(gps_data.location);
			
			if (gps_data.store_location) {
				exif.setGpsLatLong(gps_data.location.getLatitude(), gps_data.location.getLongitude());
			} else if (unknownCreator) {
				exif.removeAttribute(ExifInterface.TAG_GPS_LATITUDE_REF);
				exif.removeAttribute(ExifInterface.TAG_GPS_LATITUDE);
				exif.removeAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF);
				exif.removeAttribute(ExifInterface.TAG_GPS_LONGITUDE);
			}
			
			if (gps_data.store_geo_direction) {
				exif.setGpsDirection(gps_data.geo_direction);
			} else if (unknownCreator) {
				exif.removeAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF);
				exif.removeAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION);
			}
			
			if (gps_data.store_altitude) {
				exif.setGpsAltitude(gps_data.location.getAltitude());
			} else if (unknownCreator) {
				exif.removeAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF);
				exif.removeAttribute(ExifInterface.TAG_GPS_ALTITUDE);
			}

			if (gps_data.store_speed) {
				exif.setGpsSpeed(gps_data.location.getSpeed());
			} else if (unknownCreator) {
				exif.removeAttribute(ExifInterface.TAG_GPS_SPEED_REF);
				exif.removeAttribute(ExifInterface.TAG_GPS_SPEED);
			}
		} else if (unknownCreator) {
			exif.removeAllAttributes(ExifInterface.IFD_TYPE_GPS);
		}
	}

	private void setExifMetadata(ExifInterface exif, Metadata data) {
		exif.setAttribute(ExifInterface.TAG_SOFTWARE, software_name);
		if (data != null) {
			if (data.author != null && data.author.length() > 0) {
				exif.setAttribute(ExifInterface.TAG_ARTIST, data.author);
				exif.setAttribute(ExifInterface.TAG_COPYRIGHT, data.author);
			}
		}
	}

	/** Reads from picFile and writes the contents to saveUri.
	 */
	private void copyFileToUri(Context context, Uri saveUri, File picFile) throws IOException {
		if( MyDebug.LOG ) {
			Log.d(TAG, "copyFileToUri");
			Log.d(TAG, "saveUri: " + saveUri);
			Log.d(TAG, "picFile: " + saveUri);
		}
		InputStream inputStream = null;
		OutputStream realOutputStream = null;
		try {
			inputStream = new FileInputStream(picFile);
			realOutputStream = context.getContentResolver().openOutputStream(saveUri);
			// Transfer bytes from in to out
			byte [] buffer = new byte[1024];
			int len;
			while( (len = inputStream.read(buffer)) > 0 ) {
				realOutputStream.write(buffer, 0, len);
			}
		}
		finally {
			if( inputStream != null ) {
				inputStream.close();
			}
			if( realOutputStream != null ) {
				realOutputStream.close();
			}
		}
	}

	QueueCounter getQueueCounter() {
		return queueCounter;
	}

	// for testing:

	HDRProcessor getHDRProcessor() {
		return hdrProcessor;
	}
}
