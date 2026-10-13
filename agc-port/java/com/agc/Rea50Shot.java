package com.agc;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * REA (LineageOS port): single-shot 50 MP capture on the camera device GCam already has open.
 *
 * The vendor HAL produces the 8160x6120 image itself once the session carries Samsung's
 * high-resolution stream option: streamUseCase bits 16+ (option 2). The patched cameraserver
 * accepts those options and sends the PDK parameter string on its own, so no Samsung
 * framework API is needed. One still request is enough - the HAL's HighResolution pipeline
 * does the remosaic and encodes the JPEG - which avoids the dark frames a burst catches
 * while the HAL swaps its binning session for the quadra session.
 */
public final class Rea50Shot {

    private static final String TAG = "LEraw50";
    private static final int W = 8160;
    private static final int H = 6120;
    private static final long OPT_HR = 0x20000L;       // Samsung option 2 = high resolution
    private static final long OPT_PREVIEW = 0x10000L;  // Samsung option 1 = preview
    private static final int PREVIEWS_BEFORE_STILL = 8;
    private static final long JPEG_TIMEOUT_MS = 20000L;

    public static boolean capture(Context ctx) {
        HandlerThread ht = new HandlerThread("rea50");
        ht.start();
        final Handler handler = new Handler(ht.getLooper());
        Executor exec = new Executor() {
            public void execute(Runnable r) { handler.post(r); }
        };

        final CameraDevice dev = LEraw50.getDevice();
        if (dev == null) {
            Log.i(TAG, "50MP: no stored device - open the camera first");
            ht.quitSafely();
            return false;
        }

        ImageReader raw = null, jpg = null, prev = null;
        CameraCaptureSession sess = null;
        try {
            raw = ImageReader.newInstance(W, H, ImageFormat.RAW10, 3);
            jpg = ImageReader.newInstance(W, H, ImageFormat.JPEG, 2);
            prev = ImageReader.newInstance(1920, 1440, ImageFormat.YUV_420_888, 4);

            final Object lock = new Object();
            final byte[][] out = new byte[1][];

            jpg.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                public void onImageAvailable(ImageReader ir) {
                    try {
                        Image im = ir.acquireLatestImage();
                        if (im != null) {
                            ByteBuffer bb = im.getPlanes()[0].getBuffer();
                            byte[] b = new byte[bb.remaining()];
                            bb.get(b);
                            out[0] = b;
                            Log.i(TAG, "50MP: JPEG " + b.length + " bytes, SOF " + jpegSize(b));
                            im.close();
                        }
                    } catch (Throwable t) {
                        Log.i(TAG, "50MP jpeg cb " + t);
                    }
                    synchronized (lock) { lock.notifyAll(); }
                }
            }, handler);

            List<OutputConfiguration> ocs = new ArrayList<OutputConfiguration>();
            OutputConfiguration or = new OutputConfiguration(raw.getSurface());
            or.setStreamUseCase(OPT_HR);
            ocs.add(or);
            OutputConfiguration oj = new OutputConfiguration(jpg.getSurface());
            oj.setStreamUseCase(OPT_HR);
            ocs.add(oj);
            OutputConfiguration op = new OutputConfiguration(prev.getSurface());
            op.setStreamUseCase(OPT_PREVIEW);
            ocs.add(op);
            Log.i(TAG, "50MP: configuring RAW10+JPEG 8160x6120 at 0x20000, preview at 0x10000");

