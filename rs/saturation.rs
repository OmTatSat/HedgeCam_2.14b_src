#pragma version(1)
#pragma rs java_package_name(com.caddish_hedgehog.hedgecam2)
#pragma rs_fp_relaxed

float RV = 1.402f;
float GU = 0.34414f;
float GV = 0.71414f;
float BU = 1.772f;

void set_saturation(float saturation) {
	RV = saturation*1.402f;
	GU = saturation*0.34414f;
	GV = saturation*0.71414f;
	BU = saturation*1.772f;
}

uchar4 __attribute__((kernel)) saturate(uchar4 pixel, uint32_t x, uint32_t y) {
	float4 p = convert_float4(pixel);

	float Y = 0.299*p.r + 0.587*p.g + 0.114*p.b;
	float U = - 0.1687*p.r - 0.3313*p.g + 0.5*p.b;
	float V = 0.5*p.r - 0.4187*p.g - 0.0813*p.b;

	int4 argb;
	argb.r = (int)(Y + V * RV);
	argb.g = (int)(Y - U * GU - V * GV);
	argb.b = (int)(Y + U * BU);
	argb.a = 255;

	uchar4 out = convert_uchar4(clamp(argb, 0, 255));

	return out;
}

float FR = 1.0f;
float FG = 1.0f;
float FB = 1.0f;
float FC = 1.0f;
float FM = 1.0f;
float FY = 1.0f;

void set_saturation_advanced(float r, float g, float b) {
	FR = r;
	FG = g;
	FB = b;
	FC = (g+b)/2.0f;
	FM = (r+b)/2.0f;
	FY = (r+g)/2.0f;
}

uchar4 __attribute__((kernel)) saturate_advanced(uchar4 pixel, uint32_t x, uint32_t y) {
	float4 p = convert_float4(pixel);

	float Y = 0.299*p.r + 0.587*p.g + 0.114*p.b;
	float U = - 0.1687*p.r - 0.3313*p.g + 0.5*p.b;
	float V = 0.5*p.r - 0.4187*p.g - 0.0813*p.b;
	
	float CR = V * 1.402f;
	if (CR > 0) {
		CR *= FR;
	} else {
		CR *= FC;
	}
	float CG = - U * 0.34414f - V * 0.71414f;
	if (CG > 0) {
		CG *= FG;
	} else {
		CG *= FM;
	}
	float CB = U * 1.772f;
	if (CB > 0) {
		CB *= FB;
	} else {
		CB *= FY;
	}

	int4 argb;
	argb.r = (int)(Y + CR);
	argb.g = (int)(Y + CG);
	argb.b = (int)(Y + CB);
	argb.a = 255;

	uchar4 out = convert_uchar4(clamp(argb, 0, 255));

	return out;
}
