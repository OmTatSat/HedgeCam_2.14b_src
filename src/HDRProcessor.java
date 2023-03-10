package com.caddish_hedgehog.hedgecam2;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Environment;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RSInvalidStateException;
import android.renderscript.RenderScript;
import android.renderscript.Script;
import android.renderscript.ScriptIntrinsicBlur;
import android.renderscript.ScriptIntrinsicHistogram;
import android.renderscript.ScriptIntrinsicResize;
import android.renderscript.Type;
import android.support.annotation.RequiresApi;
import android.util.Log;

public class HDRProcessor {
	private static final String TAG = "HedgeCam/HDRProcessor";

	private final Context context;
	private RenderScript rs; // lazily created, so we don't take up resources if application isn't using HDR

	// public for access by testing
	public int [] offsets_x = null;
	public int [] offsets_y = null;
	public int sharp_index = 0;

	private enum HDRAlgorithm {
		HDRALGORITHM_STANDARD,
		HDRALGORITHM_SINGLE_IMAGE
	}

	public enum TonemappingAlgorithm {
		TONEMAPALGORITHM_CLAMP,
		TONEMAPALGORITHM_EXPONENTIAL,
		TONEMAPALGORITHM_REINHARD,
		TONEMAPALGORITHM_FILMIC,
		TONEMAPALGORITHM_ACES,
		TONEMAPALGORITHM_REINHARD_NEW
	}

	public HDRProcessor(Context context) {
		this.context = context;
	}

	/** Given a set of data Xi and Yi, this function estimates a relation between X and Y
	 *  using linear least squares.
	 *  We use it to modify the pixels of images taken at the brighter or darker exposure
	 *  levels, to estimate what the pixel should be at the "base" exposure.
	 *  We estimate as y = parameter_A * x + parameter_B.
	 */
	private static class ResponseFunction {
		float parameter_A;
		float parameter_B;

		/** Computes the response function.
		 * We pass the context, so this inner class can be made static.
		 * @param x_samples List of Xi samples. Must be at least 3 samples.
		 * @param y_samples List of Yi samples. Must be same length as x_samples.
		 * @param weights List of weights. Must be same length as x_samples.
		 */
		ResponseFunction(Context context, int id, List<Double> x_samples, List<Double> y_samples, List<Double> weights) {
			if( MyDebug.LOG )
				Log.d(TAG, "ResponseFunction");

			if( x_samples.size() != y_samples.size() ) {
				if( MyDebug.LOG )
					Log.e(TAG, "unequal number of samples");
				// throw RuntimeException, as this is a programming error
				throw new RuntimeException();
			}
			else if( x_samples.size() != weights.size() ) {
				if( MyDebug.LOG )
					Log.e(TAG, "unequal number of samples");
				// throw RuntimeException, as this is a programming error
				throw new RuntimeException();
			}
			else if( x_samples.size() <= 3 ) {
				if( MyDebug.LOG )
					Log.e(TAG, "not enough samples");
				// throw RuntimeException, as this is a programming error
				throw new RuntimeException();
			}

			// linear Y = AX + B
			boolean done = false;
			double sum_wx = 0.0;
			double sum_wx2 = 0.0;
			double sum_wxy = 0.0;
			double sum_wy = 0.0;
			double sum_w = 0.0;
			for(int i=0;i<x_samples.size();i++) {
				double x = x_samples.get(i);
				double y = y_samples.get(i);
				double w = weights.get(i);
				sum_wx += w * x;
				sum_wx2 += w * x * x;
				sum_wxy += w * x * y;
				sum_wy += w * y;
				sum_w += w;
			}
			if( MyDebug.LOG ) {
				Log.d(TAG, "sum_wx = " + sum_wx);
				Log.d(TAG, "sum_wx2 = " + sum_wx2);
				Log.d(TAG, "sum_wxy = " + sum_wxy);
				Log.d(TAG, "sum_wy = " + sum_wy);
				Log.d(TAG, "sum_w = " + sum_w);
			}
			// need to solve:
			// A . sum_wx + B . sum_w - sum_wy = 0
			// A . sum_wx2 + B . sum_wx - sum_wxy = 0
			// =>
			// A . sum_wx^2 + B . sum_w . sum_wx - sum_wy . sum_wx = 0
			// A . sum_w . sum_wx2 + B . sum_w . sum_wx - sum_w . sum_wxy = 0
			// A ( sum_wx^2 - sum_w . sum_wx2 ) = sum_wy . sum_wx - sum_w . sum_wxy
			// then plug A into:
			// B . sum_w = sum_wy - A . sum_wx
			double A_numer = sum_wy * sum_wx - sum_w * sum_wxy;
			double A_denom = sum_wx * sum_wx - sum_w * sum_wx2;
			if( MyDebug.LOG ) {
				Log.d(TAG, "A_numer = " + A_numer);
				Log.d(TAG, "A_denom = " + A_denom);
			}
			if( Math.abs(A_denom) < 1.0e-5 ) {
				if( MyDebug.LOG )
					Log.e(TAG, "denom too small");
				// will fall back to linear Y = AX
			}
			else {
				parameter_A = (float)(A_numer / A_denom);
				parameter_B = (float)((sum_wy - parameter_A * sum_wx) / sum_w);
				if( MyDebug.LOG ) {
					Log.d(TAG, "parameter_A = " + parameter_A);
					Log.d(TAG, "parameter_B = " + parameter_B);
				}
				// we don't want a function that is not monotonic, or can be negative!
				if( parameter_A < 1.0e-5 ) {
					if( MyDebug.LOG )
						Log.e(TAG, "parameter A too small or negative: " + parameter_A);
				}
				else if( parameter_B < 1.0e-5 ) {
					if( MyDebug.LOG )
						Log.e(TAG, "parameter B too small or negative: " + parameter_B);
				}
				else {
					done = true;
				}
			}
			
			if( !done ) {
				if( MyDebug.LOG )
					Log.e(TAG, "falling back to linear Y = AX");
				// linear Y = AX
				double numer = 0.0;
				double denom = 0.0;
				for(int i=0;i<x_samples.size();i++) {
					double x = x_samples.get(i);
					double y = y_samples.get(i);
					double w = weights.get(i);
					numer += w*x*y;
					denom += w*x*x;
				}
				if( MyDebug.LOG ) {
					Log.d(TAG, "numer = " + numer);
					Log.d(TAG, "denom = " + denom);
				}
				
				if( denom < 1.0e-5 ) {
					if( MyDebug.LOG )
						Log.e(TAG, "denom too small");
					parameter_A = 1.0f;
				}
				else {
					parameter_A = (float)(numer / denom);
					// we don't want a function that is not monotonic!
					if( parameter_A < 1.0e-5 ) {
						if( MyDebug.LOG )
							Log.e(TAG, "parameter A too small or negative: " + parameter_A);
						parameter_A = 1.0e-5f;
					}
				}
				parameter_B = 0.0f;
			}

			if( MyDebug.LOG ) {
				Log.d(TAG, "parameter_A = " + parameter_A);
				Log.d(TAG, "parameter_B = " + parameter_B);
			}

			if( MyDebug.LOG ) {
				// log samples to a CSV file
				File file = new File(Environment.getExternalStorageDirectory().getPath() + "/com.caddish_hedgehog.hedgecam2.hdr_samples_" + id + ".csv");
				if( file.exists() ) {
					if( !file.delete() ) {
						// keep FindBugs happy by checking return argument
						Log.e(TAG, "failed to delete csv file");
					}
				}
				FileWriter writer = null;
				try {
					writer = new FileWriter(file);
					//writer.append("Parameter," + parameter + "\n");
					writer.append("Parameters," + parameter_A + "," + parameter_B + "\n");
					writer.append("X,Y,Weight\n");
					for(int i=0;i<x_samples.size();i++) {
						//Log.d(TAG, "log: " + i + " / " + x_samples.size());
						double x = x_samples.get(i);
						double y = y_samples.get(i);
						double w = weights.get(i);
						writer.append(x + "," + y + "," + w + "\n");
					}
				}
				catch (IOException e) {
					Log.e(TAG, "failed to open csv file");
					e.printStackTrace();
				}
				finally {
					try {
						if( writer != null )
							writer.close();
					}
					catch (IOException e) {
						Log.e(TAG, "failed to close csv file");
						e.printStackTrace();
					}
				}
				MediaScannerConnection.scanFile(context, new String[] { file.getAbsolutePath() }, null, null);
			}
		}
	}

	public interface SortCallback {
		/** This is called when the sort order for the input bitmaps is known, from darkest to brightest.
		 * @param sort_order A list of length equal to the supplied bitmaps.size(). sort_order.get(i)
		 *				   returns the index in the bitmaps array of the i-th image after sorting,
		 *				   where i==0 represents the darkest image, and i==bitmaps.size()-1 is the
		 *				   brightest.
		 */
		void sortOrder(List<Integer> sort_order);
	}

