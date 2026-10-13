.class public final Lcom/agc/ReaPdkHelper;
.super Ljava/lang/Object;
.source "ReaPdkHelper.java"


# REA (LineageOS port):
#
# Replaces Samsung's CustomInterfaceHelper.setSamsungParameter(CameraDevice, String),
# which lives in /system/framework/scamera_sdk_util.jar and cannot be loaded here.
# The PDK parameter string is what switches the vendor HAL into its 50 MP
# HighResolution scenario, and on this device the patched cameraserver sends it on its
# own when a stream carries Samsung's high-resolution option, so this is a no-op.

.method public static setSamsungParameter(Landroid/hardware/camera2/CameraDevice;Ljava/lang/String;)V
    .locals 0

    return-void
.end method
