package com.caddish_hedgehog.hedgecam2.preferences;

import com.caddish_hedgehog.hedgecam2.MyDebug;
import com.caddish_hedgehog.hedgecam2.R;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.preference.DialogPreference;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.math.BigDecimal;

public class SeekBarFloatPreference extends DialogPreference implements SeekBar.OnSeekBarChangeListener, OnClickListener
{
	private static final String TAG = "HedgeCam/SeekBarFloatPreference";
	private static final String androidns="http://schemas.android.com/apk/res/android";

	private SeekBar seekbar;
	private TextView valueText;
	private Context context;

	private String suffix;
	private String summary;
	private float defValue;
	private String defaultValue;
	private float minValue;
	private float maxValue;
	private float step;
	private float floatValue;
	private String value;
	private String checkBoxTitle;
	private String checkBoxValue = null;
	private boolean valueIsCheckBox;

	public SeekBarFloatPreference(Context context, AttributeSet attrs) {
		super(context,attrs);
		this.context = context;

		int id = attrs.getAttributeResourceValue(androidns, "text", 0);
		if (id == 0) suffix = attrs.getAttributeValue(androidns, "text");
		else suffix = context.getString(id);

		id = attrs.getAttributeResourceValue(androidns, "summary", 0);
		if (id == 0) summary = attrs.getAttributeValue(androidns, "summary");
		else summary = context.getString(id);

		defValue = attrs.getAttributeFloatValue(androidns, "defaultValue", 0.0f);

		TypedArray customAttrs = context.getTheme().obtainStyledAttributes(attrs, R.styleable.preferences, 0, 0);
		minValue = customAttrs.getFloat(R.styleable.preferences_fmin, 0.0f);
		maxValue = customAttrs.getFloat(R.styleable.preferences_fmax, 100.0f);
		step = customAttrs.getFloat(R.styleable.preferences_fstep, 1.0f);
		
		checkBoxValue = customAttrs.getString(R.styleable.preferences_checkBoxValue);
		if (checkBoxValue == null) {
			defaultValue = Float.toString(defValue);
		} else {
			defaultValue = checkBoxValue;
			checkBoxTitle = customAttrs.getString(R.styleable.preferences_checkBoxTitle);
		}

		customAttrs.recycle();
	}

	@Override
	protected View onCreateDialogView() {
		Resources resources = getContext().getResources();

		LinearLayout.LayoutParams params;
		LinearLayout layout = new LinearLayout(context);
		layout.setOrientation(LinearLayout.VERTICAL);
		int padding = resources.getDimensionPixelSize(R.dimen.pref_seekbar_padding);
		layout.setPadding(padding, padding, padding, padding);

		CheckBox checkBox = null;
		if (checkBoxValue != null) {
			checkBox = new CheckBox(context);
			checkBox.setText(checkBoxTitle != null ? checkBoxTitle : "");
			checkBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
				public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
					valueIsCheckBox = isChecked;
					checkBoxChecked(isChecked);
				}
			});
			layout.addView(checkBox);
		}

		valueText = new TextView(context);
		valueText.setGravity(Gravity.CENTER_HORIZONTAL);
		valueText.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.pref_seekbar_text_large));
		
		params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		layout.addView(valueText, params);

		seekbar = new SeekBar(context);
		seekbar.setOnSeekBarChangeListener(this);
		padding = resources.getDimensionPixelSize(R.dimen.seekbar_padding_large);
		seekbar.setPadding(seekbar.getPaddingLeft(), padding, seekbar.getPaddingRight(), padding);
		layout.addView(seekbar, params);

		if (shouldPersist()) {
			value = getPersistedString(defaultValue);
			processValue(value);
		}

		if (checkBox != null)
			checkBox.setChecked(valueIsCheckBox);

		seekbar.setMax((int)((maxValue-minValue)/step+0.5f));
		int progress = (int)((floatValue-minValue)/step+0.5f);
		if (progress == 0)
			this.onProgressChanged(seekbar, 0, false);
		else
			seekbar.setProgress(progress);
		
		return layout;
	}

	@Override
	protected void onBindDialogView(View v) {
		super.onBindDialogView(v);
		seekbar.setMax((int)((maxValue-minValue)/step+0.5f));
		int progress = (int)((floatValue-minValue)/step+0.5f);
		if (progress == 0)
			this.onProgressChanged(seekbar, 0, false);
		else
			seekbar.setProgress(progress);
	}

	@Override
	protected void onSetInitialValue(boolean restore, Object def) {
		super.onSetInitialValue(restore, def);
		if (restore) {
			if (shouldPersist()) {
				value = getPersistedString(defaultValue);
				processValue(value);
			}
		} else
			floatValue = defValue;
	}

	@Override
	public CharSequence getSummary() {
		if (valueIsCheckBox) {
			return summary.replace("%s", checkBoxTitle);
		} else {
			String v = getPersistedString(defaultValue);
			return summary.replace("%s", suffix == null ? v : v + " " + suffix);
		}
	}

	@Override
	public void onProgressChanged(SeekBar s, int value, boolean fromTouch) {
		String v = getResultString(value);
		valueText.setText(suffix == null ? v : v + " " + suffix);
	}

	@Override
	public void onStartTrackingTouch(SeekBar s) {}
	@Override
	public void onStopTrackingTouch(SeekBar s) {}

	public void setValue(String v) {
		if (seekbar == null)
			processValue(v);
	}

	@Override
	public void showDialog(Bundle state) {

		super.showDialog(state);

		Button positiveButton = ((AlertDialog) getDialog()).getButton(AlertDialog.BUTTON_POSITIVE);
		positiveButton.setOnClickListener(this);
	}

	@Override
	public void onClick(View view) {
		if (shouldPersist()) {
			if (valueIsCheckBox) {
				persistString(checkBoxValue);

				setSummary(summary.replace("%s", checkBoxTitle));
			} else {
				String v = getResultString(seekbar.getProgress());
				persistString(v);

				setSummary(summary.replace("%s", suffix == null ? v : v + " " + suffix));
			}
			callChangeListener(Integer.valueOf(seekbar.getProgress()));
		}

		((AlertDialog) getDialog()).dismiss();
	}

	private String getResultString(int seekBarValue) {
		// Don't ask me, why. It's fuckin magic... :D
		return Float.toString(new BigDecimal((float)seekBarValue*step+minValue).setScale(4, BigDecimal.ROUND_HALF_UP).floatValue());
	}

	private void processValue(String v) {
		if (checkBoxValue != null && checkBoxValue.equals(v)) {
			valueIsCheckBox = true;
			floatValue = defValue;
		} else {
			try {floatValue = Float.parseFloat(v);}
			catch(NumberFormatException e) {floatValue = defValue;}
		}
	}

	private void checkBoxChecked(boolean value) {
		if (seekbar != null)
			seekbar.setEnabled(!value);
		if (valueText != null)
			valueText.setAlpha(value ? 0.1f : 1.0f);
	}
}