	/** Converts a list of bitmaps into a HDR image, which is then tonemapped to a final RGB image.
	 * @param bitmaps The list of bitmaps, which should be in order of increasing brightness (exposure).
	 *				Currently only supports a list of either 1 image, or 3 images (the 2nd should be
	 *				at the desired exposure level for the resultant image).
	 *				The bitmaps must all be the same resolution.
	 * @param assume_sorted If true, the input bitmaps should be sorted in order from darkest to brightest
	 *					  exposure. If false, the function will automatically resort.
	 * @param sort_cb	   If assume_sorted is false and this is non-null, sort_cb.sortOrder() will be
	 *					  called to indicate the sort order when this is known.
	 * @param hdr_alpha	 A value from 0.0f to 1.0f indicating the "strength" of the HDR effect. Specifically,
	 *					  this controls the level of the local contrast enhancement done in adjustHistogram().
	 * @param n_tiles	   A value of 1 or greater indicating how local the contrast enhancement algorithm should be.
	 * @param tonemapping_algorithm
	 *					  Algorithm to use for tonemapping (if multiple images are received).
	 */
	@RequiresApi(api = Build.VERSION_CODES.KITKAT)
	public Allocation processHDR(List<Bitmap> bitmaps, boolean assume_sorted, SortCallback sort_cb, boolean align, boolean crop_aligned, float hdr_alpha, int n_tiles, float unsharp_mask, int unsharp_mask_radius, TonemappingAlgorithm tonemapping_algorithm, boolean deghost) throws HDRProcessorException {
		if( MyDebug.LOG )
			Log.d(TAG, "processHDR");
		int n_bitmaps = bitmaps.size();
		if( n_bitmaps != 1 && n_bitmaps != 3 ) {
			if( MyDebug.LOG )
				Log.e(TAG, "n_bitmaps not supported: " + n_bitmaps);
			throw new HDRProcessorException(HDRProcessorException.INVALID_N_IMAGES);
		}
		for(int i=1;i<n_bitmaps;i++) {
			if( bitmaps.get(i).getWidth() != bitmaps.get(0).getWidth() ||
				bitmaps.get(i).getHeight() != bitmaps.get(0).getHeight() ) {
				if( MyDebug.LOG ) {
					Log.e(TAG, "bitmaps not of same resolution");
					for(int j=0;j<n_bitmaps;j++) {
						Log.e(TAG, "bitmaps " + j + " : " + bitmaps.get(j).getWidth() + " x " + bitmaps.get(j).getHeight());
					}
				}
				throw new HDRProcessorException(HDRProcessorException.UNEQUAL_SIZES);
			}
		}
		
		final HDRAlgorithm algorithm = n_bitmaps == 1 ? HDRAlgorithm.HDRALGORITHM_SINGLE_IMAGE : HDRAlgorithm.HDRALGORITHM_STANDARD;
		
		switch( algorithm ) {
		case HDRALGORITHM_SINGLE_IMAGE:
			if( !assume_sorted && sort_cb != null ) {
				List<Integer> sort_order = new ArrayList<>();
				sort_order.add(0);
				sort_cb.sortOrder(sort_order);
			}
			return processSingleImage(bitmaps, hdr_alpha, n_tiles, unsharp_mask, unsharp_mask_radius);
		case HDRALGORITHM_STANDARD:
			return processHDRCore(bitmaps, assume_sorted, sort_cb, align, crop_aligned, hdr_alpha, n_tiles, unsharp_mask, unsharp_mask_radius, tonemapping_algorithm, deghost);
		default:
			if( MyDebug.LOG )
				Log.e(TAG, "unknown algorithm " + algorithm);
			// throw RuntimeException, as this is a programming error
			throw new RuntimeException();
		}
	}

	/** Creates a ResponseFunction to estimate how pixels from the in_bitmap should be adjusted to
	 *  match the exposure level of out_bitmap.
	 *  The supplied offsets offset_x, offset_y give the offset for in_bitmap as computed by
	 *  autoAlignment().
	 */
	private ResponseFunction createFunctionFromBitmaps(int id, Bitmap in_bitmap, Bitmap out_bitmap, int offset_x, int offset_y) {
		if( MyDebug.LOG )
			Log.d(TAG, "createFunctionFromBitmaps");
		List<Double> x_samples = new ArrayList<>();
		List<Double> y_samples = new ArrayList<>();
		List<Double> weights = new ArrayList<>();

		final int n_samples_c = 100;
		final int n_w_samples = (int)Math.sqrt(n_samples_c);
		final int n_h_samples = n_samples_c/n_w_samples;

		double avg_in = 0.0;
		double avg_out = 0.0;
		for(int y=0;y<n_h_samples;y++) {
			double alpha = ((double)y+1.0) / ((double)n_h_samples+1.0);
			int y_coord = (int)(alpha * in_bitmap.getHeight());
			for(int x=0;x<n_w_samples;x++) {
				double beta = ((double)x+1.0) / ((double)n_w_samples+1.0);
				int x_coord = (int)(beta * in_bitmap.getWidth());
				/*if( MyDebug.LOG )
					Log.d(TAG, "sample response from " + x_coord + " , " + y_coord);*/
				if( x_coord + offset_x < 0 || x_coord + offset_x >= in_bitmap.getWidth() || y_coord + offset_y < 0 || y_coord + offset_y >= in_bitmap.getHeight() ) {
					continue;
				}
				int in_col = in_bitmap.getPixel(x_coord + offset_x, y_coord + offset_y);
				int out_col = out_bitmap.getPixel(x_coord, y_coord);
				double in_value = averageRGB(in_col);
				double out_value = averageRGB(out_col);
				avg_in += in_value;
				avg_out += out_value;
				x_samples.add(in_value);
				y_samples.add(out_value);
			}
		}
		if( x_samples.size() == 0 ) {
			Log.e(TAG, "no samples for response function!");
			// shouldn't happen, but could do with a very large offset - just make up a dummy sample
			double in_value = 255.0;
			double out_value = 255.0;
			avg_in += in_value;
			avg_out += out_value;
			x_samples.add(in_value);
			y_samples.add(out_value);
		}
		avg_in /= x_samples.size();
		avg_out /= x_samples.size();
		boolean is_dark_exposure = avg_in < avg_out;
		if( MyDebug.LOG ) {
			Log.d(TAG, "avg_in: " + avg_in);
			Log.d(TAG, "avg_out: " + avg_out);
			Log.d(TAG, "is_dark_exposure: " + is_dark_exposure);
		}
		{
			// calculate weights
			double min_value = x_samples.get(0);
			double max_value = x_samples.get(0);
			for(int i=1;i<x_samples.size();i++) {
				double value = x_samples.get(i);
				if( value < min_value )
					min_value = value;
				if( value > max_value )
					max_value = value;
			}
			double med_value = 0.5*(min_value + max_value);
			if( MyDebug.LOG ) {
				Log.d(TAG, "min_value: " + min_value);
				Log.d(TAG, "max_value: " + max_value);
				Log.d(TAG, "med_value: " + med_value);
			}
			double min_value_y = y_samples.get(0);
			double max_value_y = y_samples.get(0);
			for(int i=1;i<y_samples.size();i++) {
				double value = y_samples.get(i);
				if( value < min_value_y )
					min_value_y = value;
				if( value > max_value_y )
					max_value_y = value;
			}
			double med_value_y = 0.5*(min_value_y + max_value_y);
			if( MyDebug.LOG ) {
				Log.d(TAG, "min_value_y: " + min_value_y);
				Log.d(TAG, "max_value_y: " + max_value_y);
				Log.d(TAG, "med_value_y: " + med_value_y);
			}
			for(int i=0;i<x_samples.size();i++) {
				double value = x_samples.get(i);
				double value_y = y_samples.get(i);
				if( is_dark_exposure ) {
					// for dark exposure, also need to worry about the y values (which will be brighter than x) being overexposed
					double weight = (value <= med_value) ? value - min_value : max_value - value;
					double weight_y = (value_y <= med_value_y) ? value_y - min_value_y : max_value_y - value_y;
					if( weight_y < weight )
						weight = weight_y;
					weights.add(weight);
				}
				else {
					double weight = (value <= med_value) ? value - min_value : max_value - value;
					weights.add(weight);
				}
			}
		}
		
		return new ResponseFunction(context, id, x_samples, y_samples, weights);
	}

	/** Calculates average of RGB values for the supplied color.
	 */
	private double averageRGB(int color) {
		int r = (color & 0xFF0000) >> 16;
		int g = (color & 0xFF00) >> 8;
		int b = (color & 0xFF);
		return (r + g + b)/3.0;
		//return 0.27*r + 0.67*g + 0.06*b;
	}

