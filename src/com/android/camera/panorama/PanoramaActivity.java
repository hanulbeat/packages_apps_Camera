/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.camera.panorama;

import com.android.camera.CameraDisabledException;
import com.android.camera.CameraHardwareException;
import com.android.camera.CameraHolder;
import com.android.camera.Exif;
import com.android.camera.MenuHelper;
import com.android.camera.ModePicker;
import com.android.camera.OnClickAttr;
import com.android.camera.R;
import com.android.camera.Storage;
import com.android.camera.Thumbnail;
import com.android.camera.Util;
import com.android.camera.ui.RotateImageView;
import com.android.camera.ui.SharePopup;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.Size;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Activity to handle panorama capturing.
 */
public class PanoramaActivity extends Activity implements
        ModePicker.OnModeChangeListener,
        SurfaceTexture.OnFrameAvailableListener,
        MosaicRendererSurfaceViewRenderer.MosaicSurfaceCreateListener {
    public static final int DEFAULT_SWEEP_ANGLE = 160;
    public static final int DEFAULT_BLEND_MODE = Mosaic.BLENDTYPE_HORIZONTAL;
    public static final int DEFAULT_CAPTURE_PIXELS = 960 * 720;

    private static final int MSG_FINAL_MOSAIC_READY = 1;
    private static final int MSG_RESET_TO_PREVIEW_WITH_THUMBNAIL = 2;

    private static final String TAG = "PanoramaActivity";
    private static final int PREVIEW_STOPPED = 0;
    private static final int PREVIEW_ACTIVE = 1;
    private static final int CAPTURE_VIEWFINDER = 0;
    private static final int CAPTURE_MOSAIC = 1;

    // Speed is in unit of deg/sec
    private static final float PANNING_SPEED_THRESHOLD = 30f;

    // Ratio of nanosecond to second
    private static final float NS2S = 1.0f / 1000000000.0f;

    private boolean mPausing;

    private View mPanoControlLayout;
    private View mCaptureLayout;
    private Button mStopCaptureButton;
    private View mReviewLayout;
    private ImageView mReview;
    private CaptureView mCaptureView;
    private MosaicRendererSurfaceView mMosaicView;
    private TextView mTooFastPrompt;
    private Animation mSlideIn, mSlideOut;

    private ProgressDialog mProgressDialog;
    private String mPreparePreviewString;
    private String mGeneratePanoramaString;

    private RotateImageView mThumbnailView;
    private Thumbnail mThumbnail;
    private SharePopup mSharePopup;

    private int mPreviewWidth;
    private int mPreviewHeight;
    private Camera mCameraDevice;
    private int mCameraState;
    private int mCaptureState;
    private SensorManager mSensorManager;
    private Sensor mSensor;
    private ModePicker mModePicker;
    private MosaicFrameProcessor mMosaicFrameProcessor;
    private String mCurrentImagePath = null;
    private long mTimeTaken;
    private Handler mMainHandler;
    private SurfaceTexture mSurfaceTexture;
    private boolean mThreadRunning;
    private float[] mTransformMatrix;
    private float mHorizontalViewAngle;
    private float mVerticalViewAngle;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        createContentView();

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (mSensor == null) {
            mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION);
        }

        mTransformMatrix = new float[16];

        mPreparePreviewString =
                getResources().getString(R.string.pano_dialog_prepare_preview);
        mGeneratePanoramaString =
                getResources().getString(R.string.pano_dialog_generate_panorama);

        Context context = getApplicationContext();
        mSlideIn = AnimationUtils.loadAnimation(context, R.anim.slide_in_from_right);
        mSlideOut = AnimationUtils.loadAnimation(context, R.anim.slide_out_to_right);

        mMainHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_FINAL_MOSAIC_READY:
                        onBackgroundThreadFinished();
                        showFinalMosaic((Bitmap) msg.obj);
                        break;
                    case MSG_RESET_TO_PREVIEW_WITH_THUMBNAIL:
                        onBackgroundThreadFinished();
                        // Set the thumbnail bitmap here because mThumbnailView must be accessed
                        // from the UI thread.
                        if (mThumbnail != null) {
                            mThumbnailView.setBitmap(mThumbnail.getBitmap());
                        }
                        resetToPreview();
                        break;
                }
                clearMosaicFrameProcessorIfNeeded();
            }
        };
    }

    private void setupCamera() {
        openCamera();
        Parameters parameters = mCameraDevice.getParameters();
        setupCaptureParams(parameters);
        configureCamera(parameters);
    }

    private void releaseCamera() {
        if (mCameraDevice != null) {
            mCameraDevice.setPreviewCallbackWithBuffer(null);
            CameraHolder.instance().release();
            mCameraDevice = null;
            mCameraState = PREVIEW_STOPPED;
        }
    }

    private void openCamera() {
        try {
            mCameraDevice = Util.openCamera(this, CameraHolder.instance().getBackCameraId());
        } catch (CameraHardwareException e) {
            Util.showErrorAndFinish(this, R.string.cannot_connect_camera);
            return;
        } catch (CameraDisabledException e) {
            Util.showErrorAndFinish(this, R.string.camera_disabled);
            return;
        }
    }

    private boolean findBestPreviewSize(List<Size> supportedSizes, boolean need4To3,
            boolean needSmaller) {
        int pixelsDiff = DEFAULT_CAPTURE_PIXELS;
        boolean hasFound = false;
        for (Size size : supportedSizes) {
            int h = size.height;
            int w = size.width;
            // we only want 4:3 format.
            int d = DEFAULT_CAPTURE_PIXELS - h * w;
            if (needSmaller && d < 0) { // no bigger preview than 960x720.
                continue;
            }
            if (need4To3 && (h * 4 != w * 3)) {
                continue;
            }
            d = Math.abs(d);
            if (d < pixelsDiff) {
                mPreviewWidth = w;
                mPreviewHeight = h;
                pixelsDiff = d;
                hasFound = true;
            }
        }
        return hasFound;
    }

    private void setupCaptureParams(Parameters parameters) {
        List<Size> supportedSizes = parameters.getSupportedPreviewSizes();
        if (!findBestPreviewSize(supportedSizes, true, true)) {
            Log.w(TAG, "No 4:3 ratio preview size supported.");
            if (!findBestPreviewSize(supportedSizes, false, true)) {
                Log.w(TAG, "Can't find a supported preview size smaller than 960x720.");
                findBestPreviewSize(supportedSizes, false, false);
            }
        }
        Log.v(TAG, "preview h = " + mPreviewHeight + " , w = " + mPreviewWidth);
        parameters.setPreviewSize(mPreviewWidth, mPreviewHeight);

        List<int[]> frameRates = parameters.getSupportedPreviewFpsRange();
        int last = frameRates.size() - 1;
        int minFps = (frameRates.get(last))[Parameters.PREVIEW_FPS_MIN_INDEX];
        int maxFps = (frameRates.get(last))[Parameters.PREVIEW_FPS_MAX_INDEX];
        parameters.setPreviewFpsRange(minFps, maxFps);
        Log.v(TAG, "preview fps: " + minFps + ", " + maxFps);

        parameters.setRecordingHint(false);

        mHorizontalViewAngle = parameters.getHorizontalViewAngle();
        mVerticalViewAngle = parameters.getVerticalViewAngle();
    }

    public int getPreviewBufSize() {
        PixelFormat pixelInfo = new PixelFormat();
        PixelFormat.getPixelFormatInfo(mCameraDevice.getParameters().getPreviewFormat(), pixelInfo);
        // TODO: remove this extra 32 byte after the driver bug is fixed.
        return (mPreviewWidth * mPreviewHeight * pixelInfo.bitsPerPixel / 8) + 32;
    }

    private void configureCamera(Parameters parameters) {
        mCameraDevice.setParameters(parameters);

        int orientation = Util.getDisplayOrientation(Util.getDisplayRotation(this),
                CameraHolder.instance().getBackCameraId());
        mCameraDevice.setDisplayOrientation(orientation);
    }

    private boolean switchToOtherMode(int mode) {
        if (isFinishing()) {
            return false;
        }
        MenuHelper.gotoMode(mode, this);
        finish();
        return true;
    }

    public boolean onModeChanged(int mode) {
        if (mode != ModePicker.MODE_PANORAMA) {
            return switchToOtherMode(mode);
        } else {
            return true;
        }
    }

    @Override
    public void onMosaicSurfaceCreated(final int textureID) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mSurfaceTexture != null) {
                    mSurfaceTexture.release();
                }
                mSurfaceTexture = new SurfaceTexture(textureID);
                if (!mPausing) {
                    mSurfaceTexture.setOnFrameAvailableListener(PanoramaActivity.this);
                    startCameraPreview();
                }
            }
        });
    }

    public void runViewFinder() {
        mMosaicView.setWarping(false);
        // Call preprocess to render it to low-res and high-res RGB textures.
        mMosaicView.preprocess(mTransformMatrix);
        mMosaicView.setReady();
        mMosaicView.requestRender();
    }

    public void runMosaicCapture() {
        mMosaicView.setWarping(true);
        // Call preprocess to render it to low-res and high-res RGB textures.
        mMosaicView.preprocess(mTransformMatrix);
        // Lock the conditional variable to ensure the order of transferGPUtoCPU and
        // mMosaicFrame.processFrame().
        mMosaicView.lockPreviewReadyFlag();
        // Now, transfer the textures from GPU to CPU memory for processing
        mMosaicView.transferGPUtoCPU();
        // Wait on the condition variable (will be opened when GPU->CPU transfer is done).
        mMosaicView.waitUntilPreviewReady();
        mMosaicFrameProcessor.processFrame();
    }

    public synchronized void onFrameAvailable(SurfaceTexture surface) {
        /* This function may be called by some random thread,
         * so let's be safe and use synchronize. No OpenGL calls can be done here.
         */
        // Updating the texture should be done in the GL thread which mMosaicView is attached.
        mMosaicView.queueEvent(new Runnable() {
            @Override
            public void run() {
                mSurfaceTexture.updateTexImage();
                mSurfaceTexture.getTransformMatrix(mTransformMatrix);
            }
        });
        // Update the transformation matrix for mosaic pre-process.
        if (mCaptureState == CAPTURE_VIEWFINDER) {
            runViewFinder();
        } else {
            runMosaicCapture();
        }
    }

    public void startCapture() {
        // Reset values so we can do this again.
        mTimeTaken = System.currentTimeMillis();
        mCaptureState = CAPTURE_MOSAIC;
        mPanoControlLayout.startAnimation(mSlideOut);
        mPanoControlLayout.setVisibility(View.GONE);

        mMosaicFrameProcessor.setProgressListener(new MosaicFrameProcessor.ProgressListener() {
            @Override
            public void onProgress(boolean isFinished, float panningRateX, float panningRateY,
                    int traversedAngleX, int traversedAngleY) {
                if (isFinished) {
                    stopCapture();
                } else {
                    updateProgress(panningRateX, panningRateY, traversedAngleX, traversedAngleY);
                }
            }
        });

        mStopCaptureButton.setVisibility(View.VISIBLE);
        mCaptureView.setVisibility(View.VISIBLE);
        mMosaicView.setVisibility(View.VISIBLE);
    }

    private void stopCapture() {
        mCaptureState = CAPTURE_VIEWFINDER;
        mTooFastPrompt.setVisibility(View.GONE);

        mMosaicFrameProcessor.setProgressListener(null);
        stopCameraPreview();

        mSurfaceTexture.setOnFrameAvailableListener(null);

        if (!mThreadRunning) {
            runBackgroundThreadAndShowDialog(mPreparePreviewString, false, new Thread() {
                @Override
                public void run() {
                    byte[] jpegData = generateFinalMosaic(false);
                    Bitmap bitmap = null;
                    if (jpegData != null) {
                        bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
                    }
                    mMainHandler.sendMessage(mMainHandler.obtainMessage(
                            MSG_FINAL_MOSAIC_READY, bitmap));
                }
            });
            reportProgress(false);
        }
    }

    private void updateProgress(float panningRateX, float panningRateY,
            int traversedAngleX, int traversedAngleY) {

        mMosaicView.setReady();
        mMosaicView.requestRender();

        // TODO: Now we just display warning message by the panning speed.
        // Since we only support horizontal panning, we should display a warning message
        // in UI when there're significant vertical movements.
        if ((panningRateX * mHorizontalViewAngle > PANNING_SPEED_THRESHOLD)
                || (panningRateY * mVerticalViewAngle > PANNING_SPEED_THRESHOLD)) {
            // TODO: draw speed indication according to the UI spec.
            mTooFastPrompt.setVisibility(View.VISIBLE);
            mCaptureView.setSweepAngle(Math.max(traversedAngleX, traversedAngleY) + 1);
            mCaptureView.invalidate();
        } else {
            mTooFastPrompt.setVisibility(View.GONE);
            mCaptureView.setSweepAngle(Math.max(traversedAngleX, traversedAngleY) + 1);
            mCaptureView.invalidate();
        }
    }

    private void createContentView() {
        setContentView(R.layout.panorama);

        mCaptureState = CAPTURE_VIEWFINDER;

        mCaptureLayout = (View) findViewById(R.id.pano_capture_layout);
        mCaptureView = (CaptureView) findViewById(R.id.pano_capture_view);
        mCaptureView.setStartAngle(-DEFAULT_SWEEP_ANGLE / 2);
        mStopCaptureButton = (Button) findViewById(R.id.pano_capture_stop_button);
        mTooFastPrompt = (TextView) findViewById(R.id.pano_capture_too_fast_textview);

        mThumbnailView = (RotateImageView) findViewById(R.id.thumbnail);

        mReviewLayout = (View) findViewById(R.id.pano_review_layout);
        mReview = (ImageView) findViewById(R.id.pano_reviewarea);
        mMosaicView = (MosaicRendererSurfaceView) findViewById(R.id.pano_renderer);
        mMosaicView.getRenderer().setMosaicSurfaceCreateListener(this);
        mMosaicView.setVisibility(View.VISIBLE);

        mPanoControlLayout = (View) findViewById(R.id.pano_control_layout);
        mModePicker = (ModePicker) findViewById(R.id.mode_picker);
        mModePicker.setVisibility(View.VISIBLE);
        mModePicker.setOnModeChangeListener(this);
        mModePicker.setCurrentMode(ModePicker.MODE_PANORAMA);
    }

    @OnClickAttr
    public void onStartButtonClicked(View v) {
        // If mSurfaceTexture == null then GL setup is not finished yet.
        // No buttons can be pressed.
        if (mPausing || mThreadRunning || mSurfaceTexture == null) return;
        startCapture();
    }

    @OnClickAttr
    public void onStopButtonClicked(View v) {
        if (mPausing || mThreadRunning || mSurfaceTexture == null) return;
        stopCapture();
    }

    public void reportProgress(final boolean highRes) {
        Thread t = new Thread() {
            @Override
            public void run() {
                while (mThreadRunning) {
                    final int progress = mMosaicFrameProcessor.reportProgress(highRes);

                    try {
                        Thread.sleep(50);
                    } catch (Exception e) {
                        throw new RuntimeException("Panorama reportProgress failed", e);
                    }
                    // Update the progress bar
                    runOnUiThread(new Runnable() {
                        public void run() {
                            // Check if mProgressDialog is null because the background thread
                            // finished.
                            if (mProgressDialog != null) {
                                mProgressDialog.setProgress(progress);
                            }
                        }
                    });
                }
            }
        };
        t.start();
    }

    @OnClickAttr
    public void onOkButtonClicked(View v) {
        if (mPausing || mThreadRunning || mSurfaceTexture == null) return;
        runBackgroundThreadAndShowDialog(mGeneratePanoramaString, true, new Thread() {
            @Override
            public void run() {
                byte[] jpegData = generateFinalMosaic(true);
                int orientation = Exif.getOrientation(jpegData);
                Uri uri = savePanorama(jpegData, orientation);
                if (uri != null) {
                    // Create a thumbnail whose size is smaller than 480.
                    int ratio = (int) Math.ceil((double) 480 / mPreviewHeight);
                    int inSampleSize = Util.nextPowerOf2(ratio);
                    mThumbnail = Thumbnail.createThumbnail(
                            jpegData, orientation, inSampleSize, uri);
                }
                mMainHandler.sendMessage(
                        mMainHandler.obtainMessage(MSG_RESET_TO_PREVIEW_WITH_THUMBNAIL));
            }
        });
        reportProgress(true);
    }

    /**
     * If the style is horizontal one, the maximum progress is assumed to be 100.
     */
    private void runBackgroundThreadAndShowDialog(
            String str, boolean showPercentageProgress, Thread thread) {
        mThreadRunning = true;
        mProgressDialog = new ProgressDialog(this);
        if (showPercentageProgress) {
            mProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            mProgressDialog.setMax(100);
        }
        mProgressDialog.setMessage(str);
        mProgressDialog.show();
        thread.start();
    }

    private void onBackgroundThreadFinished() {
        mThreadRunning = false;
        mProgressDialog.dismiss();
        mProgressDialog = null;
    }

    @OnClickAttr
    public void onRetakeButtonClicked(View v) {
        if (mPausing || mThreadRunning || mSurfaceTexture == null) return;
        resetToPreview();
    }

    @OnClickAttr
    public void onThumbnailClicked(View v) {
        if (mPausing || mThreadRunning || mSurfaceTexture == null) return;
        showSharePopup();
    }

    private void showSharePopup() {
        if (mThumbnail == null) return;
        Uri uri = mThumbnail.getUri();
        if (mSharePopup == null || !uri.equals(mSharePopup.getUri())) {
            // The orientation compensation is set to 0 here because we only support landscape.
            // Panorama picture is long. Use pano_layout so the share popup can be full-screen.
            mSharePopup = new SharePopup(this, uri, mThumbnail.getBitmap(), "image/jpeg",
                    0, findViewById(R.id.pano_layout));
        }
        mSharePopup.showAtLocation(mThumbnailView, Gravity.NO_GRAVITY, 0, 0);
    }

    private void resetToPreview() {
        mCaptureState = CAPTURE_VIEWFINDER;

        mReviewLayout.setVisibility(View.GONE);
        mStopCaptureButton.setVisibility(View.GONE);
        mCaptureView.setVisibility(View.GONE);
        mPanoControlLayout.setVisibility(View.VISIBLE);
        mPanoControlLayout.startAnimation(mSlideIn);
        mCaptureLayout.setVisibility(View.VISIBLE);
        mMosaicFrameProcessor.reset();

        mSurfaceTexture.setOnFrameAvailableListener(this);

        if (!mPausing) startCameraPreview();

        mMosaicView.setVisibility(View.VISIBLE);
    }

    private void showFinalMosaic(Bitmap bitmap) {
        if (bitmap != null) {
            mReview.setImageBitmap(bitmap);
        }
        mCaptureLayout.setVisibility(View.GONE);
        mReviewLayout.setVisibility(View.VISIBLE);
        mCaptureView.setSweepAngle(0);
    }

    private Uri savePanorama(byte[] jpegData, int orientation) {
        if (jpegData != null) {
            String imagePath = PanoUtil.createName(
                    getResources().getString(R.string.pano_file_name_format), mTimeTaken);
            return Storage.addImage(getContentResolver(), imagePath, mTimeTaken, null,
                    orientation, jpegData);
        }
        return null;
    }

    private void clearMosaicFrameProcessorIfNeeded() {
        if (!mPausing || mThreadRunning) return;
        mMosaicFrameProcessor.clear();
    }

    private void initMosaicFrameProcessorIfNeeded() {
        if (mPausing || mThreadRunning) return;
        if (mMosaicFrameProcessor == null) {
            // Start the activity for the first time.
            mMosaicFrameProcessor = new MosaicFrameProcessor(DEFAULT_SWEEP_ANGLE - 5,
                    mPreviewWidth, mPreviewHeight, getPreviewBufSize());
        }
        mMosaicFrameProcessor.initialize();
    }

    @Override
    protected void onPause() {
        super.onPause();

        releaseCamera();
        mPausing = true;
        mMosaicView.onPause();
        mSensorManager.unregisterListener(mListener);
        clearMosaicFrameProcessorIfNeeded();
        System.gc();
    }

    @Override
    protected void onResume() {
        super.onResume();

        mPausing = false;
        /*
         * It is not necessary to get accelerometer events at a very high rate,
         * by using a slower rate (SENSOR_DELAY_UI), we get an automatic
         * low-pass filter, which "extracts" the gravity component of the
         * acceleration. As an added benefit, we use less power and CPU
         * resources.
         */
        mSensorManager.registerListener(mListener, mSensor, SensorManager.SENSOR_DELAY_UI);
        mCaptureState = CAPTURE_VIEWFINDER;
        setupCamera();
        if (mSurfaceTexture != null) {
            mSurfaceTexture.setOnFrameAvailableListener(this);
            startCameraPreview();
        }
        // Camera must be initialized before MosaicFrameProcessor is initialized. The preview size
        // has to be decided by camera device.
        initMosaicFrameProcessorIfNeeded();
        mMosaicView.onResume();
    }

    private final SensorEventListener mListener = new SensorEventListener() {
        private float mCompassCurrX; // degrees
        private float mCompassCurrY; // degrees
        private float mTimestamp;

        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                if (mTimestamp != 0) {
                    final float dT = (event.timestamp - mTimestamp) * NS2S;
                    mCompassCurrX += event.values[1] * dT * 180.0f / Math.PI;
                    mCompassCurrY += event.values[0] * dT * 180.0f / Math.PI;
                }
                mTimestamp = event.timestamp;

            } else if (event.sensor.getType() == Sensor.TYPE_ORIENTATION) {
                mCompassCurrX = event.values[0];
                mCompassCurrY = event.values[1];
            }

            if (mMosaicFrameProcessor != null) {
                mMosaicFrameProcessor.updateCompassValue(mCompassCurrX, mCompassCurrY);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    public byte[] generateFinalMosaic(boolean highRes) {
        mMosaicFrameProcessor.createMosaic(highRes);

        byte[] imageData = mMosaicFrameProcessor.getFinalMosaicNV21();
        if (imageData == null) {
            Log.e(TAG, "getFinalMosaicNV21() returned null.");
            return null;
        }

        int len = imageData.length - 8;
        int width = (imageData[len + 0] << 24) + ((imageData[len + 1] & 0xFF) << 16)
                + ((imageData[len + 2] & 0xFF) << 8) + (imageData[len + 3] & 0xFF);
        int height = (imageData[len + 4] << 24) + ((imageData[len + 5] & 0xFF) << 16)
                + ((imageData[len + 6] & 0xFF) << 8) + (imageData[len + 7] & 0xFF);
        Log.v(TAG, "ImLength = " + (len) + ", W = " + width + ", H = " + height);

        if (width <= 0 || height <= 0) {
            // TODO: pop up a error meesage indicating that the final result is not generated.
            Log.e(TAG, "width|height <= 0!!, len = " + (len) + ", W = " + width + ", H = " +
                    height);
            return null;
        }

        YuvImage yuvimage = new YuvImage(imageData, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvimage.compressToJpeg(new Rect(0, 0, width, height), 100, out);
        try {
            out.close();
        } catch (Exception e) {
            Log.e(TAG, "Exception in storing final mosaic", e);
            return null;
        }
        return out.toByteArray();
    }

    private void setPreviewTexture(SurfaceTexture surface) {
        try {
            mCameraDevice.setPreviewTexture(surface);
        } catch (Throwable ex) {
            releaseCamera();
            throw new RuntimeException("setPreviewTexture failed", ex);
        }
    }

    private void startCameraPreview() {
        // If we're previewing already, stop the preview first (this will blank
        // the screen).
        if (mCameraState != PREVIEW_STOPPED) stopCameraPreview();

        setPreviewTexture(mSurfaceTexture);

        try {
            Log.v(TAG, "startPreview");
            mCameraDevice.startPreview();
        } catch (Throwable ex) {
            releaseCamera();
            throw new RuntimeException("startPreview failed", ex);
        }
        mCameraState = PREVIEW_ACTIVE;
    }

    private void stopCameraPreview() {
        if (mCameraDevice != null && mCameraState != PREVIEW_STOPPED) {
            Log.v(TAG, "stopPreview");
            mCameraDevice.stopPreview();
        }
        mCameraState = PREVIEW_STOPPED;
    }
}