            final CameraCaptureSession[] ss = new CameraCaptureSession[1];
            dev.createCaptureSession(new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    ocs, exec, new CameraCaptureSession.StateCallback() {
                public void onConfigured(CameraCaptureSession s) {
                    ss[0] = s;
                    synchronized (lock) { lock.notifyAll(); }
                }
                public void onConfigureFailed(CameraCaptureSession s) {
                    Log.i(TAG, "50MP: session configure FAILED");
                    synchronized (lock) { lock.notifyAll(); }
                }
            }));
            synchronized (lock) { lock.wait(8000); }
            sess = ss[0];
            if (sess == null) {
                Log.i(TAG, "50MP: no session");
                return false;
            }
            Log.i(TAG, "50MP: session configured");

            CaptureRequest.Builder pb = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            pb.addTarget(prev.getSurface());
            setVendorKeys(pb);

            final CaptureRequest.Builder sb = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            sb.addTarget(raw.getSurface());
            sb.addTarget(jpg.getSurface());
            setVendorKeys(sb);
            try {
                int jpegOrientation = jpegOrientation(ctx, dev);
                sb.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation);
                Log.i(TAG, "50MP: JPEG_ORIENTATION=" + jpegOrientation);
            } catch (Throwable t) {
                Log.i(TAG, "50MP: could not set JPEG_ORIENTATION " + t);
            }

            final int[] frames = new int[1];
            final boolean[] fired = new boolean[1];
            sess.setRepeatingRequest(pb.build(), new CameraCaptureSession.CaptureCallback() {
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r,
                                               TotalCaptureResult res) {
                    int n;
                    synchronized (frames) { n = ++frames[0]; }
                    if (n >= PREVIEWS_BEFORE_STILL && !fired[0]) {
                        fired[0] = true;
                        Log.i(TAG, "50MP: firing single still after " + n + " previews");
                        try {
                            s.capture(sb.build(), null, handler);
                        } catch (Throwable t) {
                            Log.i(TAG, "50MP: still failed " + t);
                            synchronized (lock) { lock.notifyAll(); }
                        }
                    }
                }
            }, handler);

            long deadline = System.currentTimeMillis() + JPEG_TIMEOUT_MS;
            synchronized (lock) {
                while (out[0] == null && System.currentTimeMillis() < deadline) {
                    lock.wait(500);
                }
            }
            if (out[0] == null) {
                Log.i(TAG, "50MP: no JPEG within " + JPEG_TIMEOUT_MS + "ms");
                return false;
            }
            String name = "AGC_50MP_" + System.currentTimeMillis() + ".jpg";
            boolean saved = Eraw50IO.saveJpeg(ctx, out[0], name);
            Log.i(TAG, "50MP: saved=" + saved + " " + name + " (" + out[0].length + " bytes)");
            // Configuring our session on GCam's device closes GCam's own session, which leaves
            // its viewfinder frozen. GCam's internal restart path Class.forName()s an
            // unobfuscated class name and throws in this build, so bring the UI back ourselves.
            restartCameraUi(ctx);
            return saved;
        } catch (Throwable t) {
            Log.i(TAG, "50MP: capture EX " + t);
            return false;
        } finally {
            try { if (sess != null) sess.close(); } catch (Throwable t) { }
            try { if (raw != null) raw.close(); } catch (Throwable t) { }
            try { if (jpg != null) jpg.close(); } catch (Throwable t) { }
            try { if (prev != null) prev.close(); } catch (Throwable t) { }
            ht.quitSafely();
        }
    }

    /** Clockwise rotation the HAL must apply so the JPEG comes out upright. */
    private static int jpegOrientation(Context ctx, CameraDevice dev) throws Exception {
        CameraManager mgr = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        CameraCharacteristics cc = mgr.getCameraCharacteristics(dev.getId());
        Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int sensor = (so == null) ? 90 : so;
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        int rot = wm.getDefaultDisplay().getRotation();
        int degrees = rot == Surface.ROTATION_0 ? 0
                : rot == Surface.ROTATION_90 ? 90
                : rot == Surface.ROTATION_180 ? 180 : 270;
        Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
        boolean front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
        return front ? (sensor + degrees) % 360 : (sensor - degrees + 360) % 360;
    }

    private static void restartCameraUi(Context ctx) {
        try {
            android.content.Intent i = ctx.getPackageManager()
                    .getLaunchIntentForPackage(ctx.getPackageName());
            if (i != null) {
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
                ctx.startActivity(i);
                Log.i(TAG, "50MP: camera UI restart requested");
            }
        } catch (Throwable t) {
            Log.i(TAG, "50MP: restart failed " + t);
        }
    }

    private static void setVendorKeys(CaptureRequest.Builder b) {
        try {
            b.set(new CaptureRequest.Key<Integer>("samsung.android.control.shootingMode", Integer.class), 40);
            b.set(new CaptureRequest.Key<Integer>("samsung.android.control.ssmShotMode", Integer.class), 2);
            b.set(new CaptureRequest.Key<Integer>("samsung.android.control.capturePhysicalId", Integer.class), 56);
        } catch (Throwable t) {
            Log.i(TAG, "50MP: vendor key set failed " + t);
        }
    }

    private static String jpegSize(byte[] buf) {
        int i = 2;
        while (i + 9 < buf.length) {
            if ((buf[i] & 0xff) != 0xff) { i++; continue; }
            int marker = buf[i + 1] & 0xff;
            if (marker == 0xd8 || marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) { i += 2; continue; }
            int len = ((buf[i + 2] & 0xff) << 8) | (buf[i + 3] & 0xff);
            if (marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc) {
                int h = ((buf[i + 5] & 0xff) << 8) | (buf[i + 6] & 0xff);
                int w = ((buf[i + 7] & 0xff) << 8) | (buf[i + 8] & 0xff);
                return w + "x" + h;
            }
            i += 2 + len;
        }
        return "?";
    }

    private Rea50Shot() { }
}