	/** Core implementation of HDR algorithm.
	 *  Requires Android 4.4 (API level 19, Kitkat), due to using Renderscript without the support libraries.
	 *  And we now need Android 5.0 (API level 21, Lollipop) for forEach_Dot with LaunchOptions.
	 *  Using the support libraries (set via project.properties renderscript.support.mode) would bloat the APK
	 *  by around 1799KB! We don't care about pre-Android 4.4 (HDR requires CameraController2 which requires
	 *  Android 5.0 anyway; even if we later added support for CameraController1, we can simply say HDR requires
	 *  Android 5.0).
	 */
	@RequiresApi(api = Build.VERSION_CODES.KITKAT)
	private Allocation processHDRCore(List<Bitmap> bitmaps, boolean assume_sorted, SortCallback sort_cb, boolean align, boolean crop_aligned,
			float hdr_alpha, int n_tiles, float unsharp_mask, int unsharp_mask_radius, TonemappingAlgorithm tonemapping_algorithm, boolean deghost) {
		if( MyDebug.LOG )
			Log.d(TAG, "processHDRCore");
		
		long time_s = System.currentTimeMillis();
		
		int n_bitmaps = bitmaps.size();
		int width = bitmaps.get(0).getWidth();
		int height = bitmaps.get(0).getHeight();
		ResponseFunction [] response_functions = new ResponseFunction[n_bitmaps]; // ResponseFunction for each image (the ResponseFunction entry can be left null to indicate the Identity)
		offsets_x = new int[n_bitmaps];
		offsets_y = new int[n_bitmaps];
		/*int [][] buffers = new int[n_bitmaps][];
		for(int i=0;i<n_bitmaps;i++) {
			buffers[i] = new int[bm.getWidth()];
		}*/
		//float [] hdr = new float[3];
		//int [] rgb = new int[3];
		
		initRenderScript();
		if( MyDebug.LOG )
			Log.d(TAG, "### time after creating renderscript: " + (System.currentTimeMillis() - time_s));
		// create allocations
		Allocation [] allocations = new Allocation[n_bitmaps];
		for(int i=0;i<n_bitmaps;i++) {
			allocations[i] = Allocation.createFromBitmap(rs, bitmaps.get(i));
		}
		if( MyDebug.LOG )
			Log.d(TAG, "### time after creating allocations from bitmaps: " + (System.currentTimeMillis() - time_s));

		// perform auto-alignment
		// if assume_sorted if false, this function will also sort the allocations and bitmaps from darkest to brightest.
		BrightnessDetails brightnessDetails = autoAlignment(offsets_x, offsets_y, allocations, width, height, bitmaps, 1, assume_sorted, sort_cb, align, true, false, time_s);
		int median_brightness = brightnessDetails.median_brightness;
		if( MyDebug.LOG ) {
			Log.d(TAG, "### time after autoAlignment: " + (System.currentTimeMillis() - time_s));
			Log.d(TAG, "median_brightness: " + median_brightness);
		}

		// compute response_functions
		final int base_bitmap = (n_bitmaps-1)/2; // index of the bitmap with the base exposure and offsets
		for(int i=0;i<n_bitmaps;i++) {
			ResponseFunction function = null;
			if( i != base_bitmap ) {
				function = createFunctionFromBitmaps(i, bitmaps.get(i), bitmaps.get(base_bitmap), offsets_x[i], offsets_y[i]);
			}
			response_functions[i] = function;
		}
		if( MyDebug.LOG )
			Log.d(TAG, "### time after creating response functions: " + (System.currentTimeMillis() - time_s));

		boolean need_crop = false;
		if (crop_aligned) {
			Rect crop = new Rect(0, 0, width, height);

			if (offsets_x[0] > 0) {
				need_crop = true;
				crop.right = Math.min(crop.right, width-offsets_x[0]);
			} else if (offsets_x[0] < 0) {
				need_crop = true;
				crop.left = Math.max(crop.left, -offsets_x[0]);
			}
			if (offsets_y[0] > 0) {
				need_crop = true;
				crop.bottom = Math.min(crop.bottom, height-offsets_y[0]);
			} else if (offsets_y[0] < 0) {
				need_crop = true;
				crop.top = Math.max(crop.top, -offsets_y[0]);
			}

			if (offsets_x[2] > 0) {
				need_crop = true;
				crop.right = Math.min(crop.right, width-offsets_x[2]);
			} else if (offsets_x[2] < 0) {
				need_crop = true;
				crop.left = Math.max(crop.left, -offsets_x[2]);
			}
			if (offsets_y[2] > 0) {
				need_crop = true;
				crop.bottom = Math.min(crop.bottom, height-offsets_y[2]);
			} else if (offsets_y[2] < 0) {
				need_crop = true;
				crop.top = Math.max(crop.top, -offsets_y[2]);
			}
			
			if (need_crop) {
				offsets_x[1] = crop.left;
				offsets_y[1] = crop.top;

				offsets_x[0] += crop.left;
				offsets_y[0] += crop.top;

				offsets_x[2] += crop.left;
				offsets_y[2] += crop.top;
				
				width = crop.right-crop.left;
				height = crop.bottom-crop.top;

				if( MyDebug.LOG ) {
					Log.d(TAG, "bitmap 0 offsets: x=" + offsets_x[0] + ", y="+offsets_y[0]);
					Log.d(TAG, "bitmap 1 offsets: x=" + offsets_x[1] + ", y="+offsets_y[1]);
					Log.d(TAG, "bitmap 2 offsets: x=" + offsets_x[2] + ", y="+offsets_y[2]);
					Log.d(TAG, "cropping rectangle: left=" + crop.left + ", top=" + crop.top + ", right=" + crop.right + ", bottom=" + crop.bottom);
					Log.d(TAG, "new resolution : " + width + "x" + height);
				}
			}
		}

		// create RenderScript
		ScriptC_process_hdr processHDRScript = new ScriptC_process_hdr(rs);

		// set allocations
		processHDRScript.set_bitmap0(allocations[0]);
		processHDRScript.set_bitmap1(allocations[1]);
		processHDRScript.set_bitmap2(allocations[2]);

		// set offsets
		processHDRScript.set_offset_x0(offsets_x[0]);
		processHDRScript.set_offset_y0(offsets_y[0]);

		processHDRScript.set_offset_x1(offsets_x[1]);
		processHDRScript.set_offset_y1(offsets_y[1]);

		processHDRScript.set_offset_x2(offsets_x[2]);
		processHDRScript.set_offset_y2(offsets_y[2]);

		// set response functions
		processHDRScript.set_parameter_A0( response_functions[0].parameter_A );
		processHDRScript.set_parameter_B0( response_functions[0].parameter_B );
		// no response function for middle image
		processHDRScript.set_parameter_A2( response_functions[2].parameter_A );
		processHDRScript.set_parameter_B2( response_functions[2].parameter_B );

		// set globals
		processHDRScript.set_deghost(deghost ? 1 : 0);

		// set tonemapping algorithm
		switch( tonemapping_algorithm ) {
			case TONEMAPALGORITHM_CLAMP:
				if( MyDebug.LOG )
					Log.d(TAG, "tonemapping algorithm: clamp");
				processHDRScript.set_tonemap_algorithm( processHDRScript.get_tonemap_algorithm_clamp_c() );
				break;
			case TONEMAPALGORITHM_EXPONENTIAL:
				if( MyDebug.LOG )
					Log.d(TAG, "tonemapping algorithm: exponential");
				processHDRScript.set_tonemap_algorithm( processHDRScript.get_tonemap_algorithm_exponential_c() );
				break;
			case TONEMAPALGORITHM_REINHARD:
				if( MyDebug.LOG )
					Log.d(TAG, "tonemapping algorithm: reinhard");
				processHDRScript.set_tonemap_algorithm( processHDRScript.get_tonemap_algorithm_reinhard_c() );
				break;
			case TONEMAPALGORITHM_FILMIC:
				if( MyDebug.LOG )
					Log.d(TAG, "tonemapping algorithm: filmic");
				processHDRScript.set_tonemap_algorithm( processHDRScript.get_tonemap_algorithm_filmic_c() );
				break;
			case TONEMAPALGORITHM_ACES:
				if( MyDebug.LOG )
					Log.d(TAG, "tonemapping algorithm: aces");
				processHDRScript.set_tonemap_algorithm( processHDRScript.get_tonemap_algorithm_aces_c() );
				break;
			case TONEMAPALGORITHM_REINHARD_NEW:
				if( MyDebug.LOG )
					Log.d(TAG, "tonemapping algorithm: reinhard new");
				processHDRScript.set_tonemap_algorithm( processHDRScript.get_tonemap_algorithm_reinhard_new_c() );
				break;
		}

		float max_possible_value = response_functions[0].parameter_A * 255 + response_functions[0].parameter_B;
		if( MyDebug.LOG )
			Log.d(TAG, "max_possible_value: " + max_possible_value);
		if( max_possible_value < 255.0f ) {
			max_possible_value = 255.0f; // don't make dark images too bright, see below about linear_scale for more details
			if( MyDebug.LOG )
				Log.d(TAG, "clamp max_possible_value to: " + max_possible_value);
		}

		//hdr_alpha = 0.0f; // test
		//final float tonemap_scale_c = avg_luminance / 0.8f; // lower values tend to result in too dark pictures; higher values risk over exposed bright areas
		//final float tonemap_scale_c = 255.0f;
		//final float tonemap_scale_c = 255.0f - median_brightness;
		float tonemap_scale_c = 255.0f;
		if( median_brightness <= 0 )
			median_brightness = 1;
		if( MyDebug.LOG )
			Log.d(TAG, "median_brightness: " + median_brightness);
		int median_target = Math.min(119, 2*median_brightness);
		median_target = Math.max(median_brightness, median_target); // don't make median darker
		if( MyDebug.LOG ) {
			Log.d(TAG, "median_target: " + median_target);
			Log.d(TAG, "compare: " + 255.0f / max_possible_value);
			Log.d(TAG, "to: " + (((float)median_target)/(float)median_brightness + median_target / 255.0f - 1.0f));
		}
		if( 255.0f / max_possible_value < ((float)median_target)/(float)median_brightness + median_target / 255.0f - 1.0f) {
			// For Reinhard tonemapping:
			// As noted below, we have f(V) = V.S / (V+C), where V is the HDR value, C is tonemap_scale_c
			// and S = (Vmax + C)/Vmax (see below)
			// Ideally we try to choose C such that we map median value M to target T:
			// f(M) = T
			// => T = M . (Vmax + C) / (Vmax . (M + C))
			// => (T/M).(M + C) = (Vmax + C) / Vmax = 1 + C/Vmax
			// => C . ( T/M - 1/Vmax ) = 1 - T
			// => C = (1-T) / (T/M - 1/Vmax)
			// Since we want C <= 1, we must have:
			// 1-T <= T/M - 1/Vmax
			// => 1/Vmax <= T/M + T - 1
			// If this isn't the case, we set C to 1 (to preserve the median as close as possible).
			// Note that if we weren't doing the linear scaling below, this would reduce to choosing
			// C = M(1-T)/T. We also tend to that as max_possible_value tends to infinity. So even though
			// we only sometimes enter this case, it's important for cases where max_possible_value
			// might be estimated too large (also consider that if we ever support more than 3 images,
			// we'd risk having too large values).
			// If T=M, then this simplifies to C = 1-M.
			// I've tested that using "C = 1-M" always (and no linear scaling) also gives good results:
			// much better compared to Open Camera 1.39, though not quite as good as doing both this
			// and linear scaling (testHDR18, testHDR26, testHDR32 look too grey and/or bright).
			final float tonemap_denom = ((float)median_target)/(float)median_brightness - (255.0f / max_possible_value);
			if( MyDebug.LOG )
				Log.d(TAG, "tonemap_denom: " + tonemap_denom);
			if( tonemap_denom != 0.0f ) // just in case
				tonemap_scale_c = (255.0f - median_target) / tonemap_denom;
			//throw new RuntimeException(); // test
		}
		// Higher tonemap_scale_c values means darker results from the Reinhard tonemapping.
		// Colours brighter than 255-tonemap_scale_c will be made darker, colours darker than 255-tonemap_scale_c will be made brighter
		// (tonemap_scale_c==255 means therefore that colours will only be made darker).
		if( MyDebug.LOG )
			Log.d(TAG, "tonemap_scale_c: " + tonemap_scale_c);
		processHDRScript.set_tonemap_scale(tonemap_scale_c);

		// algorithm specific parameters
		switch( tonemapping_algorithm ) {
			case TONEMAPALGORITHM_EXPONENTIAL:
			{
				// The basic algorithm is f(V) = 1 - exp( - E * V ), where V is the HDR value, E is a
				// constant. This maps [0, infinity] to [0, 1]. However we have an estimate of the maximum
				// possible value, Vmax, so we can set a linear scaling S so that [0, Vmax] maps to [0, 1]
				// f(V) = S . (1 - exp( - E * V ))
				// so 1 = S . (1 - exp( - E * Vmax ))
				// => S = 1 / (1 - exp( - E * Vmax ))
				// Note that Vmax should be set to a minimum of 255, else we'll make darker images brighter.
				float E = processHDRScript.get_exposure();
				float linear_scale = (float)(1.0 / (1.0 - Math.exp(-E * max_possible_value / 255.0)));
				if( MyDebug.LOG )
					Log.d(TAG, "linear_scale: " + linear_scale);
				processHDRScript.set_linear_scale(linear_scale);
				break;
			}
			case TONEMAPALGORITHM_REINHARD:
			case TONEMAPALGORITHM_REINHARD_NEW:
			{
				// The basic algorithm is f(V) = V / (V+C), where V is the HDR value, C is tonemap_scale_c
				// This was used until Open Camera 1.39, but has the problem of making images too dark: it
				// maps [0, infinity] to [0, 1], but since in practice we never have very large V values, we
				// won't use the full [0, 1] range. So we apply a linear scale S:
				// f(V) = V.S / (V+C)
				// S is chosen such that the maximum possible value, Vmax, maps to 1. So:
				// 1 = Vmax . S / (Vmax + C)
				// => S = (Vmax + C)/Vmax
				// Note that we don't actually know the maximum HDR value, but instead we estimate it with
				// max_possible_value, which gives the maximum value we'd have if even the darkest image was
				// 255.0.
				// Note that if max_possible_value was less than 255, we'd end up scaling a max value less than
				// 1, to [0, 1], i.e., making dark images brighter, which we don't want, which is why above we
				// set max_possible_value to a minimum of 255. In practice, this is unlikely to ever happen
				// since max_possible_value is calculated as a maximum possible based on the response functions
				// (as opposed to the real brightest HDR value), so even for dark photos we'd expect to have
				// max_possible_value >= 255.
				// Note that the original Reinhard tonemapping paper describes a non-linear scaling by (1 + CV/Vmax^2),
				// though this is poorer performance (in terms of calculation time).
				float linear_scale = (max_possible_value + tonemap_scale_c) / max_possible_value;
				if( MyDebug.LOG )
					Log.d(TAG, "linear_scale: " + linear_scale);
				processHDRScript.set_linear_scale(linear_scale);
				break;
			}
			case TONEMAPALGORITHM_FILMIC:
			{
				// For filmic, we have f(V) = U(EV) / U(W), where V is the HDR value, U is a function.
				// We want f(Vmax) = 1, so EVmax = W
				float E = processHDRScript.get_filmic_exposure_bias();
				float W = E * max_possible_value;
				if( MyDebug.LOG )
					Log.d(TAG, "filmic W: " + W);
				processHDRScript.set_W(W);
				break;
			}
		}

		if( MyDebug.LOG )
			Log.d(TAG, "call processHDRScript");
		Allocation output_allocation;
		if (need_crop) {
			if( MyDebug.LOG )
				Log.d(TAG, "### time before creating output_allocation: " + (System.currentTimeMillis() - time_s));
			Type.Builder builder = new Type.Builder(rs, Element.RGBA_8888(rs));
			builder.setX(width);
			builder.setY(height);
			output_allocation = Allocation.createTyped(rs, builder.create());
		} else {
			output_allocation = allocations[base_bitmap];
		}

		if( MyDebug.LOG )
			Log.d(TAG, "### time before processHDRScript: " + (System.currentTimeMillis() - time_s));
		processHDRScript.forEach_hdr(output_allocation);
		if( MyDebug.LOG )
			Log.d(TAG, "### time after processHDRScript: " + (System.currentTimeMillis() - time_s));

		if( MyDebug.LOG )
			Log.d(TAG, "release bitmaps");
		// bitmaps.get(base_bitmap) will store HDR image, so free up the rest of the memory asap - we no longer need the remaining bitmaps
		for(int i=0;i<bitmaps.size();i++) {
			if (i != base_bitmap) {
				Bitmap bitmap = bitmaps.get(i);
				if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ) {
					// Buggy KitKat crashes here
					bitmap.recycle();
				} else {
					bitmaps.set(i, null);
				}
			}
		}
/*		if( adjust_histogram != 0.0f ) {
			adjustHistogram(output_allocation, output_allocation, width, height, 0.5f, time_s);
			if( MyDebug.LOG )
				Log.d(TAG, "### time after adjustHistogram: " + (System.currentTimeMillis() - time_s));
		}*/
		if( unsharp_mask != 0.0f ) {
			unsharpMask(output_allocation, width, height, unsharp_mask, unsharp_mask_radius, time_s);
			if( MyDebug.LOG )
				Log.d(TAG, "### time after unsharpMask: " + (System.currentTimeMillis() - time_s));
		}
		if( hdr_alpha != 0.0f ) {
			adjustHistogramLocal(output_allocation, output_allocation, width, height, hdr_alpha, n_tiles, time_s);
			if( MyDebug.LOG )
				Log.d(TAG, "### time after adjustHistogramLocal: " + (System.currentTimeMillis() - time_s));
		}

		if( MyDebug.LOG )
			Log.d(TAG, "### time for processHDRCore: " + (System.currentTimeMillis() - time_s));
		
		return output_allocation;
	}

	@RequiresApi(api = Build.VERSION_CODES.KITKAT)
	private Allocation processSingleImage(List<Bitmap> bitmaps, float hdr_alpha, int n_tiles, float unsharp_mask, int unsharp_mask_radius) {
		if (MyDebug.LOG)
			Log.d(TAG, "processSingleImage");

		long time_s = System.currentTimeMillis();

		int width = bitmaps.get(0).getWidth();
		int height = bitmaps.get(0).getHeight();

		initRenderScript();
		if (MyDebug.LOG)
			Log.d(TAG, "### time after creating renderscript: " + (System.currentTimeMillis() - time_s));

		// create allocation
		Allocation allocation = Allocation.createFromBitmap(rs, bitmaps.get(0));

		Allocation output_allocation = allocation;

		/*{
			// brighten?
			int [] histo = computeHistogram(allocation, false, false);
			HistogramInfo histogramInfo = getHistogramInfo(histo);
			int median_brightness = histogramInfo.median_brightness;
			int max_brightness = histogramInfo.max_brightness;
			if( MyDebug.LOG )
				Log.d(TAG, "### time after computeHistogram: " + (System.currentTimeMillis() - time_s));
			int median_target = getMedianTarget(median_brightness, 2);
			if( MyDebug.LOG ) {
				Log.d(TAG, "median brightness: " + median_brightness);
				Log.d(TAG, "median target: " + median_target);
				Log.d(TAG, "max brightness: " + max_brightness);
			}

			if( median_target > median_brightness && max_brightness < 255 ) {
				float gain = median_target / (float)median_brightness;
				if( MyDebug.LOG )
					Log.d(TAG, "gain " + gain);
				float max_possible_value = gain*max_brightness;
				if( MyDebug.LOG )
					Log.d(TAG, "max_possible_value: " + max_possible_value);
				if( max_possible_value > 255.0f ) {
					gain = 255.0f / max_brightness;
					if( MyDebug.LOG )
						Log.d(TAG, "limit gain to: " + gain);
				}
				ScriptC_avg_brighten script = new ScriptC_avg_brighten(rs);
				script.set_gain(gain);
				script.forEach_avg_brighten_gain(allocation, output_allocation);
				allocation = output_allocation; // output is now the input for subsequent operations
				if( MyDebug.LOG )
					Log.d(TAG, "### time after avg_brighten: " + (System.currentTimeMillis() - time_s));
			}
		}*/
/*		if( adjust_histogram != 0.0f ) {
			adjustHistogram(output_allocation, output_allocation, width, height, -1.0f, time_s);
			if( MyDebug.LOG )
				Log.d(TAG, "### time after adjustHistogram: " + (System.currentTimeMillis() - time_s));
		}*/

		if( unsharp_mask != 0.0f ) {
			unsharpMask(allocation, width, height, unsharp_mask, unsharp_mask_radius, time_s);
			if( MyDebug.LOG )
				Log.d(TAG, "### time after unsharpMask: " + (System.currentTimeMillis() - time_s));
		}
		if( hdr_alpha != 0.0f ) {
			adjustHistogramLocal(allocation, output_allocation, width, height, hdr_alpha, n_tiles, time_s);
			if( MyDebug.LOG )
				Log.d(TAG, "### time after adjustHistogram: " + (System.currentTimeMillis() - time_s));
		}

		if( MyDebug.LOG )
			Log.d(TAG, "time for processSingleImage: " + (System.currentTimeMillis() - time_s));
		return allocation;
			
//		return output_allocation;
	}

	private void initRenderScript() {
		if( MyDebug.LOG )
			Log.d(TAG, "initRenderScript");
		if( rs == null ) {
			// initialise renderscript
			rs = ((MainActivity)context).getRenderScript();
		}
	}


	static class BrightnessDetails {
		final int median_brightness; // median brightness value of the median image

		BrightnessDetails(int median_brightness) {
			this.median_brightness = median_brightness;
		}
	}

	/**
	 *
	 * @param bitmaps	   Only required if use_mtb is true, otherwise may be null.
	 * @param base_bitmap   Index of bitmap in bitmaps that should be kept fixed; the other bitmaps
	 *					  will be aligned relative to this.
	 * @param assume_sorted If assume_sorted if false, and use_mtb is true, this function will also
	 *					  sort the allocations and bitmaps from darkest to brightest.
	 * @param use_mtb	   Whether to align based on the median threshold bitmaps or not.
	 * @param floating_point If true, the first allocation is in floating point (F32_3) format.
	 */
	@RequiresApi(api = Build.VERSION_CODES.KITKAT)
	private BrightnessDetails autoAlignment(int [] offsets_x, int [] offsets_y, Allocation [] allocations, int width, int height, List<Bitmap> bitmaps, int base_bitmap, boolean assume_sorted, SortCallback sort_cb, boolean align, boolean use_mtb, boolean floating_point, long time_s) {
		if( MyDebug.LOG )
			Log.d(TAG, "autoAlignment");

		// initialise
		for(int i=0;i<offsets_x.length;i++) {
			offsets_x[i] = 0;
			offsets_y[i] = 0;
		}

		Allocation [] mtb_allocations = new Allocation[allocations.length];
		if( MyDebug.LOG )
			Log.d(TAG, "### time after creating mtb_allocations: " + (System.currentTimeMillis() - time_s));

		// Testing shows that in practice we get good results by only aligning the centre quarter of the images. This gives better
		// performance, and uses less memory.
		int mtb_width = width/2;
		int mtb_height = height/2;
		int mtb_x = mtb_width/2;
		int mtb_y = mtb_height/2;
		/*int mtb_width = width;
		int mtb_height = height;
		int mtb_x = 0;
		int mtb_y = 0;*/

		// create RenderScript
		ScriptC_create_mtb createMTBScript = new ScriptC_create_mtb(rs);

		LuminanceInfo [] luminanceInfos = null;
		if( use_mtb ) {
			luminanceInfos = new LuminanceInfo[allocations.length];
			for(int i = 0; i < allocations.length; i++) {
				luminanceInfos[i] = computeMedianLuminance(bitmaps.get(i), mtb_x, mtb_y, mtb_width, mtb_height);
				if( MyDebug.LOG )
					Log.d(TAG, i + ": median_value: " + luminanceInfos[i].median_value);
			}
			if( MyDebug.LOG )
				Log.d(TAG, "time after computeMedianLuminance: " + (System.currentTimeMillis() - time_s));
		}

		if( !assume_sorted && use_mtb ) {
			if( MyDebug.LOG )
				Log.d(TAG, "sort bitmaps");
			class BitmapInfo {
				final LuminanceInfo luminanceInfo;
				final Bitmap bitmap;
				final Allocation allocation;
				final int index;

				BitmapInfo(LuminanceInfo luminanceInfo, Bitmap bitmap, Allocation allocation, int index) {
					this.luminanceInfo = luminanceInfo;
					this.bitmap = bitmap;
					this.allocation = allocation;
					this.index = index;
				}

			}

			List<BitmapInfo> bitmapInfos = new ArrayList<>(bitmaps.size());
			for(int i=0;i<bitmaps.size();i++) {
				BitmapInfo bitmapInfo = new BitmapInfo(luminanceInfos[i], bitmaps.get(i), allocations[i], i);
				bitmapInfos.add(bitmapInfo);
			}
			Collections.sort(bitmapInfos, new Comparator<BitmapInfo>() {
				@Override
				public int compare(BitmapInfo o1, BitmapInfo o2) {
					return o1.luminanceInfo.median_value - o2.luminanceInfo.median_value;
				}
			});
			bitmaps.clear();
			for(int i=0;i<bitmapInfos.size();i++) {
				bitmaps.add(bitmapInfos.get(i).bitmap);
				luminanceInfos[i] = bitmapInfos.get(i).luminanceInfo;
				allocations[i] = bitmapInfos.get(i).allocation;
			}
			if( MyDebug.LOG ) {
				for(int i=0;i<allocations.length;i++) {
					Log.d(TAG, i + ": median_value: " + luminanceInfos[i].median_value);
				}
			}
			if( sort_cb != null ) {
				List<Integer> sort_order = new ArrayList<>();
				for(int i=0;i<bitmapInfos.size();i++) {
					sort_order.add( bitmapInfos.get(i).index);
				}
				if( MyDebug.LOG )
					Log.d(TAG, "sort_order: " + sort_order);
				sort_cb.sortOrder(sort_order);
			}
		}

		int median_brightness = -1;
		if( use_mtb ) {
			median_brightness = luminanceInfos[base_bitmap].median_value;
			if( MyDebug.LOG )
				Log.d(TAG, "median_brightness: " + median_brightness);
		}

		if( !align ) {
			return new BrightnessDetails(median_brightness);
		}

		for(int i=0;i<allocations.length;i++) {
			int median_value = -1;
			if( use_mtb ) {
				median_value = luminanceInfos[i].median_value;
				if( MyDebug.LOG )
					Log.d(TAG, i + ": median_value: " + median_value);

				/*if( median_value < 16 ) {
					// needed for testHDR2, testHDR28
					if( MyDebug.LOG )
						Log.d(TAG, "image too dark to do alignment");
					mtb_allocations[i] = null;
					continue;
				}*/
			}

			if( use_mtb && luminanceInfos[i].noisy ) {
				if( MyDebug.LOG )
					Log.d(TAG, "unable to compute median luminance safely");
				mtb_allocations[i] = null;
				continue;
			}

			Type.Builder builder = new Type.Builder(rs, Element.U8(rs));
			builder.setX(mtb_width);
			builder.setY(mtb_height);
			mtb_allocations[i] = Allocation.createTyped(rs, builder.create());

			// set parameters
			if( use_mtb )
				createMTBScript.set_median_value(median_value);
			createMTBScript.set_start_x(mtb_x);
			createMTBScript.set_start_y(mtb_y);
			createMTBScript.set_out_bitmap(mtb_allocations[i]);

			if( MyDebug.LOG )
				Log.d(TAG, "call createMTBScript");
			Script.LaunchOptions launch_options = new Script.LaunchOptions();
			//launch_options.setX((int)(width*0.25), (int)(width*0.75));
			//launch_options.setY((int)(height*0.25), (int)(height*0.75));
			//createMTBScript.forEach_create_mtb(allocations[i], mtb_allocations[i], launch_options);
			launch_options.setX(mtb_x, mtb_x+mtb_width);
			launch_options.setY(mtb_y, mtb_y+mtb_height);
			if( use_mtb )
				createMTBScript.forEach_create_mtb(allocations[i], launch_options);
			else {
				if( floating_point && i == 0 )
					createMTBScript.forEach_create_greyscale_f(allocations[i], launch_options);
				else
					createMTBScript.forEach_create_greyscale(allocations[i], launch_options);
			}
			if( MyDebug.LOG )
				Log.d(TAG, "time after createMTBScript: " + (System.currentTimeMillis() - time_s));

			/*if( MyDebug.LOG ) {
				// debugging
				byte [] mtb_bytes = new byte[mtb_width*mtb_height];
				mtb_allocations[i].copyTo(mtb_bytes);
				int [] pixels = new int[mtb_width*mtb_height];
				for(int j=0;j<mtb_width*mtb_height;j++) {
					byte b = mtb_bytes[j];
					pixels[j] = Color.argb(255, b, b, b);
				}
				Bitmap mtb_bitmap = Bitmap.createBitmap(pixels, mtb_width, mtb_height, Bitmap.Config.ARGB_8888);
				File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + "/mtb_bitmap" + i + ".jpg");
				try {
					OutputStream outputStream = new FileOutputStream(file);
					mtb_bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream);
					outputStream.close();
					MainActivity mActivity = (MainActivity) context;
					mActivity.getStorageUtils().broadcastFile(file, true, false, true);
				}
				catch(IOException e) {
					e.printStackTrace();
				}
				mtb_bitmap.recycle();
			}*/
		}
		if( MyDebug.LOG )
			Log.d(TAG, "### time after all createMTBScript: " + (System.currentTimeMillis() - time_s));

		// The initial step_size N should be a power of 2; the maximum offset we can achieve by the algorithm is N-1.
		// For pictures resolution 4160x3120, this gives max_ideal_size 27, and initial_step_size 32.
		// On tests testHDR1 to testHDR35, the max required offset was 24 pixels (for testHDR33) even when using
		// inital_step_size of 64.
		// Note, there isn't really a performance cost in allowing higher initial step sizes (as larger sizes have less
		// sampling - since we sample every step_size pixels - though there might be some overhead for every extra call
		// to renderscript that we do). But high step sizes have a risk of producing really bad results if we were
		// to misidentify cases as needing a large offset.
		int max_dim = Math.max(width, height); // n.b., use the full width and height here, not the mtb_width, height
		int max_ideal_size = max_dim / 150;
		int initial_step_size = 1;
		while( initial_step_size < max_ideal_size ) {
			initial_step_size *= 2;
		}
		if( MyDebug.LOG ) {
			Log.d(TAG, "max_dim: " + max_dim);
			Log.d(TAG, "max_ideal_size: " + max_ideal_size);
			Log.d(TAG, "initial_step_size: " + initial_step_size);
		}

		if( mtb_allocations[base_bitmap] == null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "base image not suitable for image alignment");
			return new BrightnessDetails(median_brightness);
		}

		// create RenderScript
		ScriptC_align_mtb alignMTBScript = new ScriptC_align_mtb(rs);

		// set parameters
		alignMTBScript.set_bitmap0(mtb_allocations[base_bitmap]);
		// bitmap1 set below

		for(int i=0;i<allocations.length;i++)  {
			if( i == base_bitmap ) {
				// don't need to align the "base" reference image
				continue;
			}
			if( mtb_allocations[i] == null ) {
				if( MyDebug.LOG )
					Log.d(TAG, "image " + i + " not suitable for image alignment");
				continue;
			}

			alignMTBScript.set_bitmap1(mtb_allocations[i]);

			int step_size = initial_step_size;
			while( step_size > 1 ) {
				step_size /= 2;
				alignMTBScript.set_off_x( offsets_x[i] );
				alignMTBScript.set_off_y( offsets_y[i] );
				alignMTBScript.set_step_size( step_size );
				if( MyDebug.LOG ) {
					Log.d(TAG, "call alignMTBScript for image: " + i);
					Log.d(TAG, "	versus base image: " + base_bitmap);
					Log.d(TAG, "step_size: " + step_size);
				}
				Allocation errorsAllocation = Allocation.createSized(rs, Element.I32(rs), 9);
				alignMTBScript.bind_errors(errorsAllocation);
				alignMTBScript.invoke_init_errors();

				// see note inside align_mtb.rs/align_mtb() for why we sample over a subset of the image
				Script.LaunchOptions launch_options = new Script.LaunchOptions();
				int stop_x = mtb_width/step_size;
				int stop_y = mtb_height/step_size;
				if( MyDebug.LOG ) {
					Log.d(TAG, "stop_x: " + stop_x);
					Log.d(TAG, "stop_y: " + stop_y);
				}
				//launch_options.setX((int)(stop_x*0.25), (int)(stop_x*0.75));
				//launch_options.setY((int)(stop_y*0.25), (int)(stop_y*0.75));
				launch_options.setX(0, stop_x);
				launch_options.setY(0, stop_y);
				if( use_mtb )
					alignMTBScript.forEach_align_mtb(mtb_allocations[base_bitmap], launch_options);
				else
					alignMTBScript.forEach_align(mtb_allocations[base_bitmap], launch_options);
				if( MyDebug.LOG )
					Log.d(TAG, "time after alignMTBScript: " + (System.currentTimeMillis() - time_s));

				int best_error = -1;
				int best_id = -1;
				int [] errors = new int[9];
				errorsAllocation.copyTo(errors);
				for(int j=0;j<9;j++) {
					int this_error = errors[j];
					if( MyDebug.LOG )
						Log.d(TAG, "	errors[" + j + "]: " + this_error);
					if( best_id==-1 || this_error < best_error ) {
						best_error = this_error;
						best_id = j;
					}
				}
				if( MyDebug.LOG )
					Log.d(TAG, "	best_id " + best_id + " error: " + best_error);
				if( best_id != -1 ) {
					int this_off_x = best_id % 3;
					int this_off_y = best_id/3;
					this_off_x--;
					this_off_y--;
					if( MyDebug.LOG ) {
						Log.d(TAG, "this_off_x: " + this_off_x);
						Log.d(TAG, "this_off_y: " + this_off_y);
					}
					offsets_x[i] += this_off_x * step_size;
					offsets_y[i] += this_off_y * step_size;
					if( MyDebug.LOG ) {
						Log.d(TAG, "offsets_x is now: " + offsets_x[i]);
						Log.d(TAG, "offsets_y is now: " + offsets_y[i]);
					}
				}
			}
		}

		/*for(int i=0;i<allocations.length;i++) {
			offsets_x[i] = 0;
			offsets_y[i] = 0;
		}*/
		return new BrightnessDetails(median_brightness);
	}

	public static class LuminanceInfo {
		final int median_value;
		final boolean noisy;

		LuminanceInfo(int median_value, boolean noisy) {
			this.median_value = median_value;
			this.noisy = noisy;
		}
	}

	public static LuminanceInfo computeMedianLuminance(Bitmap bitmap, int mtb_x, int mtb_y, int mtb_width, int mtb_height) {
		if( MyDebug.LOG )
			Log.d(TAG, "computeMedianLuminance");
		final int n_samples_c = 100;
		final int n_w_samples = (int)Math.sqrt(n_samples_c);
		final int n_h_samples = n_samples_c/n_w_samples;

		int [] histo = new int[256];
		for(int i=0;i<256;i++)
			histo[i] = 0;
		int total = 0;
		//double sum_log_luminance = 0.0;
		for(int y=0;y<n_h_samples;y++) {
			double alpha = ((double) y + 1.0) / ((double) n_h_samples + 1.0);
			//int y_coord = (int) (alpha * bitmap.getHeight());
			int y_coord = mtb_y + (int) (alpha * mtb_height);
			for(int x=0;x<n_w_samples;x++) {
				double beta = ((double) x + 1.0) / ((double) n_w_samples + 1.0);
				//int x_coord = (int) (beta * bitmap.getWidth());
				int x_coord = mtb_x + (int) (beta * mtb_width);
				/*if( MyDebug.LOG )
					Log.d(TAG, "sample value from " + x_coord + " , " + y_coord);*/
				int color = bitmap.getPixel(x_coord, y_coord);
				int r = (color & 0xFF0000) >> 16;
				int g = (color & 0xFF00) >> 8;
				int b = (color & 0xFF);
				int luminance = Math.max(r, g);
				luminance = Math.max(luminance, b);
				histo[luminance]++;
				//sum_log_luminance += Math.log(luminance+1.0); // add 1 so we don't take log of 0...;
				total++;
			}
		}
		/*float avg_luminance = (float)(Math.exp( sum_log_luminance / total ));
		if( MyDebug.LOG )
			Log.d(TAG, "avg_luminance: " + avg_luminance);*/
		int middle = total/2;
		int count = 0;
		boolean noisy = false;
		for(int i=0;i<256;i++) {
			count += histo[i];
			if( count >= middle ) {
				if( MyDebug.LOG )
					Log.d(TAG, "median luminance " + i);
				final int noise_threshold = 4;
				int n_below = 0, n_above = 0;
				for(int j=0;j<=i-noise_threshold;j++) {
					n_below += histo[j];
				}
				for(int j=0;j<=i+noise_threshold && j<256;j++) {
					n_above += histo[j];
				}
				double frac_below = n_below / (double)total;
				if( MyDebug.LOG ) {
					double frac_above = 1.0 - n_above / (double)total;
					Log.d(TAG, "count: " + count);
					Log.d(TAG, "n_below: " + n_below);
					Log.d(TAG, "n_above: " + n_above);
					Log.d(TAG, "frac_below: " + frac_below);
					Log.d(TAG, "frac_above: " + frac_above);
				}
				if( frac_below < 0.2 ) {
					// needed for testHDR2, testHDR28
					// note that we don't exclude cases where frac_above is too small, as this could be an overexposed image - see testHDR31
					if( MyDebug.LOG )
						Log.d(TAG, "too dark/noisy");
					noisy = true;
				}
				return new LuminanceInfo(i, noisy);
			}
		}
		Log.e(TAG, "computeMedianLuminance failed");
		return new LuminanceInfo(127, true);
	}

	@RequiresApi(api = Build.VERSION_CODES.KITKAT)
	private void adjustHistogram(Allocation allocation_in, Allocation allocation_out, int width, int height, float hdr_alpha, long time_s) {
		if( MyDebug.LOG )
			Log.d(TAG, "adjustHistogram");

		// create histogram
		int [] histogram = new int[256];
		if( MyDebug.LOG )
			Log.d(TAG, "time before creating histogram: " + (System.currentTimeMillis() - time_s));
		Allocation histogramAllocation = computeHistogramAllocation(allocation_in, false, false, time_s);
		if( MyDebug.LOG )
			Log.d(TAG, "time after creating histogram: " + (System.currentTimeMillis() - time_s));
		histogramAllocation.copyTo(histogram);

			/*if( MyDebug.LOG ) {
				// compare/adjust
				allocations[0].copyTo(bm);
				int [] debug_histogram = new int[256];
				for(int i=0;i<256;i++) {
					debug_histogram[i] = 0;
				}
				int [] debug_buffer = new int[width];
				for(int y=0;y<height;y++) {
					bm.getPixels(debug_buffer, 0, width, 0, y, width, 1);
					for(int x=0;x<width;x++) {
						int color = debug_buffer[x];
						float r = (float)((color & 0xFF0000) >> 16);
						float g = (float)((color & 0xFF00) >> 8);
						float b = (float)(color & 0xFF);
						//float value = 0.299f*r + 0.587f*g + 0.114f*b; // matches ScriptIntrinsicHistogram default behaviour
						float value = Math.max(r, g);
						value = Math.max(value, b);
						int i_value = (int)value;
						i_value = Math.min(255, i_value); // just in case
						debug_histogram[i_value]++;
					}
				}
				for(int x=0;x<256;x++) {
					Log.d(TAG, "histogram[" + x + "] = " + histogram[x] + " debug_histogram: " + debug_histogram[x]);
					//histogram[x] = debug_histogram[x];
				}
			}*/

		int [] c_histogram = new int[256];
		c_histogram[0] = histogram[0];
		for(int x=1;x<256;x++) {
			c_histogram[x] = c_histogram[x-1] + histogram[x];
		}
			/*if( MyDebug.LOG ) {
				for(int x=0;x<256;x++) {
					Log.d(TAG, "histogram[" + x + "] = " + histogram[x] + " cumulative: " + c_histogram[x]);
				}
			}*/
		histogramAllocation.copyFrom(c_histogram);

		ScriptC_histogram histogramAdjustScript = new ScriptC_histogram(rs);
		histogramAdjustScript.set_c_histogram(histogramAllocation);
		histogramAdjustScript.set_hdr_alpha(hdr_alpha);

		if( MyDebug.LOG )
			Log.d(TAG, "call histogramAdjustScript");
		histogramAdjustScript.forEach_histogram_adjust(allocation_in, allocation_out);
		if( MyDebug.LOG )
			Log.d(TAG, "time after histogramAdjustScript: " + (System.currentTimeMillis() - time_s));
	}

	@RequiresApi(api = Build.VERSION_CODES.KITKAT)
	private void adjustHistogramLocal(Allocation allocation_in, Allocation allocation_out, int width, int height, float hdr_alpha, int n_tiles, long time_s) {
		if( MyDebug.LOG )
			Log.d(TAG, "adjustHistogramLocal");

		// Contrast Limited Adaptive Histogram Equalisation
		// Note we don't fully equalise the histogram, rather the resultant image is the mid-point of the non-equalised and fully-equalised images
		// See https://en.wikipedia.org/wiki/Adaptive_histogram_equalization#Contrast_Limited_AHE
		// Also see "Adaptive Histogram Equalization and its Variations" ( http://www.cs.unc.edu/Research/MIDAG/pubs/papers/Adaptive%20Histogram%20Equalization%20and%20Its%20Variations.pdf ),
		// Pizer, Amburn, Austin, Cromartie, Geselowitz, Greer, ter Haar Romeny, Zimmerman, Zuiderveld (1987).

		// create histograms
		Allocation histogramAllocation = Allocation.createSized(rs, Element.I32(rs), 256);
		if( MyDebug.LOG )
			Log.d(TAG, "create histogramScript");
		ScriptC_histogram_compute histogramScript = new ScriptC_histogram_compute(rs);
		if( MyDebug.LOG )
			Log.d(TAG, "bind histogram allocation");
		histogramScript.bind_histogram(histogramAllocation);

		//final int n_tiles_c = 8;
		//final int n_tiles_c = 4;
		//final int n_tiles_c = 1;
		int [] c_histogram = new int[n_tiles*n_tiles*256];
		for(int i=0;i<n_tiles;i++) {
			double a0 = ((double)i)/(double)n_tiles;
			double a1 = ((double)i+1.0)/(double)n_tiles;
			int start_x = (int)(a0 * width);
			int stop_x = (int)(a1 * width);
			if( stop_x == start_x )
				continue;
			for(int j=0;j<n_tiles;j++) {
				double b0 = ((double)j)/(double)n_tiles;
				double b1 = ((double)j+1.0)/(double)n_tiles;
				int start_y = (int)(b0 * height);
				int stop_y = (int)(b1 * height);
				if( stop_y == start_y )
					continue;
					/*if( MyDebug.LOG )
						Log.d(TAG, i + " , " + j + " : " + start_x + " , " + start_y + " to " + stop_x + " , " + stop_y);*/
				Script.LaunchOptions launch_options = new Script.LaunchOptions();
				launch_options.setX(start_x, stop_x);
				launch_options.setY(start_y, stop_y);

					/*if( MyDebug.LOG )
						Log.d(TAG, "call histogramScript");*/
				histogramScript.invoke_init_histogram();
				histogramScript.forEach_histogram_compute(allocation_in, launch_options);

				int [] histogram = new int[256];
				histogramAllocation.copyTo(histogram);

					/*if( MyDebug.LOG ) {
						// compare/adjust
						allocations[0].copyTo(bm);
						int [] debug_histogram = new int[256];
						for(int k=0;k<256;k++) {
							debug_histogram[k] = 0;
						}
						int [] debug_buffer = new int[width];
						for(int y=start_y;y<stop_y;y++) {
							bm.getPixels(debug_buffer, 0, width, 0, y, width, 1);
							for(int x=start_x;x<stop_x;x++) {
								int color = debug_buffer[x];
								float r = (float)((color & 0xFF0000) >> 16);
								float g = (float)((color & 0xFF00) >> 8);
								float b = (float)(color & 0xFF);
								//float value = 0.299f*r + 0.587f*g + 0.114f*b; // matches ScriptIntrinsicHistogram default behaviour
								float value = Math.max(r, g);
								value = Math.max(value, b);
								int i_value = (int)value;
								i_value = Math.min(255, i_value); // just in case
								debug_histogram[i_value]++;
							}
						}
						for(int x=0;x<256;x++) {
							Log.d(TAG, "histogram[" + x + "] = " + histogram[x] + " debug_histogram: " + debug_histogram[x]);
							//histogram[x] = debug_histogram[x];
						}
					}*/

				// clip histogram, for Contrast Limited AHE algorithm
				int n_pixels = (stop_x - start_x) * (stop_y - start_y);
				int clip_limit = (5 * n_pixels) / 256;
					/*if( MyDebug.LOG )
						Log.d(TAG, "clip_limit: " + clip_limit);*/
				{
					// find real clip limit
					int bottom = 0, top = clip_limit;
					while( top - bottom > 1 ) {
						int middle = (top + bottom)/2;
						int sum = 0;
						for(int x=0;x<256;x++) {
							if( histogram[x] > middle ) {
								sum += (histogram[x] - clip_limit);
							}
						}
						if( sum > (clip_limit - middle) * 256 )
							top = middle;
						else
							bottom = middle;
					}
					clip_limit = (top + bottom)/2;
						/*if( MyDebug.LOG )
							Log.d(TAG, "updated clip_limit: " + clip_limit);*/
				}
				int n_clipped = 0;
				for(int x=0;x<256;x++) {
					if( histogram[x] > clip_limit ) {
						n_clipped += (histogram[x] - clip_limit);
						histogram[x] = clip_limit;
					}
				}
				int n_clipped_per_bucket = n_clipped / 256;
					/*if( MyDebug.LOG ) {
						Log.d(TAG, "n_clipped: " + n_clipped);
						Log.d(TAG, "n_clipped_per_bucket: " + n_clipped_per_bucket);
					}*/
				for(int x=0;x<256;x++) {
					histogram[x] += n_clipped_per_bucket;
				}

				int histogram_offset = 256*(i*n_tiles+j);
				c_histogram[histogram_offset] = histogram[0];
				for(int x=1;x<256;x++) {
					c_histogram[histogram_offset+x] = c_histogram[histogram_offset+x-1] + histogram[x];
				}
					/*if( MyDebug.LOG ) {
						for(int x=0;x<256;x++) {
							Log.d(TAG, "histogram[" + x + "] = " + histogram[x] + " cumulative: " + c_histogram[histogram_offset+x]);
						}
					}*/
			}
		}

		if( MyDebug.LOG )
			Log.d(TAG, "time after creating histograms: " + (System.currentTimeMillis() - time_s));

		Allocation c_histogramAllocation = Allocation.createSized(rs, Element.I32(rs), n_tiles*n_tiles*256);
		c_histogramAllocation.copyFrom(c_histogram);
		ScriptC_histogram_adjust histogramAdjustScript = new ScriptC_histogram_adjust(rs);
		histogramAdjustScript.set_c_histogram(c_histogramAllocation);
		histogramAdjustScript.set_hdr_alpha(hdr_alpha);
		histogramAdjustScript.set_n_tiles(n_tiles);
		histogramAdjustScript.set_width(width);
		histogramAdjustScript.set_height(height);

		if( MyDebug.LOG )
			Log.d(TAG, "call histogramAdjustScript");
		histogramAdjustScript.forEach_histogram_adjust(allocation_in, allocation_out);
		if( MyDebug.LOG )
			Log.d(TAG, "time after histogramAdjustScript: " + (System.currentTimeMillis() - time_s));
	}

	/**
	 * @param avg If true, compute the color value as the average of the rgb values. If false,
	 *			compute the color value as the maximum of the rgb values.
	 * @param floating_point Whether the allocation_in is in floating point (F32_3) format, or
	 *					   RGBA_8888 format.
	 */
	@RequiresApi(api = Build.VERSION_CODES.KITKAT)
	private Allocation computeHistogramAllocation(Allocation allocation_in, boolean avg, boolean floating_point, long time_s) {
		if( MyDebug.LOG )
			Log.d(TAG, "computeHistogramAllocation");
		Allocation histogramAllocation = Allocation.createSized(rs, Element.I32(rs), 256);
		//final boolean use_custom_histogram = false;
		final boolean use_custom_histogram = true;
		if( use_custom_histogram ) {
			if( MyDebug.LOG )
				Log.d(TAG, "create histogramScript");
			ScriptC_histogram_compute histogramScript = new ScriptC_histogram_compute(rs);
			if( MyDebug.LOG )
				Log.d(TAG, "bind histogram allocation");
			histogramScript.bind_histogram(histogramAllocation);
			histogramScript.invoke_init_histogram();
			if( MyDebug.LOG )
				Log.d(TAG, "call histogramScript");
			if( MyDebug.LOG )
				Log.d(TAG, "time before histogramScript: " + (System.currentTimeMillis() - time_s));
			if( avg ) {
				if( floating_point )
					histogramScript.forEach_histogram_compute_avg_f(allocation_in);
				else
					histogramScript.forEach_histogram_compute_avg(allocation_in);
			}
			else {
				if( floating_point )
					histogramScript.forEach_histogram_compute_f(allocation_in);
				else
					histogramScript.forEach_histogram_compute(allocation_in);
			}
			if( MyDebug.LOG )
				Log.d(TAG, "time after histogramScript: " + (System.currentTimeMillis() - time_s));
		}
		else {
			ScriptIntrinsicHistogram histogramScript = ScriptIntrinsicHistogram.create(rs, Element.U8_4(rs));
			histogramScript.setOutput(histogramAllocation);
			if( MyDebug.LOG )
				Log.d(TAG, "call histogramScript");
			histogramScript.forEach_Dot(allocation_in); // use forEach_dot(); using forEach would simply compute a histogram for red values!
		}

		//histogramAllocation.setAutoPadding(true);
		return histogramAllocation;
	}

	/**
	 * @param avg If true, compute the color value as the average of the rgb values. If false,
	 *			compute the color value as the maximum of the rgb values.
	 */
	@RequiresApi(api = Build.VERSION_CODES.KITKAT)
	public int [] computeHistogram(Bitmap bitmap, boolean avg) {
		if( MyDebug.LOG )
			Log.d(TAG, "computeHistogram");
		long time_s = System.currentTimeMillis();
		Allocation allocation_in = Allocation.createFromBitmap(rs, bitmap);
		if( MyDebug.LOG )
			Log.d(TAG, "time after createFromBitmap: " + (System.currentTimeMillis() - time_s));
		return computeHistogram(allocation_in, avg, false);
	}

	@RequiresApi(api = Build.VERSION_CODES.KITKAT)
	private int [] computeHistogram(Allocation allocation, boolean avg, boolean floating_point) {
		if( MyDebug.LOG )
			Log.d(TAG, "computeHistogram");
		long time_s = System.currentTimeMillis();
		int [] histogram = new int[256];
		Allocation histogramAllocation = computeHistogramAllocation(allocation, avg, floating_point, time_s);
		histogramAllocation.copyTo(histogram);
		return histogram;
	}

	private static class HistogramInfo {
		public final int median_brightness;
		public final int max_brightness;

		HistogramInfo(int median_brightness, int max_brightness) {
			this.median_brightness = median_brightness;
			this.max_brightness = max_brightness;
		}
	}

	private HistogramInfo getHistogramInfo(int [] histo) {
		int total = 0;
		for(int value : histo)
			total += value;
		int middle = total / 2;
		int count = 0;
		int median_brightness = -1;
		int max_brightness = 0;
		for(int i = 0; i < histo.length; i++) {
			count += histo[i];
			if( count >= middle && median_brightness == -1 ) {
				median_brightness = i;
			}
			if( histo[i] > 0 ) {
				max_brightness = i;
			}
		}

		return new HistogramInfo(median_brightness, max_brightness);
	}

	private int getMedianTarget(int median_brightness, int max_gain_factor) {
		if( median_brightness <= 0 )
			median_brightness = 1;
		if( MyDebug.LOG ) {
			Log.d(TAG, "max_gain_factor: " + max_gain_factor);
		}
		return Math.min(119, max_gain_factor*median_brightness);
	}

	@RequiresApi(api = Build.VERSION_CODES.KITKAT)
	private void unsharpMask(Allocation allocation, int width, int height, float alpha, float radius, long time_s) {
		if( MyDebug.LOG )
			Log.d(TAG, "unsharpMask, time: " + (System.currentTimeMillis() - time_s));

		ScriptC_unsharp_mask script = new ScriptC_unsharp_mask(rs);

		Type.Builder builder = new Type.Builder(rs, Element.U8(rs));
		builder.setX(width);
		builder.setY(height);
		Allocation mono_alloc = Allocation.createTyped(rs, builder.create());

		script.forEach_monochrome(allocation, mono_alloc);
		if( MyDebug.LOG )
			Log.d(TAG, "time after script.forEach_monochrome: " + (System.currentTimeMillis() - time_s));
		
		ScriptIntrinsicBlur script_blur =  ScriptIntrinsicBlur.create(rs, Element.U8(rs));
		
		radius *= (float)Math.min(width, height)/200;
		
		float q = 1;
		if (radius > 25) {
			if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP )
				q = radius/25;
			else
				radius = 25;
		}
		if( MyDebug.LOG )
			Log.d(TAG, "radius = " + radius + ", q = " + q);
		
		if (q == 1) {
			script_blur.setInput(mono_alloc);
			script_blur.setRadius(radius);

			Allocation mask_alloc = Allocation.createTyped(rs, builder.create());
			script_blur.forEach(mask_alloc);
			if( MyDebug.LOG )
				Log.d(TAG, "time after script_blur.forEach: " + (System.currentTimeMillis() - time_s));

			script.set_mask(mask_alloc);
		} else {
			ScriptIntrinsicResize script_resize =  ScriptIntrinsicResize.create(rs);

			Type.Builder builder_resize = new Type.Builder(rs, Element.U8(rs));
			builder_resize.setX((int)(width/q+0.5f));
			builder_resize.setY((int)(height/q+0.5f));
			Allocation r_alloc = Allocation.createTyped(rs, builder_resize.create());
			
			script_resize.setInput(mono_alloc);
			script_resize.forEach_bicubic(r_alloc);
			if( MyDebug.LOG )
				Log.d(TAG, "time after script_resize.forEach_bicubic: " + (System.currentTimeMillis() - time_s));
			
			script_blur.setInput(r_alloc);
			script_blur.setRadius(25);
			
			Allocation mask_alloc = Allocation.createTyped(rs, builder_resize.create());
			script_blur.forEach(mask_alloc);
			if( MyDebug.LOG )
				Log.d(TAG, "time after script_blur.forEach: " + (System.currentTimeMillis() - time_s));
			
			script_resize.setInput(mask_alloc);
			script_resize.forEach_bicubic(mono_alloc);
			if( MyDebug.LOG )
				Log.d(TAG, "time after script_resize.forEach_bicubic: " + (System.currentTimeMillis() - time_s));
			
			script.set_mask(mono_alloc);
		}
		
		script.set_alpha(alpha);
		script.forEach_unsharp_mask(allocation, allocation);
		if( MyDebug.LOG )
			Log.d(TAG, "time after script.forEach_unsharp_mask: " + (System.currentTimeMillis() - time_s));
	}
}
